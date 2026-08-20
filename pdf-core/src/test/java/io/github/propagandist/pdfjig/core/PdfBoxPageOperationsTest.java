package io.github.propagandist.pdfjig.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PdfBoxPageOperationsTest {

    @TempDir
    Path tempDir;

    private final List<Warning> warnings = new ArrayList<>();

    private final PageOperations operations = new PdfBoxPageOperations(warnings::add);

    private final TextExtraction extraction = new PdfBoxTextExtraction();

    @Nested
    class Merge {

        @Test
        @DisplayName("入力の順にページが連結される")
        void concatenatesInInputOrder() throws Exception {
            Path first = TestPdfs.withText(tempDir.resolve("a.pdf"), "A1", "A2");
            Path second = TestPdfs.withText(tempDir.resolve("b.pdf"), "B1", "B2", "B3");

            Path merged = operations.merge(
                    List.of(first, second), tempDir.resolve("merged.pdf"), MergeOptions.defaults());

            assertEquals(List.of("A1", "A2", "B1", "B2", "B3"), textsOf(merged));
        }

        @Test
        @DisplayName("入力ファイルは変更されない")
        void leavesInputsUntouched() throws Exception {
            Path first = TestPdfs.withText(tempDir.resolve("a.pdf"), "A1", "A2");
            Path second = TestPdfs.withText(tempDir.resolve("b.pdf"), "B1");

            operations.merge(
                    List.of(first, second), tempDir.resolve("merged.pdf"), MergeOptions.defaults());

            assertEquals(List.of("A1", "A2"), textsOf(first));
            assertEquals(List.of("B1"), textsOf(second));
        }

        @Test
        @DisplayName("入力が空なら NO_INPUT")
        void rejectsEmptyInput() {
            assertEquals(
                    ErrorCode.NO_INPUT,
                    assertThrows(
                                    PdfjigException.class,
                                    () -> operations.merge(
                                            List.of(),
                                            tempDir.resolve("merged.pdf"),
                                            MergeOptions.defaults()))
                            .errorCode());
        }

        @Test
        @DisplayName("暗号化の引き継ぎを指定すると ENCRYPTION_PROPAGATION_UNSUPPORTED")
        void rejectsInheritPropagation() throws Exception {
            Path input = TestPdfs.plain(tempDir.resolve("a.pdf"), 1);
            MergeOptions options = new MergeOptions(EncryptionPropagation.INHERIT);

            assertEquals(
                    ErrorCode.ENCRYPTION_PROPAGATION_UNSUPPORTED,
                    assertThrows(
                                    PdfjigException.class,
                                    () -> operations.merge(
                                            List.of(input),
                                            tempDir.resolve("merged.pdf"),
                                            options))
                            .errorCode());
        }
    }

    @Nested
    class Split {

        @Test
        @DisplayName("ページ数ごとに区切り、連番のファイル名で書き出す")
        void splitsEveryNPages() throws Exception {
            Path input = TestPdfs.withText(
                    tempDir.resolve("doc.pdf"), "P1", "P2", "P3", "P4", "P5");
            Path outputDir = tempDir.resolve("out");

            List<Path> outputs = operations.split(
                    input, SplitStrategy.everyNPages(2), outputDir);

            assertEquals(3, outputs.size());
            assertEquals("doc_001.pdf", outputs.get(0).getFileName().toString());
            assertEquals("doc_003.pdf", outputs.get(2).getFileName().toString());
            assertEquals(List.of("P1", "P2"), textsOf(outputs.get(0)));
            assertEquals(List.of("P3", "P4"), textsOf(outputs.get(1)));
            assertEquals(List.of("P5"), textsOf(outputs.get(2)));
        }

        @Test
        @DisplayName("指定した範囲だけを切り出す")
        void splitsByRanges() throws Exception {
            Path input = TestPdfs.withText(
                    tempDir.resolve("doc.pdf"), "P1", "P2", "P3", "P4");

            List<Path> outputs = operations.split(
                    input,
                    SplitStrategy.byRanges(List.of(PageRange.of(2, 3), PageRange.singlePage(1))),
                    tempDir.resolve("out"));

            assertEquals(List.of("P2", "P3"), textsOf(outputs.get(0)));
            assertEquals(List.of("P1"), textsOf(outputs.get(1)));
        }

        @Test
        @DisplayName("境界指定では先頭ページ 1 が補われる")
        void completesFirstBoundary() throws Exception {
            Path input = TestPdfs.withText(
                    tempDir.resolve("doc.pdf"), "P1", "P2", "P3", "P4");

            List<Path> outputs = operations.split(
                    input, SplitStrategy.atBoundaries(List.of(3)), tempDir.resolve("out"));

            assertEquals(2, outputs.size());
            assertEquals(List.of("P1", "P2"), textsOf(outputs.get(0)));
            assertEquals(List.of("P3", "P4"), textsOf(outputs.get(1)));
        }

        @Test
        @DisplayName("出力先ディレクトリは必要なら作る")
        void createsOutputDirectory() throws Exception {
            Path input = TestPdfs.plain(tempDir.resolve("doc.pdf"), 2);
            Path outputDir = tempDir.resolve("nested").resolve("out");

            operations.split(input, SplitStrategy.everyNPages(1), outputDir);

            assertTrue(Files.isDirectory(outputDir));
        }

        @Test
        @DisplayName("出力名が 1 つでも既存なら、何も書かずに失敗する")
        void writesNothingWhenAnyOutputExists() throws Exception {
            Path input = TestPdfs.plain(tempDir.resolve("doc.pdf"), 3);
            Path outputDir = Files.createDirectory(tempDir.resolve("out"));
            Files.createFile(outputDir.resolve("doc_003.pdf"));

            assertEquals(
                    ErrorCode.OUTPUT_ALREADY_EXISTS,
                    assertThrows(
                                    PdfjigException.class,
                                    () -> operations.split(
                                            input, SplitStrategy.everyNPages(1), outputDir))
                            .errorCode());
            assertFalse(Files.exists(outputDir.resolve("doc_001.pdf")));
        }
    }

    @Nested
    class Reorder {

        @Test
        @DisplayName("指定した順序でページが並ぶ")
        void appliesNewOrder() throws Exception {
            Path input = TestPdfs.withText(tempDir.resolve("doc.pdf"), "P1", "P2", "P3");

            Path output = operations.reorder(
                    input, List.of(3, 1, 2), tempDir.resolve("reordered.pdf"));

            assertEquals(List.of("P3", "P1", "P2"), textsOf(output));
            assertEquals(List.of("P1", "P2", "P3"), textsOf(input));
        }

        @Test
        @DisplayName("全ページの並べ替えでなければ INVALID_PAGE_ORDER")
        void rejectsIncompleteOrder() throws Exception {
            Path input = TestPdfs.plain(tempDir.resolve("doc.pdf"), 3);

            assertEquals(ErrorCode.INVALID_PAGE_ORDER, reorderFailure(input, List.of(1, 2)));
            assertEquals(ErrorCode.INVALID_PAGE_ORDER, reorderFailure(input, List.of(1, 2, 2)));
            assertEquals(ErrorCode.INVALID_PAGE_ORDER, reorderFailure(input, List.of(1, 2, 4)));
        }

        private ErrorCode reorderFailure(Path input, List<Integer> newOrder) {
            Path output = tempDir.resolve("reordered-" + newOrder.hashCode() + ".pdf");
            return assertThrows(
                            PdfjigException.class,
                            () -> operations.reorder(input, newOrder, output))
                    .errorCode();
        }
    }

    @Nested
    class Rotate {

        @Test
        @DisplayName("回転は現在の角度に加算される")
        void addsToCurrentRotation() throws Exception {
            Path input = TestPdfs.rotated(tempDir.resolve("doc.pdf"), 0, 90, 270);

            Path output = operations.rotate(
                    input,
                    Map.of(1, Rotation.CLOCKWISE_90, 2, Rotation.CLOCKWISE_90,
                            3, Rotation.CLOCKWISE_90),
                    tempDir.resolve("rotated.pdf"));

            assertEquals(List.of(90, 180, 0), TestPdfs.rotationsOf(output));
        }

        @Test
        @DisplayName("指定のないページはそのまま保たれる")
        void keepsUnlistedPages() throws Exception {
            Path input = TestPdfs.rotated(tempDir.resolve("doc.pdf"), 0, 180);

            Path output = operations.rotate(
                    input, Map.of(1, Rotation.HALF_TURN), tempDir.resolve("rotated.pdf"));

            assertEquals(List.of(180, 180), TestPdfs.rotationsOf(output));
            assertEquals(List.of(0, 180), TestPdfs.rotationsOf(input));
        }

        @Test
        @DisplayName("範囲外のページを指定すると PAGE_OUT_OF_RANGE")
        void rejectsPageOutOfRange() throws Exception {
            Path input = TestPdfs.plain(tempDir.resolve("doc.pdf"), 2);

            assertEquals(
                    ErrorCode.PAGE_OUT_OF_RANGE,
                    assertThrows(
                                    PdfjigException.class,
                                    () -> operations.rotate(
                                            input,
                                            Map.of(3, Rotation.CLOCKWISE_90),
                                            tempDir.resolve("rotated.pdf")))
                            .errorCode());
        }

        @Test
        @DisplayName("PDF 仕様に反する回転角は 0 度として扱われる")
        void treatsMalformedRotationAsZero() throws Exception {
            Path input = TestPdfs.rotated(tempDir.resolve("doc.pdf"), 45);

            Path output = operations.rotate(
                    input, Map.of(1, Rotation.CLOCKWISE_90), tempDir.resolve("rotated.pdf"));

            // PDF 仕様は /Rotate を 90 の倍数に限る。PDFBox は仕様外の値を 0 と解釈するため、
            // 45 度のページに 90 度を加えた結果は 135 度ではなく 90 度になる。
            assertEquals(List.of(90), TestPdfs.rotationsOf(output));
        }
    }

    @Nested
    class ExtractAndDelete {

        @Test
        @DisplayName("範囲のページだけが取り出される")
        void extractsRange() throws Exception {
            Path input = TestPdfs.withText(
                    tempDir.resolve("doc.pdf"), "P1", "P2", "P3", "P4");

            Path output = operations.extractPages(
                    input, PageRange.of(2, 3), tempDir.resolve("extracted.pdf"));

            assertEquals(List.of("P2", "P3"), textsOf(output));
        }

        @Test
        @DisplayName("範囲のページが取り除かれる")
        void deletesRange() throws Exception {
            Path input = TestPdfs.withText(
                    tempDir.resolve("doc.pdf"), "P1", "P2", "P3", "P4");

            Path output = operations.deletePages(
                    input, PageRange.of(2, 3), tempDir.resolve("deleted.pdf"));

            assertEquals(List.of("P1", "P4"), textsOf(output));
        }

        @Test
        @DisplayName("全ページの削除は EMPTY_RESULT")
        void rejectsDeletingEveryPage() throws Exception {
            Path input = TestPdfs.plain(tempDir.resolve("doc.pdf"), 2);

            assertEquals(
                    ErrorCode.EMPTY_RESULT,
                    assertThrows(
                                    PdfjigException.class,
                                    () -> operations.deletePages(
                                            input,
                                            PageRange.of(1, 2),
                                            tempDir.resolve("deleted.pdf")))
                            .errorCode());
        }

        @Test
        @DisplayName("文書に収まらない範囲は PAGE_OUT_OF_RANGE")
        void rejectsRangeBeyondDocument() throws Exception {
            Path input = TestPdfs.plain(tempDir.resolve("doc.pdf"), 2);

            assertEquals(
                    ErrorCode.PAGE_OUT_OF_RANGE,
                    assertThrows(
                                    PdfjigException.class,
                                    () -> operations.extractPages(
                                            input,
                                            PageRange.of(2, 5),
                                            tempDir.resolve("extracted.pdf")))
                            .errorCode());
        }
    }

    @Nested
    class OutputProtection {

        @Test
        @DisplayName("出力先が既にあれば上書きせずに失敗する")
        void neverOverwrites() throws Exception {
            Path input = TestPdfs.plain(tempDir.resolve("doc.pdf"), 2);
            Path output = Files.createFile(tempDir.resolve("taken.pdf"));

            assertEquals(
                    ErrorCode.OUTPUT_ALREADY_EXISTS,
                    assertThrows(
                                    PdfjigException.class,
                                    () -> operations.extractPages(
                                            input, PageRange.singlePage(1), output))
                            .errorCode());
            assertEquals(0L, Files.size(output));
        }

        @Test
        @DisplayName("入力を出力に指定しても入力は壊れない")
        void refusesToWriteOntoItsInput() throws Exception {
            Path input = TestPdfs.withText(tempDir.resolve("doc.pdf"), "P1", "P2");

            assertEquals(
                    ErrorCode.OUTPUT_ALREADY_EXISTS,
                    assertThrows(
                                    PdfjigException.class,
                                    () -> operations.reorder(input, List.of(2, 1), input))
                            .errorCode());
            assertEquals(List.of("P1", "P2"), textsOf(input));
        }
    }

    @Nested
    class EncryptionPropagationWarning {

        @Test
        @DisplayName("暗号化された入力を扱うと警告し、出力は平文になる")
        void warnsAndDropsProtection() throws Exception {
            // ユーザーパスワードが空なので開けるが、暗号化はされている。
            // 黙って平文で出すと、利用者は保護されているつもりで配布することになる。
            Path input = TestPdfs.ownerProtected(tempDir.resolve("protected.pdf"), "owner", 2);

            Path output = operations.extractPages(
                    input, PageRange.singlePage(1), tempDir.resolve("extracted.pdf"));

            assertEquals(List.of(Warning.ENCRYPTION_NOT_PROPAGATED), warnings);
            try (PdfDocument result = PdfDocument.open(output)) {
                assertFalse(result.encrypted());
            }
        }

        @Test
        @DisplayName("回転でも保護は落ち、警告される")
        void warnsOnRotate() throws Exception {
            Path input = TestPdfs.ownerProtected(tempDir.resolve("protected.pdf"), "owner", 1);

            Path output = operations.rotate(
                    input, Map.of(1, Rotation.CLOCKWISE_90), tempDir.resolve("rotated.pdf"));

            assertEquals(List.of(Warning.ENCRYPTION_NOT_PROPAGATED), warnings);
            try (PdfDocument result = PdfDocument.open(output)) {
                assertFalse(result.encrypted());
            }
        }

        @Test
        @DisplayName("平文の入力では警告しない")
        void staysSilentForPlainInput() throws Exception {
            Path input = TestPdfs.plain(tempDir.resolve("doc.pdf"), 2);

            operations.extractPages(
                    input, PageRange.singlePage(1), tempDir.resolve("extracted.pdf"));

            assertTrue(warnings.isEmpty());
        }
    }

    /** ページごとの本文。空白を落として比較しやすくする。 */
    private List<String> textsOf(Path pdf) {
        try (PdfDocument document = PdfDocument.open(pdf)) {
            return extraction.extractByPage(document).stream()
                    .map(page -> page.text().trim())
                    .toList();
        }
    }
}
