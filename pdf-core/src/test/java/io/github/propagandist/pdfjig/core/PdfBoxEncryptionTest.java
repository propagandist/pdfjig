package io.github.propagandist.pdfjig.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 暗号化の設定・解除・判定（#28）。
 *
 * <p><b>ここが品質の担保点である</b>——UI に依存しないので、正常系・境界・壊れた入力の
 * 3 つを見る（{@code .claude/rules/testing.md}）。
 */
class PdfBoxEncryptionTest {

    private static final String USER = "user-Passw0rd";
    private static final String OWNER = "owner-Passw0rd";

    private final Encryption encryption = new PdfBoxEncryption();

    @TempDir
    Path tempDir;

    @Nested
    @DisplayName("inspect")
    class Inspect {

        @Test
        @DisplayName("暗号化されていない文書は、すべて許可として返る")
        void plainDocumentIsNotEncrypted() throws Exception {
            Path pdf = TestPdfs.plain(tempDir.resolve("plain.pdf"), 1);

            EncryptionInfo info = encryption.inspect(pdf);

            assertEquals(EncryptionInfo.none(), info);
        }

        @Test
        @DisplayName("ユーザーパスワードが要る文書は、そのことが分かる")
        void userPasswordRequiredIsVisible() throws Exception {
            Path pdf = TestPdfs.encrypted(tempDir.resolve("encrypted.pdf"), USER);

            EncryptionInfo info = encryption.inspect(pdf);

            assertTrue(info.encrypted());
            assertTrue(info.userPasswordRequired(), "開くのにパスワードが要ることが分からない");
        }

        @Test
        @DisplayName("オーナーパスワードだけの文書は、暗号化されているがパスワードは要らない")
        void ownerOnlyIsEncryptedButOpens() throws Exception {
            Path pdf = TestPdfs.ownerProtected(tempDir.resolve("owner.pdf"), OWNER, 1);

            EncryptionInfo info = encryption.inspect(pdf);

            assertTrue(info.encrypted());
            // ★★ ここが docs/SPEC.md §4.3.1 の分かれ目である。「暗号化されているか」で
            //   問うと、この文書でも書き出しを止める窓が出る——本物に当たる前に
            //   「読まずに続行を押す」習慣ができる。
            assertFalse(info.userPasswordRequired(), "パスワードなしで開ける文書を、要ると答えている");
        }

        @Test
        @DisplayName("読めないファイルは FILE_NOT_FOUND")
        void missingFile() {
            Path missing = tempDir.resolve("does-not-exist.pdf");

            assertEquals(
                    ErrorCode.FILE_NOT_FOUND,
                    assertThrows(PdfjigException.class, () -> encryption.inspect(missing))
                            .errorCode());
        }

        @Test
        @DisplayName("PDF でないファイルは NOT_A_PDF")
        void notAPdf() throws Exception {
            Path text = Files.writeString(tempDir.resolve("not.pdf"), "これは PDF ではない");

            assertEquals(
                    ErrorCode.NOT_A_PDF,
                    assertThrows(PdfjigException.class, () -> encryption.inspect(text))
                            .errorCode());
        }
    }

    @Nested
    @DisplayName("protect")
    class Protect {

        @Test
        @DisplayName("8 つの権限フラグが往復する")
        void permissionsRoundTrip() throws Exception {
            Path input = TestPdfs.plain(tempDir.resolve("plain.pdf"), 1);
            Path output = tempDir.resolve("protected.pdf");
            // 8 つを別々の値にする。まとめて true / false にすると、取り違えても気づけない。
            AccessPermissions permissions = new AccessPermissions(true, false, true, false, true, false, true, false);

            try (Password user = Password.copyOf(USER);
                    Password owner = Password.copyOf(OWNER)) {
                encryption.protect(input, user, owner, permissions, EncryptionAlgorithm.AES_256, output);
            }

            // ★★ パスワードを渡さないと読めない。PDFBox はユーザーパスワードが要る文書で
            //   PDDocument を返さないので、暗号化辞書そのものが手に入らない（2026-09-12 実測）。
            try (Password user = Password.copyOf(USER)) {
                assertEquals(permissions, encryption.inspect(output, user).permissions());
            }
        }

        @Test
        @DisplayName("AES-256 で暗号化され、方式も往復する")
        void algorithmRoundTrips() throws Exception {
            Path input = TestPdfs.plain(tempDir.resolve("plain.pdf"), 1);
            Path output = tempDir.resolve("protected.pdf");

            try (Password user = Password.copyOf(USER);
                    Password owner = Password.copyOf(OWNER)) {
                encryption.protect(input, user, owner, AccessPermissions.none(), EncryptionAlgorithm.AES_256, output);
            }

            EncryptionInfo unopened = encryption.inspect(output);
            assertTrue(unopened.encrypted());
            assertEquals(EncryptionAlgorithm.UNKNOWN, unopened.algorithm(), "読めていないのに方式を答えている");
            assertTrue(unopened.userPasswordRequired());

            try (Password user = Password.copyOf(USER)) {
                EncryptionInfo opened = encryption.inspect(output, user);
                assertEquals(EncryptionAlgorithm.AES_256, opened.algorithm());
                assertTrue(opened.userPasswordRequired(), "開くのにパスワードが要ることは、開いた後も変わらない");
            }
        }

        @Test
        @DisplayName("支援技術のための複製は、すべて禁じても許可されたままである")
        void accessibilitySurvivesNone() {
            // ★★ 塞ぐと視覚障害者が読めなくなる（docs/SPEC.md §6.2）。
            assertTrue(AccessPermissions.none().extractForAccessibility());
        }

        @Test
        @DisplayName("渡した Password は消さない（消すのは作った場所である）")
        void doesNotEraseTheGivenPasswords() throws Exception {
            Path input = TestPdfs.plain(tempDir.resolve("plain.pdf"), 1);
            Path output = tempDir.resolve("protected.pdf");
            char[] rawUser = USER.toCharArray();
            char[] rawOwner = OWNER.toCharArray();

            Password user = Password.of(rawUser);
            Password owner = Password.of(rawOwner);
            encryption.protect(input, user, owner, AccessPermissions.all(), EncryptionAlgorithm.AES_256, output);

            // ★★ 秘密が 2 本あっても、呼ぶ側が並べて宣言すれば 1 本目で投げたときに
            //   2 本目が残らない（#146）。受け取った側が消す形だと、それが手書きの finally になる。
            assertArrayEquals(USER.toCharArray(), rawUser, "protect は読むだけである");
            assertArrayEquals(OWNER.toCharArray(), rawOwner, "protect は読むだけである");

            user.close();
            owner.close();
            assertArrayEquals(new char[rawUser.length], rawUser);
            assertArrayEquals(new char[rawOwner.length], rawOwner);
        }

        @Test
        @DisplayName("出力が既にあれば、何も書かずに失敗する")
        void refusesExistingOutput() throws Exception {
            Path input = TestPdfs.plain(tempDir.resolve("plain.pdf"), 1);
            Path output = Files.writeString(tempDir.resolve("taken.pdf"), "先客");

            try (Password user = Password.copyOf(USER);
                    Password owner = Password.copyOf(OWNER)) {
                assertEquals(
                        ErrorCode.OUTPUT_ALREADY_EXISTS,
                        assertThrows(
                                        PdfjigException.class,
                                        () -> encryption.protect(
                                                input,
                                                user,
                                                owner,
                                                AccessPermissions.all(),
                                                EncryptionAlgorithm.AES_256,
                                                output))
                                .errorCode());
            }
            assertEquals("先客", Files.readString(output), "既存の出力を書き換えている");
        }

        @Test
        @DisplayName("NONE では暗号化できない")
        void refusesNoneAlgorithm() throws Exception {
            Path input = TestPdfs.plain(tempDir.resolve("plain.pdf"), 1);
            Path output = tempDir.resolve("protected.pdf");

            try (Password user = Password.copyOf(USER);
                    Password owner = Password.copyOf(OWNER)) {
                assertEquals(
                        ErrorCode.UNSUPPORTED_ENCRYPTION,
                        assertThrows(
                                        PdfjigException.class,
                                        () -> encryption.protect(
                                                input,
                                                user,
                                                owner,
                                                AccessPermissions.all(),
                                                EncryptionAlgorithm.NONE,
                                                output))
                                .errorCode());
            }
            assertFalse(Files.exists(output), "失敗したのに出力が残っている");
        }

        @Test
        @DisplayName("★★ 書き出しで落ちても、パスワードは例外に出ない")
        void prohibitedCharacterMustNotEscapeUnwrapped() throws Exception {
            Path input = TestPdfs.plain(tempDir.resolve("plain.pdf"), 1);
            Path output = tempDir.resolve("protected.pdf");

            PdfjigException thrown;
            try (Password user = Password.copyOf(Secrets.PROHIBITED);
                    Password owner = Password.copyOf(OWNER)) {
                thrown = assertThrows(
                        PdfjigException.class,
                        () -> encryption.protect(
                                input, user, owner, AccessPermissions.all(), EncryptionAlgorithm.AES_256, output));
            }

            // ★★ 落ちるのは protect ではなく save である（#28 の申し送り）。
            //   包む関門を「方針を当てるところ」に置いていたら、ここは素の例外で抜ける。
            String rendered = Secrets.renderFully(thrown);
            assertFalse(rendered.contains("org.apache.pdfbox"), "PDFBox のフレームが残っている");
            assertFalse(rendered.contains("LEFT-TO-RIGHT MARK"), "パスワードの文字が露出している");
            assertFalse(rendered.contains(OWNER), "オーナーパスワードが露出している");
            assertFalse(Files.exists(output), "書きかけの出力が残っている");
        }
    }

    @Nested
    @DisplayName("unprotect")
    class Unprotect {

        @Test
        @DisplayName("保護を外すと、パスワードなしで開ける")
        void removesProtection() throws Exception {
            Path input = TestPdfs.encrypted(tempDir.resolve("encrypted.pdf"), USER);
            Path output = tempDir.resolve("plain.pdf");

            try (Password password = Password.copyOf(USER)) {
                encryption.unprotect(input, password, output);
            }

            assertEquals(EncryptionInfo.none(), encryption.inspect(output));
        }

        @Test
        @DisplayName("パスワードが違えば INVALID_PASSWORD で、出力も残らない")
        void wrongPassword() throws Exception {
            Path input = TestPdfs.encrypted(tempDir.resolve("encrypted.pdf"), USER);
            Path output = tempDir.resolve("plain.pdf");

            try (Password password = Password.copyOf("まったく違う")) {
                assertEquals(
                        ErrorCode.INVALID_PASSWORD,
                        assertThrows(PdfjigException.class, () -> encryption.unprotect(input, password, output))
                                .errorCode());
            }
            assertFalse(Files.exists(output), "失敗したのに出力が残っている");
        }

        @Test
        @DisplayName("パスワードが違っても、例外にパスワードは出ない")
        void wrongPasswordDoesNotLeak() throws Exception {
            Path input = TestPdfs.encrypted(tempDir.resolve("encrypted.pdf"), USER);
            Path output = tempDir.resolve("plain.pdf");

            PdfjigException thrown;
            try (Password password = Password.copyOf("まったく違う")) {
                thrown = assertThrows(PdfjigException.class, () -> encryption.unprotect(input, password, output));
            }

            String rendered = Secrets.renderFully(thrown);
            assertFalse(rendered.contains(USER), "正解のパスワードが露出している");
            assertFalse(rendered.contains("まったく違う"), "入力したパスワードが露出している");
            assertFalse(rendered.contains("org.apache.pdfbox"), "PDFBox のフレームが残っている");
        }
    }
}
