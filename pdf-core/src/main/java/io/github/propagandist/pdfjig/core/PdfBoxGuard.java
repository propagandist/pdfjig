package io.github.propagandist.pdfjig.core;

import java.io.IOException;

/**
 * PDFBox を走らせる仕事を包む、ただ 1 つの場所。
 *
 * <p><b>★★ 物差しを「{@code try} の形」から「自分たちの包みを通ったか」へ替えるために置いた</b>
 * （#178）。<b>{@code try} の形は、人が 1 行も書かなくても満たされる</b>——javac は
 * try-with-resources のために {@code catch java/lang/Throwable} を自分で吐くので、
 * 資源を開ける公開メソッドはそれだけで緑になっていた（<b>2026-09-12 実測</b>）。
 * <b>javac は自分たちのヘルパへの呼び出しを合成しない</b>ので、ここを通ったかどうかは
 * <b>偽の緑が原理的に出ない物差しになる。</b>縛るのは {@code pdf-archtest} である。
 *
 * <p><b>★ 深さを 1 段下げても直らなかった。</b>「公開メソッドの本体全体が {@code try} の中にある」
 * に替えても、{@code merge} の合成ハンドラは本体をほぼ覆っているので<b>無改修で緑になる</b>
 * （同日実測）。<b>物差しが「例外表の形」である限り、コンパイラが勝手に満たしてしまう。</b>
 *
 * <p><b>★★ 効く範囲は、静的に見える範囲より広い。</b>公開の入口でこれを通せば、
 * <b>そこから先で誰が投げても外へは出ない</b>——{@code PageReferences} の 60 か所も、
 * {@code PdfBoxPageRendering#renderScaled} のような private の実体も、
 * <b>1 つずつ包まずに守れる</b>（#178 が「内側まで包むと 128 か所が {@code try} だらけになる」と
 * 数えた側である）。<b>だから規則が見るのは公開の入口だけでよい。</b>
 *
 * <p><b>★ {@code Error} は捕まえない。</b>包むかどうかは #151 が持つ判断であり、この差分では
 * 変えていない。<b>ただし変える場所はもうここ 1 か所である</b>——以前は同じ判断が
 * 境界ごとの {@code catch} に散っていた。
 */
final class PdfBoxGuard {

    private PdfBoxGuard() {}

    /**
     * PDFBox を走らせ、失敗を {@link PdfjigException} に畳む。
     *
     * <p><b>★ 自分で分類した失敗は塗り替えない。</b>{@link PdfjigException#wrapping} が
     * 既に包んであるものを素通しするので、<b>内側で細かく分けた符号はそのまま外へ出る</b>
     * ——{@code code} は<b>絞れなかったときの行き先</b>である。
     *
     * <p><b>★ 引数を返さない版は置かない。</b>ラムダは値を返す形と返さない形の
     * <b>どちらにも当てはまる</b>ので、同じ名前で 2 つ置くと呼び出しが曖昧になる。
     * 返すものが無いところは {@code Void} で受ける（{@link PdfDocument#close()}）。
     *
     * @param code 絞れなかった失敗の符号
     * @param call PDFBox を走らせる仕事
     * @param <T>  仕事が返すもの
     * @return 仕事が返したもの
     */
    static <T> T guarded(ErrorCode code, PdfBoxCall<T> call) {
        try {
            return call.call();
        } catch (IOException | RuntimeException e) {
            throw PdfjigException.wrapping(code, e);
        }
    }

    /**
     * PDFBox を走らせる仕事。
     *
     * <p><b>★ ここだけはチェック例外を通す</b>（{@code CLAUDE.md} の「チェック例外は使わない」は
     * <b>外へ出る口の話である</b>）。PDFBox は {@code IOException} を投げるので、
     * <b>通さないと呼ぶ側が自分で捕まえることになり、畳む場所がまた散る。</b>
     */
    @FunctionalInterface
    interface PdfBoxCall<T> {

        T call() throws IOException;
    }
}
