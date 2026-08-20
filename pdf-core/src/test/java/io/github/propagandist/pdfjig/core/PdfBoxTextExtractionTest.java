package io.github.propagandist.pdfjig.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PdfBoxTextExtractionTest {

    @TempDir
    Path tempDir;

    private final TextExtraction extraction = new PdfBoxTextExtraction();

    @Test
    @DisplayName("全文抽出はすべてのページのテキストを含む")
    void extractsTextOfEveryPage() throws Exception {
        Path pdf = TestPdfs.withText(tempDir.resolve("text.pdf"), "First", "Second", "Third");

        try (PdfDocument document = PdfDocument.open(pdf)) {
            String text = extraction.extractAll(document);

            assertTrue(text.contains("First"));
            assertTrue(text.contains("Second"));
            assertTrue(text.contains("Third"));
        }
    }

    @Test
    @DisplayName("改行コードは LF に正規化され CR を含まない")
    void normalizesLineSeparator() throws Exception {
        Path pdf = TestPdfs.withText(tempDir.resolve("text.pdf"), "First", "Second");

        try (PdfDocument document = PdfDocument.open(pdf)) {
            // 環境の line.separator に従うと、同じ入力から環境ごとに異なる出力が出る。
            assertFalse(extraction.extractAll(document).contains("\r"));
        }
    }

    @Test
    @DisplayName("ページ単位の抽出はページ番号を 1 から振る")
    void numbersPagesFromOne() throws Exception {
        Path pdf = TestPdfs.withText(tempDir.resolve("text.pdf"), "First", "Second", "Third");

        try (PdfDocument document = PdfDocument.open(pdf)) {
            List<PageText> pages = extraction.extractByPage(document);

            assertEquals(3, pages.size());
            assertEquals(1, pages.get(0).pageNumber());
            assertEquals(3, pages.get(2).pageNumber());
            assertTrue(pages.get(1).text().contains("Second"));
            assertFalse(pages.get(1).text().contains("First"));
        }
    }

    @Test
    @DisplayName("テキストを持たないページでも抽出は失敗しない")
    void handlesPageWithoutText() throws Exception {
        Path pdf = TestPdfs.plain(tempDir.resolve("blank.pdf"), 2);

        try (PdfDocument document = PdfDocument.open(pdf)) {
            assertEquals(2, extraction.extractByPage(document).size());
            assertTrue(extraction.extractWithPositions(document, 1).isEmpty());
        }
    }

    @Test
    @DisplayName("座標付き抽出はグリフごとに 1 要素を返す")
    void returnsOneElementPerGlyph() throws Exception {
        Path pdf = TestPdfs.withText(tempDir.resolve("text.pdf"), "Hello");

        try (PdfDocument document = PdfDocument.open(pdf)) {
            List<PositionedText> glyphs = extraction.extractWithPositions(document, 1);

            assertEquals(5, glyphs.size());
            assertEquals("H", glyphs.get(0).text());
            assertEquals("o", glyphs.get(4).text());
        }
    }

    @Test
    @DisplayName("座標の原点はページの左上にある")
    void placesOriginAtTopLeft() throws Exception {
        Path pdf = TestPdfs.withText(tempDir.resolve("text.pdf"), "Hello");

        try (PdfDocument document = PdfDocument.open(pdf)) {
            PositionedText first = extraction.extractWithPositions(document, 1).get(0);

            // 左マージン 72pt に描いた文字は、そのまま x=72 付近に来る。
            assertEquals(TestPdfs.TEXT_LEFT, first.x(), 1.0f);
            // 上端から 72pt がベースライン。グリフ上端はそれより上（y が小さい）。
            // 左下原点のままなら y は 700 前後になるため、この検証で取り違えを検出できる。
            assertTrue(first.y() > 0f && first.y() < TestPdfs.TEXT_TOP,
                    "上端からの距離として妥当でない y: " + first.y());
            assertTrue(first.width() > 0f);
            assertTrue(first.height() > 0f);
            assertEquals(TestPdfs.FONT_SIZE, first.fontSize(), 0.01f);
        }
    }

    @Test
    @DisplayName("範囲外のページを指定すると PAGE_OUT_OF_RANGE")
    void rejectsPageOutOfRange() throws Exception {
        Path pdf = TestPdfs.withText(tempDir.resolve("text.pdf"), "Only");

        try (PdfDocument document = PdfDocument.open(pdf)) {
            assertEquals(
                    ErrorCode.PAGE_OUT_OF_RANGE,
                    assertThrows(
                                    PdfjigException.class,
                                    () -> extraction.extractWithPositions(document, 2))
                            .errorCode());
        }
    }
}
