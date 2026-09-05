package io.github.propagandist.pdfjig.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * CLAUDE.md INV-5 の検証。
 *
 * <p>パスワードが例外メッセージ・スタックトレースに載らないことを明示的に確かめる。
 * PDFBox の例外をそのまま再スローすると、この保証は簡単に壊れる。
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

        PdfjigException thrown = assertThrows(PdfjigException.class, () -> PdfDocument.open(pdf, WRONG.toCharArray()));

        assertEquals(ErrorCode.INVALID_PASSWORD, thrown.errorCode());

        String rendered = renderFully(thrown);
        assertFalse(rendered.contains(CORRECT), "正解のパスワードが例外に露出している");
        assertFalse(rendered.contains(WRONG), "入力したパスワードが例外に露出している");
    }

    @Test
    @DisplayName("原因例外は連結されない（PDFBox のメッセージが流出する経路を断つ）")
    void causeMustNotBeChained() throws Exception {
        Path pdf = TestPdfs.encrypted(tempDir.resolve("encrypted.pdf"), CORRECT);

        PdfjigException thrown = assertThrows(PdfjigException.class, () -> PdfDocument.open(pdf, WRONG.toCharArray()));

        assertEquals(null, thrown.getCause(), "原因例外を連結してはならない");
        assertEquals(
                "org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException", thrown.causeType(), "診断のために型名だけは保持する");
    }

    @Test
    @DisplayName("open に渡した char[] は失敗時もゼロ埋めされる")
    void passwordArrayIsZeroedOnFailure() throws Exception {
        Path pdf = TestPdfs.encrypted(tempDir.resolve("encrypted.pdf"), CORRECT);
        char[] password = WRONG.toCharArray();

        assertThrows(PdfjigException.class, () -> PdfDocument.open(pdf, password));

        assertArrayEquals(new char[password.length], password, "失敗時もゼロ埋めすること");
    }

    @Test
    @DisplayName("open に渡した char[] は成功時もゼロ埋めされる")
    void passwordArrayIsZeroedOnSuccess() throws Exception {
        Path pdf = TestPdfs.encrypted(tempDir.resolve("encrypted.pdf"), CORRECT);
        char[] password = CORRECT.toCharArray();

        try (PdfDocument document = PdfDocument.open(pdf, password)) {
            assertEquals(1, document.pageCount());
            assertEquals(true, document.encrypted());
        }

        assertArrayEquals(new char[password.length], password, "成功時もゼロ埋めすること");
    }

    @Test
    @DisplayName("読めないファイルに渡した char[] もゼロ埋めされる")
    void passwordArrayIsZeroedWhenFileCannotBeRead() {
        Path missing = tempDir.resolve("does-not-exist.pdf");
        char[] password = CORRECT.toCharArray();

        PdfjigException thrown = assertThrows(PdfjigException.class, () -> PdfDocument.open(missing, password));

        assertEquals(ErrorCode.FILE_NOT_FOUND, thrown.errorCode());
        assertFalse(renderFully(thrown).contains(CORRECT), "パスワードが例外に露出している");

        assertArrayEquals(new char[password.length], password, "開けなかった経路でもゼロ埋めすること");
    }

    @Test
    @DisplayName("null を渡されても、片づけが本当の失敗を握りつぶさない")
    void nullPasswordMustNotHideTheRealFailure() {
        Path missing = tempDir.resolve("does-not-exist.pdf");

        PdfjigException thrown = assertThrows(PdfjigException.class, () -> PdfDocument.open(missing, null));

        assertEquals(ErrorCode.FILE_NOT_FOUND, thrown.errorCode(), "finally から投げると、この ErrorCode ごと消える");
    }

    /** メッセージ・toString・スタックトレースをすべて連結した文字列。 */
    private static String renderFully(Throwable throwable) {
        StringWriter buffer = new StringWriter();
        try (PrintWriter writer = new PrintWriter(buffer)) {
            throwable.printStackTrace(writer);
        }
        return throwable.getMessage() + '\n' + throwable + '\n' + buffer;
    }
}
