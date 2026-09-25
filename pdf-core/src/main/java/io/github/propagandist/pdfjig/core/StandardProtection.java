package io.github.propagandist.pdfjig.core;

import java.io.IOException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;

/**
 * 保護の方針を組んで文書へ当てる、ただ 1 つの場所。
 *
 * <p><b>★★ 掛ける側が 2 つある</b>——{@code Encryption#protect}（既にある文書へ後から）と
 * {@code PageOperations#assemble}（組み立てながら。#199）。<b>方針の組み立てを写すと、
 * 片方だけ直した設定で書けるようになる</b>（{@code CLAUDE.md}「配る差分の門」1）。
 *
 * <p><b>★★ INV-5 の境界がここにある。</b>PDFBox の {@link StandardProtectionPolicy} は
 * {@code String} しか受け付けないので、<b>ここで一度だけ {@code String} が生成される</b>
 * ——<b>これは GC されるまでヒープに残り、明示的なゼロ埋めができない。</b>
 * {@code pdf-core} で {@code String} 化が起きるのは<b>ここと {@link PdfDocument#open} の
 * 2 か所だけである。</b>
 *
 * <p><b>★★ 掛けるだけで、書き出さない。</b>SASLprep が走るのは {@code protect} ではなく
 * <b>{@code save} のほうであり</b>、禁じられた文字に当たると PDFBox は
 * <b>本物のパスワードの文字と位置をメッセージに載せた {@code IllegalArgumentException} を
 * 投げる</b>（#28 の申し送り）。<b>包む関門は書き出す側に要る</b>——
 * {@code PdfBoxGuard} がそれを持つ。
 */
final class StandardProtection {

    private StandardProtection() {}

    /**
     * 文書に保護を当てる。
     *
     * @param document   対象の文書
     * @param protection 掛ける保護
     * @throws IOException PDFBox が投げる。<b>包むのは呼ぶ側である</b>（{@code PdfBoxGuard}）
     */
    static void apply(PDDocument document, Protection protection) throws IOException {
        StandardProtectionPolicy policy = new StandardProtectionPolicy(
                new String(protection.ownerPassword().value()),
                new String(protection.userPassword().value()),
                permissionOf(protection.permissions()));
        configure(policy, protection.algorithm());
        document.protect(policy);
    }

    /**
     * 方式を方針に当てる。
     *
     * <p><b>★ 網羅的な {@code switch} にする。</b>書ける方式が増えた日に
     * <b>コンパイラが知らせる</b>——列挙の側に鍵の長さを持たせると、
     * <b>{@link EncryptionAlgorithm#NONE} と {@link EncryptionAlgorithm#UNKNOWN} が
     * 同じ値を返す</b>形になり、<b>混ぜるなと書いた隣で 2 つが同値になる。</b>
     */
    private static void configure(StandardProtectionPolicy policy, EncryptionAlgorithm algorithm) {
        switch (algorithm) {
            case RC4_40 -> {
                policy.setEncryptionKeyLength(40);
                policy.setPreferAES(false);
            }
            case RC4_128 -> {
                policy.setEncryptionKeyLength(128);
                policy.setPreferAES(false);
            }
            case AES_128 -> {
                policy.setEncryptionKeyLength(128);
                policy.setPreferAES(true);
            }
            case AES_256 -> {
                policy.setEncryptionKeyLength(256);
                policy.setPreferAES(true);
            }
            case NONE, UNKNOWN -> throw new PdfjigException(ErrorCode.UNSUPPORTED_ENCRYPTION);
        }
    }

    private static AccessPermission permissionOf(AccessPermissions permissions) {
        AccessPermission permission = new AccessPermission();
        permission.setCanPrint(permissions.print());
        // ★★ 高品質の印刷は、印刷を許しているときしか意味を持たない
        //   （PDF 32000-1 の表 22。ビット 12 はビット 3 を修飾する）。両方をそのまま書くと
        //   「高品質なら印刷できる」と読める値が残る——読む側はビット 3 を見て
        //   印刷を拒むので、思ったのと違う結果になる（優先順位 2。#30 の門の 2 段目）。
        //   ★ 揃えるのはここだけである——AccessPermissions は読んだ結果を返す側でも使うので、
        //   あちらで揃えると文書が書いていることを告げられなくなる。
        permission.setCanModify(permissions.modify());
        permission.setCanExtractContent(permissions.extractContent());
        permission.setCanModifyAnnotations(permissions.modifyAnnotations());
        permission.setCanFillInForm(permissions.fillForms());
        permission.setCanAssembleDocument(permissions.assembleDocument());
        permission.setCanExtractForAccessibility(permissions.extractForAccessibility());
        permission.setCanPrintFaithful(permissions.printHighQuality() && permissions.print());
        return permission;
    }
}
