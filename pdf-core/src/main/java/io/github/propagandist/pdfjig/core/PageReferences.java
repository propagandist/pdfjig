package io.github.propagandist.pdfjig.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDestinationNameTreeNode;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentCatalog;
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageTree;
import org.apache.pdfbox.pdmodel.common.PDNameTreeNode;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionGoTo;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDNamedDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode;

/**
 * 文書に残っていないページを指す参照を取り除く。
 *
 * <p><b>なぜ要るのか。</b> ページをページツリーから外しても、しおりやリンク注釈は
 * そのページを指したままである。PDF の書き出しは <b>参照から辿り着けるオブジェクトを
 * すべて書く</b> ため、外したはずのページが「ページ一覧には出ないが、ファイルの中には在る」
 * 状態で残る。目次を持つ文書から機密ページを取り除いて渡す、という使い方で実害が出る。
 *
 * <p>したがってこれは体裁の整えではなく、<b>取り除いたものを本当に取り除くための処理</b>である。
 * ページを取り除いた後、保存する前に必ず通すこと。
 *
 * <p>宛先を失ったしおりは、宛先だけ外して残すのではなく <b>項目ごと取り除く</b>。
 * 押しても何も起きないしおりは、目次があるかのように見せかけるだけで害がある。
 */
final class PageReferences {

    private PageReferences() {}

    /**
     * いま文書に含まれていないページを指す参照を取り除く。
     *
     * @param document ページを取り除いた後の文書
     * @return 取り除いたものがあれば {@code true}
     */
    static boolean removeDangling(PDDocument document) {
        Set<COSDictionary> kept = Collections.newSetFromMap(new IdentityHashMap<>());
        for (PDPage page : document.getPages()) {
            kept.add(page.getCOSObject());
        }
        try {
            boolean removed = removeDanglingLinks(document, kept);
            removed |= pruneOutline(document, kept);
            removed |= removeDanglingNames(document, kept);
            removed |= removeDanglingOpenAction(document, kept);
            return removed;
        } catch (IOException e) {
            throw PdfjigException.wrapping(ErrorCode.IO_FAILURE, e);
        }
    }

    /**
     * 継承していたページ属性を、そのページ自身の辞書に書き込む。
     *
     * <p>ページを並べ替えるとページツリーが 1 階層に均されるため、中間ノードから
     * {@code /Resources} {@code /MediaBox} {@code /CropBox} {@code /Rotate} を
     * 受け継いでいたページは、ここで固定しておかないと寸法や資源を失う。
     */
    static void fixInherited(PDPageTree pages) {
        for (PDPage page : pages) {
            if (page.getResources() != null) {
                page.setResources(page.getResources());
            }
            page.setMediaBox(page.getMediaBox());
            page.setCropBox(page.getCropBox());
            page.setRotation(page.getRotation());
        }
    }

    /** 生き残ったページから、消えたページへ飛ぶリンク注釈を落とす。 */
    private static boolean removeDanglingLinks(PDDocument document, Set<COSDictionary> kept) throws IOException {
        boolean removed = false;
        for (PDPage page : document.getPages()) {
            List<PDAnnotation> annotations = page.getAnnotations();
            List<PDAnnotation> remaining = new ArrayList<>(annotations.size());
            for (PDAnnotation annotation : annotations) {
                if (annotation instanceof PDAnnotationLink link && pointsOutside(document, destinationOf(link), kept)) {
                    removed = true;
                } else {
                    remaining.add(annotation);
                }
            }
            if (remaining.size() != annotations.size()) {
                page.setAnnotations(remaining);
            }
        }
        return removed;
    }

    /**
     * 宛先を失ったしおりを落とす。落とした項目の子は親に繰り上げる。
     *
     * <p>元の木を直接いじらず、生きている項目だけを写した木を作って差し替える。
     * PDFBox には項目を取り除く手段がなく、{@code /First} {@code /Last} {@code /Prev}
     * {@code /Next} {@code /Count} を自前でつなぎ直すより、写すほうが間違いが起きにくい。
     */
    private static boolean pruneOutline(PDDocument document, Set<COSDictionary> kept) throws IOException {
        PDDocumentCatalog catalog = document.getDocumentCatalog();
        PDDocumentOutline outline = catalog.getDocumentOutline();
        if (outline == null) {
            return false;
        }

        PDDocumentOutline pruned = new PDDocumentOutline();
        if (!copySurviving(document, outline, pruned, kept)) {
            return false;
        }
        catalog.setDocumentOutline(pruned.getFirstChild() == null ? null : pruned);
        return true;
    }

    /**
     * {@code source} の子のうち、宛先が生きているものだけを {@code target} の下に写す。
     *
     * @return 落とした項目があれば {@code true}
     */
    private static boolean copySurviving(
            PDDocument document, PDOutlineNode source, PDOutlineNode target, Set<COSDictionary> kept)
            throws IOException {
        boolean removed = false;
        for (PDOutlineItem item : source.children()) {
            if (pointsOutside(document, destinationOf(item), kept)) {
                // 宛先の消えた項目は落とすが、その子はまだ生きているかもしれない。
                // 木ごと消すと、残っているページへのしおりまで失われる。
                removed = true;
                copySurviving(document, item, target, kept);
            } else {
                PDOutlineItem copy = detached(item);
                target.addLast(copy);
                removed |= copySurviving(document, item, copy, kept);
            }
        }
        return removed;
    }

    /**
     * 木のつながりを外した写しを返す。
     *
     * <p>表題・宛先・書体・色はそのまま持ち越す。つなぎ直しは {@code addLast} に任せる。
     */
    private static PDOutlineItem detached(PDOutlineItem item) {
        COSDictionary copy = new COSDictionary(item.getCOSObject());
        copy.removeItem(COSName.FIRST);
        copy.removeItem(COSName.LAST);
        copy.removeItem(COSName.NEXT);
        copy.removeItem(COSName.PREV);
        copy.removeItem(COSName.PARENT);
        copy.removeItem(COSName.COUNT);
        return new PDOutlineItem(copy);
    }

    /** 消えたページを指す名前付き宛先を落とす。 */
    private static boolean removeDanglingNames(PDDocument document, Set<COSDictionary> kept) throws IOException {
        PDDocumentNameDictionary names = document.getDocumentCatalog().getNames();
        if (names == null || names.getDests() == null) {
            return false;
        }

        Map<String, PDPageDestination> all = new LinkedHashMap<>();
        collectNames(names.getDests(), all);

        Map<String, PDPageDestination> surviving = new LinkedHashMap<>();
        for (Map.Entry<String, PDPageDestination> entry : all.entrySet()) {
            if (!pointsOutside(document, entry.getValue(), kept)) {
                surviving.put(entry.getKey(), entry.getValue());
            }
        }
        if (surviving.size() == all.size()) {
            return false;
        }

        if (surviving.isEmpty()) {
            names.setDests(null);
            return true;
        }
        // 木を平らにして書き戻す。名前の引きかたは変わらない。
        PDDestinationNameTreeNode replacement = new PDDestinationNameTreeNode();
        replacement.setNames(surviving);
        names.setDests(replacement);
        return true;
    }

    private static void collectNames(PDNameTreeNode<PDPageDestination> node, Map<String, PDPageDestination> into)
            throws IOException {
        Map<String, PDPageDestination> names = node.getNames();
        if (names != null) {
            into.putAll(names);
        }
        List<? extends PDNameTreeNode<PDPageDestination>> kids = node.getKids();
        if (kids != null) {
            for (PDNameTreeNode<PDPageDestination> kid : kids) {
                collectNames(kid, into);
            }
        }
    }

    /** 開いたときに飛ぶ先が消えていたら、その指定を落とす。 */
    private static boolean removeDanglingOpenAction(PDDocument document, Set<COSDictionary> kept) throws IOException {
        PDDocumentCatalog catalog = document.getDocumentCatalog();
        Object openAction = catalog.getOpenAction();
        PDDestination destination = null;
        if (openAction instanceof PDDestination direct) {
            destination = direct;
        } else if (openAction instanceof PDActionGoTo goTo) {
            destination = goTo.getDestination();
        }
        if (!pointsOutside(document, destination, kept)) {
            return false;
        }
        catalog.setOpenAction(null);
        return true;
    }

    private static PDDestination destinationOf(PDAnnotationLink link) throws IOException {
        PDDestination destination = link.getDestination();
        if (destination == null && link.getAction() instanceof PDActionGoTo goTo) {
            destination = goTo.getDestination();
        }
        return destination;
    }

    private static PDDestination destinationOf(PDOutlineItem item) throws IOException {
        PDDestination destination = item.getDestination();
        if (destination == null && item.getAction() instanceof PDActionGoTo goTo) {
            destination = goTo.getDestination();
        }
        return destination;
    }

    /**
     * その宛先が、いま文書に無いページを指しているか。
     *
     * <p>ページに解決できない宛先（外部 URL、ページ番号だけの指定など）は {@code false} を返す。
     * 文書の中のページを掴んでいないため、消したページを引きずり出すことがない。
     * 判断がつかないものを消さないのは意図した振る舞いである。
     */
    private static boolean pointsOutside(PDDocument document, PDDestination destination, Set<COSDictionary> kept)
            throws IOException {
        COSDictionary page = pageOf(document, destination);
        return page != null && !kept.contains(page);
    }

    private static COSDictionary pageOf(PDDocument document, PDDestination destination) throws IOException {
        PDPageDestination resolved;
        if (destination instanceof PDNamedDestination named) {
            resolved = document.getDocumentCatalog().findNamedDestinationPage(named);
        } else if (destination instanceof PDPageDestination direct) {
            resolved = direct;
        } else {
            return null;
        }
        if (resolved == null) {
            return null;
        }
        PDPage page = resolved.getPage();
        return page == null ? null : page.getCOSObject();
    }
}
