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
 * 境界ごとの {@code catch} に散っていた。<b>だから {@code pdf-core} に
 * 「{@code catch (IOException | RuntimeException)} を書いて包む」形を新しく作らないこと</b>
 * ——作った瞬間に、この行が嘘になる。
 *
 * <h2>符号の選び方</h2>
 *
 * <p><b>入口へ渡す {@code ErrorCode} は、最後まで絞れなかったときの行き先である。</b>
 * 内側で分類したものは {@link PdfjigException#wrapping} が素通しするので、
 * <b>ここへ来るのは「何が起きたか分からなかった」場合だけ</b>である。
 * <b>だから「そのとき何をしていたか」を名指す。</b>
 *
 * <ul>
 *   <li><b>読む・ページの木をいじる</b> → {@link ErrorCode#NOT_A_PDF}</li>
 *   <li><b>書き出しが主である</b> → {@link ErrorCode#IO_FAILURE}</li>
 *   <li><b>パスワードを渡して開く</b> → {@link ErrorCode#PASSWORD_OR_DOCUMENT_FAILURE}</li>
 *   <li><b>描画・抽出</b> → それぞれの専用の符号</li>
 * </ul>
 *
 * <p><b>★ 揃えていないと、同じ公開メソッドが内側の分岐で違う符号を返すことになる</b>
 * ——呼ぶ側は分岐を見られないので、<b>何をすればよいか分からなくなる</b>
 * （{@code CLAUDE.md} 優先順位 2）。
 */
final class PdfBoxGuard {

    private PdfBoxGuard() {}

    /**
     * PDFBox を走らせ、失敗を {@link PdfjigException} に畳む。
     *
     * <p><b>★ 自分で分類した失敗は塗り替えない。</b>{@link PdfjigException#wrapping} が
     * 既に包んであるものを素通しするので、<b>内側で細かく分けた符号はそのまま外へ出る</b>
     * ——{@code code} は<b>絞れなかったときの行き先</b>である（上の「符号の選び方」）。
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
     * 返すもののない仕事を包む。
     *
     * <p><b>★ 同じ名前で重ねない。</b>ラムダは<b>値を返す形と返さない形のどちらにも
     * 当てはまる</b>ので、{@code guarded} という名前を 2 つ置くと呼び出しが曖昧になる。
     * <b>名前を分ければ済む</b>——{@code pdf-archtest} の規則は<b>呼ぶ先の型だけ</b>を見るので、
     * どちらを呼んでも「包みを通った」と数えられる。
     *
     * @param code   絞れなかった失敗の符号
     * @param action PDFBox を走らせる仕事
     */
    static void guardedRun(ErrorCode code, PdfBoxAction action) {
        guarded(code, () -> {
            action.run();
            return null;
        });
    }

    /**
     * PDFBox を走らせる仕事。
     *
     * <p><b>★ ここだけはチェック例外を通す</b>（{@code .claude/rules/modules-and-invariants.md} の「チェック例外は使わない」は
     * <b>外へ出る口の話である</b>）。PDFBox は {@code IOException} を投げるので、
     * <b>通さないと呼ぶ側が自分で捕まえることになり、畳む場所がまた散る。</b>
     */
    @FunctionalInterface
    interface PdfBoxCall<T> {

        T call() throws IOException;
    }

    /** 返すもののない {@link PdfBoxCall}。 */
    @FunctionalInterface
    interface PdfBoxAction {

        void run() throws IOException;
    }
}
