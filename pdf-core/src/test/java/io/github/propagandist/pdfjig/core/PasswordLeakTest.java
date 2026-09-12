package io.github.propagandist.pdfjig.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * CLAUDE.md INV-5 の検証。
 *
 * <p>パスワードが例外メッセージ・スタックトレースに載らないことを明示的に確かめる。
 * PDFBox の例外をそのまま再スローすると、この保証は簡単に壊れる。
 *
 * <p><b>あわせて、開く経路のどれを通っても枠を出れば消えていることを見る。</b>
 * {@code open} は渡された秘密を読むだけであり、<b>消すのは作った場所である</b>（#146）。
 */
class PasswordLeakTest {

    private static final String CORRECT = "Sup3r-Secret-Passw0rd";
    private static final String WRONG = "totally-wrong-guess";

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("パスワード誤りの例外に、入力したパスワードも正解のパスワードも現れない")
    void exceptionMustNotRevealAnyPassword() throws Exception {
        Path pdf = TestPdfs.encrypted(tempDir.resolve("encrypted.pdf"), CORRECT);

        PdfjigException thrown;
        try (Password password = Password.copyOf(WRONG)) {
            thrown = assertThrows(PdfjigException.class, () -> PdfDocument.open(pdf, password));
        }

        assertEquals(ErrorCode.INVALID_PASSWORD, thrown.errorCode());

        String rendered = Secrets.renderFully(thrown);
        assertFalse(rendered.contains(CORRECT), "正解のパスワードが例外に露出している");
        assertFalse(rendered.contains(WRONG), "入力したパスワードが例外に露出している");
    }

    @Test
    @DisplayName("原因例外は連結されない（PDFBox のメッセージが流出する経路を断つ）")
    void causeMustNotBeChained() throws Exception {
        Path pdf = TestPdfs.encrypted(tempDir.resolve("encrypted.pdf"), CORRECT);

        PdfjigException thrown;
        try (Password password = Password.copyOf(WRONG)) {
            thrown = assertThrows(PdfjigException.class, () -> PdfDocument.open(pdf, password));
        }

        assertEquals(null, thrown.getCause(), "原因例外を連結してはならない");
        assertEquals(
                "org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException", thrown.causeType(), "診断のために型名だけは保持する");
    }

    @Test
    @DisplayName("open は渡された秘密を消さない（消すのは作った場所である）")
    void openDoesNotEraseWhatItWasGiven() throws Exception {
        Path pdf = TestPdfs.encrypted(tempDir.resolve("encrypted.pdf"), CORRECT);
        char[] raw = CORRECT.toCharArray();

        try (Password password = Password.of(raw);
                PdfDocument document = PdfDocument.open(pdf, password)) {
            assertEquals(1, document.pageCount());
            assertEquals(true, document.encrypted());
            // ★★ 開いた後もまだ読める。ここで消す形にすると、片づけが 2 か所になり、
            //   どちらが持ち主かを註でしか書けなくなる（#146）。
            assertArrayEquals(CORRECT.toCharArray(), raw, "open は読むだけである");
        }

        assertArrayEquals(new char[raw.length], raw, "枠を出たら消えていること");
    }

    @Test
    @DisplayName("パスワードが誤っていた経路でも、枠を出れば消えている")
    void passwordIsZeroedAfterFailure() throws Exception {
        Path pdf = TestPdfs.encrypted(tempDir.resolve("encrypted.pdf"), CORRECT);
        char[] raw = WRONG.toCharArray();

        try (Password password = Password.of(raw)) {
            assertThrows(PdfjigException.class, () -> PdfDocument.open(pdf, password));
        }

        assertArrayEquals(new char[raw.length], raw, "失敗した経路でもゼロ埋めすること");
    }

    @Test
    @DisplayName("読めないファイルに渡した経路でも、枠を出れば消えている")
    void passwordIsZeroedWhenFileCannotBeRead() {
        Path missing = tempDir.resolve("does-not-exist.pdf");
        char[] raw = CORRECT.toCharArray();

        PdfjigException thrown;
        try (Password password = Password.of(raw)) {
            thrown = assertThrows(PdfjigException.class, () -> PdfDocument.open(missing, password));
        }

        assertEquals(ErrorCode.FILE_NOT_FOUND, thrown.errorCode());
        assertFalse(Secrets.renderFully(thrown).contains(CORRECT), "パスワードが例外に露出している");

        assertArrayEquals(new char[raw.length], raw, "開けなかった経路でもゼロ埋めすること");
    }

    @Test
    @DisplayName("読めないことは、秘密に触れる前に分かる")
    void unreadableFileIsRejectedBeforeTheSecretIsTouched() {
        Path missing = tempDir.resolve("does-not-exist.pdf");

        // ★★ null を渡せるのは、この順序を縛るためである。requireReadable が先に投げるので
        //   ここへ届かない——後ろへ回すと、開けないファイルでも先に秘密を String 化することになる。
        PdfjigException thrown = assertThrows(PdfjigException.class, () -> PdfDocument.open(missing, null));

        assertEquals(ErrorCode.FILE_NOT_FOUND, thrown.errorCode(), "秘密を読む前に落ちること");
    }

    @Test
    @DisplayName("パスワードに使えない文字が混ざっても、PDFBox の例外がそのまま出ない")
    void prohibitedCharacterMustNotEscapeUnwrapped() throws Exception {
        Path pdf = TestPdfs.encrypted(tempDir.resolve("encrypted.pdf"), CORRECT);
        char[] raw = Secrets.PROHIBITED.toCharArray();

        PdfjigException thrown;
        try (Password password = Password.of(raw)) {
            thrown = assertThrows(PdfjigException.class, () -> PdfDocument.open(pdf, password));
        }

        assertEquals(ErrorCode.PASSWORD_OR_DOCUMENT_FAILURE, thrown.errorCode());
        assertEquals("java.lang.IllegalArgumentException", thrown.causeType(), "包んだ相手の型だけは残す");

        assertEquals(
                ErrorCode.PASSWORD_OR_DOCUMENT_FAILURE.defaultMessage(), thrown.getMessage(), "文言は ErrorCode の定数だけである");

        // ★★ これが本体である。PDFBox のフレームが 1 つでも残っていれば、その版の文言が
        //   printStackTrace から出る。特定の語が出ていないことで見ると 2 通りに漏れる——
        //   向こうが言い換えたときと、getCause 以外（addSuppressed）で繋がれたときである。
        //   SASLprep のメッセージは 4 通りあり、「Prohibited」を含まない形が 3 つある。
        String rendered = Secrets.renderFully(thrown);
        assertFalse(rendered.contains("org.apache.pdfbox"), "PDFBox のフレームが残っている");
        assertFalse(rendered.contains("LEFT-TO-RIGHT MARK"), "パスワードの文字が露出している");

        assertArrayEquals(new char[raw.length], raw, "包む経路でもゼロ埋めすること");
    }
}
