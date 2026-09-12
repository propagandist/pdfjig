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
     */
    @Override
    public void close() {
        Arrays.fill(value, '\0');
    }

    /**
     * 中身。
     *
     * <p>パッケージプライベート。pdf-core の内部実装のみが使う（INV-5）。
     */
    char[] value() {
        return value;
    }
}
