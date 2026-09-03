package io.github.propagandist.pdfjig.desktop;

import java.nio.file.Path;

/**
 * 書き出しに失敗し、元の実体が作業場所に残ったまま終わったこと。
 *
 * <p><b>★★ 出力先には何も無く、元は控えの中にしか無い。</b>退避まで進んで入れ替えに失敗し、
 * <b>巻き戻しにも失敗した</b>ときにここへ来る（{@code DocumentWriter#move}）。
 * <b>利用者から見えるのは「保存に失敗しました」とファイルが消えたことだけである</b>——
 * {@code .pdfjig-*} は<b>よく分からないゴミにしか見えない</b>ので、消される（#124）。
 *
 * <p><b>★ 場所を運ぶためだけの型である。</b>{@code ErrorCode} は分類しか持たず、
 * <b>{@code PdfjigException} に場所を持たせることはできない</b>——あちらは
 * <b>「利用者の入力を含まないことが保証されている」ことを根拠にログへ書かれている</b>
 * （{@code docs/SPEC.md} §10.4、{@code Logs.Diagnostics#describe}）。
 * 持たせた日に、その保証が黙って破れる。
 *
 * <p><b>★★ メッセージには場所を入れない。</b>{@link Logs} は例外のメッセージを読まないが、
 * <b>捕まらなかった例外は {@code printStackTrace} も通る</b>（{@link Logs#start}）。
 * <b>取り出すのは {@link #kept()} からだけにする</b>——出す先を選べるのは、
 * 出してよい場所を知っている側だけである（{@link Messages}）。
 *
 * <p><b>ログとの線は「残り続けるかどうか」で引いてある。</b>§10.4 が禁じているのは
 * <b>残り続ける記録</b>に文書のパスを書くことであり、<b>その場限りのダイアログは別である</b>
 * ——そこに出さなければ、利用者は自分の文書がどこにあるのかを知る手立てを持たない
 * （{@code CLAUDE.md} 優先順位 2）。
 */
final class ReplacedFileKeptException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * 控えの置き場所。
     *
     * <p><b>直列化しない。</b>この例外を運ぶのは同じプロセスの中だけであり、
     * {@link Path} は直列化できない実装がありうる。
     */
    private final transient Path kept;

    private ReplacedFileKeptException(Path kept, Throwable failed) {
        // ★ メッセージに場所を入れない（上の★★）。原因はそのまま連ねる——
        //   利用者に出る文言は原因の側が持っており、こちらはそこへ場所を足すだけである。
        super("元の実体を作業場所に残したまま失敗した", failed);
        this.kept = kept;
    }

    /**
     * 失敗を、控えの在り処まで伝えられる形にする。
     *
     * <p><b>★★ 印だけを見る</b>（{@code OutputWorkspace#stillHoldsOriginal}）。
     * <b>どの経路で失敗したかを数え上げない</b>——数え上げると、
     * <b>次に増えた失敗の仕方が黙って「場所を言わない」側へ落ちる。</b>
     * 印は「元の実体をここに抱えたままである」ことそのものであり、
     * <b>抱えたまま失敗が外へ出ることが、伝えるべき状態の定義である。</b>
     *
     * <p><b>抱えていなければ、包まずにそのまま返す。</b>巻き戻せていれば元は出力先にあり、
     * <b>そこで場所を言うと「消えていない」を「どこかへ行った」と読ませることになる</b>
     * （{@code CLAUDE.md} 優先順位 2）。
     *
     * @param failed    起きた失敗
     * @param workspace 書き出しに使った作業場所
     * @return 控えを抱えたままなら包んだもの。そうでなければ {@code failed} そのもの
     */
    static RuntimeException reporting(RuntimeException failed, OutputWorkspace workspace) {
        return workspace.stillHoldsOriginal() ? new ReplacedFileKeptException(workspace.replaced(), failed) : failed;
    }

    /**
     * 控えの置き場所を返す。
     *
     * @return 元の実体が残っているファイルのパス
     */
    Path kept() {
        return kept;
    }
}
