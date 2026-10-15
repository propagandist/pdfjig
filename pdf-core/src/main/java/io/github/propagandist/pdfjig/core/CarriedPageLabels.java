package io.github.propagandist.pdfjig.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNull;
import org.apache.pdfbox.pdmodel.PDDocument;

/**
 * ページラベルを、ページと一緒に運ぶ（#257）。
 *
 * <p><b>なぜ要るのか。</b> {@code /PageLabels} は<b>ページの添字</b>で範囲の始まりを決める。
 * ページを並べ替えたり消したりしても範囲は添字のまま残るので、
 * <b>閲覧ソフトのページ番号欄が中身とずれた番号を出す</b>——表紙を消すと、目次に {@code i} が付く。
 * 複数の入力を結合すると、ラベルの無い入力のページが前の入力の最後の範囲を引き継ぐ。
 *
 * <p>そこで、各出どころの範囲を {@link #read} で控え、並べ終えてから {@link #write} で
 * 出力の並びに範囲を組み直す。隣り合うページが同じ範囲の続きなら 1 つにまとめる。
 *
 * <ul>
 *   <li><b>ラベルを持つ出どころが 1 つも無ければ {@code /PageLabels} を作らない</b></li>
 *   <li>あれば、ラベルの無い出どころのページには<b>元のページ番号</b>を算用数字で付ける——
 *       その文書を開いた閲覧ソフトが出していた番号である</li>
 *   <li><b>出力の並びが出どころのページそのままなら、何もしない</b>——回転だけの保存で中身を変えない</li>
 *   <li><b>★ 読めない出どころが 1 つでもあれば、何もしない</b>（利用者が 2026-10-15 に決めた）。
 *       この修正の前と同じく、{@code /PageLabels} は添字のまま残る。
 *       失敗にすると、修正の前に保存できていた文書が保存できなくなる。
 *       外すと、持っていたものを黙って失う（{@code CLAUDE.md} 優先順位 1）</li>
 * </ul>
 *
 * <p><b>★★ 数の木は自分で読み、範囲の辞書は元のものを写して運ぶ</b>（#257 の門の 2 段目）。
 * PDFBox の {@code PDPageLabels} を通すと、<b>壊れた木を黙って読み飛ばし</b>
 * （整数でないキーの葉を捨てる・値の {@code null} をそのまま返す）、<b>循環で
 * {@code StackOverflowError} を投げ</b>、<b>接頭辞を文字列に直して書き戻すのでバイト列が変わる</b>
 * （UTF-8 の「中」の 0xAD が 0x00 になる）。どれも 2026-10-15 に実測した。
 * ここでは<b>解釈するもの</b>（節・{@code /Kids}・{@code /Nums}・キー・範囲の辞書・{@code /St}）の型を
 * 確かめ、合わなければ「読めない」とする。{@code /S} と {@code /P} は解釈せず、そのまま運ぶ。
 * 書くときは {@code /St} だけを直す。
 */
final class CarriedPageLabels {

    /** ラベルを持つ出どころだけの、出どころの添字から範囲への写像。 */
    private final Map<Integer, TreeMap<Integer, COSDictionary>> labelled = new HashMap<>();

    /** 出どころの添字から、読んだ文書とそのページ数への写像。並びがそのままかを見るのに使う。 */
    private final Map<Integer, Read> reads = new HashMap<>();

    private boolean leftAlone;

    private record Read(PDDocument document, int pageCount) {}

    /**
     * 1 ページのラベル。
     *
     * @param range 元の範囲の辞書。ラベルを持たない出どころのページなら {@code null}
     * @param number そのページの番号
     */
    private record Label(COSDictionary range, long number) {

        /** {@code previous} と同じ範囲の、すぐ次のページとして書けるか。 */
        boolean follows(Label previous) {
            return range == previous.range && number == previous.number + 1;
        }
    }

    /**
     * 出どころの範囲を控える。
     *
     * <p><b>ページを並べ替える・切り詰める前に呼ぶこと。</b>並びがそのままかを見るために
     * 出どころのページ数を控えるが、並べ替えた後はそれが出力のページ数になっている。
     */
    void read(int sourceIndex, PDDocument document) {
        reads.put(sourceIndex, new Read(document, document.getNumberOfPages()));

        // 宛先の無い参照と null は、閲覧ソフトから見てラベルが無いのと同じである。
        COSBase entry = document.getDocumentCatalog().getCOSObject().getDictionaryObject(COSName.PAGE_LABELS);
        if (entry == null || entry instanceof COSNull) {
            return;
        }
        TreeMap<Integer, COSDictionary> ranges;
        try {
            ranges = rangesOf(entry);
        } catch (RuntimeException e) {
            // 参照は辿った時点で読み込まれる。そこで投げる経路があるかは確かめていない。
            // 投げても保存を止めないための備えである——読めないのと同じに扱う。
            ranges = null;
        }
        if (ranges != null && ranges.isEmpty()) {
            // 範囲を 1 つも持たない木は、閲覧ソフトから見てラベルが無いのと同じである。
            return;
        }
        // 範囲が添字 0 から始まらない木は、0 の手前をどう出すかが閲覧ソフトに任されている。運べない。
        if (ranges == null || !ranges.containsKey(0)) {
            leftAlone = true;
            return;
        }
        labelled.put(sourceIndex, ranges);
    }

    /**
     * 出力の各ページに、控えたラベルを付け直す。
     *
     * @param document 出力の文書。ページは既に {@code pages} の順に並んでいること
     * @param pages 出力の各ページがどの出どころの何ページ目か
     */
    void write(PDDocument document, List<PageSelection> pages) {
        if (leftAlone || labelled.isEmpty() || unchanged(document, pages)) {
            return;
        }

        COSArray nums = new COSArray();
        Label previous = null;
        for (int index = 0; index < pages.size(); index++) {
            Label label = labelOf(pages.get(index));
            if (previous == null || !label.follows(previous)) {
                nums.add(COSInteger.get(index));
                nums.add(rangeStartingAt(label));
            }
            previous = label;
        }
        COSDictionary labels = new COSDictionary();
        labels.setItem(COSName.NUMS, nums);
        document.getDocumentCatalog().getCOSObject().setItem(COSName.PAGE_LABELS, labels);
    }

    /**
     * 出力が、<b>その出どころの文書そのもの</b>に、全ページを元の順に並べたものか。
     *
     * <p>★ 結合先の文書は当てはまらない。並びが元のままでも、結合先の {@code /PageLabels} は
     * {@code PDFMergerUtility} が写したもので、元の木そのものではない——
     * 根の {@code /Nums} しか写さないので、{@code /Kids} に置かれた範囲が落ちている（#257 の門）。
     */
    private boolean unchanged(PDDocument document, List<PageSelection> pages) {
        if (pages.isEmpty()) {
            return true;
        }
        int sourceIndex = pages.get(0).sourceIndex();
        Read read = reads.get(sourceIndex);
        if (read == null || read.document() != document || pages.size() != read.pageCount()) {
            return false;
        }
        for (int index = 0; index < pages.size(); index++) {
            PageSelection selection = pages.get(index);
            if (selection.sourceIndex() != sourceIndex || selection.pageNumber() != index + 1) {
                return false;
            }
        }
        return true;
    }

    /** 出力のそのページが、元の文書で持っていたラベル。 */
    private Label labelOf(PageSelection selection) {
        TreeMap<Integer, COSDictionary> ranges = labelled.get(selection.sourceIndex());
        if (ranges == null) {
            return new Label(null, selection.pageNumber());
        }
        int index = selection.pageNumber() - 1;
        Map.Entry<Integer, COSDictionary> range = ranges.floorEntry(index);
        long start = range.getValue().getLong(COSName.ST, 1);
        return new Label(range.getValue(), start + (index - range.getKey()));
    }

    /** そのページから始まる範囲の辞書。元の辞書を写し、{@code /St} だけを直す。 */
    private static COSDictionary rangeStartingAt(Label label) {
        COSDictionary range;
        if (label.range() == null) {
            range = new COSDictionary();
            range.setItem(COSName.S, COSName.D);
        } else {
            range = new COSDictionary(label.range());
        }
        // /St の既定は 1 である。元の辞書が書いていなかったなら、1 のときは書かない。
        if (label.number() != 1 || range.containsKey(COSName.ST)) {
            range.setLong(COSName.ST, label.number());
        }
        return range;
    }

    /**
     * 数の木を歩き、範囲の始まりの添字から範囲の辞書への写像を返す。
     *
     * <p><b>読めなければ {@code null}。</b>次のどれか 1 つでも当たれば読めないとする——
     * 節が辞書でない・{@code /Kids} や {@code /Nums} が配列でない・{@code /Nums} の長さが奇数・
     * キーが 0 以上の整数でない・値が辞書でない・{@code /St} が運べる範囲の整数でない・
     * 同じキーが 2 度出る・同じ節に 2 度着く（循環を含む）。
     *
     * <p><b>再帰しない。</b>深い木で {@code StackOverflowError} を踏まないため、積んで歩く。
     * 欠けた参照は {@code null} として積み、辞書でないので読めないとする。
     */
    private static TreeMap<Integer, COSDictionary> rangesOf(COSBase root) {
        TreeMap<Integer, COSDictionary> ranges = new TreeMap<>();
        Set<COSDictionary> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        List<COSBase> pending = new ArrayList<>();
        pending.add(root);

        while (!pending.isEmpty()) {
            if (!(pending.removeLast() instanceof COSDictionary node) || !visited.add(node)) {
                return null;
            }
            if (node.containsKey(COSName.KIDS)) {
                if (!(node.getDictionaryObject(COSName.KIDS) instanceof COSArray kids)) {
                    return null;
                }
                for (int i = 0; i < kids.size(); i++) {
                    pending.add(kids.getObject(i));
                }
            }
            if (node.containsKey(COSName.NUMS)) {
                if (!(node.getDictionaryObject(COSName.NUMS) instanceof COSArray nums) || nums.size() % 2 != 0) {
                    return null;
                }
                for (int i = 0; i < nums.size(); i += 2) {
                    if (!(nums.getObject(i) instanceof COSInteger key)
                            || key.longValue() < 0
                            || key.longValue() > Integer.MAX_VALUE
                            || !(nums.getObject(i + 1) instanceof COSDictionary range)
                            || !startIsCarriable(range)
                            || ranges.put(key.intValue(), range) != null) {
                        return null;
                    }
                }
            }
        }
        return ranges;
    }

    /**
     * 範囲の {@code /St} を、番号を足しても {@code long} があふれない形で運べるか。
     *
     * <p>添字の差は {@code int} に収まるので、その分の余白を両端に取る。
     */
    private static boolean startIsCarriable(COSDictionary range) {
        if (!range.containsKey(COSName.ST)) {
            return true;
        }
        return range.getDictionaryObject(COSName.ST) instanceof COSInteger start
                && start.longValue() <= Long.MAX_VALUE - Integer.MAX_VALUE
                && start.longValue() >= Long.MIN_VALUE + Integer.MAX_VALUE;
    }
}
