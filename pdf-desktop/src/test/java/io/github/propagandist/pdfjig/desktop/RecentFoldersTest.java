package io.github.propagandist.pdfjig.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecentFoldersTest {

    private final RecentFolders folders = new RecentFolders();

    @Test
    @DisplayName("何も使っていなければ空")
    void startsEmpty() {
        assertTrue(folders.reading().isEmpty());
        assertTrue(folders.writing().isEmpty());
    }

    @Test
    @DisplayName("読んだファイルの置かれていたフォルダを覚える")
    void remembersFolderOfReadFile(@TempDir Path directory) {
        folders.rememberReadFile(directory.resolve("scan.pdf"));

        assertEquals(directory, folders.reading().orElseThrow());
    }

    @Test
    @DisplayName("書き出したファイルの置き先フォルダを覚える")
    void remembersFolderOfWrittenFile(@TempDir Path directory) {
        folders.rememberWrittenFile(directory.resolve("scan-edited.pdf"));

        assertEquals(directory, folders.writing().orElseThrow());
    }

    @Test
    @DisplayName("書き出し先に選ばれたフォルダはそのまま覚える")
    void remembersChosenFolder(@TempDir Path directory) {
        folders.rememberWrittenFolder(directory);

        assertEquals(directory, folders.writing().orElseThrow());
    }

    @Test
    @DisplayName("読む用と書く用は混ざらない")
    void keepsReadingAndWritingApart(@TempDir Path source, @TempDir Path target) {
        folders.rememberReadFile(source.resolve("scan.pdf"));
        folders.rememberWrittenFolder(target);

        // 書き出し先を選んでも、次に PDF を探す場所は変わらない。
        assertEquals(source, folders.reading().orElseThrow());
        assertEquals(target, folders.writing().orElseThrow());
    }

    @Test
    @DisplayName("後から使ったフォルダで上書きする")
    void keepsOnlyTheLatest(@TempDir Path first, @TempDir Path second) {
        folders.rememberReadFile(first.resolve("a.pdf"));
        folders.rememberReadFile(second.resolve("b.pdf"));

        assertEquals(second, folders.reading().orElseThrow());
    }

    @Test
    @DisplayName("覚えたあとに消えたフォルダは返さない")
    void forgetsFolderThatDisappeared(@TempDir Path parent) throws IOException {
        // USB を抜く、ネットワークの割り当てが切れる、利用者が片づける。
        Path removable = Files.createDirectory(parent.resolve("removable"));
        folders.rememberReadFile(removable.resolve("scan.pdf"));
        assertEquals(removable, folders.reading().orElseThrow());

        Files.delete(removable);

        assertTrue(folders.reading().isEmpty());
    }

    @Test
    @DisplayName("フォルダではなくファイルを指していたら返さない")
    void ignoresPathThatIsNotAFolder(@TempDir Path parent) throws IOException {
        Path file = Files.createFile(parent.resolve("not-a-folder"));
        folders.rememberWrittenFolder(file);

        assertTrue(folders.writing().isEmpty());
    }

    @Test
    @DisplayName("覚えていた状態に戻せる")
    void restoresRememberedFolders(@TempDir Path source, @TempDir Path target) {
        folders.restoreUnused(source, target);

        assertEquals(source, folders.reading().orElseThrow());
        assertEquals(target, folders.writing().orElseThrow());
    }

    @Test
    @DisplayName("戻すときに片方だけでもよい")
    void restoresOnlyOneSide(@TempDir Path source) {
        folders.restoreUnused(source, null);

        assertEquals(source, folders.reading().orElseThrow());
        assertTrue(folders.writing().isEmpty());
    }

    @Test
    @DisplayName("★ 復元は、その間に使われた側を塗り潰さない")
    void doesNotOverwriteWhatWasUsedWhileRestoring(@TempDir Path opened, @TempDir Path remembered) {
        // 復元は背景で走る。ファイルの関連付けから起動した経路では、戻る前に
        // start が起動引数のファイルを開き、その置き場をここへ覚えている。
        folders.rememberReadFile(opened.resolve("scan.pdf"));

        folders.restoreUnused(remembered, remembered);

        // いま開いた文書の隣が残る。塗り潰すと、古い値が保存されて残ってしまう。
        assertEquals(opened, folders.reading().orElseThrow());
        // 使われていない側には入る。
        assertEquals(remembered, folders.writing().orElseThrow());
    }

    @Test
    @DisplayName("保存のために取り出すときは存在を確かめない")
    void keepsRememberedValueForSaving(@TempDir Path parent) throws IOException {
        // 終了したその瞬間に USB が抜けていても、次に挿せば同じ場所である。
        // 消えたものを落とすのは取り出すとき（reading()）でよい。
        Path removable = Files.createDirectory(parent.resolve("removable"));
        folders.rememberReadFile(removable.resolve("scan.pdf"));
        Files.delete(removable);

        assertTrue(folders.reading().isEmpty());
        assertEquals(removable, folders.rememberedReading().orElseThrow());
    }

    @Test
    @DisplayName("何も覚えていなければ、取り出すものも無い")
    void hasNothingToSaveWhenUnused() {
        assertTrue(folders.rememberedReading().isEmpty());
        assertTrue(folders.rememberedWriting().isEmpty());
    }

    @Test
    @DisplayName("親を持たないパスは覚えない")
    void ignoresPathWithoutParent(@TempDir Path directory) {
        folders.rememberReadFile(directory.resolve("scan.pdf"));

        // 要素が 1 つの相対パスには親が無い。覚えると次から必ず外れる。
        folders.rememberReadFile(Path.of("scan.pdf"));

        assertEquals(directory, folders.reading().orElseThrow());
    }
}
