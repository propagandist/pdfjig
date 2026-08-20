package io.github.propagandist.pdfjig.desktop;

import io.github.propagandist.pdfjig.core.PageRendering;
import io.github.propagandist.pdfjig.core.PdfBoxPageRendering;
import io.github.propagandist.pdfjig.core.PdfDocument;
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
 * <p>描画は専用のスレッド 1 本に直列化する。{@link PdfDocument} はスレッド安全ではなく、
 * 複数スレッドから同時に描画すると壊れるため（{@link PageRendering} の規約）。
 * 直列化はスクロールの応答性も保つ。1 本で足りない速度は、そもそも
 * 可視範囲だけを描画することで確保する。
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

    private final PdfDocument document;

    private final PageRendering rendering = new PdfBoxPageRendering();

    private final int edgePixels;

    /** 描画スレッドと JavaFX スレッドの双方から触るため、これ自身をロックに使う。 */
    private final LruCache<Integer, Image> cache = new LruCache<>(CACHE_CAPACITY);

    private final ExecutorService renderer = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "pdfjig-thumbnail");
        // ウィンドウを閉じてもプロセスが残らないようにする。
        thread.setDaemon(true);
        return thread;
    });

    /**
     * @param document   対象文書。このオブジェクトは文書を閉じない
     * @param edgePixels サムネイルの長辺の画素数
     */
    public ThumbnailSource(PdfDocument document, int edgePixels) {
        if (document == null) {
            throw new IllegalArgumentException("document は null にできません。");
        }
        if (edgePixels < 1) {
            throw new IllegalArgumentException("edgePixels は 1 以上でなければなりません。");
        }
        this.document = document;
        this.edgePixels = edgePixels;
    }

    /**
     * 既に描画済みのサムネイルを、待たずに取り出す。
     *
     * <p>セルが表示された瞬間に呼ぶ。ここで得られれば描画を待たずに済み、
     * スクロールが引っかからない。
     *
     * @param pageNumber ページ番号（1 始まり）
     * @return 保持していればそのサムネイル
     */
    public Optional<Image> cached(int pageNumber) {
        synchronized (cache) {
            return cache.get(pageNumber);
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
     * @param pageNumber ページ番号（1 始まり）
     * @return 実行中または待機中の描画
     */
    public Task<Image> request(int pageNumber) {
        Task<Image> task = new Task<>() {
            @Override
            protected Image call() {
                Optional<Image> hit;
                synchronized (cache) {
                    hit = cache.get(pageNumber);
                }
                if (hit.isPresent()) {
                    return hit.get();
                }

                Image image = SwingFXUtils.toFXImage(
                        rendering.renderThumbnail(document, pageNumber, edgePixels), null);
                synchronized (cache) {
                    cache.put(pageNumber, image);
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
}
