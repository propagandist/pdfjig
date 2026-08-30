package io.github.propagandist.pdfjig.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SettingsTest {

    /** 1 文字のバックスラッシュ。ソースに {@code \\u} と書くと Java がユニコード脱出として読む。 */
    private static final String BACKSLASH = "\\";

    @Test
    @DisplayName("まだ無いファイルからは空で始まる")
    void startsEmptyWhenFileIsMissing(@TempDir Path directory) {
        Settings settings = Settings.load(directory.resolve("settings.properties"));

        assertTrue(settings.folder(Settings.READING_FOLDER).isEmpty());
        assertTrue(settings.folder(Settings.WRITING_FOLDER).isEmpty());
    }

    @Test
    @DisplayName("書いたものを読み直せる")
    void survivesRoundTrip(@TempDir Path directory) {
        Path file = directory.resolve("settings.properties");
        Settings written = Settings.load(file);
        written.putFolder(Settings.READING_FOLDER, Path.of("C:/scan/in"));
        written.putFolder(Settings.WRITING_FOLDER, Path.of("C:/scan/out"));
        written.save(file);

        Settings read = Settings.load(file);

        assertEquals(Path.of("C:/scan/in"), read.folder(Settings.READING_FOLDER).orElseThrow());
        assertEquals(
                Path.of("C:/scan/out"), read.folder(Settings.WRITING_FOLDER).orElseThrow());
    }

    @Test
    @DisplayName("区切りと日本語を含むパスも往復する")
    void survivesRoundTripForAwkwardPaths(@TempDir Path directory) {
        // store は \ と : をエスケープするので、C:\業務 は C\:\\業務 と書かれる。
        // 読み書きが対なので壊れないことを、実際に往復させて確かめる。
        Path awkward = Path.of("C:" + BACKSLASH + "業務 資料" + BACKSLASH + "2026");
        Path file = directory.resolve("settings.properties");
        Settings written = Settings.load(file);
        written.putFolder(Settings.READING_FOLDER, awkward);
        written.save(file);

        assertEquals(
                awkward, Settings.load(file).folder(Settings.READING_FOLDER).orElseThrow());
    }

    @Test
    @DisplayName("null を渡すと覚えていたものを忘れる")
    void forgetsWhenGivenNull(@TempDir Path directory) {
        Path file = directory.resolve("settings.properties");
        Settings settings = Settings.load(file);
        settings.putFolder(Settings.READING_FOLDER, Path.of("C:/scan/in"));
        settings.putFolder(Settings.READING_FOLDER, null);
        settings.save(file);

        assertTrue(Settings.load(file).folder(Settings.READING_FOLDER).isEmpty());
    }

    @Test
    @DisplayName("壊れたファイルは捨てて既定に戻る")
    void ignoresBrokenFile(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("settings.properties");
        // 値が壊れたユニコード脱出で終わっている。Properties#load はここで投げる。
        Files.writeString(file, "folder.reading=" + BACKSLASH + "u00zz\n", StandardCharsets.UTF_8);

        Settings settings = Settings.load(file);

        // ★ 投げないことが肝心である。設定が壊れているせいで起動しない形を作らない。
        assertTrue(settings.folder(Settings.READING_FOLDER).isEmpty());
    }

    @Test
    @DisplayName("パスとして読めない値は覚えていないのと同じ")
    void ignoresValueThatIsNotAPath(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("settings.properties");
        // 手で書き換えられていることがある。パスに使えない文字を入れておく。
        Files.writeString(file, "folder.reading=a" + (char) 0 + "b\n", StandardCharsets.UTF_8);

        assertTrue(Settings.load(file).folder(Settings.READING_FOLDER).isEmpty());
    }

    @Test
    @DisplayName("ファイルではなくフォルダを指されたら空として扱う")
    void ignoresDirectoryInsteadOfFile(@TempDir Path directory) {
        assertTrue(Settings.load(directory).folder(Settings.READING_FOLDER).isEmpty());
    }

    @Test
    @DisplayName("親フォルダが無ければ作る")
    void createsMissingParent(@TempDir Path directory) {
        Path file = directory.resolve("pdfjig").resolve("settings.properties");
        Settings settings = Settings.load(file);
        settings.putFolder(Settings.READING_FOLDER, Path.of("C:/scan/in"));

        settings.save(file);

        assertTrue(Files.isRegularFile(file));
    }

    @Test
    @DisplayName("書けなくても投げない")
    void staysQuietWhenItCannotWrite(@TempDir Path directory) throws IOException {
        // 親にあたる場所がファイルなので、フォルダを作れない。
        Path blocking = Files.createFile(directory.resolve("blocked"));
        Path file = blocking.resolve("settings.properties");
        Settings settings = Settings.load(file);
        settings.putFolder(Settings.READING_FOLDER, Path.of("C:/scan/in"));

        // 閉じる操作を失敗させない。覚えられなかっただけである。
        settings.save(file);

        assertFalse(Files.isRegularFile(file));
    }

    @Test
    @DisplayName("書き終えたら一時ファイルを残さない")
    void leavesNoTemporaryFile(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("settings.properties");
        Settings settings = Settings.load(file);
        settings.putFolder(Settings.READING_FOLDER, Path.of("C:/scan/in"));
        settings.save(file);
        // 2 度目は既にあるファイルを置き換える経路を通る。
        settings.save(file);

        try (Stream<Path> entries = Files.list(directory)) {
            assertEquals(
                    List.of("settings.properties"),
                    entries.map(entry -> entry.getFileName().toString())
                            .sorted()
                            .toList());
        }
    }

    @Test
    @DisplayName("★ 渡した鍵のほかは 1 つも書かれない（INV-5）")
    void writesNothingButWhatItWasGiven(@TempDir Path directory) throws IOException {
        // パスワードを置く API はそもそも無いが、後から putAll のような口が開くと
        // 平文のまま消えずに残る。書き出されるものが渡したものだけであることを縛る。
        Path file = directory.resolve("settings.properties");
        Settings settings = Settings.load(file);
        settings.putFolder(Settings.READING_FOLDER, Path.of("C:/scan/in"));
        settings.putFolder(Settings.WRITING_FOLDER, Path.of("C:/scan/out"));
        settings.save(file);

        List<String> keys = Files.readAllLines(file, StandardCharsets.UTF_8).stream()
                .filter(line -> !line.startsWith("#") && !line.isBlank())
                .map(line -> line.substring(0, line.indexOf('=')))
                .sorted()
                .toList();

        assertEquals(List.of(Settings.READING_FOLDER, Settings.WRITING_FOLDER), keys);
    }

    @Test
    @DisplayName("親を持たないパスを渡されても投げない")
    void staysQuietForPathWithoutParent() {
        // 置き場を組み立てるのは UserDataDirectory なので実際には起きないが、
        // createTempFile は null の親で NPE を投げ、それは IOException の catch を素通りする。
        Settings settings = Settings.load(Path.of("settings.properties"));
        settings.putFolder(Settings.READING_FOLDER, Path.of("C:/scan/in"));

        settings.save(Path.of("settings.properties"));

        assertFalse(Files.exists(Path.of("settings.properties")));
    }

    @Test
    @DisplayName("見出しは ASCII で書く（日本語だと脱出されて読めなくなる）")
    void keepsTheHeaderReadable(@TempDir Path directory) throws IOException {
        // store は見出しの中の U+00FF を超える文字を、Writer の文字集合にかかわらず
        // ユニコード脱出へ変える。人が読める形式を選んだ理由が、その見出しで潰れる。
        Path file = directory.resolve("settings.properties");
        Settings settings = Settings.load(file);
        settings.putFolder(Settings.READING_FOLDER, Path.of("C:/scan/in"));
        settings.save(file);

        String header = Files.readAllLines(file, StandardCharsets.UTF_8).stream()
                .filter(line -> line.startsWith("#"))
                .findFirst()
                .orElseThrow();

        assertFalse(header.contains(BACKSLASH + "u"), "見出しが脱出されている: " + header);
        assertTrue(header.contains("INV-5"));
    }

    @Test
    @DisplayName("どちらも覚えていなければファイルを作らない")
    void writesNoFileWhenNothingIsRemembered(@TempDir Path directory) {
        Path file = directory.resolve("settings.properties");

        Settings.store(file, Optional.empty(), Optional.empty());

        // 中身の無いファイルはアンインストールしても残る。覚えることが無いのに置かない。
        assertFalse(Files.exists(file));
    }

    @Test
    @DisplayName("★ 覚えていない側は、前に書いたものを消さない")
    void keepsWhatItCannotSee(@TempDir Path directory) {
        // 復元は背景で走るので、すぐ閉じられると間に合わないことがある。
        // それを「忘れろ」と読むと、開いて即閉じただけで前回のぶんが消える。
        Path file = directory.resolve("settings.properties");
        Settings.store(file, Optional.of(Path.of("C:/scan/in")), Optional.of(Path.of("C:/scan/out")));

        Settings.store(file, Optional.empty(), Optional.of(Path.of("C:/other")));

        Settings read = Settings.load(file);
        assertEquals(Path.of("C:/scan/in"), read.folder(Settings.READING_FOLDER).orElseThrow());
        assertEquals(Path.of("C:/other"), read.folder(Settings.WRITING_FOLDER).orElseThrow());
    }

    @Test
    @DisplayName("知らない鍵は読み書きで消えない")
    void keepsKeysItDoesNotKnow(@TempDir Path directory) throws IOException {
        // 新しい版が足した項目を、古い版が黙って捨てないこと。
        Path file = directory.resolve("settings.properties");
        Files.writeString(file, "future.thing=42\n", StandardCharsets.UTF_8);

        Settings settings = Settings.load(file);
        settings.putFolder(Settings.READING_FOLDER, Path.of("C:/scan/in"));
        settings.save(file);

        assertTrue(Files.readString(file, StandardCharsets.UTF_8).contains("future.thing=42"));
    }
}
