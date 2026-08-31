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
            // replaced/ はこちらが作る退避先である（#119）。ほかには何も無い。
            assertEquals(
                    List.of("replaced"),
                    namesIn(workspace.file().getParent()),
                    "書き込み先の隣に他人のものがあるなら、その名前は他人にも用意できる（CWE-377）");
        }
    }

    @Test
    @DisplayName("退避先は、まだ存在しない")
    void offersASetAsidePathThatDoesNotExistYet(@TempDir Path directory) {
        try (OutputWorkspace workspace = OutputWorkspace.nextTo(directory.resolve("out.pdf"))) {
            assertFalse(Files.exists(workspace.replaced()));
            // 入れ物は先に作ってある。Files.move は親が無ければ落ちる。
            assertTrue(Files.isDirectory(workspace.replaced().getParent()));
        }
    }

    /**
     * 入れ替えが済んでいれば、控えは片づく。
     *
     * <p><b>「消してはならない」の裏側を見る。</b>抱えている側にだけ倒すと、
     * <b>正体の分からない隠しものが消えなくなる</b>——それは {@link #discardsWhatAnEarlierRunLeftBehind}
     * が守っているものと同じである。
     */
    @Test
    @DisplayName("入れ替えが済んでいれば、控えは片づく")
    void discardsTheCopyOnceTheReplacementIsInPlace(@TempDir Path directory) throws IOException {
        Path output = directory.resolve("out.pdf");
        Path workspace;

        try (OutputWorkspace place = OutputWorkspace.nextTo(output)) {
            workspace = place.file().getParent();
            Files.writeString(place.replaced(), "元のファイル");
            // 入れ替えまで済んだ状態。出力先には新しいものが載っている。
            Files.writeString(output, "新しいファイル");
        }

        assertFalse(Files.exists(workspace));
        assertEquals(List.of("out.pdf"), namesIn(directory));
    }

    /**
     * 出力先が空いたままなら、控えを消さない。
     *
     * <p><b>★★ 退避したあと入れ替えにも巻き戻しにも失敗した状態である。</b>
     * <b>元の実体はここにしか無い</b>ので、片づけると利用者の文書が消える
     * （{@code CLAUDE.md} 優先順位 1）。
     */
    @Test
    @DisplayName("出力先が空いたままなら、控えを消さない")
    void keepsTheCopyWhileTheOutputIsGone(@TempDir Path directory) throws IOException {
        Path kept;

        try (OutputWorkspace place = OutputWorkspace.nextTo(directory.resolve("out.pdf"))) {
            kept = place.replaced();
            Files.writeString(kept, "元のファイル");
            // 出力先には何も置かない。落ちたところがここである。
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
        Path abandoned = Files.createDirectory(directory.resolve(".pdfjig-abandoned"));
        Path kept = Files.writeString(
                Files.createDirectory(abandoned.resolve("replaced")).resolve("out.pdf"), "元のファイル");

        try (OutputWorkspace workspace = OutputWorkspace.nextTo(directory.resolve("out.pdf"))) {
            assertEquals("元のファイル", Files.readString(kept), "唯一残っていた元を、次の保存が消している（#119）");
            assertTrue(Files.exists(workspace.file().getParent()));
        }
    }

    @Test
    @DisplayName("元が戻っていれば、残った控えも片づける")
    void discardsAnAbandonedCopyOnceTheOriginalIsBack(@TempDir Path directory) throws IOException {
        Path output = Files.writeString(directory.resolve("out.pdf"), "戻っている");
        Path abandoned = Files.createDirectory(directory.resolve(".pdfjig-abandoned"));
        Files.writeString(Files.createDirectory(abandoned.resolve("replaced")).resolve("out.pdf"), "古い控え");

        try (OutputWorkspace workspace = OutputWorkspace.nextTo(output)) {
            assertFalse(Files.exists(abandoned), "元が戻っているのに抱え続けると、隠しものが二度と消えなくなる");
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

    /** フォルダの直下にある名前を並べる。 */
    private static List<String> namesIn(Path directory) throws IOException {
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.map(entry -> entry.getFileName().toString()).sorted().toList();
        }
    }
}
