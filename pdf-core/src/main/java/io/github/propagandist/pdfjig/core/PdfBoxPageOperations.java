package io.github.propagandist.pdfjig.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;
import org.apache.pdfbox.multipdf.PDFMergerUtility;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;

/**
 * PDFBox による {@link PageOperations} の実装。
 *
 * <p>暗号化された入力を扱った場合の警告先をコンストラクタで必ず受け取る。
 * 警告を捨てるなら {@link WarningListener#ignoring()} を明示的に渡すこと。
 */
public final class PdfBoxPageOperations implements PageOperations {

    /** 分割の出力ファイル名。連番は 3 桁を下限とし、それを超えれば桁が伸びる。 */
    private static final String SPLIT_NAME_FORMAT = "%s_%03d.pdf";

    private final WarningListener warnings;

    public PdfBoxPageOperations(WarningListener warnings) {
        if (warnings == null) {
            throw new IllegalArgumentException("warnings は null にできません。");
        }
        this.warnings = warnings;
    }

    @Override
    public Path merge(List<Path> inputs, Path output, MergeOptions options) {
        if (inputs == null || inputs.isEmpty()) {
            throw new PdfjigException(ErrorCode.NO_INPUT);
        }
        requireSupported(options.encryptionPropagation());
        requireAbsent(output);

        try (OpenDocuments sources = new OpenDocuments();
                PDDocument merged = new PDDocument()) {
            PDFMergerUtility merger = new PDFMergerUtility();
            for (Path input : inputs) {
                // appendDocument は入力のページを参照でつなぐ。保存が終わるまで
                // 入力を閉じられないため、まとめて開いたまま保持する。
                merger.appendDocument(merged, sources.open(input).delegate());
            }
            save(merged, output);
        } catch (IOException e) {
            throw PdfjigException.wrapping(ErrorCode.IO_FAILURE, e);
        }
        return output;
    }

    @Override
    public List<Path> split(Path input, SplitStrategy strategy, Path outputDir) {
        requireSupported(strategy.encryptionPropagation());
        if (outputDir == null) {
            throw new IllegalArgumentException("outputDir は null にできません。");
        }

        try (PdfDocument source = open(input)) {
            List<PageRange> ranges = resolveRanges(strategy, source.pageCount());
            List<Path> outputs = splitOutputPaths(input, outputDir, ranges.size());
            // 1 つでも書けないなら、何も書かずに失敗させる。
            outputs.forEach(PdfBoxPageOperations::requireAbsent);

            createDirectories(outputDir);
            for (int i = 0; i < ranges.size(); i++) {
                writePages(source, pageNumbersOf(ranges.get(i)), outputs.get(i));
            }
            return List.copyOf(outputs);
        }
    }

    @Override
    public Path reorder(Path input, List<Integer> newOrder, Path output) {
        requireAbsent(output);
        try (PdfDocument source = open(input)) {
            requirePermutation(newOrder, source.pageCount());
            writePages(source, newOrder, output);
        }
        return output;
    }

    @Override
    public Path assemble(Path input, List<PageSelection> pages, Path output) {
        return assemble(List.of(input), pages, output);
    }

    @Override
    public Path assemble(List<Path> inputs, List<PageSelection> pages, Path output) {
        if (inputs == null || inputs.isEmpty()) {
            throw new PdfjigException(ErrorCode.NO_INPUT);
        }
        requireAbsent(output);

        // importPage は元のページ辞書を参照する。保存が終わるまで入力を閉じられないため、
        // まとめて開いたまま保持する。merge と同じ約束である。
        try (OpenDocuments sources = new OpenDocuments()) {
            List<PdfDocument> documents = new ArrayList<>(inputs.size());
            for (Path input : inputs) {
                documents.add(sources.open(input));
            }
            requireSelectable(pages, documents);
            writeSelections(documents, pages, output);
        }
        return output;
    }

    @Override
    public Path rotate(Path input, Map<Integer, Rotation> rotations, Path output) {
        if (rotations == null) {
            throw new IllegalArgumentException("rotations は null にできません。");
        }
        requireAbsent(output);

        try (PdfDocument source = open(input)) {
            int pageCount = source.pageCount();
            rotations
                    .keySet()
                    .forEach(pageNumber -> PageRange.singlePage(pageNumber).validateAgainst(pageCount));

            // 回転はページ属性の変更だけで済む。ページを詰め替えると、しおりや
            // 注釈といった文書全体の構造を落とす経路ができるため、元の文書を保存する。
            PDDocument delegate = source.delegate();
            rotations.forEach((pageNumber, rotation) -> {
                PDPage page = delegate.getPage(pageNumber - 1);
                page.setRotation(rotationOf(page).plus(rotation).degrees());
            });

            // 入力が暗号化されていた場合、PDFBox は保護を保ったまま保存しようとする。
            // M0 が扱うのは EncryptionPropagation.NONE のみであり、
            // 保護は落ちる（警告は open で発している）。
            delegate.setAllSecurityToBeRemoved(true);
            save(delegate, output);
        }
        return output;
    }

    @Override
    public Path extractPages(Path input, PageRange range, Path output) {
        requireAbsent(output);
        try (PdfDocument source = open(input)) {
            range.validateAgainst(source.pageCount());
            writePages(source, pageNumbersOf(range), output);
        }
        return output;
    }

    @Override
    public Path deletePages(Path input, PageRange range, Path output) {
        requireAbsent(output);
        try (PdfDocument source = open(input)) {
            int pageCount = source.pageCount();
            range.validateAgainst(pageCount);

            List<Integer> remaining = IntStream.rangeClosed(1, pageCount)
                    .boxed()
                    .filter(pageNumber -> !range.contains(pageNumber))
                    .toList();
            if (remaining.isEmpty()) {
                throw new PdfjigException(ErrorCode.EMPTY_RESULT);
            }
            writePages(source, remaining, output);
        }
        return output;
    }

    /** 入力を開き、暗号化されていれば警告する。 */
    private PdfDocument open(Path input) {
        PdfDocument document = PdfDocument.open(input);
        if (document.encrypted()) {
            warnings.onWarning(Warning.ENCRYPTION_NOT_PROPAGATED);
        }
        return document;
    }

    private static List<PageRange> resolveRanges(SplitStrategy strategy, int pageCount) {
        return switch (strategy) {
            case SplitStrategy.EveryNPages every -> {
                List<PageRange> ranges = new ArrayList<>();
                for (int first = 1; first <= pageCount; first += every.pages()) {
                    ranges.add(PageRange.of(first, Math.min(first + every.pages() - 1, pageCount)));
                }
                yield List.copyOf(ranges);
            }
            case SplitStrategy.ByRanges byRanges -> {
                byRanges.ranges().forEach(range -> range.validateAgainst(pageCount));
                yield byRanges.ranges();
            }
            case SplitStrategy.AtBoundaries boundaries -> {
                List<Integer> starts = boundaries.startPages();
                if (starts.get(starts.size() - 1) > pageCount) {
                    throw new PdfjigException(ErrorCode.PAGE_OUT_OF_RANGE);
                }
                List<PageRange> ranges = new ArrayList<>(starts.size());
                for (int i = 0; i < starts.size(); i++) {
                    int last = i + 1 < starts.size() ? starts.get(i + 1) - 1 : pageCount;
                    ranges.add(PageRange.of(starts.get(i), last));
                }
                yield List.copyOf(ranges);
            }
        };
    }

    private static List<Path> splitOutputPaths(Path input, Path outputDir, int count) {
        String baseName = baseNameOf(input);
        List<Path> outputs = new ArrayList<>(count);
        for (int number = 1; number <= count; number++) {
            outputs.add(outputDir.resolve(String.format(Locale.ROOT, SPLIT_NAME_FORMAT, baseName, number)));
        }
        return outputs;
    }

    private static String baseNameOf(Path input) {
        String fileName = input.getFileName().toString();
        int extension = fileName.lastIndexOf('.');
        return extension > 0 ? fileName.substring(0, extension) : fileName;
    }

    private static void writePages(PdfDocument source, List<Integer> pageNumbers, Path output) {
        writeSelections(
                List.of(source), pageNumbers.stream().map(PageSelection::of).toList(), output);
    }

    private static void writeSelections(List<PdfDocument> sources, List<PageSelection> pages, Path output) {
        try (PDDocument target = new PDDocument()) {
            for (PageSelection selection : pages) {
                PdfDocument source = sources.get(selection.sourceIndex());
                // importPage が返すのは元のページ辞書の複製である。ここで向きを変えても
                // 元の文書には及ばず、同じページを二度含めてそれぞれ別の向きにもできる。
                PDPage imported = target.importPage(source.delegate().getPage(selection.pageNumber() - 1));
                if (selection.rotated()) {
                    imported.setRotation(rotationOf(imported)
                            .plus(selection.additionalRotation())
                            .degrees());
                }
            }
            save(target, output);
        } catch (IOException e) {
            throw PdfjigException.wrapping(ErrorCode.IO_FAILURE, e);
        }
    }

    private static List<Integer> pageNumbersOf(PageRange range) {
        return IntStream.rangeClosed(range.firstPage(), range.lastPage())
                .boxed()
                .toList();
    }

    /**
     * ページの現在の回転角。
     *
     * <p>PDF 仕様は {@code /Rotate} を 90 の倍数に限っており、PDFBox は仕様外の値を
     * 0 とみなして返す。したがってここに 90 の倍数以外が来ることはない。
     */
    private static Rotation rotationOf(PDPage page) {
        return Rotation.ofDegrees(page.getRotation());
    }

    private static void requirePermutation(List<Integer> newOrder, int pageCount) {
        if (newOrder == null || newOrder.size() != pageCount) {
            throw new PdfjigException(ErrorCode.INVALID_PAGE_ORDER);
        }
        Set<Integer> seen = new HashSet<>(pageCount);
        for (Integer pageNumber : newOrder) {
            if (pageNumber == null || pageNumber < 1 || pageNumber > pageCount || !seen.add(pageNumber)) {
                throw new PdfjigException(ErrorCode.INVALID_PAGE_ORDER);
            }
        }
    }

    /**
     * 出どころとページ番号が、開いた入力の範囲に収まっているか確かめる。
     *
     * <p>出どころの番号が範囲外でも {@link ErrorCode#PAGE_OUT_OF_RANGE} を使う。
     * 利用者にとっては「指定されたページが文書の範囲外」であり、内部の添字を
     * 別の失敗として区別しても意味がない。
     */
    private static void requireSelectable(List<PageSelection> pages, List<PdfDocument> sources) {
        if (pages == null || pages.isEmpty()) {
            throw new PdfjigException(ErrorCode.EMPTY_RESULT);
        }
        for (PageSelection selection : pages) {
            if (selection == null || selection.sourceIndex() >= sources.size()) {
                throw new PdfjigException(ErrorCode.PAGE_OUT_OF_RANGE);
            }
            if (selection.pageNumber() > sources.get(selection.sourceIndex()).pageCount()) {
                throw new PdfjigException(ErrorCode.PAGE_OUT_OF_RANGE);
            }
        }
    }

    private static void requireSupported(EncryptionPropagation propagation) {
        if (propagation != EncryptionPropagation.NONE) {
            throw new PdfjigException(ErrorCode.ENCRYPTION_PROPAGATION_UNSUPPORTED);
        }
    }

    private static void requireAbsent(Path output) {
        if (output == null) {
            throw new IllegalArgumentException("output は null にできません。");
        }
        if (Files.exists(output)) {
            throw new PdfjigException(ErrorCode.OUTPUT_ALREADY_EXISTS);
        }
    }

    private static void createDirectories(Path directory) {
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw PdfjigException.wrapping(ErrorCode.IO_FAILURE, e);
        }
    }

    private static void save(PDDocument document, Path output) {
        try {
            document.save(output.toFile());
        } catch (IOException e) {
            throw PdfjigException.wrapping(ErrorCode.IO_FAILURE, e);
        }
    }

    /**
     * 複数の入力を開いたまま保持し、まとめて閉じる。
     *
     * <p>結合では保存が終わるまで入力を閉じられない。閉じる際の失敗は最初の 1 件を投げ、
     * 残りは抑制例外として付ける。
     */
    private final class OpenDocuments implements AutoCloseable {

        private final List<PdfDocument> documents = new ArrayList<>();

        PdfDocument open(Path path) {
            PdfDocument document = PdfBoxPageOperations.this.open(path);
            documents.add(document);
            return document;
        }

        @Override
        public void close() {
            PdfjigException failure = null;
            for (PdfDocument document : documents) {
                try {
                    document.close();
                } catch (PdfjigException e) {
                    if (failure == null) {
                        failure = e;
                    } else {
                        failure.addSuppressed(e);
                    }
                }
            }
            if (failure != null) {
                throw failure;
            }
        }
    }
}
