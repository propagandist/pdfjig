package io.github.propagandist.pdfjig.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.IntStream;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.multipdf.PDFMergerUtility;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;

/**
 * PDFBox による {@link PageOperations} の実装。
 *
 * <p>暗号化された入力を扱った場合の警告先をコンストラクタで必ず受け取る。
 * 警告を捨てるなら {@link WarningListener#ignoring()} を明示的に渡すこと。
 *
 * <p><b>書き出しの方針。</b> ページを新しい文書に詰め替えると、しおり・フォーム・
 * 添付ファイル・文書情報・ページラベル・タグ構造といった文書全体の構造がすべて落ちる。
 * さらに、詰め替えたページは元の文書のオブジェクトを参照したままになるため、
 * 取り除いたはずのページが参照から辿れて出力に残る。
 * したがって <b>可能な限り元の文書を保存する</b>。詳しくは {@link #write} を見ること。
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
            PDFMergerUtility merger = newMerger();
            PDDocumentInformation information = null;
            for (Path input : inputs) {
                // appendDocument は入力のページを参照でつなぐ。保存が終わるまで
                // 入力を閉じられないため、まとめて開いたまま保持する。
                PdfDocument source = sources.open(input);
                if (information == null) {
                    information = detachedInformationOf(source);
                }
                merger.appendDocument(merged, source.delegate());
            }
            applyInformation(merged, information, inputs.size() > 1);
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

        List<PageRange> ranges;
        try (PdfDocument source = open(input)) {
            ranges = resolveRanges(strategy, source.pageCount());
        }
        List<Path> outputs = splitOutputPaths(input, outputDir, ranges.size());
        // 1 つでも書けないなら、何も書かずに失敗させる。
        outputs.forEach(PdfBoxPageOperations::requireAbsent);

        createDirectories(outputDir);
        for (int i = 0; i < ranges.size(); i++) {
            // 出力ごとに開き直す。書き出しは元の文書からページを取り除いていくため、
            // 同じ文書を次の範囲に使い回すことはできない。
            try (PdfDocument source = openQuietly(input)) {
                writeRange(source, ranges.get(i), outputs.get(i));
            }
        }
        return List.copyOf(outputs);
    }

    @Override
    public Path reorder(Path input, List<Integer> newOrder, Path output) {
        requireAbsent(output);
        try (PdfDocument source = open(input)) {
            requirePermutation(newOrder, source.pageCount());
            writeFromSingleSource(source, selectionsOf(newOrder), output);
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

        // 結合では保存が終わるまで入力を閉じられない。まとめて開いたまま保持する。
        try (OpenDocuments sources = new OpenDocuments()) {
            List<PdfDocument> documents = new ArrayList<>(inputs.size());
            for (Path input : inputs) {
                documents.add(sources.open(input));
            }
            requireSelectable(pages, documents);
            write(inputs, documents, pages, output);
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

            // 回転はページ属性の変更だけで済む。ページの並びに手を触れないため、
            // ページツリーを均す必要もない。元の文書をそのまま保存する。
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
            writeRange(source, range, output);
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
            writeFromSingleSource(source, selectionsOf(remaining), output);
        }
        return output;
    }

    /**
     * 選ばれたページを 1 つのファイルに書き出す。
     *
     * <p>やり方は 2 つある。
     *
     * <ul>
     *   <li><b>1 つの入力から、同じページを二度使わずに出す場合</b>は、元の文書から
     *       要らないページを取り除いて並べ替え、その文書を保存する。しおり・フォーム・
     *       添付ファイル・文書情報・ページラベル・タグ構造は、この方法でのみ残せる</li>
     *   <li><b>複数の入力を混ぜる場合と、同じページを複数回出す場合</b>は上の方法が使えない。
     *       必要なページだけに切り詰めた複製を作り、{@code PDFMergerUtility} で結合する。
     *       こちらは深い複製を作るため、入力のオブジェクトを参照したまま
     *       出力に持ち込むことはない</li>
     * </ul>
     *
     * <p>どちらの道でも、出力に含まれないページを指す参照は
     * {@link PageReferences} で取り除いてから保存する。これを怠ると、
     * 取り除いたはずのページが参照から辿れて出力に残る。
     */
    private void write(List<Path> inputs, List<PdfDocument> sources, List<PageSelection> pages, Path output) {
        int single = singleSourceIndexOf(pages);
        if (single >= 0) {
            writeFromSingleSource(sources.get(single), pages, output);
        } else {
            writeByMerging(inputs, pages, output);
        }
    }

    /**
     * すべての指定が 1 つの入力から来ていて、同じページを二度使っていなければ、
     * その出どころの添字を返す。そうでなければ {@code -1}。
     */
    private static int singleSourceIndexOf(List<PageSelection> pages) {
        int sourceIndex = pages.get(0).sourceIndex();
        Set<Integer> seen = new HashSet<>(pages.size());
        for (PageSelection selection : pages) {
            if (selection.sourceIndex() != sourceIndex || !seen.add(selection.pageNumber())) {
                return -1;
            }
        }
        return sourceIndex;
    }

    /** 元の文書から要らないページを取り除いて並べ替え、その文書を保存する。 */
    private void writeFromSingleSource(PdfDocument source, List<PageSelection> pages, Path output) {
        PDDocument document = source.delegate();

        // 並べ替えでページツリーが 1 階層に均される。継承していた属性を先に固定する。
        PageReferences.fixInherited(document.getPages());

        List<PDPage> ordered = new ArrayList<>(pages.size());
        for (PageSelection selection : pages) {
            ordered.add(document.getPage(selection.pageNumber() - 1));
        }
        replacePages(document, ordered);
        applyRotations(ordered, pages);

        if (PageReferences.removeDangling(document)) {
            warnings.onWarning(Warning.DANGLING_REFERENCES_REMOVED);
        }

        // 入力が暗号化されていた場合、PDFBox は保護を保ったまま保存しようとする。
        // M0 が扱うのは EncryptionPropagation.NONE のみであり、
        // 保護は落ちる（警告は open で発している）。
        document.setAllSecurityToBeRemoved(true);
        save(document, output);
    }

    /**
     * 必要なページだけに切り詰めた複製を作り、結合してから並べ替える。
     *
     * <p>同じページを複数回出す場合は、その入力を回数ぶん別々に切り詰めて足す。
     */
    private void writeByMerging(List<Path> inputs, List<PageSelection> pages, Path output) {
        try (OpenDocuments copies = new OpenDocuments();
                PDDocument target = new PDDocument()) {
            PDFMergerUtility merger = newMerger();

            // 「出どころ・ページ番号・何回目」が結合後の何ページ目になるか。
            Map<List<Integer>, Integer> positions = new HashMap<>();
            PDDocumentInformation information = null;
            int appended = 0;

            for (int sourceIndex = 0; sourceIndex < inputs.size(); sourceIndex++) {
                List<List<Integer>> rounds = roundsOf(pages, sourceIndex);
                for (int round = 0; round < rounds.size(); round++) {
                    List<Integer> wanted = rounds.get(round);
                    PdfDocument copy = copies.openQuietly(inputs.get(sourceIndex));
                    if (information == null) {
                        information = detachedInformationOf(copy);
                    }
                    trimTo(copy, wanted);
                    merger.appendDocument(target, copy.delegate());

                    for (int i = 0; i < wanted.size(); i++) {
                        positions.put(List.of(sourceIndex, wanted.get(i), round), appended + i);
                    }
                    appended += wanted.size();
                }
            }

            PageReferences.fixInherited(target.getPages());
            List<PDPage> ordered = orderedPagesOf(target, pages, positions);
            replacePages(target, ordered);
            applyRotations(ordered, pages);

            applyInformation(
                    target,
                    information,
                    pages.stream()
                                    .mapToInt(PageSelection::sourceIndex)
                                    .distinct()
                                    .count()
                            > 1);

            target.setAllSecurityToBeRemoved(true);
            save(target, output);
        } catch (IOException e) {
            throw PdfjigException.wrapping(ErrorCode.IO_FAILURE, e);
        }
    }

    /**
     * その出どころのページを、何回目の複製にどれだけ含めるか。
     *
     * <p>戻り値の {@code i} 番目は「{@code i} 回目の複製に残すページ番号」を昇順で並べたもの。
     * 同じページを 2 回出すなら、そのページは 0 回目と 1 回目の両方に現れる。
     */
    private static List<List<Integer>> roundsOf(List<PageSelection> pages, int sourceIndex) {
        Map<Integer, Integer> counts = new TreeMap<>();
        for (PageSelection selection : pages) {
            if (selection.sourceIndex() == sourceIndex) {
                counts.merge(selection.pageNumber(), 1, Integer::sum);
            }
        }
        int rounds = counts.values().stream().mapToInt(Integer::intValue).max().orElse(0);

        List<List<Integer>> wanted = new ArrayList<>(rounds);
        for (int round = 0; round < rounds; round++) {
            List<Integer> pageNumbers = new ArrayList<>();
            for (Map.Entry<Integer, Integer> entry : counts.entrySet()) {
                if (entry.getValue() > round) {
                    pageNumbers.add(entry.getKey());
                }
            }
            wanted.add(List.copyOf(pageNumbers));
        }
        return List.copyOf(wanted);
    }

    /** 文書を、指定のページだけに切り詰める。宛先を失った参照もここで落とす。 */
    private void trimTo(PdfDocument document, List<Integer> wanted) {
        PDDocument delegate = document.delegate();
        PageReferences.fixInherited(delegate.getPages());

        List<PDPage> kept = new ArrayList<>(wanted.size());
        for (int pageNumber : wanted) {
            kept.add(delegate.getPage(pageNumber - 1));
        }
        replacePages(delegate, kept);

        if (PageReferences.removeDangling(delegate)) {
            warnings.onWarning(Warning.DANGLING_REFERENCES_REMOVED);
        }
    }

    /** 結合後の文書から、指定の順に並べたページを取り出す。 */
    private static List<PDPage> orderedPagesOf(
            PDDocument target, List<PageSelection> pages, Map<List<Integer>, Integer> positions) {
        Map<List<Integer>, Integer> used = new HashMap<>();
        List<PDPage> ordered = new ArrayList<>(pages.size());
        for (PageSelection selection : pages) {
            List<Integer> page = List.of(selection.sourceIndex(), selection.pageNumber());
            int round = used.merge(page, 1, Integer::sum) - 1;
            ordered.add(target.getPage(positions.get(List.of(selection.sourceIndex(), selection.pageNumber(), round))));
        }
        return ordered;
    }

    /**
     * 文書のページを、与えられた並びに置き換える。
     *
     * <p>{@code PDPageTree} の追加・削除ではなく {@code /Kids} を自分で書き換える。
     * PDFBox の追加処理はページから辿れる参照をすべて歩くが、目次のように
     * <b>ページどうしが互いを指し合う文書</b>では、その歩きが戻ってこない。
     * 並べ替えに要るのは順序の入れ替えだけで、歩く必要がない。
     *
     * <p>ページツリーはここで 1 階層に均される。継承していた属性は
     * {@link PageReferences#fixInherited} で先に固定しておくこと。
     */
    private static void replacePages(PDDocument document, List<PDPage> ordered) {
        COSDictionary root = document.getDocumentCatalog().getPages().getCOSObject();
        COSArray kids = new COSArray();
        for (PDPage page : ordered) {
            COSDictionary dictionary = page.getCOSObject();
            dictionary.setItem(COSName.PARENT, root);
            kids.add(dictionary);
        }
        root.setItem(COSName.KIDS, kids);
        root.setInt(COSName.COUNT, ordered.size());
    }

    private static void applyRotations(List<PDPage> ordered, List<PageSelection> pages) {
        for (int i = 0; i < pages.size(); i++) {
            PageSelection selection = pages.get(i);
            if (selection.rotated()) {
                PDPage page = ordered.get(i);
                page.setRotation(
                        rotationOf(page).plus(selection.additionalRotation()).degrees());
            }
        }
    }

    /**
     * 結合先の文書情報を、先頭の入力のものに揃える。
     *
     * <p><b>上書きが要る。</b> {@code PDFMergerUtility} は文書情報を入力ごとに埋めていくため、
     * 放っておくと「題名は 1 つ目、作成者は 2 つ目」という混ざり方をする。
     * どこから来た値なのかを説明できず、題名と作成者が食い違う文書ができあがる。
     * 先頭の入力のものに揃えれば、少なくとも一言で説明できる。
     *
     * <p>先頭の入力が文書情報を持たないなら、空にする。混ざったものを残すくらいなら
     * 何も無いほうが正直である。
     *
     * @param mixed 出どころが 2 つ以上あるか。あるなら黙っておかない
     */
    private void applyInformation(PDDocument merged, PDDocumentInformation information, boolean mixed) {
        merged.setDocumentInformation(information == null ? new PDDocumentInformation() : information);
        if (mixed) {
            warnings.onWarning(Warning.METADATA_FROM_FIRST_INPUT);
        }
    }

    /**
     * 文書情報の写しを返す。
     *
     * <p>元の辞書をそのまま結合先に差すと、閉じた文書のオブジェクトを参照することになる。
     * 中身は文字列と日付だけなので、浅い写しで足りる。
     */
    private static PDDocumentInformation detachedInformationOf(PdfDocument document) {
        PDDocumentInformation information = document.delegate().getDocumentInformation();
        if (information == null) {
            return null;
        }
        return new PDDocumentInformation(new COSDictionary(information.getCOSObject()));
    }

    private static PDFMergerUtility newMerger() {
        PDFMergerUtility merger = new PDFMergerUtility();
        // フォームは束ねる。既定のままだと、同じ名前の欄がある文書を結合したときに
        // 片方の入力値が黙って消える。
        merger.setAcroFormMergeMode(PDFMergerUtility.AcroFormMergeMode.JOIN_FORM_FIELDS_MODE);
        return merger;
    }

    /** 入力を開き、暗号化や電子署名があれば警告する。 */
    private PdfDocument open(Path input) {
        PdfDocument document = PdfDocument.open(input);
        if (document.encrypted()) {
            warnings.onWarning(Warning.ENCRYPTION_NOT_PROPAGATED);
        }
        if (document.signed()) {
            warnings.onWarning(Warning.SIGNATURE_INVALIDATED);
        }
        return document;
    }

    /**
     * 入力を開くが、警告は発しない。
     *
     * <p>同じ入力を書き出しの都合で開き直す場合に使う。開いた回数だけ警告が出ると、
     * 利用者には何件の問題があるのか分からなくなる。警告は入力ごとに 1 度でよい。
     */
    private static PdfDocument openQuietly(Path input) {
        return PdfDocument.open(input);
    }

    /** ページ番号の並びを、向きを変えない指定に直す。 */
    private static List<PageSelection> selectionsOf(List<Integer> pageNumbers) {
        return pageNumbers.stream().map(PageSelection::of).toList();
    }

    private void writeRange(PdfDocument source, PageRange range, Path output) {
        writeFromSingleSource(source, selectionsOf(pageNumbersOf(range)), output);
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
            return keep(PdfBoxPageOperations.this.open(path));
        }

        PdfDocument openQuietly(Path path) {
            return keep(PdfBoxPageOperations.openQuietly(path));
        }

        private PdfDocument keep(PdfDocument document) {
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
