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

    /**
     * SASLprep が禁じる文字を含むパスワード。U+200E は LEFT-TO-RIGHT MARK である。
     *
     * <p>右書き文脈やパスワード管理ソフトからの貼り付けで実際に混ざる。
     * JavaFX の入力欄が落とすのは ASCII の制御文字だけであり、これは残る。
     */
    private static final String PROHIBITED = "pa\u200Ess";

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

    @Test
    @DisplayName("パスワードに使えない文字が混ざっても、PDFBox の例外がそのまま出ない")
    void prohibitedCharacterMustNotEscapeUnwrapped() throws Exception {
        Path pdf = TestPdfs.encrypted(tempDir.resolve("encrypted.pdf"), CORRECT);
        char[] password = PROHIBITED.toCharArray();

        PdfjigException thrown = assertThrows(PdfjigException.class, () -> PdfDocument.open(pdf, password));

        assertEquals(ErrorCode.PASSWORD_OR_DOCUMENT_FAILURE, thrown.errorCode());
        assertEquals("java.lang.IllegalArgumentException", thrown.causeType(), "包んだ相手の型だけは残す");

        // PDFBox は禁止文字の Unicode 名と、その位置をメッセージに載せる。
        // 文言そのものに縛られないよう、包めていれば通る形で見る。
        String rendered = renderFully(thrown);
        assertFalse(rendered.contains("LEFT-TO-RIGHT MARK"), "パスワードの文字が例外に露出している");
        assertFalse(rendered.contains("Prohibited"), "PDFBox のメッセージがそのまま出ている");

        assertArrayEquals(new char[password.length], password, "包む経路でもゼロ埋めすること");
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
