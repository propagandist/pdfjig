package io.github.propagandist.pdfjig.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * パスワードの持ち主（CLAUDE.md INV-5）。
 *
 * <p><b>見ているのは「誰が消すか」である。</b>{@code char[]} を註で守っていた頃、
 * 同じ軸で 3 本の破れが出た（#135 / #144 / #145）。
 * <b>持ち主を移す側と受け取る側の振る舞いが、ここで分かれていることを確かめる。</b>
 */
class PasswordTest {

    private static final String SECRET = "Sup3r-Secret-Passw0rd";

    @Test
    @DisplayName("持ち主として閉じるとゼロ埋めされる")
    void closeZeroesTheValue() {
        char[] raw = SECRET.toCharArray();

        try (Password password = Password.of(raw)) {
            assertArrayEquals(SECRET.toCharArray(), password.value(), "閉じるまでは読める");
        }

        assertArrayEquals(new char[raw.length], raw, "閉じたらゼロ埋めすること");
    }

    @Test
    @DisplayName("持ち主を移した後は、閉じても消えない")
    void handedOffValueSurvivesClose() {
        char[] raw = SECRET.toCharArray();

        try (Password password = Password.of(raw)) {
            password.handOff();
        }

        // ★★ ここが背景スレッドへ渡す経路の本体である。渡した側が消すと、
        //   まだ読んでいない秘密が消え、正しいパスワードが弾かれる。
        assertArrayEquals(SECRET.toCharArray(), raw, "移した後は、渡した側が消してはならない");
    }

    @Test
    @DisplayName("受け取った側は、移された後でも消す")
    void eraseIgnoresTheHandOffMark() {
        char[] raw = SECRET.toCharArray();
        Password password = Password.of(raw);
        password.handOff();

        password.erase();

        // ★★ erase が印を見ると、まさに消すべき経路で消さなくなる。
        //   受け取った側（PdfDocument#open の finally）が呼ぶのはこちらである。
        assertArrayEquals(new char[raw.length], raw, "受け取った側は無条件に消すこと");
    }

    @Test
    @DisplayName("二度閉じても、二度消しても落ちない")
    void closingTwiceIsHarmless() {
        char[] raw = SECRET.toCharArray();
        Password password = Password.of(raw);

        password.close();
        password.erase();
        password.close();

        assertArrayEquals(new char[raw.length], raw, "二重の片づけは実際に起きる経路である");
    }

    @Test
    @DisplayName("of は写し取らない（渡した配列そのものの持ち主になる）")
    void ofTakesOwnershipWithoutCopying() {
        char[] raw = SECRET.toCharArray();

        Password password = Password.of(raw);

        // 写しを作ると「どちらを消すのか」がまた 2 つになる。それがこの型で無くしたものである。
        assertSame(raw, password.value(), "同じ配列を指していること");
    }

    @Test
    @DisplayName("copyOf は写し取り、元の入れ物には触らない")
    void copyOfDoesNotTouchTheSource() {
        StringBuilder typed = new StringBuilder(SECRET);

        try (Password password = Password.copyOf(typed)) {
            assertArrayEquals(SECRET.toCharArray(), password.value(), "写し取れていること");
        }

        // 消せない入れ物（JavaFX の入力欄）から写し取る形であり、元は消せない。
        // 消せないものを消したふりをしないために、ここで確かめておく。
        assertEquals(SECRET, typed.toString(), "元の入れ物は変えない");
    }

    @Test
    @DisplayName("空のパスワードも持ち主を持てる")
    void emptyPasswordIsStillOwned() {
        try (Password password = Password.copyOf("")) {
            assertEquals(0, password.value().length);
        }
    }

    @Test
    @DisplayName("null からは作れない（片づけの中で落ちないように、構築時に弾く）")
    void nullIsRejectedAtConstruction() {
        // ★ 片づけの中で NPE を投げると、本当の失敗を置き換える（#135）。
        //   構築時に弾けば、置き換える相手がまだ存在しない。
        assertThrows(NullPointerException.class, () -> Password.of(null));
        assertThrows(NullPointerException.class, () -> Password.copyOf(null));
    }
}
