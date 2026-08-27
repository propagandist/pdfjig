package io.github.propagandist.pdfjig.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 書き出しの作業場所。
 *
 * <p>見るのは「割り込ませないこと」と「残さないこと」の 2 つである。
 * どちらも出力先のフォルダの中身で確かめられるため、画面を立てる必要がない
 * （{@code pdf-desktop/src/uiTest} は実行に 1 セッションを要する）。
 */
class OutputWorkspaceTest {

    @Test
    @DisplayName("書き込み先の隣には、pdfjig が置いたもの以外が無い")
    void writesInsideAPlaceOfItsOwn(@TempDir Path directory) throws IOException {
        // 出力先は、ほかの誰かも書けるフォルダでありうる。
        Files.createFile(directory.resolve("someone-else.txt"));

        try (OutputWorkspace workspace = OutputWorkspace.nextTo(directory.resolve("out.pdf"))) {
            assertEquals(
                    List.of(), namesIn(workspace.file().getParent()), "書き込み先の隣に他人のものがあるなら、その名前は他人にも用意できる（CWE-377）");
        }
    }

    @Test
    @DisplayName("書き込み先は、まだ存在しない")
    void offersAPathThatDoesNotExistYet(@TempDir Path directory) {
        // pdf-core は既存の出力を拒む（ErrorCode.OUTPUT_ALREADY_EXISTS）。
        try (OutputWorkspace workspace = OutputWorkspace.nextTo(directory.resolve("out.pdf"))) {
            assertFalse(Files.exists(workspace.file()));
        }
    }

    @Test
    @DisplayName("書きかけで終わっても、出力先に何も残らない")
    void leavesNothingBehindWhenTheWriteFails(@TempDir Path directory) throws IOException {
        Path written;
        try (OutputWorkspace workspace = OutputWorkspace.nextTo(directory.resolve("out.pdf"))) {
            written = workspace.file();
            // 書き出しが途中で失敗した状態を作る。置き換えには進まない。
            Files.writeString(written, "書きかけ");
        }

        assertFalse(Files.exists(written));
        assertEquals(List.of(), namesIn(directory));
    }

    @Test
    @DisplayName("前の書き出しが残した作業場所を、次の書き出しで片づける")
    void discardsWhatAnEarlierRunLeftBehind(@TempDir Path directory) throws IOException {
        // 保存中にウィンドウを閉じると JVM ごと落ち、後始末が走らないまま残る。
        Path abandoned = Files.createDirectory(directory.resolve(".pdfjig-abandoned"));
        Files.createFile(abandoned.resolve("out.pdf"));

        try (OutputWorkspace workspace = OutputWorkspace.nextTo(directory.resolve("out.pdf"))) {
            assertFalse(Files.exists(abandoned), "残ったものを片づけないと、利用者には正体の分からない隠しものが増えていく");
            assertTrue(Files.exists(workspace.file().getParent()), "片づけが自分の作業場所まで消してはならない");
        }
    }

    @Test
    @DisplayName("pdfjig が作ったものでない隣人には触らない")
    void leavesOtherThingsAlone(@TempDir Path directory) throws IOException {
        Path otherDirectory = Files.createDirectory(directory.resolve("important"));
        // 名前の頭は同じだが、こちらが作るのはディレクトリだけである。ファイルは他人のもの。
        Path lookalike = Files.createFile(directory.resolve(".pdfjig-not-a-directory"));

        try (OutputWorkspace workspace = OutputWorkspace.nextTo(directory.resolve("out.pdf"))) {
            assertTrue(Files.exists(otherDirectory));
            assertTrue(Files.exists(lookalike));
            assertTrue(Files.exists(workspace.file().getParent()));
        }
    }

    /** フォルダの直下にある名前を並べる。 */
    private static List<String> namesIn(Path directory) throws IOException {
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.map(entry -> entry.getFileName().toString()).sorted().toList();
        }
    }
}
