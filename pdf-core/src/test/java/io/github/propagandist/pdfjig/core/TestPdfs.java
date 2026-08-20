package io.github.propagandist.pdfjig.core;

import java.io.IOException;
import java.nio.file.Path;
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
 */
final class TestPdfs {

    /** {@link #withText} が使うフォントサイズ（pt）。 */
    static final float FONT_SIZE = 12f;

    /** {@link #withText} が使う左マージン（pt）。 */
    static final float TEXT_LEFT = 72f;

    /** {@link #withText} が使う上マージン（pt）。ベースラインまでの距離。 */
    static final float TEXT_TOP = 72f;

    private TestPdfs() {
    }

    /** 指定ページ数の平文 PDF を作る。 */
    static Path plain(Path target, int pageCount) throws IOException {
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
    static Path withText(Path target, String... pageTexts) throws IOException {
        try (PDDocument document = new PDDocument()) {
            for (String text : pageTexts) {
                PDPage page = new PDPage();
                document.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    content.beginText();
                    content.setFont(
                            new PDType1Font(Standard14Fonts.FontName.HELVETICA), FONT_SIZE);
                    content.newLineAtOffset(
                            TEXT_LEFT, page.getMediaBox().getHeight() - TEXT_TOP);
                    content.showText(text);
                    content.endText();
                }
            }
            document.save(target.toFile());
        }
        return target;
    }

    /** AES-256 で暗号化した 1 ページの PDF を作る。 */
    static Path encrypted(Path target, String userPassword) throws IOException {
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());

            AccessPermission permissions = new AccessPermission();
            StandardProtectionPolicy policy =
                    new StandardProtectionPolicy(userPassword, userPassword, permissions);
            policy.setEncryptionKeyLength(256);
            document.protect(policy);

            document.save(target.toFile());
        }
        return target;
    }
}
