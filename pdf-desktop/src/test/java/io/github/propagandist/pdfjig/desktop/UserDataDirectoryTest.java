package io.github.propagandist.pdfjig.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class UserDataDirectoryTest {

    @Test
    @DisplayName("環境変数の下に pdfjig を掘る")
    void resolvesUnderLocalAppData() {
        assertEquals(
                Path.of("C:/Users/x/AppData/Local").resolve("pdfjig"),
                UserDataDirectory.resolve("C:/Users/x/AppData/Local").orElseThrow());
    }

    @Test
    @DisplayName("環境変数が無ければ空")
    void hasNoDirectoryWithoutVariable() {
        // Windows 以外では LOCALAPPDATA が無い。CI の ubuntu もここに当たる。
        assertTrue(UserDataDirectory.resolve(null).isEmpty());
    }

    @Test
    @DisplayName("環境変数が空白なら空")
    void hasNoDirectoryForBlankVariable() {
        assertTrue(UserDataDirectory.resolve("").isEmpty());
        assertTrue(UserDataDirectory.resolve("   ").isEmpty());
    }

    @Test
    @DisplayName("パスとして読めない値なら空")
    void hasNoDirectoryForUnreadableVariable() {
        // 環境変数に何が入っているかは制御できない。読めないなら覚えないだけである。
        assertTrue(UserDataDirectory.resolve("a" + (char) 0 + "b").isEmpty());
    }

    @Test
    @DisplayName("設定ファイルは置き場の直下に置く")
    void settingsFileSitsInTheDirectory() {
        // 環境変数を差し替えられないので、2 つが同じ環境の上で食い違わないことを見る。
        // Windows では両方とも値が返り、ubuntu の CI では両方とも空になる。
        Optional<Path> directory = UserDataDirectory.locate();
        Optional<Path> file = UserDataDirectory.settingsFile();

        assertEquals(directory.isPresent(), file.isPresent());
        directory.ifPresent(found -> {
            assertEquals(found, file.orElseThrow().getParent());
            assertEquals("pdfjig", found.getFileName().toString());
            assertEquals("settings.properties", file.orElseThrow().getFileName().toString());
        });
    }
}
