package io.github.propagandist.pdfjig.core;

import java.util.Arrays;
import java.util.Objects;

/**
 * パスワードの持ち主。
 *
 * <p>作った場所で try-with-resources に載せる（CLAUDE.md「リソース管理」）。ゼロ埋めは
 * {@link #close()} が行うので、<b>片づけの文を書き忘れる場所が構文として無い。</b>
 *
 * <p><b>★★ 持ち主は移せる。</b>入力を背景スレッドへ渡す経路があり、そこでは
 * <b>渡した側が閉じてはならない</b>——読んでいる最中に消すと、正しいパスワードが弾かれる。
 * 渡したことを {@link #handOff()} で記録すると、以後の {@link #close()} は何もしない。
 * <b>消すのは受け取った側である</b>（{@link #erase()}）。
 *
 * <p><b>★★ 書き忘れたときの壊れ方が、以前とは逆を向いている。</b>
 * {@link #handOff()} を書き忘れると、背景が読む前に消えて<b>正しいパスワードが弾かれる</b>
 * ——画面に出る。註で守っていた頃は、書き忘れると<b>平文が黙って残った</b>
 * （#135 / #144 / #145 の 3 本が同じ形である）。<b>見える側へ倒すのが、この型の値である。</b>
 *
 * <p><b>{@code String} にしない</b>（CLAUDE.md INV-5）。中身を取り出す {@link #value()} は
 * パッケージプライベートであり、<b>pdf-core の外から素の配列には触れない。</b>
 */
public final class Password implements AutoCloseable {

    /** 入力そのもの。構築時に非 null を確かめてあるので、片づけが NPE を投げることはない。 */
    private final char[] value;

    /**
     * 持ち主が移ったか。
     *
     * <p><b>読むのは {@link #close()} だけであり、書くのは {@link #handOff()} だけである。</b>
     * どちらも<b>作った場所（渡した側）のスレッドから呼ばれる</b>ので、スレッドをまたいだ
     * 見え方の問題は起きない。<b>受け取った側は {@link #erase()} を呼び、この印を見ない</b>
     * ——見ると「移されたから消さない」という逆の判断になり、平文が残る。
     */
    private boolean handedOff;

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
     * 持ち主が移ったことを記録する。
     *
     * <p>以後の {@link #close()} は何もしない。<b>呼ぶのは、受け取った側が確かに走り出したと
     * 分かった後である</b>——走り出す前に呼ぶと、断られた経路と始め方が投げた経路で
     * 誰も消さなくなる。
     */
    public void handOff() {
        handedOff = true;
    }

    /**
     * 持ち主として片づける。
     *
     * <p>移していなければゼロ埋めし、移していれば何もしない。<b>何度呼んでもよい。</b>
     */
    @Override
    public void close() {
        if (handedOff) {
            return;
        }
        erase();
    }

    /**
     * 無条件にゼロ埋めする。
     *
     * <p><b>受け取った側が呼ぶ。</b>{@link #close()} と分けてあるのは、
     * <b>受け取った側が「移された」印を見てはならない</b>ためである——見ると、
     * まさに消すべき経路で消さなくなる。
     *
     * <p><b>何度呼んでもよい。</b>渡した側の {@link #close()} と二重になる経路がある
     * （同じスレッドの中で終わる呼び出し）。二度ゼロ埋めしても結果は変わらない。
     */
    void erase() {
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
