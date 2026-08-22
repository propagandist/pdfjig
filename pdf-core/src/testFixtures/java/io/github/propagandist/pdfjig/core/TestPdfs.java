package io.github.propagandist.pdfjig.core;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

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

    /** 各ページの回転角を先頭から順に返す。 */
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
