package io.github.propagandist.pdfjig.desktop;

import io.github.propagandist.pdfjig.core.ErrorCode;
import io.github.propagandist.pdfjig.core.PageRendering;
import io.github.propagandist.pdfjig.core.PdfBoxPageRendering;
import io.github.propagandist.pdfjig.core.PdfDocument;
import io.github.propagandist.pdfjig.core.PdfjigException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javafx.concurrent.Task;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.image.Image;

/**
 * サムネイルを非同期に供給する。
 *
 * <p>複数の文書を受け持つ。1 つの編集セッションに複数のファイルのページが混ざるため
 * （SPEC.md §7.1）、どの文書の何ページ目かでキャッシュを引く。
 *
 * <p>描画は<b>文書がいくつあっても専用のスレッド 1 本に直列化する</b>。
 * {@link PdfDocument} はスレッド安全ではなく、複数スレッドから同時に描画すると壊れるため
 * （{@link PageRendering} の規約）。文書ごとにスレッドを立てても、直列化を保つ手間が増えるだけで
 * 速くはならない。1 本で足りない速度は、そもそも可視範囲だけを描画することで確保する。
 *
 * <p>保持する枚数の上限も 1 つにまとめる。文書数に比例して膨らませると、
 * 何枚分のメモリを使うのか読めなくなる。
 *
 * <p>JavaFX Application Thread では、描画済みの {@link Image} を差し込むだけにする
 * （SPEC.md §7.2）。
 *
 * <p><b>文書を閉じる前に、必ずこのオブジェクトを閉じること。</b>
 * 描画の最中に文書が閉じられると、その描画は壊れた状態を読むことになる。
 * 1 つだけ外すときも同じであり、{@link #removeSource(int)} は走っている描画が終わるまで戻らない。
 */
public final class ThumbnailSource implements AutoCloseable {

    /** 保持する枚数。メモリ量ではなく枚数で切る（HANDOVER.md 3-1）。 */
    private static final int CACHE_CAPACITY = 200;

    /**
     * 描画スレッドの応答を待つ上限。
     *
     * <p>閉じるときと、受け持っている文書を 1 つ外すときの両方で使う。
     */
    private static final long RENDERING_TIMEOUT_SECONDS = 5L;

    /** キャッシュの鍵。同じページ番号でも文書が違えば別のサムネイルになる。 */
    private record PageKey(int sourceIndex, int pageNumber) {}

    private final PageRendering rendering;

    private final int edgePixels;

    /**
     * 受け持っている文書。出どころ番号がこの並びの添字になる。
     *
     * <p>描画スレッドと JavaFX スレッドの双方から読むため、追加も読み出しもこれ自身をロックに使う。
     */
    private final List<PdfDocument> documents = new ArrayList<>();

    /**
     * サムネイルの保持。
     *
     * <p><b>同期の責任はこのクラスが持つ。</b> {@link LruCache} は自分では守らない。
     * 描画スレッドと JavaFX スレッドの双方から触るため、触る箇所はすべてこれ自身をロックにして包む。
     * {@link LruCache#get} も内部の順序を書き換えるので、引くだけの箇所も例外ではない。
     */
    private final LruCache<PageKey, Image> cache = new LruCache<>(CACHE_CAPACITY);

    /**
     * 描画を 1 本のスレッドに直列化する実行器。
     *
     * <p>{@code Executors.newSingleThreadExecutor} で包まないのは、待機中の描画を捨てるために
     * キューへ触る必要があるためである（{@link #awaitRendering()}）。あちらが返す実装はキューを隠す。
     */
    private final ThreadPoolExecutor renderer =
            new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(), runnable -> {
                Thread thread = new Thread(runnable, "pdfjig-thumbnail");
                // ウィンドウを閉じてもプロセスが残らないようにする。
                thread.setDaemon(true);
                return thread;
            });

    /**
     * @param edgePixels サムネイルの長辺の画素数
     */
    public ThumbnailSource(int edgePixels) {
        this(edgePixels, new PdfBoxPageRendering());
    }

    /**
     * 描画を差し替えられる形。
     *
     * <p>テストが描画を止めたまま、外す操作との待ち合わせを確かめるために使う。
     * 実際の描画の速さで当てにいくと、落ちるかどうかが機械の速さで決まる
     * （CLAUDE.md「不安定なテストの扱い」）。
     *
     * @param edgePixels サムネイルの長辺の画素数
     * @param rendering  ページを描く実装
     */
    ThumbnailSource(int edgePixels, PageRendering rendering) {
        if (edgePixels < 1) {
            throw new IllegalArgumentException("edgePixels は 1 以上でなければなりません。");
        }
        if (rendering == null) {
            throw new IllegalArgumentException("rendering は null にできません。");
        }
        this.edgePixels = edgePixels;
        this.rendering = rendering;
    }

    /**
     * 文書を受け持たせる。このオブジェクトは文書を閉じない。
     *
     * @param document 対象文書
     * @return 出どころ番号（0 始まり）
     */
    public int addSource(PdfDocument document) {
        if (document == null) {
            throw new IllegalArgumentException("document は null にできません。");
        }
        synchronized (documents) {
            documents.add(document);
            return documents.size() - 1;
        }
    }

    /**
     * 既に描画済みのサムネイルを、待たずに取り出す。
     *
     * <p>セルが表示された瞬間に呼ぶ。ここで得られれば描画を待たずに済み、
     * スクロールが引っかからない。
     *
     * @param sourceIndex 出どころ番号
     * @param pageNumber  その文書の中でのページ番号（1 始まり）
     * @return 保持していればそのサムネイル
     */
    public Optional<Image> cached(int sourceIndex, int pageNumber) {
        synchronized (cache) {
            return cache.get(new PageKey(sourceIndex, pageNumber));
        }
    }

    /**
     * サムネイルの描画を依頼する。
     *
     * <p>戻り値の {@link Task} に {@code setOnSucceeded} / {@code setOnFailed} を
     * 付けて結果を受け取る。セルが別のページに使い回されたときは
     * {@link Task#cancel(boolean)} を呼ぶこと。まだ始まっていない描画は取り消され、
     * 高速なスクロールで見えないページを描き続けることがなくなる。
     *
     * <p><b>受け持っていない出どころ番号でも例外を投げない。</b> 一覧が組み直される前に、
     * 外した文書の番号で依頼が来ることがある。その場合は失敗する {@link Task} を返すので、
     * {@code setOnFailed} が拾う。
     *
     * @param sourceIndex 出どころ番号
     * @param pageNumber  その文書の中でのページ番号（1 始まり）
     * @return 実行中または待機中の描画
     */
    public Task<Image> request(int sourceIndex, int pageNumber) {
        PageKey key = new PageKey(sourceIndex, pageNumber);
        // 依頼した時点の文書を捕まえておく。描画のときに番号から引くと、その間に
        // 文書が外されて番号が繰り下がっていた場合、別の文書を描いてしまう。
        Optional<PdfDocument> document = documentAt(sourceIndex);

        Task<Image> task = new Task<>() {
            @Override
            protected Image call() {
                Optional<Image> hit;
                synchronized (cache) {
                    hit = cache.get(key);
                }
                if (hit.isPresent()) {
                    return hit.get();
                }

                // 素の IndexOutOfBoundsException を JavaFX スレッドへ出さない。
                // 描けないことは失敗として返し、呼び出し側に伝える。
                PdfDocument target = document.orElseThrow(() -> new PdfjigException(ErrorCode.PAGE_OUT_OF_RANGE));

                Image image = SwingFXUtils.toFXImage(rendering.renderThumbnail(target, pageNumber, edgePixels), null);
                synchronized (cache) {
                    cache.put(key, image);
                }
                return image;
            }
        };
        renderer.execute(task);
        return task;
    }

    /**
     * 受け持っている文書を 1 つ外す。このオブジェクトは文書を閉じない。
     *
     * <p><b>走っている描画が終わるまで戻らない。</b> 描画は依頼した時点の鍵で結果を入れるため、
     * 番号が繰り下がった後に入り直すと、別の文書の絵がその鍵で引けるようになる。
     * 呼び出し側はこの直後に文書を閉じるので、待つのはここでなければならない。
     *
     * <p><b>キャッシュは丸ごと捨てる。</b> 鍵は (出どころ, ページ番号) であり、
     * 外したぶん後ろの番号が繰り下がると鍵の意味が変わる。付け替えるより捨てるほうが確実で、
     * 描き直す費用は可視範囲だけに収まる。
     *
     * @param sourceIndex 外す出どころ番号
     * @throws PdfjigException 描画が終わるのを待てなかった場合は
     *                         {@link ErrorCode#THUMBNAIL_RENDERING_BUSY}。文書は外れない
     */
    public void removeSource(int sourceIndex) {
        awaitRendering();
        synchronized (documents) {
            documents.remove(sourceIndex);
        }
        invalidate();
    }

    /** 保持しているサムネイルをすべて捨てる。ページを回転したときなどに呼ぶ。 */
    public void invalidate() {
        synchronized (cache) {
            cache.clear();
        }
    }

    @Override
    public void close() {
        renderer.shutdownNow();
        try {
            // 描画中のスレッドが文書を触っている間に文書を閉じると壊れる。
            renderer.awaitTermination(RENDERING_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 走っている描画が終わるまで待つ。待機中のものは捨てる。
     *
     * <p>待たずに捨てるのは、この後どのみちキャッシュごと捨てられるためである。可視範囲ぶんが
     * 溜まっているときに全部を走らせてから戻ると、待ちがそのまま JavaFX スレッドの固まりになる。
     * 捨てたぶんは一覧が組み直されるときに出し直される。
     *
     * <p><b>待てなかったときは進まない。</b> 黙って進むと、呼び出し側が描画中の文書を閉じる——
     * それはいま直そうとしている状態そのものである。{@link #close()} が待てなくても進むのとは
     * 非対称だが、あちらは終了処理であり、戻って続ける先が無い。
     */
    private void awaitRendering() {
        List<Runnable> waiting = new ArrayList<>();
        renderer.getQueue().drainTo(waiting);
        for (Runnable runnable : waiting) {
            if (runnable instanceof Task<?> task) {
                task.cancel(false);
            }
        }

        try {
            // 描画は 1 本のスレッドに直列化されている。これが動いたときには、
            // 走っていた描画は終わっている。
            renderer.submit(() -> {}).get(RENDERING_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PdfjigException(ErrorCode.THUMBNAIL_RENDERING_BUSY);
        } catch (ExecutionException | TimeoutException e) {
            throw PdfjigException.wrapping(ErrorCode.THUMBNAIL_RENDERING_BUSY, e);
        }
    }

    /**
     * 出どころ番号から文書を引く。
     *
     * @param sourceIndex 出どころ番号
     * @return 受け持っていればその文書。範囲外なら空
     */
    private Optional<PdfDocument> documentAt(int sourceIndex) {
        synchronized (documents) {
            if (sourceIndex < 0 || sourceIndex >= documents.size()) {
                return Optional.empty();
            }
            return Optional.of(documents.get(sourceIndex));
        }
    }
}
