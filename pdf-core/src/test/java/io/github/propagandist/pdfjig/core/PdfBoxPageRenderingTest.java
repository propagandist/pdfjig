package io.github.propagandist.pdfjig.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PdfBoxPageRenderingTest {

    @TempDir
    Path tempDir;

    private final PageRendering rendering = new PdfBoxPageRendering();

    @Test
    @DisplayName("サムネイルの長辺は指定した画素数になる")
    void fitsThumbnailToLongEdge() throws Exception {
        Path pdf = TestPdfs.plain(tempDir.resolve("doc.pdf"), 1);

        try (PdfDocument document = PdfDocument.open(pdf)) {
            BufferedImage image = rendering.renderThumbnail(document, 1, 120);

            assertEquals(120, Math.max(image.getWidth(), image.getHeight()));
            // レターサイズは縦長なので、短辺はそれより小さい。縦横比が保たれている証拠。
            assertTrue(image.getWidth() < image.getHeight());
        }
    }

    @Test
    @DisplayName("横長のページでも長辺に合わせて収まる")
    void fitsLandscapePage() throws Exception {
        // 90 度回転したページは、描画結果の幅と高さが入れ替わる。
        Path pdf = TestPdfs.rotated(tempDir.resolve("doc.pdf"), 90);

        try (PdfDocument document = PdfDocument.open(pdf)) {
            BufferedImage image = rendering.renderThumbnail(document, 1, 120);

            assertEquals(120, Math.max(image.getWidth(), image.getHeight()));
            assertTrue(image.getWidth() > image.getHeight());
        }
    }

    @Test
    @DisplayName("解像度を上げると画素数が増える")
    void scalesWithDpi() throws Exception {
        Path pdf = TestPdfs.plain(tempDir.resolve("doc.pdf"), 1);

        try (PdfDocument document = PdfDocument.open(pdf)) {
            BufferedImage low = rendering.render(document, 1, 36f);
            BufferedImage high = rendering.render(document, 1, 72f);

            assertEquals(low.getWidth() * 2, high.getWidth(), 1);
            assertEquals(low.getHeight() * 2, high.getHeight(), 1);
        }
    }

    @Test
    @DisplayName("範囲外のページを指定すると PAGE_OUT_OF_RANGE")
    void rejectsPageOutOfRange() throws Exception {
        Path pdf = TestPdfs.plain(tempDir.resolve("doc.pdf"), 2);

        try (PdfDocument document = PdfDocument.open(pdf)) {
            assertEquals(
                    ErrorCode.PAGE_OUT_OF_RANGE,
                    assertThrows(
                                    PdfjigException.class,
                                    () -> rendering.renderThumbnail(document, 3, 120))
                            .errorCode());
        }
    }

    @Test
    @DisplayName("描画は入力文書を変更しない")
    void leavesDocumentUntouched() throws Exception {
        Path pdf = TestPdfs.rotated(tempDir.resolve("doc.pdf"), 0, 90);

        try (PdfDocument document = PdfDocument.open(pdf)) {
            rendering.renderThumbnail(document, 1, 120);
            rendering.renderThumbnail(document, 2, 120);
        }

        assertEquals(List.of(0, 90), TestPdfs.rotationsOf(pdf));
    }
}
