package io.github.propagandist.pdfjig.core;

import java.util.Arrays;
import java.util.Objects;

/**
 * パスワードの持ち主。
 *
 * <p><b>作った場所で try-with-resources に載せる</b>（CLAUDE.md「リソース管理」）。
 * ゼロ埋めは {@link #close()} が行うので、<b>片づけの文を書き忘れる場所が構文として無い。</b>
 *
 * <p><b>★★ 受け取った側は消さない。</b>{@code PdfDocument#open} も
 * {@code Encryption} も、渡された秘密を読むだけである——<b>消すのは作った場所である。</b>
 * 秘密が 2 本になっても同じで、{@code try (Password user = …; Password owner = …)} と
 * 並べれば、<b>1 本目で投げたときに 2 本目が残る形にならない</b>
 * ——#146 が「手で書いた {@code finally} がいちばん間違えるところ」と呼んだ経路である。
 *
 * <p><b>★★ 枠より長く生きる仕事へ渡すときは、枠ごと渡す</b>——
 * {@code BackgroundTasks#run(Password, …)} がそれである。<b>渡した側は何も書かない。</b>
 * 走り出したかどうかで持ち主が変わるので、<b>それを唯一知っている側が閉じる。</b>
 *
 * <p><b>{@code String} にしない</b>（CLAUDE.md INV-5）。中身を取り出す {@link #value()} は
 * パッケージプライベートであり、<b>pdf-core の外から素の配列には触れない。</b>
 */
public final class Password implements AutoCloseable {

    /** 入力そのもの。構築時に非 null を確かめてあるので、片づけが NPE を投げることはない。 */
    private final char[] value;

    /**
     * 片づけ済みか。
     *
     * <p><b>★★ ゼロ埋めした配列は、そのまま読めてしまう</b>（#193）。持ち主より長く使おうとした
     * 誤りが、<b>PDFBox へ {@code \0} の列を渡す形で静かに通る。</b>
     * <b>{@link Source} が鍵を書き出しの間ずっと持つようになって、初めて届く経路である。</b>
     *
     * <p><b>★ 実測（2026-09-13。AES-256）——符号は変わらない。</b>検査が無くても
     * {@code PASSWORD_OR_DOCUMENT_FAILURE} であり、<b>変わるのは原因の型だけである</b>
     * （PDFBox の {@code IllegalArgumentException} → こちらの {@code IllegalStateException}）。
     * <b>SASLprep が {@code \0} を弾くためで、そこまで届いている。</b>
     * ★★ <b>RC4 では測っていない</b>——あちらは SASLprep を通らないので、
     * <b>「鍵が違う」として扱われる可能性がある。</b>
     *
     * <p><b>それでも置くのは、誤りに名前を付けるためである。</b>
     * 「文書かパスワードのどちらかが悪い」ではなく、<b>「閉じた鍵を使った」と言える。</b>
     *
     * <p><b>★★ {@code volatile} である。</b>閉じるのと読むのは<b>別のスレッドになりうる</b>
     * ——{@link Source} が書き出しの間ずっと鍵を持ち、その書き出しは画面の裏で走る
     * （{@code BackgroundTasks}）。<b>印が見えなければ、この検査は静かに素通りする</b>
     * ——<b>防ごうとした形がそのまま起きる。</b>
     */
    private volatile boolean closed;

    private Password(char[] value) {
        this.value = value;
    }

    /**
     * 既にある配列の持ち主になる。
     *
     * <p><b>写し取らない。</b>渡した配列はこの {@code Password} のものになるので、
     * <b>呼び出し側は以後その配列を読まない・消さない。</b>写しを作ると、
     * 「どちらを消すのか」がまた 2 つになる——それがこの型で無くしたかったものである。
     *
     * @param value パスワード。{@code null} は渡せない
     * @return 持ち主
     */
    public static Password of(char[] value) {
        // ★ ここで弾く。片づけの中で NPE を投げると、本当の失敗を置き換える（#135 の finally）。
        //   構築時なら、置き換える相手がまだ存在しない。
        return new Password(Objects.requireNonNull(value, "value"));
    }

    /**
     * 入力を写し取って持ち主になる。
     *
     * <p><b>{@code toString()} を経由しない。</b>新たな {@code String} を作れば、
     * それもまたヒープに残る対象が増えるだけである（INV-5）。
     *
     * @param typed 入力。消せない入れ物（JavaFX の入力欄など）を想定する
     * @return 持ち主
     */
    public static Password copyOf(CharSequence typed) {
        Objects.requireNonNull(typed, "typed");
        char[] copy = new char[typed.length()];
        for (int i = 0; i < copy.length; i++) {
            copy[i] = typed.charAt(i);
        }
        return new Password(copy);
    }

    /**
     * ゼロ埋めする。
     *
     * <p><b>何度呼んでもよい。</b>二度埋めても結果は変わらない。
     *
     * <p><b>★ 閉じた後は {@link #value()} が投げる。</b>ゼロ埋めした配列は読めてしまうので、
     * <b>持ち主より長く使おうとしたことを、静かに通さない</b>（#193）。
     */
    @Override
    public void close() {
        Arrays.fill(value, '\0');
        closed = true;
    }

    /**
     * 空か。
     *
     * <p><b>★ 答えるのは長さが 0 かどうかだけである。</b>中身は返さないので、
     * <b>この口は INV-5 の境界を作らない</b>——「鍵が設定されているか」は
     * <b>画面が判断に使う</b>（{@link Protection#userPasswordRequired()}）。
     *
     * <p><b>★ 閉じた後も答える。</b>ゼロ埋めは長さを変えず、判定は中身を読まない。
     *
     * @return 空なら {@code true}
     */
    public boolean isEmpty() {
        return value.length == 0;
    }

    /**
     * 中身。
     *
     * <p>パッケージプライベート。pdf-core の内部実装のみが使う（INV-5）。
     */
    char[] value() {
        if (closed) {
            // ★ 中身は載せない。載せるものがそもそも秘密である（INV-5）。
            throw new IllegalStateException("閉じた Password は読めません。鍵の枠は書き出しが終わるまで開けておくこと。");
        }
        return value;
    }
}
