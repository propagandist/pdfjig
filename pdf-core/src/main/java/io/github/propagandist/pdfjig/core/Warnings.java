package io.github.propagandist.pdfjig.core;

import java.util.ArrayList;
import java.util.List;

/**
 * 警告を溜めて、包みを抜けてから流す。
 *
 * <p><b>★★ {@link WarningListener} を動かすのは呼ぶ側のコードである。</b>
 * {@link PdfBoxGuard#guarded} の中で走らせると、<b>そこで投げた失敗を包みが飲む</b>——
 * 「ファイルの読み書きに失敗しました」に化け、<b>呼ぶ側の失敗が入力のせいにされる</b>
 * （#178。{@code CLAUDE.md} 優先順位 2）。<b>型では区別が付かない</b>
 * ——PDFBox が投げたのか呼ぶ側が投げたのかは、捕まえた側からは分からない。
 * <b>だから走らせる場所で分ける。</b>
 *
 * <p><b>★★ 受け口を握るのはこの型だけである。</b>{@code pdf-archtest} が
 * 「{@code onWarning} を呼ぶのはここだけ」を縛る——<b>クラスを数え上げないので、
 * 境界が増えても規則を直さずに効く</b>（{@code PdfBoxTextExtraction} に口が付く日は #180）。
 * 呼び出し場所の集合では足りなかった: <b>受け口を引数で受け取って流すメソッドと、
 * 直に呼ぶメソッドが、そこでは区別できない</b>（2026-09-13 実測。違反 5 件のうち 4 件が
 * 直してはならないものだった）。<b>問われているのは「どこで呼ぶか」ではなく
 * 「どの受け口へ流すか」である。</b>
 *
 * <p><b>★ 通知の時機は、溜める分だけ遅れる。</b>公開の入口がすべて包まれた以上、
 * <b>包みの中で流せる経路は 1 つも残っていない</b>——遅れは包みの代償であって、
 * 理由のない移動ではない（PR #188 の 1 段目が「理由なく時機を動かした」と却下したのは、
 * 包みの無い経路まで溜める形に広げたときである）。
 *
 * <p><b>★ 失敗したときは流れない。</b>例外が先に外へ出るので {@link #report()} へ来ない。
 * 画面は例外を優先して出し、{@code DocumentWriter} は溜めたものを捨てるので、
 * <b>捨てる層が 1 つ手前へ動いただけである。</b>
 *
 * <p><b>★ 呼び出しごとに作る。</b>{@code PageOperations} の実装は状態を持たない約束であり、
 * インスタンスに持たせると複数スレッドから同時に呼べなくなる。
 */
final class Warnings {

    private final WarningListener listener;

    private final List<Warning> collected = new ArrayList<>();

    Warnings(WarningListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("warnings は null にできません。");
        }
        this.listener = listener;
    }

    /** 溜める。ここでは呼ぶ側のコードは走らない。 */
    void add(Warning warning) {
        collected.add(warning);
    }

    /**
     * 溜めたものを利用者へ伝える。<b>包みの外で呼ぶこと。</b>
     *
     * <p><b>★★ ここで受け手が投げたとき、出力は既に書かれている。書けたものは消さない</b>
     * ——<b>通知に失敗したことは、書き出しに失敗したことではない</b>
     * （{@code CLAUDE.md} 優先順位 1）。<b>呼ぶ側には自分が投げた失敗がそのまま届く</b>ので、
     * 出力が在ることは分かる。<b>#178 が引き取った判断であり、いまは 1 つの形に揃っている</b>
     * ——以前は {@code split} だけが後始末の {@code try} の外で流しており、
     * <b>同じ原因で 2 通りの結末になっていた。</b>
     *
     * <p><b>★ メソッド参照で書かない。</b>{@code collected.forEach(listener::onWarning)} だと
     * ArchUnit が呼び出しとして数えないことがあり、<b>上の規則がここを見落とす。</b>
     */
    void report() {
        for (Warning warning : collected) {
            listener.onWarning(warning);
        }
    }
}
