package io.github.propagandist.pdfjig.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 包んだ例外が、診断に要るものだけを持つこと（#152）。
 *
 * <p><b>★ 落ちた位置は残し、メッセージは残さない。</b>包む前は、原因の例外が診断ログへ届き、
 * PDFBox のどこで落ちたかが読めた。包むと型名しか残らず、「{@code open} の中で何かが起きた」までしか分からない。
 * フレーム（クラス名・メソッド名・ファイル名・行番号）は利用者の入力を含まないので持ってよい。
 */
class PdfjigExceptionTest {

    @Test
    @DisplayName("包んだ例外は、原因が投げられた位置のフレームを持つ")
    void keepsWhereTheCauseWasThrown() {
        IOException cause = new IOException("password=" + Secrets.PROHIBITED);
        cause.setStackTrace(new StackTraceElement[] {
            new StackTraceElement("org.apache.pdfbox.Parser", "parse", "Parser.java", 42),
            new StackTraceElement("org.apache.pdfbox.Loader", "loadPDF", "Loader.java", 7)
        });

        PdfjigException wrapped = PdfjigException.wrapping(ErrorCode.NOT_A_PDF, cause);

        assertEquals(List.of(cause.getStackTrace()), wrapped.causeFrames(), "落ちた位置を落としている（#152）");
    }

    @Test
    @DisplayName("原因のフレームは、例外の連結にも printStackTrace にも出ない")
    void keepsTheCauseFramesOutOfTheStackTrace() {
        IOException cause = new IOException("password=" + Secrets.PROHIBITED);
        cause.setStackTrace(
                new StackTraceElement[] {new StackTraceElement("org.apache.pdfbox.Parser", "parse", "Parser.java", 42)
                });

        PdfjigException wrapped = PdfjigException.wrapping(ErrorCode.NOT_A_PDF, cause);

        // ★ 連結すると、printStackTrace からメッセージが出る（#144 が塞いだ穴）。持つだけで、繋がない。
        String rendered = Secrets.renderFully(wrapped);
        assertFalse(rendered.contains("org.apache.pdfbox"), "原因のフレームが printStackTrace に出ている");
        assertFalse(rendered.contains("password="), "原因のメッセージが出ている");
    }

    @Test
    @DisplayName("原因を持たない例外のフレームは空で、包んであるものはそのまま返る")
    void hasNoCauseFramesWithoutACause() {
        PdfjigException plain = new PdfjigException(ErrorCode.IO_FAILURE);

        assertTrue(plain.causeFrames().isEmpty());
        assertSame(plain, PdfjigException.wrapping(ErrorCode.NOT_A_PDF, plain));
    }
}
