package io.github.propagandist.pdfjig.core;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Map;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentCatalog;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary;
import org.apache.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDPageLabelRange;
import org.apache.pdfbox.pdmodel.common.PDPageLabels;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification;
import org.apache.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDSignatureField;

/**
 * テスト用の PDF をその場で生成する。
 *
 * <p>リポジトリに PDF を置かない（CLAUDE.md INV-6）。フィクスチャは常にここで作る。
 *
 * <p>testFixtures に置いてあるのは、画面を操作するテスト（{@code pdf-desktop} の
 * {@code uiTest}）からも同じフィクスチャを使うためである。生成の作法が 2 か所に分かれると、
 * 「片方だけ直した壊れた PDF」でテストが通ってしまう。
 *
 * <p>PDFBox は {@code testFixturesImplementation} で取ってある。ここが返すのは
 * {@link Path} だけであり、{@code PDDocument} が下流モジュールへ漏れることはない
 * （CLAUDE.md「PDFBox の型を他モジュールに漏らさない」）。
 */
public final class TestPdfs {

    /** {@link #withText} が使うフォントサイズ（pt）。 */
    public static final float FONT_SIZE = 12f;

    /** {@link #withText} が使う左マージン（pt）。 */
    public static final float TEXT_LEFT = 72f;

    /** {@link #withText} が使う上マージン（pt）。ベースラインまでの距離。 */
    public static final float TEXT_TOP = 72f;

    /** {@link #rich} が付けるしおりの表題。ページ番号を後ろに付ける。 */
    public static final String OUTLINE_TITLE_PREFIX = "Bookmark ";

    /** {@link #rich} が付ける題名。 */
    public static final String DOCUMENT_TITLE = "Rich Document";

    /** {@link #rich} が付ける作成者。 */
    public static final String DOCUMENT_AUTHOR = "pdfjig";

    /** {@link #rich} が添付するファイルの名前。 */
    public static final String ATTACHMENT_NAME = "note.txt";

    /** {@link #rich} が添付するファイルの中身。 */
    public static final String ATTACHMENT_BODY = "attached";

    /** {@link #withStructTree} が各ページに与える構造要素の型。 */
    public static final String STRUCT_ELEMENT_TYPE = "P";

    /** {@link #withNestedOutline} の親項目の表題。2 ページ目を指す。 */
    public static final String NESTED_PARENT_TITLE = "Parent";

    /** {@link #withNestedOutline} の子項目の表題。3 ページ目を指す。 */
    public static final String NESTED_CHILD_TITLE = "Child";

    /** {@link #signed} が署名辞書に書く名前。 */
    public static final String SIGNER_NAME = "pdfjig test";

    private TestPdfs() {}

    /** 指定ページ数の平文 PDF を作る。 */
    public static Path plain(Path target, int pageCount) throws IOException {
        try (PDDocument document = new PDDocument()) {
            for (int i = 0; i < pageCount; i++) {
                document.addPage(new PDPage());
            }
            document.save(target.toFile());
        }
        return target;
    }

    /**
     * 1 ページにつき 1 行のテキストを描いた PDF を作る。
     *
     * <p>標準 14 フォントは ASCII しか持たないため、{@code pageTexts} は ASCII に限る。
     * テキストはページ左端から 72pt、上端から 72pt（ベースライン）の位置に置かれる。
     */
    public static Path withText(Path target, String... pageTexts) throws IOException {
        try (PDDocument document = new PDDocument()) {
            for (String text : pageTexts) {
                PDPage page = new PDPage();
                document.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    content.beginText();
                    content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), FONT_SIZE);
                    content.newLineAtOffset(TEXT_LEFT, page.getMediaBox().getHeight() - TEXT_TOP);
                    content.showText(text);
                    content.endText();
                }
            }
            document.save(target.toFile());
        }
        return target;
    }

    /**
     * 指定した回転角を持つページからなる PDF を作る。
     *
     * <p>PDF 仕様に反する角度もそのまま書き込める。壊れた入力に対する挙動を試すため。
     */
    public static Path rotated(Path target, int... rotations) throws IOException {
        try (PDDocument document = new PDDocument()) {
            for (int rotation : rotations) {
                PDPage page = new PDPage();
                page.setRotation(rotation);
                document.addPage(page);
            }
            document.save(target.toFile());
        }
        return target;
    }

    /** AES-256 で暗号化した 1 ページの PDF を作る。 */
    public static Path encrypted(Path target, String userPassword) throws IOException {
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());

            AccessPermission permissions = new AccessPermission();
            StandardProtectionPolicy policy = new StandardProtectionPolicy(userPassword, userPassword, permissions);
            policy.setEncryptionKeyLength(256);
            document.protect(policy);

            document.save(target.toFile());
        }
        return target;
    }

    /**
     * オーナーパスワードだけを設定した PDF を作る。
     *
     * <p>ユーザーパスワードが空なので誰でも開けるが、暗号化はされている。
     * 「開けるのに保護されている」という、伝播の判定が効く状態を作るために使う。
     */
    public static Path ownerProtected(Path target, String ownerPassword, int pageCount) throws IOException {
        try (PDDocument document = new PDDocument()) {
            for (int i = 0; i < pageCount; i++) {
                document.addPage(new PDPage());
            }

            AccessPermission permissions = new AccessPermission();
            permissions.setCanPrint(false);
            StandardProtectionPolicy policy = new StandardProtectionPolicy(ownerPassword, "", permissions);
            policy.setEncryptionKeyLength(256);
            document.protect(policy);

            document.save(target.toFile());
        }
        return target;
    }

    /**
     * ページどうしを内部リンクでつないだ PDF を作る。
     *
     * <p>各ページに、次のページ（最後のページは先頭）へ飛ぶ {@code Link} 注釈を 1 つ置く。
     * 目次や相互参照を持つ実務文書を模したものであり、ページを取り除いたときに
     * 宛先を失う参照がどう扱われるかを試すために使う。
     *
     * <p>本文の描き方は {@link #withText} と同じ。したがって {@code pageTexts} は ASCII に限る。
     */
    public static Path withInternalLinks(Path target, String... pageTexts) throws IOException {
        try (PDDocument document = new PDDocument()) {
            List<PDPage> pages = new ArrayList<>(pageTexts.length);
            for (String text : pageTexts) {
                PDPage page = new PDPage();
                document.addPage(page);
                pages.add(page);
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    content.beginText();
                    content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), FONT_SIZE);
                    content.newLineAtOffset(TEXT_LEFT, page.getMediaBox().getHeight() - TEXT_TOP);
                    content.showText(text);
                    content.endText();
                }
            }

            for (int i = 0; i < pages.size(); i++) {
                PDPageFitDestination destination = new PDPageFitDestination();
                destination.setPage(pages.get((i + 1) % pages.size()));

                PDAnnotationLink link = new PDAnnotationLink();
                link.setDestination(destination);
                link.setRectangle(new PDRectangle(TEXT_LEFT, TEXT_LEFT, 100f, 20f));
                pages.get(i).getAnnotations().add(link);
            }

            document.save(target.toFile());
        }
        return target;
    }

    /**
     * 実務の文書が持っているものを一通り備えた PDF を作る。
     *
     * <p>しおり（1 ページにつき 1 項目）・文書情報・添付ファイル・ページラベル・
     * ページどうしの内部リンクを持つ。書き出しでこれらが失われないことを確かめるために使う。
     *
     * <p>本文の描き方は {@link #withText} と同じ。{@code pageTexts} は ASCII に限る。
     */
    public static Path rich(Path target, String... pageTexts) throws IOException {
        withInternalLinks(target, pageTexts);

        try (PDDocument document = Loader.loadPDF(target.toFile())) {
            PDDocumentCatalog catalog = document.getDocumentCatalog();

            PDDocumentOutline outline = new PDDocumentOutline();
            catalog.setDocumentOutline(outline);
            for (int i = 0; i < pageTexts.length; i++) {
                PDPageFitDestination destination = new PDPageFitDestination();
                destination.setPage(document.getPage(i));

                PDOutlineItem item = new PDOutlineItem();
                item.setTitle(OUTLINE_TITLE_PREFIX + (i + 1));
                item.setDestination(destination);
                outline.addLast(item);
            }

            PDDocumentInformation information = new PDDocumentInformation();
            information.setTitle(DOCUMENT_TITLE);
            information.setAuthor(DOCUMENT_AUTHOR);
            document.setDocumentInformation(information);

            PDComplexFileSpecification specification = new PDComplexFileSpecification();
            specification.setFile(ATTACHMENT_NAME);
            specification.setEmbeddedFile(new PDEmbeddedFile(
                    document, new ByteArrayInputStream(ATTACHMENT_BODY.getBytes(StandardCharsets.UTF_8))));

            PDEmbeddedFilesNameTreeNode files = new PDEmbeddedFilesNameTreeNode();
            files.setNames(Map.of(ATTACHMENT_NAME, specification));
            PDDocumentNameDictionary names = new PDDocumentNameDictionary(catalog);
            names.setEmbeddedFiles(files);
            catalog.setNames(names);

            PDPageLabels labels = new PDPageLabels(document);
            PDPageLabelRange roman = new PDPageLabelRange();
            roman.setStyle(PDPageLabelRange.STYLE_ROMAN_LOWER);
            labels.setLabelItem(0, roman);
            catalog.setPageLabels(labels);

            document.save(target.toFile());
        }
        return target;
    }

    /**
     * 入れ子のしおりを持つ 3 ページの PDF を作る。
     *
     * <p>親は 2 ページ目を指し、その子は 3 ページ目を指す。2 ページ目だけを取り除いたときに、
     * 親が落ちて子が繰り上がることを確かめるために使う。
     */
    public static Path withNestedOutline(Path target) throws IOException {
        withText(target, "P1", "P2", "P3");

        try (PDDocument document = Loader.loadPDF(target.toFile())) {
            PDDocumentOutline outline = new PDDocumentOutline();
            document.getDocumentCatalog().setDocumentOutline(outline);

            PDOutlineItem parent = new PDOutlineItem();
            parent.setTitle(NESTED_PARENT_TITLE);
            parent.setDestination(document.getPage(1));
            outline.addLast(parent);

            PDOutlineItem child = new PDOutlineItem();
            child.setTitle(NESTED_CHILD_TITLE);
            child.setDestination(document.getPage(2));
            parent.addLast(child);

            document.save(target.toFile());
        }
        return target;
    }

    /**
     * 中間ノードから寸法を継承するページツリーを持つ PDF を作る。
     *
     * <p>各ページ自身は {@code /MediaBox} を持たず、親の {@code /Pages} ノードが持つ。
     * 並べ替えでページツリーを均すときに寸法が失われないことを確かめるために使う。
     */
    public static Path withInheritedMediaBox(Path target, PDRectangle box, int pageCount) throws IOException {
        try (PDDocument document = new PDDocument()) {
            for (int i = 0; i < pageCount; i++) {
                document.addPage(new PDPage(box));
            }

            COSDictionary root = document.getDocumentCatalog().getPages().getCOSObject();
            COSArray kids = (COSArray) root.getDictionaryObject(COSName.KIDS);

            COSDictionary middle = new COSDictionary();
            middle.setItem(COSName.TYPE, COSName.PAGES);
            middle.setItem(COSName.KIDS, kids);
            middle.setInt(COSName.COUNT, pageCount);
            middle.setItem(COSName.PARENT, root);
            middle.setItem(COSName.MEDIA_BOX, box.getCOSArray());

            for (int i = 0; i < kids.size(); i++) {
                COSDictionary page = (COSDictionary) kids.getObject(i);
                page.removeItem(COSName.MEDIA_BOX);
                page.setItem(COSName.PARENT, middle);
            }

            COSArray replacement = new COSArray();
            replacement.add(middle);
            root.setItem(COSName.KIDS, replacement);
            root.setInt(COSName.COUNT, pageCount);

            document.save(target.toFile());
        }
        return target;
    }

    /**
     * 電子署名の辞書を持つ PDF を作る。
     *
     * <p>署名そのものは中身が空であり、検証には通らない。pdfjig が見るのは
     * <b>署名が在るか</b> だけなので、辞書があれば足りる。本物の署名を作るには
     * 証明書と BouncyCastle が要るが、それはここで確かめたいことではない。
     */
    public static Path signed(Path target, int pageCount) throws IOException {
        Path unsigned = target.resolveSibling(target.getFileName() + ".unsigned");
        plain(unsigned, pageCount);

        try (PDDocument document = Loader.loadPDF(unsigned.toFile());
                OutputStream out = Files.newOutputStream(target)) {
            PDSignature signature = new PDSignature();
            signature.setFilter(PDSignature.FILTER_ADOBE_PPKLITE);
            signature.setSubFilter(PDSignature.SUBFILTER_ADBE_PKCS7_DETACHED);
            signature.setName(SIGNER_NAME);
            signature.setSignDate(Calendar.getInstance());

            document.addSignature(signature, content -> new byte[0]);
            document.saveIncremental(out);
        }
        Files.delete(unsigned);
        return target;
    }

    /**
     * 署名欄はあるが、まだ署名されていない PDF を作る。
     *
     * <p>署名欄があるだけで「署名済み」と扱ってしまわないことを確かめるために使う。
     */
    public static Path withEmptySignatureField(Path target, int pageCount) throws IOException {
        try (PDDocument document = new PDDocument()) {
            for (int i = 0; i < pageCount; i++) {
                document.addPage(new PDPage());
            }

            PDAcroForm form = new PDAcroForm(document);
            PDSignatureField field = new PDSignatureField(form);
            form.getFields().add(field);
            document.getPage(0).getAnnotations().add(field.getWidgets().get(0));
            document.getDocumentCatalog().setAcroForm(form);

            document.save(target.toFile());
        }
        return target;
    }

    /** 各ページの回転角を先頭から順に返す。 */
    /**
     * タグ付き PDF を作る。{@code /StructTreeRoot} の構造要素が {@code /Pg} でページを直接指す。
     *
     * <p><b>この形は珍しくない。</b> Word / PowerPoint / InDesign / Google Docs は
     * 既定でタグ付き PDF を出力する。アクセシビリティ準拠が要る文書はどれもこの形になる。
     *
     * <p>ページツリーから外しても {@code /Pg} は指したままになるため、
     * <b>参照を辿って書き出す限り、外したページの中身が出力に残る経路</b>になる。
     */
    public static Path withStructTree(Path target, String... pageTexts) throws IOException {
        try (PDDocument document = new PDDocument()) {
            COSArray kids = new COSArray();
            for (int i = 0; i < pageTexts.length; i++) {
                PDPage page = new PDPage();
                document.addPage(page);
                page.getCOSObject().setInt(COSName.STRUCT_PARENTS, i);
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    content.beginText();
                    content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), FONT_SIZE);
                    content.newLineAtOffset(TEXT_LEFT, page.getMediaBox().getHeight() - TEXT_TOP);
                    content.showText(pageTexts[i]);
                    content.endText();
                }
                COSDictionary element = new COSDictionary();
                element.setItem(COSName.TYPE, COSName.getPDFName("StructElem"));
                element.setItem(COSName.S, COSName.getPDFName(STRUCT_ELEMENT_TYPE));
                element.setItem(COSName.PG, page.getCOSObject());
                element.setInt(COSName.K, i);
                kids.add(element);
            }
            COSDictionary root = new COSDictionary();
            root.setItem(COSName.TYPE, COSName.getPDFName("StructTreeRoot"));
            root.setItem(COSName.K, kids);
            document.getDocumentCatalog().getCOSObject().setItem(COSName.STRUCT_TREE_ROOT, root);
            document.save(target.toFile());
        }
        return target;
    }

    public static List<Integer> rotationsOf(Path pdf) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf.toFile())) {
            List<Integer> rotations = new ArrayList<>();
            for (PDPage page : document.getPages()) {
                rotations.add(page.getRotation());
            }
            return rotations;
        }
    }
}
