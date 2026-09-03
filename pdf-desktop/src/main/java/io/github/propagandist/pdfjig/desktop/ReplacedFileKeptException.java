package io.github.propagandist.pdfjig.desktop;

import java.nio.file.Path;

/**
 * 書き出しに失敗し、元の実体が作業場所に残ったまま終わったこと。
 *
 * <p><b>★★ 出力先には何も無く、元は控えの中にしか無い</b>（#124）。退避まで進んで
 * 入れ替えに失敗し、<b>巻き戻しにも失敗した</b>ときにここへ来る（{@code DocumentWriter#move}）。
 * <b>投げるかどうかを決めるのは作業場所である</b>（{@link OutputWorkspace#failing}）——
 * <b>ここから直に作らないこと。</b>
 *
 * <p><b>★ 場所を運ぶためだけの型である。</b>{@code ErrorCode} は分類しか持たず、
 * <b>{@code PdfjigException} に場所を持たせることはできない</b>——あちらは
 * <b>「利用者の入力を含まないことが保証されている」ことを根拠にログへ書かれている</b>
 * （{@code Logs.Diagnostics#describe}）。持たせた日に、その保証が黙って破れる。
 *
 * <p><b>★★ メッセージには場所を入れない。</b>{@link Logs} は例外のメッセージを読まないが、
 * <b>捕まらなかった例外は {@code printStackTrace} も通る</b>（{@link Logs#start}）。
 * <b>取り出すのは {@link #kept()} からだけにする</b>——出す先を選べるのは、
 * 出してよい場所を知っている側だけである（{@link Messages}）。
 *
 * <p>画面に出してよく、記録には書かない理由は {@code docs/SPEC.md} §10.4 が持つ。<b>ここへ写さない。</b>
 */
final class ReplacedFileKeptException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * 控えの置き場所。
     *
     * <p><b>★ {@code transient} を外さないこと。</b>{@link Path} の実装は直列化できず
     * （{@code sun.nio.fs.WindowsPath}）、外すと {@code -Xlint:serial} が警告を出して
     * <b>{@code -Werror} でビルドが落ちる</b>（2026-09-04 実測）。
     * <b>直列化しないので、失うものは無い</b>——この例外が渡るのは同じプロセスの中だけである。
     */
    private final transient Path kept;

    ReplacedFileKeptException(Path kept, Throwable failed) {
        // ★ メッセージに場所を入れない（上の★★）。原因はそのまま連ねる——
        //   利用者に出る文言は原因の側が持っており、こちらはそこへ場所を足すだけである。
        super("元の実体を作業場所に残したまま失敗した", failed);
        this.kept = kept;
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
