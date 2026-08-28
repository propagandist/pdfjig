package io.github.propagandist.pdfjig.desktop;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.propagandist.pdfjig.core.PageRendering;
import io.github.propagandist.pdfjig.core.PdfDocument;
import io.github.propagandist.pdfjig.core.TestPdfs;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import javafx.concurrent.Task;
import javafx.scene.image.Image;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.api.FxToolkit;

/**
 * 受け持っている文書を外したときの {@link ThumbnailSource} の振る舞い。
 *
 * <p><b>画面は立てない。</b> それでもここ（{@code uiTest}）に置いてあるのは、
 * JavaFX Toolkit を要するためである——{@link Task} の状態遷移は
 * {@code Platform.runLater} を通り、{@code SwingFXUtils.toFXImage} は
 * JavaFX の画像を作る。{@code src/test} は ubuntu の {@code build} でも走るので、
 * そちらへ置くと Toolkit を起こせずに落ちる。
 *
 * <p>TestFX の robot は使わない。{@link DesktopUiTest} を継承していないのはそのためで、
 * 開発機のマウスとキーボードを取り上げない。
 *
 * <p>描画は {@link GatedRendering} で止める。実際の描画の速さで待ち合わせを当てにいくと、
 * 落ちるかどうかが機械の速さで決まるテストになる（CLAUDE.md「不安定なテストの扱い」）。
 */
class ThumbnailSourceUiTest {

    /** 何かが起きるのを待つ上限。CI のランナーは遅いので短くしない。 */
    private static final int TIMEOUT_SECONDS = 20;

    /**
     * 外す側が戻ってしまわないことを見るための猶予。
     *
     * <p><b>これは「待てば直る」種類の待ちではない。</b> 直っていれば描画を解放するまで
     * 絶対に戻れないので、長く取っても偽の緑にならない。短すぎると、外す側のスレッドが
     * まだ動き出していないだけの状態を「待っている」と読みうるので、そちら側に倒してある。
     */
    private static final long GRACE_MILLIS = 2_000L;

    private static final int EDGE_PIXELS = 32;

    private final List<PdfDocument> opened = new ArrayList<>();

    private ThumbnailSource source;

    @BeforeAll
    static void startToolkit() throws Exception {
        // 画面は出さない。JavaFX Toolkit を起こすためだけに呼ぶ。
        FxToolkit.registerPrimaryStage();
    }

    @AfterEach
    void tearDown() {
        if (source != null) {
            source.close();
        }
        opened.forEach(PdfDocument::close);
        opened.clear();
    }

    @Test
    void 外す前に始まった描画が終わるまで待ってから捨てる(@TempDir Path directory) throws Exception {
        GatedRendering rendering = new GatedRendering();
        source = new ThumbnailSource(EDGE_PIXELS, rendering);
        // 3 つ受け持たせる。外すと番号が繰り下がり、旧 2 が新 1 になる。
        // 2 つでは繰り下がった先の鍵を引く相手が居ないので、この不具合は再現しない。
        for (int i = 0; i < 3; i++) {
            source.addSource(open(directory, i));
        }

        Task<Image> drawing = source.request(1, 1);
        assertTrue(rendering.started.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "描画が始まらない");

        Thread remover = new Thread(() -> source.removeSource(0), "remover");
        remover.start();
        remover.join(GRACE_MILLIS);
        boolean waitedForRendering = remover.isAlive();

        rendering.release.countDown();
        // 戻った時点で、描画の結果はキャッシュに入り終えている。
        drawing.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        remover.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));

        // 2 つの穴（待たないこと・古い鍵で入り直すこと）は根が 1 つなので、まとめて見る。
        assertAll(
                () -> assertFalse(remover.isAlive(), "描画を解放しても外す側が戻らない"),
                () -> assertTrue(waitedForRendering, "removeSource が、走っている描画の終わりを待たずに戻った"),
                () -> assertTrue(source.cached(1, 1).isEmpty(), "外す前に始まった描画の結果が、繰り下がった番号の鍵で残っている"));
    }

    @Test
    void 外した後の番号で描画を頼まれても素の例外を投げない(@TempDir Path directory) throws Exception {
        GatedRendering rendering = new GatedRendering();
        rendering.release.countDown();
        source = new ThumbnailSource(EDGE_PIXELS, rendering);
        source.addSource(open(directory, 0));

        // 一覧が組み直される前に、外した文書の番号で依頼が来ることがある。
        Task<Image> drawing = assertDoesNotThrow(() -> source.request(1, 1), "範囲外の出どころ番号で素の例外が JavaFX スレッドへ出る");

        // 失敗として返れば、呼び出し側が「（表示できません）」を出せる。
        assertThrows(ExecutionException.class, () -> drawing.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }

    private PdfDocument open(Path directory, int index) throws IOException {
        Path file = TestPdfs.plain(directory.resolve("source-" + index + ".pdf"), 3);
        PdfDocument document = PdfDocument.open(file);
        opened.add(document);
        return document;
    }

    /** 解放するまで描画を止めたままにする実装。 */
    private static final class GatedRendering implements PageRendering {

        private final CountDownLatch started = new CountDownLatch(1);

        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public BufferedImage renderThumbnail(PdfDocument document, int pageNumber, int maxEdgePixels) {
            started.countDown();
            try {
                if (!release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("描画が解放されないまま上限に達した。");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("描画が割り込まれた。");
            }
            return new BufferedImage(maxEdgePixels, maxEdgePixels, BufferedImage.TYPE_INT_RGB);
        }

        @Override
        public BufferedImage render(PdfDocument document, int pageNumber, float dpi) {
            throw new UnsupportedOperationException("サムネイル以外の描画はこの経路を通らない。");
        }
    }
}
