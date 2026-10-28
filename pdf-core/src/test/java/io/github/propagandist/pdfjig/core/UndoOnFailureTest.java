package io.github.propagandist.pdfjig.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 後始末は、どの失敗でも走る（#151 の門の 2 段目）。
 *
 * <p><b>★★ 溢れた失敗を包むようにしたので、後始末が受ける範囲もそれに追いつかなければならない。</b>
 * 包みが「きれいに失敗した」と言うのに書きかけの平文が残る——それがいちばん危ない形である。
 * <b>メモリ切れのように包まないものでも後始末はする。</b>何を包むかと、後始末をするかは別の判断である。
 */
class UndoOnFailureTest {

    @Test
    @DisplayName("溢れた失敗でも後始末をして、同じ失敗を投げ直す")
    void undoesOnAStackOverflow() {
        StackOverflowError overflow = new StackOverflowError();
        List<Throwable> undone = new ArrayList<>();

        assertSame(
                overflow,
                assertThrows(
                        StackOverflowError.class,
                        () -> UndoOnFailure.runThrowing(undone::add, () -> {
                            throw overflow;
                        })));
        assertSame(
                overflow,
                assertThrows(
                        StackOverflowError.class,
                        () -> UndoOnFailure.run(undone::add, () -> {
                            throw overflow;
                        })));

        assertEquals(2, undone.size(), "溢れた失敗で後始末が走らず、書きかけが残る");
    }

    @Test
    @DisplayName("包まない失敗（メモリ切れ）でも後始末をする")
    void undoesOnErrorsItDoesNotWrap() {
        OutOfMemoryError memory = new OutOfMemoryError();
        List<Throwable> undone = new ArrayList<>();

        assertSame(
                memory,
                assertThrows(
                        OutOfMemoryError.class,
                        () -> UndoOnFailure.runThrowing(undone::add, () -> {
                            throw memory;
                        })));

        assertSame(memory, undone.isEmpty() ? null : undone.get(0), "メモリ切れで後始末が走っていない");
    }
}
