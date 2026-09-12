package io.github.propagandist.pdfjig.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.pdmodel.encryption.PDEncryption;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;

/**
 * PDFBox による {@link Encryption} の実装。
 *
 * <p>状態を持たない。複数スレッドから同時に呼び出してよい。
 */
public final class PdfBoxEncryption implements Encryption {

    @Override
    public EncryptionInfo inspect(Path input) {
        requireReadable(input);
        try (PDDocument document = Loader.loadPDF(input.toFile())) {
            return infoOf(document, false);
        } catch (InvalidPasswordException e) {
            // ★★ 開けなかった。暗号化されていることは分かるが、方式も権限も読めていない
            //   ——PDFBox は文書を返さないので、暗号化辞書そのものが手に入らない
            //   （2026-09-12 実測）。NONE ではなく UNKNOWN を返す。混ぜると、保護されて
            //   いない文書と区別が付かなくなる（優先順位 2）。
            return new EncryptionInfo(true, EncryptionAlgorithm.UNKNOWN, true, AccessPermissions.all());
        } catch (IOException | RuntimeException e) {
            throw PdfjigException.wrapping(ErrorCode.NOT_A_PDF, e);
        }
    }

    @Override
    public EncryptionInfo inspect(Path input, Password password) {
        requireReadable(input);
        try (PDDocument document = Loader.loadPDF(input.toFile(), new String(password.value()))) {
            // ★ 開けたということは、渡したパスワードで足りたということである。
            //   それがユーザーパスワードだったかオーナーパスワードだったかは、ここからは分からない
            //   ——分からないことを分からないまま扱うため、パスワードなしで開けるかを別に見る。
            return infoOf(document, needsUserPassword(input));
        } catch (InvalidPasswordException e) {
            throw PdfjigException.wrapping(ErrorCode.INVALID_PASSWORD, e);
        } catch (IOException | RuntimeException e) {
            throw PdfjigException.wrapping(ErrorCode.NOT_A_PDF, e);
        }
    }

    /** パスワードなしでは開けないか。 */
    private static boolean needsUserPassword(Path input) {
        try {
            // ★ 開けたかどうかだけが要るので、閉じて捨てる。try-with-resources に載せると
            //   「本体で参照されない」警告になり、-Werror で落ちる。
            Loader.loadPDF(input.toFile()).close();
            return false;
        } catch (InvalidPasswordException e) {
            return true;
        } catch (IOException | RuntimeException e) {
            throw PdfjigException.wrapping(ErrorCode.NOT_A_PDF, e);
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
        if (algorithm == EncryptionAlgorithm.NONE) {
            throw new PdfjigException(ErrorCode.UNSUPPORTED_ENCRYPTION);
        }
        requireAbsent(output);
        requireReadable(input);

        try (PDDocument document = Loader.loadPDF(input.toFile())) {
            // ★★ String 化はここでも避けられない。PDFBox の StandardProtectionPolicy は
            //   String しか受け付けない（PdfDocument#open と同じ既知の限界。INV-5）。
            StandardProtectionPolicy policy = new StandardProtectionPolicy(
                    new String(ownerPassword.value()), new String(userPassword.value()), permissionOf(permissions));
            policy.setEncryptionKeyLength(algorithm.keyLengthBits());
            policy.setPreferAES(algorithm.aes());
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
        requireReadable(input);

        try (PDDocument document = Loader.loadPDF(input.toFile(), new String(password.value()))) {
            document.setAllSecurityToBeRemoved(true);
            document.save(output.toFile());
        } catch (InvalidPasswordException e) {
            throw PdfjigException.wrapping(ErrorCode.INVALID_PASSWORD, e);
        } catch (IOException | RuntimeException e) {
            deleteQuietly(output);
            throw PdfjigException.wrapping(ErrorCode.PASSWORD_OR_DOCUMENT_FAILURE, e);
        }
        return output;
    }

    /**
     * 開いた文書から状態を組み立てる。
     *
     * @param document            開いた文書
     * @param userPasswordWasUsed ユーザーパスワードを使って開いたか
     */
    private static EncryptionInfo infoOf(PDDocument document, boolean userPasswordWasUsed) {
        if (!document.isEncrypted()) {
            return EncryptionInfo.none();
        }
        PDEncryption encryption = document.getEncryption();
        return new EncryptionInfo(
                true,
                algorithmOf(encryption),
                userPasswordWasUsed,
                // ★★ 素の /P を読む。getCurrentAccessPermission は認証の結果であり、
                //   オーナーパスワードで開くとすべてを許可した値になる——同じ文書でも
                //   誰が開いたかで答えが変わる（docs/SPEC.md §4.3.1 / §6.1.1）。
                permissionsOf(new AccessPermission(encryption.getPermissions())));
    }

    /**
     * 方式を読む。
     *
     * <p><b>鍵の長さだけでは足りない</b>——128 ビットは RC4 と AES の両方にある。
     * <b>版（{@code /R}）で分ける</b>: 2 までが RC4、4 は AES-128（{@code /V} が 4 のとき）、
     * 5 以上が AES-256 である。
     */
    private static EncryptionAlgorithm algorithmOf(PDEncryption encryption) {
        int revision = encryption.getRevision();
        if (revision >= 5) {
            return EncryptionAlgorithm.AES_256;
        }
        if (revision == 4) {
            return EncryptionAlgorithm.AES_128;
        }
        return encryption.getLength() > 40 ? EncryptionAlgorithm.RC4_128 : EncryptionAlgorithm.RC4_40;
    }

    private static AccessPermissions permissionsOf(AccessPermission permission) {
        return new AccessPermissions(
                permission.canPrint(),
                permission.canModify(),
                permission.canExtractContent(),
                permission.canModifyAnnotations(),
                permission.canFillInForm(),
                permission.canAssembleDocument(),
                permission.canExtractForAccessibility(),
                permission.canPrintFaithful());
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

    private static void requireReadable(Path path) {
        if (!Files.isReadable(path)) {
            throw new PdfjigException(ErrorCode.FILE_NOT_FOUND);
        }
    }

    private static void requireAbsent(Path output) {
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
