package io.github.propagandist.pdfjig.core;

import java.io.IOException;
import java.util.function.Consumer;

/**
 * 仕事が失敗したら後始末をしてから、同じ失敗を投げ直す（#151 の門の 2 段目）。
 *
 * <p>書きかけの出力を消す・書いた分を巻き戻す・開いた文書を閉じる、の置き場である。
 * <b>失敗の分類（何を {@link PdfjigException} に包むか）は {@link PdfBoxGuard} が持ち、ここは持たない。</b>
 *
 * <p><b>★ {@link PdfBoxGuard} に置かない。</b>{@code pdf-archtest} は公開の入口が {@code PdfBoxGuard} を
 * 呼んだかを型で数えるので、そこに置くと、包まずに後始末だけをした入口まで「包みを通った」と数えられる。
 */
final class UndoOnFailure {

    private UndoOnFailure() {}

    /**
     * 仕事を走らせ、失敗したら {@code undo} に渡してから投げ直す。
     *
     * @param undo 後始末。起きた失敗を受け取る（閉じ損ねを抑えて付けるため）。<b>投げないこと</b>
     * @param work 仕事
     */
    static void runThrowing(Consumer<Throwable> undo, PdfBoxGuard.PdfBoxAction work) throws IOException {
        try {
            work.run();
        } catch (IOException | RuntimeException e) {
            undo.accept(e);
            throw e;
        }
    }

    /**
     * チェック例外を投げない仕事の {@link #runThrowing}。
     *
     * <p><b>★ 同じ名前で重ねない。</b>ラムダはどちらの形にも当てはまるので、呼び出しが曖昧になる
     * （{@code PdfBoxGuard#guardedRun} と同じ理由）。
     */
    static void run(Consumer<Throwable> undo, Runnable work) {
        try {
            work.run();
        } catch (RuntimeException e) {
            undo.accept(e);
            throw e;
        }
    }
}
