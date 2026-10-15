package io.github.propagandist.pdfjig.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDPageLabelRange;
import org.apache.pdfbox.pdmodel.common.PDPageLabels;

/**
 * ページラベルを、ページと一緒に運ぶ（#257）。
 *
 * <p><b>なぜ要るのか。</b> {@code /PageLabels} は<b>ページの添字</b>で範囲の始まりを決める。
 * ページを並べ替えたり消したりしても範囲は添字のまま残るので、
 * <b>閲覧ソフトのページ番号欄が中身とずれた番号を出す</b>——表紙を消すと、目次に {@code i} が付く。
 * 複数の入力を結合すると、ラベルの無い入力のページが前の入力の最後の範囲を引き継ぐ。
 *
 * <p>そこで、<b>並べ替える前に各ページのラベルを読み</b>、出力の並びで範囲を組み直す。
 * 隣り合うページが同じ範囲の続きなら 1 つにまとめる。
 *
 * <p><b>ラベルを持つ出どころが 1 つも無ければ {@code /PageLabels} を作らない。</b>
 * あれば、ラベルの無い出どころのページには<b>元のページ番号</b>を算用数字で付ける——
 * その文書を開いた閲覧ソフトが出していた番号である。
 */
final class CarriedPageLabels {

    private static final COSName START = COSName.getPDFName("St");

    private CarriedPageLabels() {}

    /**
     * 1 ページのラベル。
     *
     * @param style 番号の書式。{@code null} なら番号を出さず、接頭辞だけになる
     * @param prefix 接頭辞。無ければ {@code null}
     * @param number そのページの番号
     */
    record Label(String style, String prefix, int number) {

        /** ラベルを持たない文書で、閲覧ソフトがそのページに出す番号。 */
        static Label plain(int pageNumber) {
            return new Label(PDPageLabelRange.STYLE_DECIMAL, null, pageNumber);
        }

        /** {@code previous} と同じ範囲の、すぐ次のページとして書けるか。 */
        boolean follows(Label previous) {
            return Objects.equals(style, previous.style)
                    && Objects.equals(prefix, previous.prefix)
                    && (style == null || number == previous.number + 1);
        }
    }

    /**
     * 文書の各ページが持つラベルを、ページの並びで返す。
     *
     * <p><b>ページを並べ替える前に呼ぶこと。</b>範囲は添字で決まるので、
     * 並べ替えた後に読むと、まさに直したいずれたラベルが返る。
     *
     * @return {@code /PageLabels} を持たなければ空
     */
    static Optional<List<Label>> read(PDDocument document) {
        PDPageLabels labels;
        try {
            labels = document.getDocumentCatalog().getPageLabels();
        } catch (IOException e) {
            throw PdfjigException.wrapping(ErrorCode.IO_FAILURE, e);
        }
        if (labels == null) {
            return Optional.empty();
        }

        // PDPageLabels は添字 0 に既定の範囲（算用数字）を置いてから読むので、
        // どのページにも必ず手前の範囲がある。
        TreeMap<Integer, PDPageLabelRange> ranges = new TreeMap<>();
        for (int index : labels.getPageIndices()) {
            ranges.put(index, labels.getPageLabelRange(index));
        }

        int pageCount = document.getNumberOfPages();
        List<Label> perPage = new ArrayList<>(pageCount);
        for (int index = 0; index < pageCount; index++) {
            Map.Entry<Integer, PDPageLabelRange> range = ranges.floorEntry(index);
            PDPageLabelRange value = range.getValue();
            perPage.add(new Label(value.getStyle(), value.getPrefix(), value.getStart() + (index - range.getKey())));
        }
        return Optional.of(List.copyOf(perPage));
    }

    /**
     * 出力の各ページに、元のラベルを付け直す。
     *
     * @param document 出力の文書。ページは既に {@code pages} の順に並んでいること
     * @param labelled ラベルを持つ出どころだけの、出どころの添字から {@link #read} の結果への写像
     * @param pages 出力の各ページがどの出どころの何ページ目か
     */
    static void write(PDDocument document, Map<Integer, List<Label>> labelled, List<PageSelection> pages) {
        if (labelled.isEmpty()) {
            document.getDocumentCatalog().getCOSObject().removeItem(COSName.PAGE_LABELS);
            return;
        }

        PDPageLabels labels = new PDPageLabels(document);
        Label previous = null;
        for (int index = 0; index < pages.size(); index++) {
            PageSelection selection = pages.get(index);
            List<Label> source = labelled.get(selection.sourceIndex());
            Label label = source == null ? Label.plain(selection.pageNumber()) : source.get(selection.pageNumber() - 1);
            if (previous == null || !label.follows(previous)) {
                labels.setLabelItem(index, rangeStartingAt(label));
            }
            previous = label;
        }
        document.getDocumentCatalog().setPageLabels(labels);
    }

    private static PDPageLabelRange rangeStartingAt(Label label) {
        PDPageLabelRange range = new PDPageLabelRange();
        if (label.style() != null) {
            range.setStyle(label.style());
        }
        if (label.prefix() != null) {
            range.setPrefix(label.prefix());
        }
        // setStart は 1 未満を拒む。元の文書が持っていた値は、仕様に反していてもそのまま運ぶ
        // ——ここで投げると、ラベルのせいで保存そのものが止まる。
        if (label.number() != 1) {
            range.getCOSObject().setInt(START, label.number());
        }
        return range;
    }
}
