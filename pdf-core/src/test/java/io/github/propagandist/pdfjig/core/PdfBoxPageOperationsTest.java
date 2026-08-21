package io.github.propagandist.pdfjig.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDPageLabels;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode;
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

            Path merged =
                    operations.merge(List.of(first, second), tempDir.resolve("merged.pdf"), MergeOptions.defaults());

            assertEquals(List.of("A1", "A2", "B1", "B2", "B3"), textsOf(merged));
        }

        @Test
        @DisplayName("入力ファイルは変更されない")
        void leavesInputsUntouched() throws Exception {
            Path first = TestPdfs.withText(tempDir.resolve("a.pdf"), "A1", "A2");
            Path second = TestPdfs.withText(tempDir.resolve("b.pdf"), "B1");

            operations.merge(List.of(first, second), tempDir.resolve("merged.pdf"), MergeOptions.defaults());

            assertEquals(List.of("A1", "A2"), textsOf(first));
            assertEquals(List.of("B1"), textsOf(second));
        }

        @Test
        @DisplayName("文書情報は先頭の入力のものが残り、その旨を伝える")
        void keepsDocumentInformationOfFirstInput() throws Exception {
            Path first = TestPdfs.rich(tempDir.resolve("a.pdf"), "A1", "A2");
            Path second = TestPdfs.withText(tempDir.resolve("b.pdf"), "B1");

            Path merged =
                    operations.merge(List.of(first, second), tempDir.resolve("merged.pdf"), MergeOptions.defaults());

            assertEquals(TestPdfs.DOCUMENT_TITLE, titleOf(merged));
            assertTrue(warnings.contains(Warning.METADATA_FROM_FIRST_INPUT));
        }

        @Test
        @DisplayName("2 つ目にしか題名が無くても、先頭の入力の文書情報が使われる")
        void alwaysTakesInformationFromTheFirstInput() throws Exception {
            Path first = TestPdfs.withText(tempDir.resolve("a.pdf"), "A1");
            Path second = TestPdfs.rich(tempDir.resolve("b.pdf"), "B1");

            Path merged =
                    operations.merge(List.of(first, second), tempDir.resolve("merged.pdf"), MergeOptions.defaults());

            assertNull(titleOf(merged), "先頭の入力に題名が無いのに題名が付いている。");
        }

        @Test
        @DisplayName("入力が 1 つなら、文書情報の警告は出ない")
        void doesNotWarnAboutMetadataForSingleInput() throws Exception {
            Path only = TestPdfs.rich(tempDir.resolve("a.pdf"), "A1", "A2");

            Path merged = operations.merge(List.of(only), tempDir.resolve("merged.pdf"), MergeOptions.defaults());

            assertEquals(TestPdfs.DOCUMENT_TITLE, titleOf(merged));
            assertFalse(warnings.contains(Warning.METADATA_FROM_FIRST_INPUT));
        }

        @Test
        @DisplayName("入力が空なら NO_INPUT")
        void rejectsEmptyInput() {
            assertEquals(
                    ErrorCode.NO_INPUT,
                    assertThrows(
                                    PdfjigException.class,
                                    () -> operations.merge(
                                            List.of(), tempDir.resolve("merged.pdf"), MergeOptions.defaults()))
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
                                    () -> operations.merge(List.of(input), tempDir.resolve("merged.pdf"), options))
                            .errorCode());
        }
    }

    @Nested
    class Split {

        @Test
        @DisplayName("ページ数ごとに区切り、連番のファイル名で書き出す")
        void splitsEveryNPages() throws Exception {
            Path input = TestPdfs.withText(tempDir.resolve("doc.pdf"), "P1", "P2", "P3", "P4", "P5");
            Path outputDir = tempDir.resolve("out");

            List<Path> outputs = operations.split(input, SplitStrategy.everyNPages(2), outputDir);

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
            Path input = TestPdfs.withText(tempDir.resolve("doc.pdf"), "P1", "P2", "P3", "P4");

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
            Path input = TestPdfs.withText(tempDir.resolve("doc.pdf"), "P1", "P2", "P3", "P4");

            List<Path> outputs =
                    operations.split(input, SplitStrategy.atBoundaries(List.of(3)), tempDir.resolve("out"));

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
                                    () -> operations.split(input, SplitStrategy.everyNPages(1), outputDir))
                            .errorCode());
            assertFalse(Files.exists(outputDir.resolve("doc_001.pdf")));
        }

        @Test
        @DisplayName("書き出しの途中で失敗したら、それまでに書いたものも残さない")
        void removesPartialOutputOnFailure() throws Exception {
            // 出力先が既にある場合は書き出しに入る前に弾かれるため、この経路は通らない。
            // 実際に途中で落ちるのはディスクが尽きた・権限を失ったといった場合であり、
            // それは環境に依らせて起こせない。警告の通知を故意に失敗させて代わりにする。
            //
            // ページどうしがリンクで繋がった文書を 1 ページずつに切ると、宛先を失った
            // 参照の警告が出力ごとに 1 度ずつ出る。その 2 度目で落とせば、
            // 1 つ目を書き終えて 2 つ目を書く前という狙った位置で失敗する。
            Path input = TestPdfs.withInternalLinks(tempDir.resolve("doc.pdf"), "P1", "P2", "P3");
            Path outputDir = tempDir.resolve("out");

            AtomicInteger notified = new AtomicInteger();
            PageOperations failing = new PdfBoxPageOperations(warning -> {
                if (notified.incrementAndGet() == 2) {
                    throw new IllegalStateException("書き出しの途中で失敗させる");
                }
            });

            assertThrows(
                    IllegalStateException.class, () -> failing.split(input, SplitStrategy.everyNPages(1), outputDir));

            assertEquals(List.of(), listFilesIn(outputDir), "途中まで書いた出力が残っている。");
        }
    }

    @Nested
    class Reorder {

        @Test
        @DisplayName("指定した順序でページが並ぶ")
        void appliesNewOrder() throws Exception {
            Path input = TestPdfs.withText(tempDir.resolve("doc.pdf"), "P1", "P2", "P3");

            Path output = operations.reorder(input, List.of(3, 1, 2), tempDir.resolve("reordered.pdf"));

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
            return assertThrows(PdfjigException.class, () -> operations.reorder(input, newOrder, output))
                    .errorCode();
        }
    }

    @Nested
    class Assemble {

        @Test
        @DisplayName("指定したページを指定した順に並べる")
        void keepsGivenSelectionAndOrder() throws Exception {
            Path input = TestPdfs.withText(tempDir.resolve("doc.pdf"), "P1", "P2", "P3", "P4");

            Path output = operations.assemble(
                    input, List.of(PageSelection.of(4), PageSelection.of(1)), tempDir.resolve("assembled.pdf"));

            assertEquals(List.of("P4", "P1"), textsOf(output));
        }

        @Test
        @DisplayName("同じページを複数回含められる")
        void allowsRepeatedPages() throws Exception {
            Path input = TestPdfs.withText(tempDir.resolve("doc.pdf"), "P1", "P2");

            Path output = operations.assemble(
                    input,
                    List.of(PageSelection.of(2), PageSelection.of(2), PageSelection.of(1)),
                    tempDir.resolve("assembled.pdf"));

            assertEquals(List.of("P2", "P2", "P1"), textsOf(output));
        }

        @Test
        @DisplayName("並べ替えと回転を一度に確定できる")
        void appliesOrderAndRotationTogether() throws Exception {
            Path input = TestPdfs.rotated(tempDir.resolve("doc.pdf"), 0, 90, 180);

            Path output = operations.assemble(
                    input,
                    List.of(
                            new PageSelection(0, 3, Rotation.NONE),
                            new PageSelection(0, 2, Rotation.CLOCKWISE_90),
                            new PageSelection(0, 1, Rotation.HALF_TURN)),
                    tempDir.resolve("assembled.pdf"));

            // 元の向きに加算される。3 ページ目は 180 のまま、2 ページ目は 90+90、
            // 1 ページ目は 0+180。
            assertEquals(List.of(180, 180, 180), TestPdfs.rotationsOf(output));
        }

        @Test
        @DisplayName("同じページを別々の向きで含められる")
        void rotatesRepeatedPagesIndependently() throws Exception {
            Path input = TestPdfs.rotated(tempDir.resolve("doc.pdf"), 0);

            Path output = operations.assemble(
                    input,
                    List.of(PageSelection.of(1), new PageSelection(0, 1, Rotation.CLOCKWISE_90)),
                    tempDir.resolve("assembled.pdf"));

            assertEquals(List.of(0, 90), TestPdfs.rotationsOf(output));
        }

        @Test
        @DisplayName("回転しても入力は変わらない")
        void leavesInputUntouched() throws Exception {
            Path input = TestPdfs.rotated(tempDir.resolve("doc.pdf"), 0, 90);

            operations.assemble(
                    input,
                    List.of(
                            new PageSelection(0, 1, Rotation.CLOCKWISE_90),
                            new PageSelection(0, 2, Rotation.CLOCKWISE_90)),
                    tempDir.resolve("assembled.pdf"));

            assertEquals(List.of(0, 90), TestPdfs.rotationsOf(input));
        }

        @Test
        @DisplayName("空の指定は EMPTY_RESULT")
        void rejectsEmptySelection() throws Exception {
            Path input = TestPdfs.plain(tempDir.resolve("doc.pdf"), 2);

            assertEquals(
                    ErrorCode.EMPTY_RESULT,
                    assertThrows(
                                    PdfjigException.class,
                                    () -> operations.assemble(input, List.of(), tempDir.resolve("assembled.pdf")))
                            .errorCode());
        }

        @Test
        @DisplayName("範囲外のページを含むと PAGE_OUT_OF_RANGE")
        void rejectsPageOutOfRange() throws Exception {
            Path input = TestPdfs.plain(tempDir.resolve("doc.pdf"), 2);

            assertEquals(
                    ErrorCode.PAGE_OUT_OF_RANGE,
                    assertThrows(
                                    PdfjigException.class,
                                    () -> operations.assemble(
                                            input,
                                            List.of(PageSelection.of(1), PageSelection.of(3)),
                                            tempDir.resolve("assembled.pdf")))
                            .errorCode());
        }
    }

    @Nested
    class AssembleAcrossInputs {

        @Test
        @DisplayName("複数の入力からページを混ぜて並べられる")
        void mixesPagesFromSeveralInputs() throws Exception {
            Path first = TestPdfs.withText(tempDir.resolve("first.pdf"), "A1", "A2");
            Path second = TestPdfs.withText(tempDir.resolve("second.pdf"), "B1", "B2", "B3");

            Path output = operations.assemble(
                    List.of(first, second),
                    List.of(PageSelection.of(1, 3), PageSelection.of(0, 1), PageSelection.of(1, 1)),
                    tempDir.resolve("assembled.pdf"));

            assertEquals(List.of("B3", "A1", "B1"), textsOf(output));
        }

        @Test
        @DisplayName("出どころごとに向きを加えられる")
        void rotatesPerSource() throws Exception {
            Path first = TestPdfs.rotated(tempDir.resolve("first.pdf"), 0);
            Path second = TestPdfs.rotated(tempDir.resolve("second.pdf"), 90);

            Path output = operations.assemble(
                    List.of(first, second),
                    List.of(
                            new PageSelection(0, 1, Rotation.CLOCKWISE_90),
                            new PageSelection(1, 1, Rotation.CLOCKWISE_90)),
                    tempDir.resolve("assembled.pdf"));

            assertEquals(List.of(90, 180), TestPdfs.rotationsOf(output));
        }

        @Test
        @DisplayName("混ぜても入力は変わらない")
        void leavesInputsUntouched() throws Exception {
            Path first = TestPdfs.rotated(tempDir.resolve("first.pdf"), 0);
            Path second = TestPdfs.rotated(tempDir.resolve("second.pdf"), 90);

            operations.assemble(
                    List.of(first, second),
                    List.of(new PageSelection(1, 1, Rotation.HALF_TURN), new PageSelection(0, 1, Rotation.HALF_TURN)),
                    tempDir.resolve("assembled.pdf"));

            assertEquals(List.of(0), TestPdfs.rotationsOf(first));
            assertEquals(List.of(90), TestPdfs.rotationsOf(second));
        }

        @Test
        @DisplayName("範囲外の出どころを指すと PAGE_OUT_OF_RANGE")
        void rejectsSourceOutOfRange() throws Exception {
            Path input = TestPdfs.plain(tempDir.resolve("doc.pdf"), 2);

            assertEquals(
                    ErrorCode.PAGE_OUT_OF_RANGE,
                    assertThrows(
                                    PdfjigException.class,
                                    () -> operations.assemble(
                                            List.of(input),
                                            List.of(PageSelection.of(1, 1)),
                                            tempDir.resolve("assembled.pdf")))
                            .errorCode());
        }

        @Test
        @DisplayName("ページ番号は出どころごとに数える")
        void countsPagesPerSource() throws Exception {
            Path first = TestPdfs.plain(tempDir.resolve("first.pdf"), 5);
            Path second = TestPdfs.plain(tempDir.resolve("second.pdf"), 1);

            // 2 つ目は 1 ページしかない。合計で数えていれば通ってしまう指定。
            assertEquals(
                    ErrorCode.PAGE_OUT_OF_RANGE,
                    assertThrows(
                                    PdfjigException.class,
                                    () -> operations.assemble(
                                            List.of(first, second),
                                            List.of(PageSelection.of(1, 2)),
                                            tempDir.resolve("assembled.pdf")))
                            .errorCode());
        }

        @Test
        @DisplayName("入力が空だと NO_INPUT")
        void rejectsEmptyInputs() {
            assertEquals(
                    ErrorCode.NO_INPUT,
                    assertThrows(
                                    PdfjigException.class,
                                    () -> operations.assemble(
                                            List.of(), List.of(PageSelection.of(1)), tempDir.resolve("assembled.pdf")))
                            .errorCode());
        }

        @Test
        @DisplayName("暗号化された入力が混ざれば、その分だけ警告が出る")
        void warnsForEachEncryptedInput() throws Exception {
            Path plain = TestPdfs.plain(tempDir.resolve("plain.pdf"), 1);
            Path protectedByOwner = TestPdfs.ownerProtected(tempDir.resolve("owner.pdf"), "owner", 1);

            List<Warning> collected = new ArrayList<>();
            PageOperations warned = new PdfBoxPageOperations(collected::add);

            warned.assemble(
                    List.of(plain, protectedByOwner),
                    List.of(PageSelection.of(0, 1), PageSelection.of(1, 1)),
                    tempDir.resolve("assembled.pdf"));

            // 他の警告も出るが、ここで見たいのは暗号化の警告が入力 1 つにつき 1 度であること。
            assertEquals(
                    List.of(Warning.ENCRYPTION_NOT_PROPAGATED),
                    collected.stream()
                            .filter(warning -> warning == Warning.ENCRYPTION_NOT_PROPAGATED)
                            .toList());
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
                    Map.of(1, Rotation.CLOCKWISE_90, 2, Rotation.CLOCKWISE_90, 3, Rotation.CLOCKWISE_90),
                    tempDir.resolve("rotated.pdf"));

            assertEquals(List.of(90, 180, 0), TestPdfs.rotationsOf(output));
        }

        @Test
        @DisplayName("指定のないページはそのまま保たれる")
        void keepsUnlistedPages() throws Exception {
            Path input = TestPdfs.rotated(tempDir.resolve("doc.pdf"), 0, 180);

            Path output = operations.rotate(input, Map.of(1, Rotation.HALF_TURN), tempDir.resolve("rotated.pdf"));

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
                                            input, Map.of(3, Rotation.CLOCKWISE_90), tempDir.resolve("rotated.pdf")))
                            .errorCode());
        }

        @Test
        @DisplayName("PDF 仕様に反する回転角は 0 度として扱われる")
        void treatsMalformedRotationAsZero() throws Exception {
            Path input = TestPdfs.rotated(tempDir.resolve("doc.pdf"), 45);

            Path output = operations.rotate(input, Map.of(1, Rotation.CLOCKWISE_90), tempDir.resolve("rotated.pdf"));

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
            Path input = TestPdfs.withText(tempDir.resolve("doc.pdf"), "P1", "P2", "P3", "P4");

            Path output = operations.extractPages(input, PageRange.of(2, 3), tempDir.resolve("extracted.pdf"));

            assertEquals(List.of("P2", "P3"), textsOf(output));
        }

        @Test
        @DisplayName("範囲のページが取り除かれる")
        void deletesRange() throws Exception {
            Path input = TestPdfs.withText(tempDir.resolve("doc.pdf"), "P1", "P2", "P3", "P4");

            Path output = operations.deletePages(input, PageRange.of(2, 3), tempDir.resolve("deleted.pdf"));

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
                                            input, PageRange.of(1, 2), tempDir.resolve("deleted.pdf")))
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
                                            input, PageRange.of(2, 5), tempDir.resolve("extracted.pdf")))
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
                                    () -> operations.extractPages(input, PageRange.singlePage(1), output))
                            .errorCode());
            assertEquals(0L, Files.size(output));
        }

        @Test
        @DisplayName("入力を出力に指定しても入力は壊れない")
        void refusesToWriteOntoItsInput() throws Exception {
            Path input = TestPdfs.withText(tempDir.resolve("doc.pdf"), "P1", "P2");

            assertEquals(
                    ErrorCode.OUTPUT_ALREADY_EXISTS,
                    assertThrows(PdfjigException.class, () -> operations.reorder(input, List.of(2, 1), input))
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

            Path output = operations.extractPages(input, PageRange.singlePage(1), tempDir.resolve("extracted.pdf"));

            assertEquals(List.of(Warning.ENCRYPTION_NOT_PROPAGATED), warnings);
            try (PdfDocument result = PdfDocument.open(output)) {
                assertFalse(result.encrypted());
            }
        }

        @Test
        @DisplayName("回転でも保護は落ち、警告される")
        void warnsOnRotate() throws Exception {
            Path input = TestPdfs.ownerProtected(tempDir.resolve("protected.pdf"), "owner", 1);

            Path output = operations.rotate(input, Map.of(1, Rotation.CLOCKWISE_90), tempDir.resolve("rotated.pdf"));

            assertEquals(List.of(Warning.ENCRYPTION_NOT_PROPAGATED), warnings);
            try (PdfDocument result = PdfDocument.open(output)) {
                assertFalse(result.encrypted());
            }
        }

        @Test
        @DisplayName("平文の入力では警告しない")
        void staysSilentForPlainInput() throws Exception {
            Path input = TestPdfs.plain(tempDir.resolve("doc.pdf"), 2);

            operations.extractPages(input, PageRange.singlePage(1), tempDir.resolve("extracted.pdf"));

            assertTrue(warnings.isEmpty());
        }
    }

    /**
     * 出力に含めなかったページが、出力ファイルの中に残っていないことを確かめる。
     *
     * <p>ページ数や見えている本文を数えるだけでは捕まらない。ページツリーから外れていても、
     * 生き残ったページの参照から辿れるオブジェクトは保存時に書き出されるため、
     * 捨てたはずのページが「見えないが在る」状態になりうる。
     * 目次や相互参照を持つ文書から機密ページを取り除いて渡す、という使い方で実害が出る。
     */
    @Nested
    class NoLeakage {

        @Test
        @DisplayName("取り出さなかったページの内容が出力に残らない")
        void extractLeavesNothingBehind() throws Exception {
            Path input = TestPdfs.withInternalLinks(tempDir.resolve("doc.pdf"), "KEEPME", "SECRETA", "SECRETB");

            Path output = operations.extractPages(input, PageRange.singlePage(1), tempDir.resolve("extracted.pdf"));

            assertEquals(List.of("KEEPME"), textsOf(output));
            assertNotReachable(output, "SECRETA", "SECRETB");
        }

        @Test
        @DisplayName("削除したページの内容が出力に残らない")
        void deleteLeavesNothingBehind() throws Exception {
            Path input = TestPdfs.withInternalLinks(tempDir.resolve("doc.pdf"), "KEEPME", "SECRETA", "SECRETB");

            Path output = operations.deletePages(input, PageRange.of(2, 3), tempDir.resolve("deleted.pdf"));

            assertEquals(List.of("KEEPME"), textsOf(output));
            assertNotReachable(output, "SECRETA", "SECRETB");
        }

        @Test
        @DisplayName("並べ替えても捨てたページの内容が出力に残らない")
        void assembleLeavesNothingBehind() throws Exception {
            Path input = TestPdfs.withInternalLinks(tempDir.resolve("doc.pdf"), "KEEPME", "SECRETA", "ALSOKEPT");

            Path output = operations.assemble(
                    input, List.of(PageSelection.of(3), PageSelection.of(1)), tempDir.resolve("assembled.pdf"));

            assertEquals(List.of("ALSOKEPT", "KEEPME"), textsOf(output));
            assertNotReachable(output, "SECRETA");
        }
    }

    /**
     * 書き出しで文書全体の構造が失われないこと。
     *
     * <p>ページを新しい文書に詰め替えると、しおり・文書情報・添付ファイル・ページラベルは
     * ページに属さないためすべて落ちる。出力が入力より劣化する経路を作らないための担保である。
     */
    @Nested
    class StructurePreserved {

        @Test
        @DisplayName("範囲を取り出しても、しおり・文書情報・添付・ページラベルが残る")
        void extractKeepsDocumentStructure() throws Exception {
            Path input = TestPdfs.rich(tempDir.resolve("doc.pdf"), "P1", "P2", "P3");

            Path output = operations.extractPages(input, PageRange.of(1, 2), tempDir.resolve("extracted.pdf"));

            assertEquals(
                    List.of(TestPdfs.OUTLINE_TITLE_PREFIX + 1, TestPdfs.OUTLINE_TITLE_PREFIX + 2),
                    outlineTitlesOf(output));
            assertEquals(TestPdfs.DOCUMENT_TITLE, titleOf(output));
            assertEquals(List.of(TestPdfs.ATTACHMENT_NAME), attachmentNamesOf(output));
            assertEquals(List.of("i", "ii"), pageLabelsOf(output));
        }

        @Test
        @DisplayName("並べ替えても、しおりと文書情報が残る")
        void assembleKeepsDocumentStructure() throws Exception {
            Path input = TestPdfs.rich(tempDir.resolve("doc.pdf"), "P1", "P2", "P3");

            Path output = operations.assemble(
                    input,
                    List.of(PageSelection.of(3), PageSelection.of(1), PageSelection.of(2)),
                    tempDir.resolve("assembled.pdf"));

            assertEquals(List.of("P3", "P1", "P2"), textsOf(output));
            // どのページも捨てていないため、しおりは 3 つとも残る。
            assertEquals(3, outlineTitlesOf(output).size());
            assertEquals(TestPdfs.DOCUMENT_TITLE, titleOf(output));
            assertEquals(List.of(TestPdfs.ATTACHMENT_NAME), attachmentNamesOf(output));
        }

        @Test
        @DisplayName("何も変えずに書き出しても構造は残る")
        void writingUnchangedKeepsDocumentStructure() throws Exception {
            Path input = TestPdfs.rich(tempDir.resolve("doc.pdf"), "P1", "P2", "P3");

            Path output = operations.assemble(
                    input,
                    List.of(PageSelection.of(1), PageSelection.of(2), PageSelection.of(3)),
                    tempDir.resolve("saved.pdf"));

            assertEquals(3, outlineTitlesOf(output).size());
            assertEquals(TestPdfs.DOCUMENT_TITLE, titleOf(output));
            assertEquals(List.of(TestPdfs.ATTACHMENT_NAME), attachmentNamesOf(output));
            assertEquals(List.of("i", "ii", "iii"), pageLabelsOf(output));
            assertTrue(warnings.isEmpty(), "何も捨てていないのに警告が出ている。");
        }

        @Test
        @DisplayName("並べ替えても、中間ノードから継承していた寸法が失われない")
        void reorderKeepsInheritedMediaBox() throws Exception {
            PDRectangle box = new PDRectangle(200f, 400f);
            Path input = TestPdfs.withInheritedMediaBox(tempDir.resolve("doc.pdf"), box, 3);

            Path output = operations.reorder(input, List.of(3, 1, 2), tempDir.resolve("reordered.pdf"));

            assertEquals(List.of(200f, 200f, 200f), widthsOf(output));
            assertEquals(List.of(400f, 400f, 400f), heightsOf(output));
        }

        @Test
        @DisplayName("複数の入力を混ぜると、両方のしおりが残り、文書情報の出どころを断る")
        void mergingKeepsBothOutlines() throws Exception {
            Path first = TestPdfs.rich(tempDir.resolve("first.pdf"), "A1", "A2");
            Path second = TestPdfs.rich(tempDir.resolve("second.pdf"), "B1", "B2");

            Path output = operations.assemble(
                    List.of(first, second),
                    List.of(
                            PageSelection.of(0, 1),
                            PageSelection.of(1, 1),
                            PageSelection.of(0, 2),
                            PageSelection.of(1, 2)),
                    tempDir.resolve("mixed.pdf"));

            assertEquals(List.of("A1", "B1", "A2", "B2"), textsOf(output));
            assertEquals(4, outlineTitlesOf(output).size());
            assertEquals(TestPdfs.DOCUMENT_TITLE, titleOf(output));
            assertTrue(warnings.contains(Warning.METADATA_FROM_FIRST_INPUT));
        }

        @Test
        @DisplayName("混ぜても、文書情報は先頭の入力のものだけになる")
        void takesDocumentInformationFromTheFirstInputOnly() throws Exception {
            Path first = TestPdfs.withText(tempDir.resolve("first.pdf"), "A1");
            Path second = TestPdfs.rich(tempDir.resolve("second.pdf"), "B1");

            Path output = operations.assemble(
                    List.of(first, second),
                    List.of(PageSelection.of(0, 1), PageSelection.of(1, 1)),
                    tempDir.resolve("mixed.pdf"));

            // 先頭に題名が無いのだから、2 つ目のものを拾ってはならない。
            assertNull(titleOf(output), "先頭の入力に題名が無いのに題名が付いている。");
        }

        @Test
        @DisplayName("1 つの入力しか使わないなら、文書情報の警告は出ない")
        void doesNotWarnAboutMetadataForSingleInput() throws Exception {
            Path input = TestPdfs.rich(tempDir.resolve("doc.pdf"), "P1", "P2");

            operations.assemble(input, List.of(PageSelection.of(1), PageSelection.of(1)), tempDir.resolve("twice.pdf"));

            assertFalse(warnings.contains(Warning.METADATA_FROM_FIRST_INPUT));
        }
    }

    /** 宛先を失った参照の扱い。 */
    @Nested
    class DanglingReferences {

        @Test
        @DisplayName("宛先の消えたしおりは落ち、その子は繰り上がる")
        void promotesChildrenOfRemovedBookmarks() throws Exception {
            Path input = TestPdfs.withNestedOutline(tempDir.resolve("doc.pdf"));

            Path output = operations.deletePages(input, PageRange.singlePage(2), tempDir.resolve("deleted.pdf"));

            // 親は消えた 2 ページ目を指していた。子はまだ生きている 3 ページ目を指す。
            assertEquals(List.of(TestPdfs.NESTED_CHILD_TITLE), outlineTitlesOf(output));
            assertTrue(warnings.contains(Warning.DANGLING_REFERENCES_REMOVED));
        }

        @Test
        @DisplayName("ページを捨てれば、宛先を失った参照を取り除いたことを伝える")
        void warnsWhenReferencesAreRemoved() throws Exception {
            Path input = TestPdfs.rich(tempDir.resolve("doc.pdf"), "P1", "P2", "P3");

            operations.extractPages(input, PageRange.singlePage(1), tempDir.resolve("extracted.pdf"));

            assertTrue(warnings.contains(Warning.DANGLING_REFERENCES_REMOVED));
        }

        @Test
        @DisplayName("すべてのページを残すなら、何も取り除かない")
        void keepsEveryReferenceWhenNoPageIsDropped() throws Exception {
            Path input = TestPdfs.rich(tempDir.resolve("doc.pdf"), "P1", "P2", "P3");

            operations.reorder(input, List.of(2, 3, 1), tempDir.resolve("reordered.pdf"));

            assertFalse(warnings.contains(Warning.DANGLING_REFERENCES_REMOVED));
        }
    }

    /**
     * 電子署名は保てない。保てないことを黙っていない、という担保。
     *
     * <p>署名はページの並びに紐づくため、並べ替えれば必ず壊れる。pdfjig は署名を作らず
     * 検証もしないが、既にあるものを黙って壊すのは別の話である。
     */
    @Nested
    class SignatureWarning {

        @Test
        @DisplayName("電子署名のある入力を扱うと、署名が無効になることを伝える")
        void warnsForSignedInput() throws Exception {
            Path input = TestPdfs.signed(tempDir.resolve("signed.pdf"), 3);

            operations.extractPages(input, PageRange.singlePage(1), tempDir.resolve("extracted.pdf"));

            assertTrue(warnings.contains(Warning.SIGNATURE_INVALIDATED));
        }

        @Test
        @DisplayName("回転でも署名は壊れるため、同じように伝える")
        void warnsOnRotate() throws Exception {
            Path input = TestPdfs.signed(tempDir.resolve("signed.pdf"), 2);

            operations.rotate(input, Map.of(1, Rotation.CLOCKWISE_90), tempDir.resolve("rotated.pdf"));

            assertTrue(warnings.contains(Warning.SIGNATURE_INVALIDATED));
        }

        @Test
        @DisplayName("署名欄があるだけの入力では警告しない")
        void doesNotWarnForEmptySignatureField() throws Exception {
            Path input = TestPdfs.withEmptySignatureField(tempDir.resolve("field.pdf"), 2);

            operations.extractPages(input, PageRange.singlePage(1), tempDir.resolve("extracted.pdf"));

            assertFalse(warnings.contains(Warning.SIGNATURE_INVALIDATED));
        }

        @Test
        @DisplayName("署名のない入力では警告しない")
        void doesNotWarnForPlainInput() throws Exception {
            Path input = TestPdfs.plain(tempDir.resolve("plain.pdf"), 2);

            operations.extractPages(input, PageRange.singlePage(1), tempDir.resolve("extracted.pdf"));

            assertFalse(warnings.contains(Warning.SIGNATURE_INVALIDATED));
        }
    }

    /** しおりの表題を、木を上から辿った順に並べる。 */
    private static List<String> outlineTitlesOf(Path pdf) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf.toFile())) {
            PDDocumentOutline outline = document.getDocumentCatalog().getDocumentOutline();
            List<String> titles = new ArrayList<>();
            if (outline != null) {
                collectTitles(outline, titles);
            }
            return titles;
        }
    }

    private static void collectTitles(PDOutlineNode node, List<String> into) {
        for (PDOutlineItem item : node.children()) {
            into.add(item.getTitle());
            collectTitles(item, into);
        }
    }

    private static String titleOf(Path pdf) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf.toFile())) {
            return document.getDocumentInformation().getTitle();
        }
    }

    private static List<String> attachmentNamesOf(Path pdf) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf.toFile())) {
            PDDocumentNameDictionary names = document.getDocumentCatalog().getNames();
            if (names == null || names.getEmbeddedFiles() == null) {
                return List.of();
            }
            Map<String, PDComplexFileSpecification> files =
                    names.getEmbeddedFiles().getNames();
            return files == null ? List.of() : List.copyOf(files.keySet());
        }
    }

    private static List<String> pageLabelsOf(Path pdf) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf.toFile())) {
            PDPageLabels labels = document.getDocumentCatalog().getPageLabels();
            return labels == null ? List.of() : List.of(labels.getLabelsByPageIndices());
        }
    }

    private static List<Float> widthsOf(Path pdf) throws IOException {
        return mediaBoxSizesOf(pdf, true);
    }

    private static List<Float> heightsOf(Path pdf) throws IOException {
        return mediaBoxSizesOf(pdf, false);
    }

    private static List<Float> mediaBoxSizesOf(Path pdf, boolean width) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf.toFile())) {
            List<Float> sizes = new ArrayList<>(document.getNumberOfPages());
            for (PDPage page : document.getPages()) {
                sizes.add(
                        width
                                ? page.getMediaBox().getWidth()
                                : page.getMediaBox().getHeight());
            }
            return sizes;
        }
    }

    /** 出力から辿り着けるどのストリームにも、与えた文字列が現れないこと。 */
    private static void assertNotReachable(Path pdf, String... absent) throws IOException {
        String reachable = reachableStreamsOf(pdf);
        for (String needle : absent) {
            assertFalse(reachable.contains(needle), "出力に含めなかったページの内容 '" + needle + "' が出力ファイルに残っている。");
        }
    }

    /**
     * 出力の trailer から辿り着けるストリームをすべて連結して返す。
     *
     * <p>{@code COSWriter} が書き出す対象と同じ到達可能性を、こちらでも辿る。
     * ページツリーに現れないオブジェクトも、参照さえあればここに現れる。
     */
    private static String reachableStreamsOf(Path pdf) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf.toFile())) {
            StringBuilder collected = new StringBuilder();
            collect(document.getDocument().getTrailer(), Collections.newSetFromMap(new IdentityHashMap<>()), collected);
            return collected.toString();
        }
    }

    private static void collect(COSBase base, Set<COSBase> seen, StringBuilder collected) throws IOException {
        if (base == null) {
            return;
        }
        if (base instanceof COSObject reference) {
            collect(reference.getObject(), seen, collected);
            return;
        }
        if (!seen.add(base)) {
            return;
        }
        if (base instanceof COSStream stream) {
            // 復号したうえで見る。圧縮されたままの生バイト列を探しても見つからない。
            try (InputStream in = stream.createInputStream()) {
                collected.append(new String(in.readAllBytes(), StandardCharsets.ISO_8859_1));
            }
        }
        if (base instanceof COSDictionary dictionary) {
            for (COSBase value : dictionary.getValues()) {
                collect(value, seen, collected);
            }
        } else if (base instanceof COSArray array) {
            for (int i = 0; i < array.size(); i++) {
                collect(array.get(i), seen, collected);
            }
        }
    }

    /** そのディレクトリにあるファイル名。ディレクトリが無ければ空。 */
    private static List<String> listFilesIn(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (var entries = Files.list(directory)) {
            return entries.map(path -> path.getFileName().toString()).sorted().toList();
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
