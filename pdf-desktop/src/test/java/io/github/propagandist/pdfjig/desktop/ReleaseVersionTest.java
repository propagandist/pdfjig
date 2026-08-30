package io.github.propagandist.pdfjig.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ReleaseVersionTest {

    @Test
    @DisplayName("正式版の版数を読む")
    void readsReleaseVersion() {
        assertEquals(Optional.of(new ReleaseVersion(0, 1, 1, false)), ReleaseVersion.parse("0.1.1"));
    }

    @Test
    @DisplayName("タグの v を落とす")
    void readsTagForm() {
        assertEquals(Optional.of(new ReleaseVersion(0, 1, 1, false)), ReleaseVersion.parse("v0.1.1"));
    }

    @Test
    @DisplayName("接尾辞は落とさず、開発版として覚える")
    void remembersSuffixAsDevelopment() {
        ReleaseVersion snapshot = ReleaseVersion.parse("0.1.2-SNAPSHOT").orElseThrow();

        assertEquals(new ReleaseVersion(0, 1, 2, true), snapshot);
        // 接尾辞の中身は覚えない。-SNAPSHOT と -rc1 の順序を決める立場にないため。
        assertEquals(snapshot, ReleaseVersion.parse("0.1.2-rc1").orElseThrow());
    }

    @Test
    @DisplayName("9 桁までは読む")
    void readsUpToNineDigits() {
        assertEquals(Optional.of(new ReleaseVersion(999_999_999, 0, 0, false)), ReleaseVersion.parse("999999999.0.0"));
    }

    @Test
    @DisplayName("10 桁は読まない（int に収まらないものを投げずに捨てる）")
    void refusesTenDigits() {
        // ここへ来る文字列は応答のヘッダであり、こちらが形を決められない。
        // NumberFormatException で落ちる経路を作らないための境界である。
        assertEquals(Optional.empty(), ReleaseVersion.parse("1000000000.0.0"));
    }

    @Test
    @DisplayName("前後の空白は落とす")
    void trimsSurroundingSpace() {
        assertEquals(Optional.of(new ReleaseVersion(1, 2, 3, false)), ReleaseVersion.parse("  1.2.3  "));
    }

    @Test
    @DisplayName("版数として読めないものは捨てる")
    void refusesUnreadable() {
        List<String> unreadable = Arrays.asList(
                null,
                "",
                "   ",
                // BuildInfo がリソースを読めなかったときに返す値。null にも空にもならない。
                "unknown",
                "0.1",
                "0.1.1.1",
                "0.1.x",
                "-1.0.0",
                "v0.1.1 と何か",
                "release-0.1.1");

        for (String text : unreadable) {
            assertEquals(Optional.empty(), ReleaseVersion.parse(text), String.valueOf(text));
        }
    }

    @Test
    @DisplayName("数値 3 つを上の桁から比べる")
    void comparesNumbersFromTheTop() {
        ReleaseVersion base = new ReleaseVersion(1, 2, 3, false);

        assertTrue(base.isOlderThan(new ReleaseVersion(2, 0, 0, false)));
        assertTrue(base.isOlderThan(new ReleaseVersion(1, 3, 0, false)));
        assertTrue(base.isOlderThan(new ReleaseVersion(1, 2, 4, false)));
        assertFalse(base.isOlderThan(new ReleaseVersion(1, 2, 3, false)));
        assertFalse(base.isOlderThan(new ReleaseVersion(1, 2, 2, false)));
        assertFalse(base.isOlderThan(new ReleaseVersion(0, 9, 9, false)));
    }

    @Test
    @DisplayName("接尾辞は比較に影響しない")
    void ignoresSuffixWhenComparing() {
        ReleaseVersion snapshot = new ReleaseVersion(0, 1, 2, true);

        assertFalse(snapshot.isOlderThan(new ReleaseVersion(0, 1, 2, false)));
        assertTrue(snapshot.isOlderThan(new ReleaseVersion(0, 1, 3, false)));
    }

    @Test
    @DisplayName("画面に出す形は数値 3 つだけである")
    void showsNumbersOnly() {
        assertEquals("0.1.2", new ReleaseVersion(0, 1, 2, false).text());
    }
}
