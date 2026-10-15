package io.github.propagandist.pdfjig.core;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
 * <p>そこで、<b>並べ替える前に各出どころのラベルを {@link #read} で控え</b>、
 * 並べ終えてから {@link #write} で出力の並びに範囲を組み直す。
 * 隣り合うページが同じ範囲の続きなら 1 つにまとめる。
 *
 * <ul>
 *   <li><b>ラベルを持つ出どころが 1 つも無ければ {@code /PageLabels} を作らない</b></li>
 *   <li>あれば、ラベルの無い出どころのページには<b>元のページ番号</b>を算用数字で付ける——
 *       その文書を開いた閲覧ソフトが出していた番号である</li>
 *   <li><b>★ 読めない出どころが 1 つでもあれば、何もしない</b>（利用者が 2026-10-15 に決めた）。
 *       この修正の前と同じく、{@code /PageLabels} は添字のまま残る。
 *       失敗にすると、修正の前に保存できていた文書が保存できなくなる。
 *       外すと、持っていたものを黙って失う（{@code CLAUDE.md} 優先順位 1）</li>
 * </ul>
 */
final class CarriedPageLabels {

    /**
     * ラベルを持つ出どころだけの、出どころの添字から範囲への写像。範囲は始まりの添字で引く。
     *
     * <p><b>ページごとのラベルではなく範囲を控える。</b>出力に残すのが 1 ページでも、
     * ページごとに控えると出どころの全ページ分を作ることになる。
     */
    private final Map<Integer, TreeMap<Integer, Label>> labelled = new HashMap<>();

    private boolean unreadable;

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
     * 出どころの各ページが持つラベルを控える。
     *
     * <p><b>ページを並べ替える・切り詰める前に呼ぶこと。</b>範囲は添字で決まるので、
     * 後で読むと、まさに直したいずれたラベルが返る。
     */
    void read(int sourceIndex, PDDocument document) {
        PDPageLabels labels;
        try {
            labels = document.getDocumentCatalog().getPageLabels();
        } catch (IOException | RuntimeException e) {
            // 型の合わない数の木は、PDFBox の中で IOException にも ClassCastException にもなる。
            // どちらでも「読めない」であり、扱いは同じである。
            unreadable = true;
            return;
        }
        if (labels == null) {
            return;
        }

        // PDPageLabels は添字 0 に既定の範囲（算用数字）を置いてから読むので、
        // どのページにも必ず手前の範囲がある。各範囲は、その始まりのページのラベルとして控える。
        TreeMap<Integer, Label> ranges = new TreeMap<>();
        for (int index : labels.getPageIndices()) {
            PDPageLabelRange range = labels.getPageLabelRange(index);
            ranges.put(index, new Label(range.getStyle(), range.getPrefix(), range.getStart()));
        }
        labelled.put(sourceIndex, ranges);
    }

    /** 出力のそのページが、元の文書で持っていたラベル。 */
    private Label labelOf(PageSelection selection) {
        TreeMap<Integer, Label> ranges = labelled.get(selection.sourceIndex());
        if (ranges == null) {
            return Label.plain(selection.pageNumber());
        }
        int index = selection.pageNumber() - 1;
        Map.Entry<Integer, Label> range = ranges.floorEntry(index);
        Label first = range.getValue();
        return new Label(first.style(), first.prefix(), first.number() + (index - range.getKey()));
    }

    /**
     * 出力の各ページに、控えたラベルを付け直す。
     *
     * @param document 出力の文書。ページは既に {@code pages} の順に並んでいること
     * @param pages 出力の各ページがどの出どころの何ページ目か
     */
    void write(PDDocument document, List<PageSelection> pages) {
        if (unreadable) {
            return;
        }
        if (labelled.isEmpty()) {
            document.getDocumentCatalog().getCOSObject().removeItem(COSName.PAGE_LABELS);
            return;
        }

        PDPageLabels labels = new PDPageLabels(document);
        Label previous = null;
        for (int index = 0; index < pages.size(); index++) {
            Label label = labelOf(pages.get(index));
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
            range.getCOSObject().setInt(COSName.ST, label.number());
        }
        return range;
    }
}
