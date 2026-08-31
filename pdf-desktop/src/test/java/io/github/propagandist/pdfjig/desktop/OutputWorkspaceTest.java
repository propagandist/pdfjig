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
 *
 * <p><b>★ #119 で 3 つ目が増えた——「消してはならないものを消さないこと」。</b>
 * 退避した元の実体がここに入るようになり、<b>片づけの既定が「消す」のままだと、
 * いちばん失って困る場面でだけ消す</b>ことになる。
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
    @DisplayName("退避先は、まだ存在しない")
    void offersASetAsidePathThatDoesNotExistYet(@TempDir Path directory) {
        try (OutputWorkspace workspace = OutputWorkspace.nextTo(directory.resolve("out.pdf"))) {
            assertFalse(Files.exists(workspace.replaced()));
        }
    }

    /**
     * 控えを抱えていれば、片づけない。
     *
     * <p><b>★★ 退避したあと入れ替えにも巻き戻しにも失敗した状態である。</b>
     * <b>元の実体はここにしか無い</b>ので、片づけると利用者の文書が消える
     * （{@code CLAUDE.md} 優先順位 1）。
     *
     * <p><b>済んだ控えがここに残ることは無い</b>——{@code DocumentWriter#assemble} が
     * 置き換えの直後に捨てる。<b>だから「在る」を「まだ済んでいない」と読んでよい。</b>
     */
    @Test
    @DisplayName("控えを抱えていれば、片づけない")
    void keepsTheWorkspaceThatHoldsTheOnlyCopy(@TempDir Path directory) throws IOException {
        Path kept;

        try (OutputWorkspace workspace = OutputWorkspace.nextTo(directory.resolve("out.pdf"))) {
            kept = workspace.replaced();
            Files.writeString(kept, "元のファイル");
        }

        assertEquals("元のファイル", Files.readString(kept), "元がここにしか無いのに片づけている（#119）");
    }

    /**
     * 落ちた後に残った控えを、次の書き出しが消さない。
     *
     * <p><b>★★ ここがいちばんありそうな流れである</b>——保存が落ちる → 直して同じフォルダへ
     * 保存し直す。{@link OutputWorkspace#nextTo} は<b>そのたびに残り物を片づける</b>ので、
     * 素通しにすると<b>唯一残っていた元が、次の保存で消える。</b>
     */
    @Test
    @DisplayName("落ちた後に残った控えを、次の書き出しが消さない")
    void keepsAnAbandonedCopyOnTheNextWrite(@TempDir Path directory) throws IOException {
        Path kept = abandonedCopyIn(directory);

        try (OutputWorkspace workspace = OutputWorkspace.nextTo(directory.resolve("out.pdf"))) {
            assertEquals("元のファイル", Files.readString(kept), "唯一残っていた元を、次の保存が消している（#119）");
            assertTrue(Files.exists(workspace.file().getParent()));
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

    /**
     * 前の書き出しが落ちて、控えを抱えたまま残った作業場所を作る。
     *
     * <p><b>生きた API では作れない</b>——{@link OutputWorkspace#close} が走らないまま
     * JVM ごと落ちた状態だからである。<b>名前は {@link OutputWorkspace} の private な決めごとと
     * 揃っていなければならない</b>ので、ここで 1 か所にまとめてある。
     *
     * @return 抱えられている控え
     */
    private static Path abandonedCopyIn(Path directory) throws IOException {
        Path abandoned = Files.createDirectory(directory.resolve(".pdfjig-abandoned"));
        return Files.writeString(abandoned.resolve("replaced.pdf"), "元のファイル");
    }

    /** フォルダの直下にある名前を並べる。 */
    private static List<String> namesIn(Path directory) throws IOException {
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.map(entry -> entry.getFileName().toString()).sorted().toList();
        }
    }
}
