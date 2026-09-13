package io.github.propagandist.pdfjig.core;

import static io.github.propagandist.pdfjig.core.PdfBoxGuard.guarded;
import static io.github.propagandist.pdfjig.core.PdfBoxGuard.guardedRun;

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
import java.util.TreeSet;
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
 *
 * <p><b>★★ 公開メソッドはすべて同じ枠で書く</b>（#178）。
 *
 * <pre>{@code
 * 引数だけで決まる検め   ← 包みの外。IllegalArgumentException は畳まない
 * Warnings warnings = new Warnings(listener);
 * ... = guarded(符号, () -> 実体(..., warnings));
 * warnings.report();    ← 包みの外。ここで初めて呼ぶ側のコードが走る
 * }</pre>
 *
 * <p><b>★ 文書を開かないと決まらない検めは、包みの中にある</b>——ページ数に照らす類である
 * （{@code requirePermutation} / {@code requireSelectable} / {@code PageRange#validateAgainst}）。
 * <b>どれも {@link PdfjigException} を投げる</b>ので、包みは素通しする。
 * <b>だから引数の {@code null} は、外で弾いておかなければならない</b>——
 * 中で {@code NullPointerException} になると、<b>包みがそれを「PDF として読み取れません」に畳み、
 * 正しい入力のせいにする</b>（{@code CLAUDE.md} 優先順位 2）。
 *
 * <p><b>★ 枠を 1 つにまとめない。</b>畳むと {@code guarded} を呼ぶのが 1 か所になり、
 * <b>{@code pdf-archtest} が公開の入口を見られなくなる</b>——規則が見ているのは
 * 「その入口が包みを通ったか」であって、「どこかに包みが在るか」ではない。
 */
public final class PdfBoxPageOperations implements PageOperations {

    /** 分割の出力ファイル名。連番は 3 桁を下限とし、それを超えれば桁が伸びる。 */
    private static final String SPLIT_NAME_FORMAT = "%s_%03d.pdf";

    private final WarningListener listener;

    private final DocumentSaver saver;

    public PdfBoxPageOperations(WarningListener warnings) {
        this(warnings, PdfBoxPageOperations::saveDocument);
    }

    /**
     * 書き出しを差し替えられる形。
     *
     * <p><b>テストが「1 つ目を書き終えて 2 つ目を書く前」で落とすために使う</b>（#178）。
     * ディスクが尽きた・権限を失ったという本物の失敗は環境に依らせて起こせず、
     * <b>Windows では出力先のディレクトリを書き込み不可にもできない</b>
     * （{@code File#setWritable(false)} が {@code false} を返し、3 ファイルとも書けた。
     * <b>2026-09-13 実測</b>）。
     *
     * <p><b>★★ ここが無かった間、故障注入には {@link WarningListener} を使っていた</b>
     * ——<b>「実装は例外を投げてはならない」と定めた契約を、常用の道具にしていた。</b>
     * そのせいで {@code writeFromSingleSource} を溜める形へ替えられず、
     * <b>規律を守るはずの層が、規律を破る形に依存していた。</b>
     *
     * <p>先例は {@code BackgroundTasks} の {@code starter} と
     * {@code ThumbnailSource} の {@code PageRendering} である。
     *
     * @param warnings 警告の受け口
     * @param saver    書き出す手。渡されたものを書けば、どう書いてもよい
     */
    PdfBoxPageOperations(WarningListener warnings, DocumentSaver saver) {
        if (warnings == null) {
            throw new IllegalArgumentException("warnings は null にできません。");
        }
        if (saver == null) {
            throw new IllegalArgumentException("saver は null にできません。");
        }
        this.listener = warnings;
        this.saver = saver;
    }

    @Override
    public Path merge(Sources inputs, Path output, MergeOptions options) {
        requireInputs(inputs);
        requireSupported(options.encryptionPropagation());
        requireAbsent(output);

        Warnings warnings = new Warnings(listener);
        guardedRun(ErrorCode.IO_FAILURE, () -> mergeInto(inputs, output, warnings));
        warnings.report();
        return output;
    }

    private void mergeInto(Sources inputs, Path output, Warnings warnings) throws IOException {
        writingTo(output, () -> mergeAllInto(inputs, output, warnings));
    }

    private void mergeAllInto(Sources inputs, Path output, Warnings warnings) throws IOException {
        try (OpenDocuments sources = new OpenDocuments();
                PDDocument merged = new PDDocument()) {
            PDFMergerUtility merger = newMerger();
            PDDocumentInformation information = null;
            for (Source input : inputs.all()) {
                // appendDocument は入力のページを参照でつなぐ。保存が終わるまで
                // 入力を閉じられないため、まとめて開いたまま保持する。
                PdfDocument source = sources.open(input, warnings);
                if (information == null) {
                    information = detachedInformationOf(source);
                }
                merger.appendDocument(merged, source.delegate());
            }
            applyInformation(merged, information, inputs.size() > 1, warnings);
            saver.save(merged, output);
        }
    }

    @Override
    public List<Path> split(Source input, SplitStrategy strategy, Path outputDir) {
        requireSource(input);
        requireSupported(strategy.encryptionPropagation());
        if (outputDir == null) {
            throw new IllegalArgumentException("outputDir は null にできません。");
        }

        Warnings warnings = new Warnings(listener);
        List<Path> outputs = guarded(ErrorCode.NOT_A_PDF, () -> splitInto(input, strategy, outputDir, warnings));
        warnings.report();
        return outputs;
    }

    private List<Path> splitInto(Source input, SplitStrategy strategy, Path outputDir, Warnings warnings) {
        List<PageRange> ranges;
        try (PdfDocument source = open(input, warnings)) {
            ranges = resolveRanges(strategy, source.pageCount());
        }
        List<Path> outputs = splitOutputPaths(input.path(), outputDir, ranges.size());
        // 1 つでも書けないなら、何も書かずに失敗させる。
        outputs.forEach(PdfBoxPageOperations::requireAbsent);

        createDirectories(outputDir);
        // 途中で失敗したら、それまでに書いたものを消す。9 個中 4 個だけ残った状態を
        // 黙って失敗として返すと、利用者は何が出来上がったのか確かめる術がない。
        // requireAbsent を通しているため、ここで消してよいのはこの呼び出しが作ったものだけである。
        List<Path> written = new ArrayList<>(outputs.size());
        try {
            for (int i = 0; i < ranges.size(); i++) {
                // 書く前に控える。書きかけで失敗したファイルも後始末の対象にする。
                written.add(outputs.get(i));
                // 出力ごとに開き直す。書き出しは元の文書からページを取り除いていくため、
                // 同じ文書を次の範囲に使い回すことはできない。
                try (PdfDocument source = openQuietly(input)) {
                    writeRange(source, ranges.get(i), outputs.get(i), warnings);
                }
            }
        } catch (RuntimeException e) {
            written.forEach(PdfBoxPageOperations::deleteQuietly);
            throw e;
        }
        return List.copyOf(outputs);
    }

    @Override
    public Path reorder(Source input, List<Integer> newOrder, Path output) {
        requireSource(input);
        requireAbsent(output);

        Warnings warnings = new Warnings(listener);
        guardedRun(ErrorCode.NOT_A_PDF, () -> reorderInto(input, newOrder, output, warnings));
        warnings.report();
        return output;
    }

    private void reorderInto(Source input, List<Integer> newOrder, Path output, Warnings warnings) throws IOException {
        writingTo(output, () -> {
            try (PdfDocument source = open(input, warnings)) {
                requirePermutation(newOrder, source.pageCount());
                writeFromSingleSource(source, selectionsOf(newOrder), output, warnings);
            }
        });
    }

    @Override
    public Path assemble(Sources inputs, List<PageSelection> pages, Path output) {
        requireInputs(inputs);
        requireAbsent(output);

        Warnings warnings = new Warnings(listener);
        guardedRun(ErrorCode.NOT_A_PDF, () -> assembleInto(inputs, pages, output, warnings));
        warnings.report();
        return output;
    }

    /**
     * 選ばれたページを 1 つのファイルに書き出す実体。
     *
     * <p><b>★ 公開の {@code assemble} からは、ここを直に呼ぶ。</b>公開メソッドどうしで
     * 呼び合うと、<b>内側の {@code report} が外側の包みの中で走る</b>——
     * まさに {@link Warnings} が避けている形である。
     */
    private void assembleInto(Sources inputs, List<PageSelection> pages, Path output, Warnings warnings)
            throws IOException {
        writingTo(output, () -> assembleAllInto(inputs, pages, output, warnings));
    }

    private void assembleAllInto(Sources inputs, List<PageSelection> pages, Path output, Warnings warnings)
            throws IOException {
        // 結合では保存が終わるまで入力を閉じられない。まとめて開いたまま保持する。
        try (OpenDocuments sources = new OpenDocuments()) {
            List<PdfDocument> documents = new ArrayList<>(inputs.size());
            for (Source input : inputs.all()) {
                documents.add(sources.openQuietly(input));
            }
            requireSelectable(pages, documents);
            warnAboutContributing(documents, pages, warnings);
            write(inputs, documents, pages, output, warnings);
        }
    }

    @Override
    public List<Path> assembleEach(Sources inputs, List<List<PageSelection>> segments, Path outputDir) {
        requireInputs(inputs);
        if (segments == null || segments.isEmpty()) {
            throw new PdfjigException(ErrorCode.EMPTY_RESULT);
        }
        if (outputDir == null) {
            throw new IllegalArgumentException("outputDir は null にできません。");
        }

        Warnings warnings = new Warnings(listener);
        List<Path> outputs =
                guarded(ErrorCode.NOT_A_PDF, () -> assembleEachInto(inputs, segments, outputDir, warnings));
        warnings.report();
        return outputs;
    }

    private List<Path> assembleEachInto(
            Sources inputs, List<List<PageSelection>> segments, Path outputDir, Warnings warnings) throws IOException {
        // 名前は最初の入力から作る。split と同じ規則を使う。
        List<Path> outputs = splitOutputPaths(inputs.get(0).path(), outputDir, segments.size());
        // 1 つでも書けないなら、何も書かずに失敗させる。
        outputs.forEach(PdfBoxPageOperations::requireAbsent);

        createDirectories(outputDir);
        // 途中で失敗したら、それまでに書いたものを消す。requireAbsent を通しているため、
        // ここで消してよいのはこの呼び出しが作ったものだけである。
        List<Path> written = new ArrayList<>(outputs.size());
        try {
            for (int i = 0; i < segments.size(); i++) {
                // 書く前に控える。書きかけで失敗したファイルも後始末の対象にする。
                written.add(outputs.get(i));
                assembleInto(inputs, segments.get(i), outputs.get(i), warnings);
            }
        } catch (RuntimeException e) {
            written.forEach(PdfBoxPageOperations::deleteQuietly);
            throw e;
        }
        return List.copyOf(outputs);
    }

    @Override
    public Path rotate(Source input, Map<Integer, Rotation> rotations, Path output) {
        requireSource(input);
        if (rotations == null) {
            throw new IllegalArgumentException("rotations は null にできません。");
        }
        requireAbsent(output);

        Warnings warnings = new Warnings(listener);
        guardedRun(ErrorCode.NOT_A_PDF, () -> rotateInto(input, rotations, output, warnings));
        warnings.report();
        return output;
    }

    private void rotateInto(Source input, Map<Integer, Rotation> rotations, Path output, Warnings warnings)
            throws IOException {
        writingTo(output, () -> rotateAllInto(input, rotations, output, warnings));
    }

    private void rotateAllInto(Source input, Map<Integer, Rotation> rotations, Path output, Warnings warnings) {
        try (PdfDocument source = open(input, warnings)) {
            int pageCount = source.pageCount();
            rotations
                    .keySet()
                    .forEach(pageNumber -> PageRange.singlePage(pageNumber).validateAgainst(pageCount));

            // 回転はページ属性の変更だけで済む。ページの並びに手を触れないため、
            // ページツリーを均す必要もない。元の文書をそのまま保存する。
            PDDocument delegate = source.delegate();
            for (Map.Entry<Integer, Rotation> entry : rotations.entrySet()) {
                PDPage page = delegate.getPage(entry.getKey() - 1);
                page.setRotation(rotationOf(page).plus(entry.getValue()).degrees());
            }
            // 入力が暗号化されていた場合、PDFBox は保護を保ったまま保存しようとする。
            // M0 が扱うのは EncryptionPropagation.NONE のみであり、
            // 保護は落ちる（警告は open で発している）。
            delegate.setAllSecurityToBeRemoved(true);
            saver.save(delegate, output);
        }
    }

    @Override
    public Path extractPages(Source input, PageRange range, Path output) {
        requireSource(input);
        requireRange(range);
        requireAbsent(output);

        Warnings warnings = new Warnings(listener);
        guardedRun(ErrorCode.NOT_A_PDF, () -> extractPagesInto(input, range, output, warnings));
        warnings.report();
        return output;
    }

    private void extractPagesInto(Source input, PageRange range, Path output, Warnings warnings) throws IOException {
        writingTo(output, () -> {
            try (PdfDocument source = open(input, warnings)) {
                range.validateAgainst(source.pageCount());
                writeRange(source, range, output, warnings);
            }
        });
    }

    @Override
    public Path deletePages(Source input, PageRange range, Path output) {
        requireSource(input);
        requireRange(range);
        requireAbsent(output);

        Warnings warnings = new Warnings(listener);
        guardedRun(ErrorCode.NOT_A_PDF, () -> deletePagesInto(input, range, output, warnings));
        warnings.report();
        return output;
    }

    private void deletePagesInto(Source input, PageRange range, Path output, Warnings warnings) throws IOException {
        writingTo(output, () -> deleteAllInto(input, range, output, warnings));
    }

    private void deleteAllInto(Source input, PageRange range, Path output, Warnings warnings) {
        try (PdfDocument source = open(input, warnings)) {
            int pageCount = source.pageCount();
            range.validateAgainst(pageCount);

            List<Integer> remaining = IntStream.rangeClosed(1, pageCount)
                    .boxed()
                    .filter(pageNumber -> !range.contains(pageNumber))
                    .toList();
            if (remaining.isEmpty()) {
                throw new PdfjigException(ErrorCode.EMPTY_RESULT);
            }
            writeFromSingleSource(source, selectionsOf(remaining), output, warnings);
        }
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
    private void write(
            Sources inputs, List<PdfDocument> sources, List<PageSelection> pages, Path output, Warnings warnings) {
        int single = singleSourceIndexOf(pages);
        if (single >= 0) {
            writeFromSingleSource(sources.get(single), pages, output, warnings);
        } else {
            writeByMerging(inputs, pages, output, warnings);
        }
    }

    /**
     * 出力に寄与する入力についてだけ、暗号化と署名を伝える。
     *
     * <p><b>開いた時点では伝えない。</b> 画面で「PDF を追加」したあと、足したほうのページを
     * 1 枚残らず消してから保存すると、その入力は出力に 1 バイトも寄与しないまま
     * {@code inputs} に残る（{@code deleteSelected} は並びから外すだけで、
     * ファイル一覧からは外さない）。そこで開いた時点で警告すると、
     * <b>署名の無い出力について「署名が無効になる」と告げる</b>ことになる。
     *
     * <p>中身と食い違う警告は、次に本物の署名済み文書を編集したときに無視される
     * （{@code CLAUDE.md} 優先順位 2）。
     *
     * <p>寄与する入力が複数あれば<b>その数だけ伝える</b>。1 つにまとめない——
     * どれが暗号化されていたのかは、数が合っていないと利用者から辿れない。
     */
    private static void warnAboutContributing(
            List<PdfDocument> documents, List<PageSelection> pages, Warnings warnings) {
        Set<Integer> contributing = new TreeSet<>();
        for (PageSelection selection : pages) {
            contributing.add(selection.sourceIndex());
        }
        for (int index : contributing) {
            PdfDocument document = documents.get(index);
            if (document.encrypted()) {
                warnings.add(Warning.ENCRYPTION_NOT_PROPAGATED);
            }
            if (document.signed()) {
                warnings.add(Warning.SIGNATURE_INVALIDATED);
            }
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

    /**
     * 元の文書から要らないページを取り除いて並べ替え、その文書を保存する。
     *
     * <p><b>★★ ここが 5 つの公開操作の書き出しの実体である</b>——{@code reorder} /
     * {@code extractPages} / {@code deletePages} / {@code assemble} / {@code split} が通る。
     *
     * <p><b>★★ ここは長く包めなかった</b>（#178）。この下は
     * {@code PageReferences.removeDangling} から警告を出すが、<b>受け口を直に呼んでいた間は
     * そこで呼ぶ側のコードが走り</b>、包むと<b>呼ぶ側が投げた失敗まで
     * 「PDF として読み取れません」に塗り替わった。</b><b>型では区別が付かない</b>ので、
     * 先に {@link Warnings} を通す形へ替えてある——<b>いま走るのは溜めることだけである。</b>
     */
    private void writeFromSingleSource(PdfDocument source, List<PageSelection> pages, Path output, Warnings warnings) {
        PDDocument document = source.delegate();

        // 並べ替えでページツリーが 1 階層に均される。継承していた属性を先に固定する。
        PageReferences.fixInherited(document.getPages());

        List<PDPage> all = new ArrayList<>();
        document.getPages().forEach(all::add);

        List<PDPage> ordered = new ArrayList<>(pages.size());
        for (PageSelection selection : pages) {
            ordered.add(document.getPage(selection.pageNumber() - 1));
        }
        replacePages(document, ordered);
        applyRotations(ordered, pages);

        if (PageReferences.removeDangling(document)) {
            warnings.add(Warning.DANGLING_REFERENCES_REMOVED);
        }
        // 参照を畳んだ後に、出力に含めないページの中身を捨てる。
        // これは利用者に伝えることではない——警告は「しおりやリンクを落とした」ことを指す。
        PageReferences.discard(all, ordered);

        // 入力が暗号化されていた場合、PDFBox は保護を保ったまま保存しようとする。
        // M0 が扱うのは EncryptionPropagation.NONE のみであり、
        // 保護は落ちる（警告は open で発している）。
        document.setAllSecurityToBeRemoved(true);
        saver.save(document, output);
    }

    /**
     * 必要なページだけに切り詰めた複製を作り、結合してから並べ替える。
     *
     * <p>同じページを複数回出す場合は、その入力を回数ぶん別々に切り詰めて足す。
     *
     * <p><b>★ ここだけ符号が {@link ErrorCode#IO_FAILURE} である。</b>公開の入口の包みは
     * {@link ErrorCode#NOT_A_PDF} を渡すので、<b>内側で言い直す</b>——
     * {@link PdfjigException#wrapping} が既に包んであるものを素通しするため、
     * <b>入口の符号には塗り替わらない。</b>
     *
     * <p><b>★★ 同じ公開メソッドが内側の分岐で違う符号を返す。</b>{@code assemble} は
     * 出どころが 1 つなら {@link ErrorCode#NOT_A_PDF}、混ざれば {@link ErrorCode#IO_FAILURE} に
     * なる——<b>呼ぶ側はその分岐を見られない</b>（{@code CLAUDE.md} 優先順位 2）。
     * <b>この差分では揃えていない</b>——{@code merge} の符号ごと動かすことになり、
     * <b>利用者へ届く値が変わる</b>。#191 が持つ。
     */
    private void writeByMerging(Sources inputs, List<PageSelection> pages, Path output, Warnings warnings) {
        guardedRun(ErrorCode.IO_FAILURE, () -> mergeCopiesInto(inputs, pages, output, warnings));
    }

    private void mergeCopiesInto(Sources inputs, List<PageSelection> pages, Path output, Warnings warnings)
            throws IOException {
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
                    trimTo(copy, wanted, warnings);
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

            boolean mixed = pages.stream()
                            .mapToInt(PageSelection::sourceIndex)
                            .distinct()
                            .count()
                    > 1;
            applyInformation(target, information, mixed, warnings);

            target.setAllSecurityToBeRemoved(true);
            saver.save(target, output);
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
    private static void trimTo(PdfDocument document, List<Integer> wanted, Warnings warnings) {
        PDDocument delegate = document.delegate();
        PageReferences.fixInherited(delegate.getPages());

        List<PDPage> all = new ArrayList<>();
        delegate.getPages().forEach(all::add);

        List<PDPage> kept = new ArrayList<>(wanted.size());
        for (int pageNumber : wanted) {
            kept.add(delegate.getPage(pageNumber - 1));
        }
        replacePages(delegate, kept);

        if (PageReferences.removeDangling(delegate)) {
            warnings.add(Warning.DANGLING_REFERENCES_REMOVED);
        }
        PageReferences.discard(all, kept);
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
    private static void applyInformation(
            PDDocument merged, PDDocumentInformation information, boolean mixed, Warnings warnings) {
        merged.setDocumentInformation(information == null ? new PDDocumentInformation() : information);
        if (mixed) {
            warnings.add(Warning.METADATA_FROM_FIRST_INPUT);
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

    /**
     * 入力を開き、暗号化や電子署名があれば伝える。
     *
     * <p><b>★★ 検めるところで投げたら、開いたものを閉じてから投げ直す</b>（#150）。
     * {@code signed()} はフォームと欄の木を辿るので、<b>壊れた {@code /AcroForm} で投げる</b>
     * ——そのとき文書は {@code OpenDocuments} にも呼ぶ側の try-with-resources にも渡っておらず、
     * <b>誰も閉じない。</b>Windows では<b>その PDF への手が握られたまま</b>になり、
     * 後から同じ場所へ保存できない。
     *
     * <p><b>{@code pdf-desktop} の {@code DocumentSession#wrap} が同じ形を持っている。</b>
     */
    private static PdfDocument open(Source input, Warnings warnings) {
        PdfDocument document = openQuietly(input);
        try {
            if (document.encrypted()) {
                warnings.add(Warning.ENCRYPTION_NOT_PROPAGATED);
            }
            if (document.signed()) {
                warnings.add(Warning.SIGNATURE_INVALIDATED);
            }
        } catch (RuntimeException e) {
            // ★★ 閉じる側も投げうる（PdfDocument#close は未検査例外を IO_FAILURE で包む）。
            //   そのまま書くと、開いた後に投げた本当の失敗がそこで消える。
            //   ★ 伝えるのは元の失敗である。閉じられなかったことは抑制例外として付ける
            //   （OpenDocuments と同じ作法）。
            try {
                document.close();
            } catch (RuntimeException closing) {
                e.addSuppressed(closing);
            }
            throw e;
        }
        return document;
    }

    /**
     * 入力を開くが、警告は発しない。
     *
     * <p>同じ入力を書き出しの都合で開き直す場合に使う。開いた回数だけ警告が出ると、
     * 利用者には何件の問題があるのか分からなくなる。警告は入力ごとに 1 度でよい。
     */
    private static PdfDocument openQuietly(Source input) {
        return input.password() == null
                ? PdfDocument.open(input.path())
                : PdfDocument.open(input.path(), input.password());
    }

    /** ページ番号の並びを、向きを変えない指定に直す。 */
    private static List<PageSelection> selectionsOf(List<Integer> pageNumbers) {
        return pageNumbers.stream().map(PageSelection::of).toList();
    }

    private void writeRange(PdfDocument source, PageRange range, Path output, Warnings warnings) {
        writeFromSingleSource(source, selectionsOf(pageNumbersOf(range)), output, warnings);
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

    /**
     * 入力が渡されているか。
     *
     * <p><b>★ 包みの外で弾く。</b>{@link Sources} も {@link Source} も<b>参照そのものの
     * {@code null} は見られない</b>——中で {@code NullPointerException} になると
     * {@link ErrorCode#NOT_A_PDF} に畳まれて、<b>正しい入力のせいにされる</b>
     * （#193 の門の 1 段目と 2 段目）。
     */
    private static void requireInputs(Sources inputs) {
        if (inputs == null) {
            throw new PdfjigException(ErrorCode.NO_INPUT);
        }
    }

    /** 入力が渡されているか。理由は {@link #requireInputs} と同じである。 */
    private static void requireSource(Source input) {
        if (input == null) {
            throw new PdfjigException(ErrorCode.NO_INPUT);
        }
    }

    /**
     * 範囲が渡されているか。
     *
     * <p><b>★ 包みの外で弾く。</b>{@code range.validateAgainst} は文書を開かないと呼べないので
     * 包みの中に在り、<b>そこで {@code NullPointerException} になると
     * {@link ErrorCode#NOT_A_PDF} に畳まれて、正しい入力のせいにされる</b>（#178 の門の 2 段目）。
     */
    private static void requireRange(PageRange range) {
        if (range == null) {
            throw new IllegalArgumentException("range は null にできません。");
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

    /**
     * 書き出す。失敗したら、この呼び出しが作った出力を残さない。
     *
     * <p><b>★★ 残すと、平文が「失敗した」と告げたまま置き去りになる</b>（#193 の門の 2 段目）。
     * <b>鍵の要る入力を扱えるようになって、初めて重くなった</b>——差分の前にここへ届いたのは
     * オーナーパスワードだけの文書であり、<b>中身はもともと誰でも開けた。</b>
     * いまは<b>ユーザーパスワードで守られた文書を、完全に復号した写しが残りうる</b>
     * （{@code SECURITY.md}「対象範囲」／{@code CLAUDE.md} 優先順位 1・2）。
     *
     * <p><b>★★ 書き終えた後の失敗でも消す。</b>{@code PdfDocument#close} は<b>閉じるときに
     * 初めて投げることがある</b>（#150）ので、<b>完全に書けた平文が残る形がいちばん危ない。</b>
     * ★ <b>{@code PdfBoxEncryption#protectInto} が閉じる失敗を消す範囲から外しているのは、
     * あちらの出力が保護されているからである</b>——<b>ここは逆で、残るものが平文である。</b>
     *
     * <p><b>★ 消してよいのは、この呼び出しが作ったものだけである。</b>
     * どの経路も先に {@link #requireAbsent} を通っている。
     *
     * <p><b>★ 通知は流れない。</b>例外が先に外へ出るので {@code Warnings#report} へ来ないが、
     * <b>出力が消えているので告げることが無い</b>——「保護は引き継がれません」は
     * <b>残った平文について言う言葉である。</b>
     */
    private void writingTo(Path output, PdfBoxGuard.PdfBoxAction write) throws IOException {
        try {
            write.run();
        } catch (IOException | RuntimeException e) {
            deleteQuietly(output);
            throw e;
        }
    }

    /**
     * 後始末で消す。消せなくても黙っている。
     *
     * <p>ここに来ている時点で、既に何かが失敗している。その失敗のほうが利用者にとって
     * 重要であり、後始末できなかったことを重ねて伝えても混乱するだけである。
     */
    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            // 消せなくても、元の失敗は元の失敗のまま伝わる。
        }
    }

    private static void createDirectories(Path directory) {
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw PdfjigException.wrapping(ErrorCode.IO_FAILURE, e);
        }
    }

    /**
     * 書き出す手。
     *
     * <p>既定は {@link PdfBoxPageOperations#saveDocument}。差し替える理由は
     * {@link PdfBoxPageOperations#PdfBoxPageOperations(WarningListener, DocumentSaver)} を見ること。
     */
    @FunctionalInterface
    interface DocumentSaver {

        /**
         * 書き出す。
         *
         * @throws PdfjigException 書けない場合は {@link ErrorCode#IO_FAILURE}
         */
        void save(PDDocument document, Path output);
    }

    /**
     * 既定の書き出し。
     *
     * <p><b>★★ 失敗は必ず {@link ErrorCode#IO_FAILURE} である。</b>ここで包まないと、
     * 呼ぶ側の包みが拾って<b>その場の符号に塗り替える</b>——
     * {@code rotate} なら「PDF として読み取れません」になり、
     * <b>出力が書けなかっただけなのに入力のせいにされる</b>（#150）。
     * 包んであれば {@link PdfjigException#wrapping} が素通しするので、
     * <b>どの呼ぶ側を通っても符号は変わらない。</b>
     */
    static void saveDocument(PDDocument document, Path output) {
        guardedRun(ErrorCode.IO_FAILURE, () -> document.save(output.toFile()));
    }

    /**
     * 複数の入力を開いたまま保持し、まとめて閉じる。
     *
     * <p>結合では保存が終わるまで入力を閉じられない。閉じる際の失敗は最初の 1 件を投げ、
     * 残りは抑制例外として付ける。
     */
    private static final class OpenDocuments implements AutoCloseable {

        private final List<PdfDocument> documents = new ArrayList<>();

        PdfDocument open(Source source, Warnings warnings) {
            return keep(PdfBoxPageOperations.open(source, warnings));
        }

        PdfDocument openQuietly(Source source) {
            return keep(PdfBoxPageOperations.openQuietly(source));
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
