package io.github.propagandist.pdfjig.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;

/**
 * PDFBox による {@link Encryption} の実装。
 *
 * <p>状態を持たない。複数スレッドから同時に呼び出してよい。
 *
 * <p><b>★★ 開くのは {@link PdfDocument} を通す。</b>{@code Loader} を直に呼ばない——
 * <b>INV-5 の {@code String} 化も、PDFBox の例外の分類も、あちらが 1 か所で持っている。</b>
 * ここが自前で開くと、<b>同じ判断の写しが 2 つになり、片方が腐る。</b>
 */
public final class PdfBoxEncryption implements Encryption {

    @Override
    public EncryptionInfo inspect(Path input) {
        try (PdfDocument document = PdfDocument.open(input)) {
            return document.encryption();
        } catch (PdfjigException e) {
            if (e.errorCode() == ErrorCode.PASSWORD_REQUIRED) {
                // ★★ 開けなかった。暗号化されていることは分かるが、方式も権限も読めていない
                //   ——PDFBox は文書を返さないので、暗号化辞書そのものが手に入らない
                //   （2026-09-12 実測）。NONE ではなく UNKNOWN を返す（EncryptionAlgorithm）。
                //   ★ 権限に all() が入るのは置き値であって、読めた値ではない（#187）。
                return new EncryptionInfo(true, EncryptionAlgorithm.UNKNOWN, true, AccessPermissions.all());
            }
            throw e;
        }
    }

    @Override
    public EncryptionInfo inspect(Path input, Password password) {
        // ★ パスワードが要ったかどうかは、渡して開いただけでは分からない——オーナー
        //   パスワードだけの文書も同じ道で開ける。先に訊いてから、その答えを載せる。
        boolean required = inspect(input).userPasswordRequired();
        try (PdfDocument document = PdfDocument.open(input, password)) {
            return document.encryptionWith(required);
        }
    }

    @Override
    public Path protect(
            Path input,
            Password userPassword,
            Password ownerPassword,
            AccessPermissions permissions,
            EncryptionAlgorithm algorithm,
            Path output) {
        requireAbsent(output);

        try (PdfDocument source = PdfDocument.open(input)) {
            PDDocument document = source.delegate();
            // ★★ String 化はここでも避けられない。PDFBox の StandardProtectionPolicy は
            //   String しか受け付けない（PdfDocument#open と同じ既知の限界。INV-5）。
            //   ★ pdf-core で String 化が起きるのは、あちらとここの 2 か所だけである。
            StandardProtectionPolicy policy = new StandardProtectionPolicy(
                    new String(ownerPassword.value()), new String(userPassword.value()), permissionOf(permissions));
            configure(policy, algorithm);
            document.protect(policy);

            // ★★ ここが漏えいの関門である（#28 の申し送り）。SASLprep が走るのは protect ではなく
            //   save のほうであり、禁じられた文字に当たると PDFBox は本物のパスワードの文字と
            //   位置をメッセージに載せた IllegalArgumentException を投げる——IOException ではない。
            //   wrapping は型名しか残さないので、ここで包めば漏れない（#144 / INV-5）。
            document.save(output.toFile());
        } catch (IOException | RuntimeException e) {
            // 書けたところまでを残さない。保護が掛かっていない半端な出力は、
            // 保護されているつもりで配られる元になる（優先順位 1・2）。
            deleteQuietly(output);
            throw PdfjigException.wrapping(ErrorCode.PASSWORD_OR_DOCUMENT_FAILURE, e);
        }
        return output;
    }

    @Override
    public Path unprotect(Path input, Password password, Path output) {
        requireAbsent(output);

        try (PdfDocument source = PdfDocument.open(input, password)) {
            PDDocument document = source.delegate();
            document.setAllSecurityToBeRemoved(true);
            document.save(output.toFile());
        } catch (IOException | RuntimeException e) {
            deleteQuietly(output);
            // ★ 自分で分類した失敗は塗り替えない。PdfDocument#open が INVALID_PASSWORD を
            //   付けていれば、wrapping がそれを素通しする。
            throw PdfjigException.wrapping(ErrorCode.IO_FAILURE, e);
        }
        return output;
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
        permission.setCanModify(permissions.modify());
        permission.setCanExtractContent(permissions.extractContent());
        permission.setCanModifyAnnotations(permissions.modifyAnnotations());
        permission.setCanFillInForm(permissions.fillForms());
        permission.setCanAssembleDocument(permissions.assembleDocument());
        permission.setCanExtractForAccessibility(permissions.extractForAccessibility());
        permission.setCanPrintFaithful(permissions.printHighQuality());
        return permission;
    }

    /**
     * 出力が既にあれば拒む。
     *
     * <p>{@code pdf-core} 全体の契約である（{@code docs/SPEC.md} §4.2）。
     * <b>★ 同じ検査が {@code PdfBoxPageOperations} にもある</b>——
     * <b>寄せ先を作るのは差分の外へ広がるので、#187 が持つ。</b>
     */
    private static void requireAbsent(Path output) {
        if (output == null) {
            throw new IllegalArgumentException("output は null にできません。");
        }
        if (Files.exists(output)) {
            throw new PdfjigException(ErrorCode.OUTPUT_ALREADY_EXISTS);
        }
    }

    /** 書きかけを消す。消せなくても、伝えるべきは元の失敗である。 */
    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException | RuntimeException ignored) {
            // 握りつぶす。ここで投げると本当の失敗が消える。
        }
    }
}
