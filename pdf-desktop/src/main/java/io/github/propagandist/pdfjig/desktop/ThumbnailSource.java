package io.github.propagandist.pdfjig.desktop;

import io.github.propagandist.pdfjig.core.PageRendering;
import io.github.propagandist.pdfjig.core.PdfBoxPageRendering;
import io.github.propagandist.pdfjig.core.PdfDocument;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
 */
public final class ThumbnailSource implements AutoCloseable {

    /** 保持する枚数。メモリ量ではなく枚数で切る（HANDOVER.md 3-1）。 */
    private static final int CACHE_CAPACITY = 200;

    /** 描画スレッドの停止を待つ上限。 */
    private static final long SHUTDOWN_TIMEOUT_SECONDS = 5L;

    /** キャッシュの鍵。同じページ番号でも文書が違えば別のサムネイルになる。 */
    private record PageKey(int sourceIndex, int pageNumber) {
    }

    private final PageRendering rendering = new PdfBoxPageRendering();

    private final int edgePixels;

    /**
     * 受け持っている文書。出どころ番号がこの並びの添字になる。
     *
     * <p>描画スレッドと JavaFX スレッドの双方から読むため、追加も読み出しもこれ自身をロックに使う。
     */
    private final List<PdfDocument> documents = new ArrayList<>();

    /** 描画スレッドと JavaFX スレッドの双方から触るため、これ自身をロックに使う。 */
    private final LruCache<PageKey, Image> cache = new LruCache<>(CACHE_CAPACITY);

    private final ExecutorService renderer = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "pdfjig-thumbnail");
        // ウィンドウを閉じてもプロセスが残らないようにする。
        thread.setDaemon(true);
        return thread;
    });

    /**
     * @param edgePixels サムネイルの長辺の画素数
     */
    public ThumbnailSource(int edgePixels) {
        if (edgePixels < 1) {
            throw new IllegalArgumentException("edgePixels は 1 以上でなければなりません。");
        }
        this.edgePixels = edgePixels;
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
     * @param sourceIndex 出どころ番号
     * @param pageNumber  その文書の中でのページ番号（1 始まり）
     * @return 実行中または待機中の描画
     */
    public Task<Image> request(int sourceIndex, int pageNumber) {
        PageKey key = new PageKey(sourceIndex, pageNumber);
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

                Image image = SwingFXUtils.toFXImage(
                        rendering.renderThumbnail(documentAt(sourceIndex), pageNumber, edgePixels),
                        null);
                synchronized (cache) {
                    cache.put(key, image);
                }
                return image;
            }
        };
        renderer.execute(task);
        return task;
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
            renderer.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private PdfDocument documentAt(int sourceIndex) {
        synchronized (documents) {
            return documents.get(sourceIndex);
        }
    }
}
