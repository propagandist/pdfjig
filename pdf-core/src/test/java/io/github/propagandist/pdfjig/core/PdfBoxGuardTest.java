package io.github.propagandist.pdfjig.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 包みが {@code Error} のどこに線を引くか（#151）。
 *
 * <p><b>★ 溢れた失敗を直に投げて見る。</b>細工 PDF で本当に溢れるかはスタックの上限と JIT の状態で揺れる
 * （{@code PdfDocumentTest}）。包みの振る舞いそのものは、ここで環境に依らずに縛る。
 */
class PdfBoxGuardTest {

    @Test
    @DisplayName("溢れた失敗は、入口の符号で PdfjigException に畳む")
    void wrapsAStackOverflow() {
        PdfjigException thrown = assertThrows(
                PdfjigException.class,
                () -> PdfBoxGuard.guarded(ErrorCode.RENDERING_FAILED, () -> {
                    throw new StackOverflowError();
                }));

        assertEquals(ErrorCode.RENDERING_FAILED, thrown.errorCode(), "絞れなかった失敗と同じ行き先にする");
        assertEquals(StackOverflowError.class.getName(), thrown.causeType());
    }

    /**
     * 溢れた失敗のほかの {@code Error} は、包まずに通す。
     *
     * <p>メモリが尽きたなら、続けても次の確保で落ちる。「開けませんでした」と言って使い続けさせるほうが害が大きい。
     * クラスが見つからないのは配り方の誤りであって、文書のせいにしない。
     */
    @Test
    @DisplayName("メモリ切れと、配り方の誤りは包まずに通す")
    void letsOtherErrorsThrough() {
        OutOfMemoryError memory = new OutOfMemoryError();
        NoClassDefFoundError linkage = new NoClassDefFoundError();

        assertSame(
                memory,
                assertThrows(
                        OutOfMemoryError.class,
                        () -> PdfBoxGuard.guardedRun(ErrorCode.NOT_A_PDF, () -> {
                            throw memory;
                        })));
        assertSame(
                linkage,
                assertThrows(
                        NoClassDefFoundError.class,
                        () -> PdfBoxGuard.guardedRun(ErrorCode.NOT_A_PDF, () -> {
                            throw linkage;
                        })));
    }
}
