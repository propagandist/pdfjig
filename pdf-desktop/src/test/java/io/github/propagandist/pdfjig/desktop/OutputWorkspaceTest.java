package io.github.propagandist.pdfjig.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.nio.file.ExtendedOpenOption;
import io.github.propagandist.pdfjig.core.ErrorCode;
import io.github.propagandist.pdfjig.core.PdfjigException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
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

        try (OutputWorkspace workspace = OutputWorkspace.nextTo(directory.resolve("out.pdf"), found -> {})) {
            // 保存の間の押さえ（#222。Windows でだけ置く）は pdfjig が置いたものである。
            List<String> others = namesIn(workspace.file().getParent()).stream()
                    .filter(name -> !name.equals(OutputWorkspace.PIN))
                    .toList();
            assertEquals(List.of(), others, "書き込み先の隣に他人のものがあるなら、その名前は他人にも用意できる（CWE-377）");
        }
    }

    @Test
    @DisplayName("退避先は、まだ存在しない")
    void offersASetAsidePathThatDoesNotExistYet(@TempDir Path directory) {
        try (OutputWorkspace workspace = OutputWorkspace.nextTo(directory.resolve("out.pdf"), found -> {})) {
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
     * <p><b>抱えているかどうかは印で決まる</b>（{@code holdOriginal} / {@code releaseOriginal}）。
     * 控えそのものを印にすると、<b>いちど消せなかっただけで片づけが永久に閉じる</b>——
     * それを見るのが {@link #stopsHoldingEvenWhenTheCopyCannotBeDeleted} である。
     */
    @Test
    @DisplayName("控えを抱えていれば、片づけない")
    void keepsTheWorkspaceThatHoldsTheOnlyCopy(@TempDir Path directory) throws IOException {
        Path kept;

        try (OutputWorkspace workspace = OutputWorkspace.nextTo(directory.resolve("out.pdf"), found -> {})) {
            workspace.holdOriginal();
            kept = workspace.replaced();
            Files.writeString(kept, "元のファイル");
        }

        assertEquals("元のファイル", Files.readString(kept), "元がここにしか無いのに片づけている（#119）");
    }

    /**
     * 持ち主が頼めば、控えを抱えていても書けた出力を消す。元は残す（#184）。
     *
     * <p><b>頼むのは、出力が復号した中身で、鍵なしで開けるときだけである</b>（{@code DocumentWriter}）。
     * #119 の判断（元を失わない）は動かさない。
     */
    @Test
    @DisplayName("頼めば、控えを抱えていても書けた出力を消す")
    void dropsTheWrittenOutputWhenAsked(@TempDir Path directory) throws IOException {
        Path kept;
        Path written;

        try (OutputWorkspace workspace = OutputWorkspace.nextTo(directory.resolve("out.pdf"), found -> {})) {
            workspace.holdOriginal();
            kept = workspace.replaced();
            written = workspace.file();
            Files.writeString(kept, "元のファイル");
            Files.writeString(written, "復号された中身");

            assertEquals(null, workspace.dropWrittenOutput(), "消せたのに、消し損ねたと返している");
        }

        assertFalse(Files.exists(written), "書けた出力が作業場所に残っている（#184）");
        assertEquals("元のファイル", Files.readString(kept), "元がここにしか無いのに片づけている（#119）");
    }

    /**
     * 頼まれなければ、控えを抱えている間は書けた出力も残す。
     *
     * <p><b>開いている文書へ上書き保存して失敗したとき、編集を反映した中身はここにしか無い。</b>
     * ふつうの文書でそれまで消すと、元を戻さない限り保存し直すこともできない（#184 の門）。
     */
    @Test
    @DisplayName("頼まれなければ、控えを抱えている間は書けた出力も残す")
    void keepsAnOrdinaryWrittenOutputWhileHolding(@TempDir Path directory) throws IOException {
        Path written;

        try (OutputWorkspace workspace = OutputWorkspace.nextTo(directory.resolve("out.pdf"), found -> {})) {
            workspace.holdOriginal();
            Files.writeString(workspace.replaced(), "元のファイル");
            written = Files.writeString(workspace.file(), "編集した中身");
        }

        assertEquals("編集した中身", Files.readString(written));
    }

    /**
     * 前の書き出しの残り物では、書けた出力にも触らない（#184 の境界）。
     *
     * <p><b>別の窓が退避と入れ替えの間にいると、残り物と同じ形をしている。</b>そこで出力を消すと、
     * これから出力先へ移すはずのものが消え、<b>あちらの保存が失敗する。</b>済んだかを知っているのは持ち主だけである。
     */
    @Test
    @DisplayName("前の書き出しの残り物では、書けた出力にも触らない")
    void leavesTheOutputOfAWorkspaceItDoesNotOwn(@TempDir Path directory) throws IOException {
        Path kept = abandonedCopyIn(directory);
        Path written = Files.writeString(kept.resolveSibling("output.pdf"), "書きかけ");

        try (OutputWorkspace workspace = OutputWorkspace.nextTo(directory.resolve("out.pdf"), found -> {})) {
            assertTrue(Files.exists(written), "持ち主でないのに、書けた出力を消している");
            assertEquals("元のファイル", Files.readString(kept));
            assertTrue(Files.exists(workspace.file().getParent()));
        }
    }

    /**
     * 前の書き出しが残した控えを、次の書き出しが見つけて知らせる（#138）。
     *
     * <p><b>★★ アプリごと落ちた回には、失敗に在り処を載せる形が届かない。</b>{@code close} も
     * {@code catch} も走らないためである。<b>見つけられるのは次に同じフォルダへ書き出すときで、
     * そこで黙ると、利用者から見えるのは出力先に増えた {@code .pdfjig-*} だけになる。</b>
     *
     * <p><b>控えが実在するものだけを知らせる。</b>印だけ残って控えの無いもの（退避の前に落ちた回）を
     * 知らせると、無事に出力先にあるものを「ここにしか無い」と伝える——{@code failing} と同じ理由である。
     */
    @Test
    @DisplayName("前の書き出しが残した控えを、次の書き出しが見つけて知らせる")
    void reportsTheCopyAPreviousWriteLeftBehind(@TempDir Path directory) throws IOException {
        Path kept;
        try (OutputWorkspace crashed = OutputWorkspace.nextTo(directory.resolve("out.pdf"), found -> {})) {
            crashed.holdOriginal();
            kept = crashed.replaced();
            Files.writeString(kept, "元のファイル");
        }
        try (OutputWorkspace markedOnly = OutputWorkspace.nextTo(directory.resolve("other.pdf"), found -> {})) {
            // 印を立てたところで落ちた。元はまだ出力先にある。
            markedOnly.holdOriginal();
        }

        List<List<Path>> found = new ArrayList<>();
        OutputWorkspace.nextTo(directory.resolve("out.pdf"), found::add).close();
        assertEquals(List.of(List.of(kept)), found, "見つけた控えを知らせていない（#138）");
        assertEquals("元のファイル", Files.readString(kept), "知らせただけでなく、片づけてもいない");
    }

    /**
     * 置き換えが済んだ作業場所は、控えが消せずに残っていても知らせない。
     *
     * <p><b>★★ 知らせてよいのは印（{@code held}）があるものだけである。</b>印を外した後の控えは
     * 1 世代前であって「ここにしか無い」ものではない。<b>それを知らせると、成功した保存を
     * 「途中で終わった」と伝える。</b>
     *
     * <p><b>★ 控えを消せない状態を作る。</b>作業場所ごと消えてしまうと、判定を緩めても
     * 何も見つからず、<b>この検めは落ちようがない</b>（#138 の門で出た）。
     * 削除を共有しない開き方で握っておけば、片づけは控えを消せずに作業場所を残す。
     * <b>Windows でだけ作れる状態である。</b>
     */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    @DisplayName("置き換えが済んだ作業場所は、控えが消せずに残っていても知らせない")
    void doesNotReportACopyThatIsNoLongerHeld(@TempDir Path directory) throws IOException {
        Path copy;
        OutputWorkspace done = OutputWorkspace.nextTo(directory.resolve("out.pdf"), found -> {});
        done.holdOriginal();
        copy = done.replaced();
        Files.writeString(copy, "元のファイル");
        done.releaseOriginal();

        try (FileChannel held = FileChannel.open(copy, StandardOpenOption.READ, ExtendedOpenOption.NOSHARE_DELETE)) {
            done.close();
            assertTrue(held.isOpen() && Files.exists(copy), "控えを消せない状態になっていない。前提が変わっている");

            List<List<Path>> found = new ArrayList<>();
            OutputWorkspace.nextTo(directory.resolve("out.pdf"), found::add).close();
            assertEquals(List.of(), found, "置き換えが済んだ作業場所を「途中で終わった」と伝えている");
        }
    }

    /**
     * 前の書き出しが残した作業場所の中のジャンクションを、次の保存が降りない（#125）。
     *
     * <p><b>★★ 出力先に書ける第三者が、残った作業場所の中に任意の場所を指すジャンクションを置けば、
     * 次の保存がその先を消していた。</b>{@code Files.walk} は既定で {@code FOLLOW_LINKS} を
     * 付けていなくても、Windows のジャンクションを降りる。<b>競合を要らない</b>——置いておけば、
     * 次の保存で必ず走る（#125 の本文）。
     *
     * <p><b>ジャンクションも作業場所も残る</b>——片づけるのは pdfjig が作る 4 つの名前だけである。
     * 一覧を取って消す形は、作業場所ごとすり替えられると、第三者が選んだ名前を指す先で消す（#125 の門）。
     */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    @DisplayName("残った作業場所の中のジャンクションを、次の保存が降りない")
    void doesNotFollowAJunctionInsideALeftoverWorkspace(@TempDir Path directory) throws Exception {
        Path precious = preciousOutside(directory);
        Path out = Files.createDirectory(directory.resolve("out"));
        // ★ 名前を手で組まない。pdfjig が作ったものと同じ名前でなければ、この筋を通ったことにならない。
        Path leftover = leftBehind(out.resolve("out.pdf")).file().getParent();
        junction(leftover.resolve("link"), precious.getParent());

        OutputWorkspace.nextTo(out.resolve("out.pdf"), found -> {}).close();

        try {
            assertEquals("消えてはならない", Files.readString(precious), "作業場所の外を消している（#125）");
            // ★ 作業場所は残る。中のジャンクションは pdfjig が作らないもので、正体の分からないものは消さない。
            assertTrue(Files.exists(leftover.resolve("link"), LinkOption.NOFOLLOW_LINKS), "知らないものを消している");
        } finally {
            // ★ 残したまま終えない。@TempDir の後始末は walkFileTree で、ジャンクションを降りる。
            Files.deleteIfExists(leftover.resolve("link"));
        }
    }

    /**
     * {@code .pdfjig-*} そのものがジャンクションでも、指す先を消さない（#125）。
     *
     * <p><b>★★ 入口でもリンクを辿っていた。</b>{@code Files::isDirectory} はジャンクションの先を
     * 作業場所と読み、片づけが指す先の中身を消した（#125 の {@code /code-review max} で再現）。
     * <b>自分で作った作業場所ではないので、リンクそのものにも触らない。</b>
     */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    @DisplayName(".pdfjig-* そのものがジャンクションでも、指す先を消さない")
    void doesNotFollowAWorkspaceThatIsAJunction(@TempDir Path directory) throws Exception {
        Path precious = preciousOutside(directory);
        Path out = Files.createDirectory(directory.resolve("out"));
        // ★ 名前を手で組まない。作った作業場所を空けて、同じ名前でジャンクションに差し替える。
        Path planted = leftBehind(out.resolve("out.pdf")).file().getParent();
        Files.delete(planted);
        junction(planted, precious.getParent());
        try {
            OutputWorkspace.nextTo(out.resolve("out.pdf"), found -> {}).close();

            assertEquals("消えてはならない", Files.readString(precious), "ジャンクションの先を消している（#125）");
            assertTrue(Files.exists(planted, LinkOption.NOFOLLOW_LINKS), "自分で作っていないリンクを消している");
        } finally {
            // ★ 残したまま終えない。@TempDir の後始末は walkFileTree で、ジャンクションを降りる。
            Files.deleteIfExists(planted);
        }
    }

    /**
     * 保存の間、作業場所も出力先フォルダも改名できない（#222）。
     *
     * <p><b>作業場所は出力先フォルダの権限を引き継ぐ。</b>そこに書ける者は、保存の最中に作業場所を
     * どけてジャンクションへ差し替えられる——新しい文書も、上書き保存で退避する利用者の元のファイルも、
     * 指す先へ行く。<b>どけられなければ差し替えられない。</b>出力先フォルダも同じで、
     * どけて同じ名前のジャンクションを置けば、作業場所ごと指す先へ移る。
     */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    @DisplayName("保存の間、作業場所も出力先フォルダも改名できない")
    void pinsTheWorkspaceAndItsFolderWhileSaving(@TempDir Path directory) throws IOException {
        Path out = Files.createDirectory(directory.resolve("out"));
        try (OutputWorkspace workspace = OutputWorkspace.nextTo(out.resolve("out.pdf"), found -> {})) {
            Path place = workspace.file().getParent();

            assertThrows(
                    FileSystemException.class,
                    () -> Files.move(place, out.resolve("moved-away")),
                    "保存の最中に作業場所をどけられる。どけた跡にジャンクションを置かれる");
            assertThrows(
                    FileSystemException.class,
                    () -> Files.move(out, directory.resolve("moved-away")),
                    "保存の最中に出力先フォルダをどけられる");

            // ★ 押さえそのものを消させない。改名を断るだけなら削除を共有する開き方でも足りるが、
            //   それだと pin を消してから作業場所をどけられる（#222 の門で再現）。
            Path pin = place.resolve(OutputWorkspace.PIN);
            assertTrue(Files.exists(pin), "押さえのファイルが無い");
            assertThrows(FileSystemException.class, () -> Files.delete(pin), "保存の最中に押さえのファイルを消せる");
        }
        assertEquals(List.of(), namesIn(out), "放した後に作業場所が片づいていない");
    }

    /**
     * 別の窓がいま使っている作業場所には触らず、控えとしても知らせない（#222）。
     *
     * <p><b>退避と入れ替えの間にいる窓は、落ちた後と同じ形をしている。</b>見分けられないと、
     * あちらの退避した元を「前の保存が残した控え」と伝え、書きかけを消しにいって
     * 「片づけられなかった」と記録する。<b>使っている窓は押さえを開いているので、それで見分ける。</b>
     */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    @DisplayName("別の窓がいま使っている作業場所には触らず、控えとしても知らせない")
    void leavesAWorkspaceAnotherWindowIsUsing(@TempDir Path directory) throws IOException {
        try (OutputWorkspace busy = OutputWorkspace.nextTo(directory.resolve("out.pdf"), found -> {})) {
            busy.holdOriginal();
            Files.writeString(busy.replaced(), "あちらの元のファイル");
            Files.writeString(busy.file(), "あちらの書きかけ");

            List<List<Path>> reported = new ArrayList<>();
            OutputWorkspace.nextTo(directory.resolve("other.pdf"), reported::add)
                    .close();

            assertEquals(List.of(), reported, "使っている窓の退避を、落ちた後の控えとして伝えている");
            assertEquals("あちらの書きかけ", Files.readString(busy.file()), "使っている窓の書きかけに触っている");
            busy.releaseOriginal();
        }
    }

    /**
     * 押さえの名前でものを置かれても、落ちた書き出しの控えは知らせる（#222 の門の 2 段目）。
     *
     * <p><b>「使われている」と読むのは、ふつうのファイルを消そうとして共有違反で断られたときだけである。</b>
     * 中身のあるディレクトリを {@code pin} の名前で置かれると、消そうとして断られる。それを
     * 「使われている」と読むと、置くだけで唯一の控えを知らせなくさせられる。
     */
    @Test
    @DisplayName("押さえの名前でものを置かれても、落ちた書き出しの控えは知らせる")
    void reportsTheCopyEvenWhenSomethingIsPlantedAsThePin(@TempDir Path directory) throws IOException {
        Path kept = abandonedCopyIn(directory);
        Files.writeString(
                Files.createDirectories(kept.resolveSibling(OutputWorkspace.PIN))
                        .resolve("x"),
                "置かれたもの");

        List<List<Path>> reported = new ArrayList<>();
        OutputWorkspace.nextTo(directory.resolve("out.pdf"), reported::add).close();

        assertEquals(List.of(List.of(kept)), reported, "置かれたものに黙らされて、唯一の控えを知らせていない");
    }

    /**
     * 押さえていても、保存に要る改名と削除は通る（#222）。
     *
     * <p>上書き保存は、出力先の元のファイルを作業場所へ退避し、書けたものを出力先へ移す。
     * 押さえ方が改名をまとめて断る形だと、保存そのものができなくなる。
     */
    @Test
    @DisplayName("押さえていても、保存に要る改名と削除は通る")
    void stillLetsTheSaveMoveFilesInAndOut(@TempDir Path directory) throws IOException {
        Path out = directory.resolve("out.pdf");
        Files.writeString(out, "元");
        try (OutputWorkspace workspace = OutputWorkspace.nextTo(out, found -> {})) {
            Files.writeString(workspace.file(), "新");
            Files.move(out, workspace.replaced(), StandardCopyOption.ATOMIC_MOVE);
            Files.move(workspace.file(), out, StandardCopyOption.ATOMIC_MOVE);
            Files.delete(workspace.replaced());
        }

        assertEquals("新", Files.readString(out));
        assertEquals(List.of("out.pdf"), namesIn(directory), "作業場所が片づいていない");
    }

    /**
     * 作業場所の中の、pdfjig が作らないものには触らない（#125）。
     *
     * <p><b>pdfjig が作業場所に作るのは 4 つのファイルだけである。</b>ほかのものがあるなら第三者が置いたもので、
     * 中を辿ると深さを決められ（再帰が溢れて保存ごと落ちた）、一覧を取って消すと、作業場所ごと
     * すり替えられたときに、置いた名前が指す先で消える。<b>触らない。</b>作業場所も残る。
     */
    @Test
    @DisplayName("作業場所の中の、pdfjig が作らないものには触らない")
    void doesNotDescendIntoADirectoryInsideTheWorkspace(@TempDir Path directory) throws IOException {
        Path out = Files.createDirectory(directory.resolve("out"));
        Path leftover = leftBehind(out.resolve("out.pdf")).file().getParent();
        Path planted = Files.writeString(
                Files.createDirectories(leftover.resolve("planted").resolve("deeper"))
                        .resolve("x.txt"),
                "中身");

        Path named = Files.writeString(leftover.resolve("report.xlsx"), "名前を選んで置いたもの");

        OutputWorkspace.nextTo(out.resolve("out.pdf"), found -> {}).close();

        assertTrue(Files.exists(planted), "作業場所の中のディレクトリを降りて消している");
        assertTrue(Files.exists(named), "pdfjig が作らない名前を消している——すり替えられると、この名前が指す先で消える");
    }

    /** 出力先の外にある、消えてはならないファイル。 */
    private static Path preciousOutside(Path directory) throws IOException {
        Path victim = Files.createDirectory(directory.resolve("victim"));
        return Files.writeString(victim.resolve("precious.txt"), "消えてはならない");
    }

    /**
     * ジャンクションを作る。<b>管理者権限は要らない</b>——だから第三者も置ける。
     *
     * <p><b>作れたものがジャンクションであることを確かめてから返す。</b>普通のディレクトリが
     * できていると、この検めは落ちようがない。
     */
    private static Path junction(Path link, Path target) throws Exception {
        Process made = new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString())
                .redirectErrorStream(true)
                .start();
        String said = new String(made.getInputStream().readAllBytes(), Charset.defaultCharset());
        assertTrue(made.waitFor(30, TimeUnit.SECONDS), "mklink が戻らない");
        assertEquals(0, made.exitValue(), "ジャンクションを作れなかった: " + said);
        BasicFileAttributes attributes =
                Files.readAttributes(link, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        assertTrue(attributes.isOther(), "できたものがジャンクションではない。前提が変わっている");
        return link;
    }

    @Test
    @DisplayName("抱えるのをやめれば、片づく")
    void discardsOnceItStopsHolding(@TempDir Path directory) throws IOException {
        Path workspace;

        try (OutputWorkspace place = OutputWorkspace.nextTo(directory.resolve("out.pdf"), found -> {})) {
            workspace = place.file().getParent();
            place.holdOriginal();
            Files.writeString(place.replaced(), "元のファイル");
            // 置き換えが済んだ。控えはもう唯一の実体ではない。
            place.releaseOriginal();
        }

        assertFalse(Files.exists(workspace));
        assertEquals(List.of(), namesIn(directory));
    }

    /**
     * 控えが実在するときだけ、失敗に在り処を載せる。
     *
     * <p><b>★★ 印だけでは足りない。</b>{@code releaseOriginal} は<b>印を消せなくても続ける</b>ので、
     * <b>巻き戻して元へ返したのに印が残る</b>ことがありうる。印だけを見ると、そのとき
     * <b>出力先に無事にあるものを「作業場所にしか無い」と伝える</b>——
     * <b>利用者は無事なファイルを探しに行って、見つけられない</b>（優先順位 2）。
     *
     * <p><b>原因はそのまま連ねる。</b>何が起きたのかを持っているのは原因の側であり、
     * <b>落とすと「場所は分かるが理由が分からない」失敗になる。</b>
     */
    @Test
    @DisplayName("控えが実在するときだけ、失敗に在り処を載せる")
    void putsTheKeptPlaceOnTheFailureOnlyWhenTheCopyIsThere(@TempDir Path directory) throws IOException {
        try (OutputWorkspace workspace = OutputWorkspace.nextTo(directory.resolve("out.pdf"), found -> {})) {
            PdfjigException failed = new PdfjigException(ErrorCode.IO_FAILURE);
            assertTrue(workspace.failing(failed, null).isEmpty(), "何も退避していないのに控えの在り処を載せている");

            workspace.holdOriginal();
            assertTrue(workspace.failing(failed, null).isEmpty(), "印だけを見て在り処を載せている。控えが無いのに「ここに残っている」と言うことになる（#124）");

            Files.writeString(workspace.replaced(), "元のファイル");
            ReplacedFileKeptException kept = assertInstanceOf(
                    ReplacedFileKeptException.class,
                    workspace.failing(failed, null).orElseThrow());
            assertEquals(workspace.replaced(), kept.kept(), "控えの在り処が退避先と違う");
            assertSame(failed, kept.getCause(), "何が起きたのかを落としている");

            workspace.releaseOriginal();
            assertTrue(workspace.failing(failed, null).isEmpty(), "元の場所へ返したのに、控えの在り処を載せている");
        }
    }

    /**
     * 控えの在り処を、例外のメッセージには入れない。
     *
     * <p><b>★★ {@link Logs} は例外のメッセージを読まないが、捕まらなかった例外は
     * {@code printStackTrace} も通る</b>（{@link Logs#start}）。<b>文書のパスを
     * 残り続ける記録へ入れないという線は、そこでも守られていなければならない</b>
     * （{@code docs/SPEC.md} §10.4）。<b>取り出す口は {@code kept()} だけである。</b>
     *
     * <p><b>丸ごと一致で見る。</b>作業場所の名前だけを探す形にすると、
     * <b>別の書き方でパスが混ざった日に素通りする。</b>
     */
    @Test
    @DisplayName("控えの在り処を、例外のメッセージには入れない")
    void keepsTheKeptPlaceOutOfTheMessage(@TempDir Path directory) throws IOException {
        try (OutputWorkspace workspace = OutputWorkspace.nextTo(directory.resolve("out.pdf"), found -> {})) {
            workspace.holdOriginal();
            Files.writeString(workspace.replaced(), "元のファイル");

            RuntimeException reported = workspace
                    .failing(new PdfjigException(ErrorCode.IO_FAILURE), null)
                    .orElseThrow();

            assertEquals(
                    "元の実体を作業場所に残したまま失敗した",
                    reported.getMessage(),
                    "例外のメッセージにパスが混ざっている。printStackTrace を通れば、そのまま残り続ける記録になる（SPEC §10.4）");
        }
    }

    /**
     * Error でも、控えの在り処は載る。
     *
     * <p><b>★★ 受けるのは {@code Throwable} である。</b>退避が済んだ後に
     * {@code OutOfMemoryError} が上がっただけで在り処を言えなくなると、
     * <b>控えは残るのに画面には汎用の失敗しか出ない</b>——#124 がそのまま戻る。
     */
    @Test
    @DisplayName("Error でも、控えの在り処は載る")
    void putsTheKeptPlaceOnAnErrorToo(@TempDir Path directory) throws IOException {
        try (OutputWorkspace workspace = OutputWorkspace.nextTo(directory.resolve("out.pdf"), found -> {})) {
            workspace.holdOriginal();
            Files.writeString(workspace.replaced(), "元のファイル");
            OutOfMemoryError failed = new OutOfMemoryError();

            RuntimeException reported = workspace.failing(failed, null).orElseThrow();

            assertSame(failed, reported.getCause(), "Error を受け取れていない。控えが残るのに何も言わない道ができる");
        }
    }

    /**
     * 控えを消せなくても、抱えるのはやめられる。
     *
     * <p><b>★★ ここが「印を控えそのものにしない」理由である。</b>控えを印にすると、
     * <b>いちど消せなかっただけで {@code discard} の関門が永久に閉じる</b>——
     * {@link OutputWorkspace#close} からも、以後どの {@code discardAbandoned} からも
     * 素通りするので、<b>成功した保存のたびに利用者の文書の複製が 1 つずつ積み上がる。</b>
     *
     * <p><b>実際に起きる</b>——出力先が読み取り専用だと、改名は通るのに削除だけが
     * {@code AccessDenied} で落ちる（2026-09-01 実測）。ここでは<b>掴んだままにして代える</b>——
     * {@link java.io.FileOutputStream} は {@code FILE_SHARE_DELETE} を付けないので、
     * Windows では開いている間そのファイルを消せない。
     * <b>作業場所は残ってよい。残ってはならないのは、二度と片づけられなくなることである。</b>
     */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    @DisplayName("控えを消せなくても、抱えるのはやめられる")
    void stopsHoldingEvenWhenTheCopyCannotBeDeleted(@TempDir Path directory) throws IOException {
        // try-with-resources にしない。掴んでいる最中に片づけさせるのがこのテストである。
        OutputWorkspace place = OutputWorkspace.nextTo(directory.resolve("out.pdf"), found -> {});
        Path workspace = place.file().getParent();
        Path kept = place.replaced();
        place.holdOriginal();
        Files.writeString(kept, "元のファイル");
        place.releaseOriginal();

        // ★ java.io で開く。java.nio の Files#newOutputStream は FILE_SHARE_DELETE を付けるので、
        //   掴んでいても消せてしまい、この筋を再現できない（2026-09-01 実測）。
        try (OutputStream held = new FileOutputStream(kept.toFile(), true)) {
            place.close();
            assertTrue(Files.exists(kept), "この筋では消せないはずで、消えているなら前提が変わっている");
            held.flush();
        }

        // 掴みが外れた後。閉じ込められていなければ、次の書き出しが片づけ直せる。
        try (OutputWorkspace next = OutputWorkspace.nextTo(directory.resolve("out.pdf"), found -> {})) {
            assertTrue(Files.exists(next.file().getParent()));
        }
        assertFalse(Files.exists(workspace), "控えを消せなかっただけで、片づけが永久に閉じている（#119）");
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

        try (OutputWorkspace workspace = OutputWorkspace.nextTo(directory.resolve("out.pdf"), found -> {})) {
            assertEquals("元のファイル", Files.readString(kept), "唯一残っていた元を、次の保存が消している（#119）");
            assertTrue(Files.exists(workspace.file().getParent()));
        }
    }

    /**
     * 片づけると決めてから消すまでの間に別の窓が退避しても、その控えを消さない（#139）。
     *
     * <p><b>別の窓は、作業場所を作ってから押さえるまでの一瞬だけ、落ちた後と同じ形をしている。</b>
     * 片づける側がその一瞬に「使われていない」「抱えていない」と見て、消し始める前にあちらが
     * 押さえ・印を立て・元を退避すると、<b>消えるのはあちらの元のファイルそのものである。</b>
     * 本物の割り込みは作れないので、順序を手で組む。あちらは本物の押さえ方（{@code pin}）で押さえる。
     */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    @DisplayName("片づけると決めてから消すまでに別の窓が退避しても、控えを消さない")
    void keepsACopyHeldAfterTheCleanupLooked(@TempDir Path directory) throws IOException {
        // 作ったが、まだ押さえていない別の窓の作業場所。
        OutputWorkspace other = leftBehind(directory.resolve("out.pdf"));
        Path place = other.file().getParent();
        FileChannel[] pinned = {null};
        boolean[] ran = {false};

        try {
            OutputWorkspace.discard(place, () -> {
                ran[0] = true;
                try {
                    pinned[0] = OutputWorkspace.pin(place, true);
                } catch (FileAlreadyExistsException refused) {
                    // 片づける側が押さえている。あちらは作業場所を作り直すので、元には触らない。
                    return;
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                other.holdOriginal();
                try {
                    Files.writeString(other.replaced(), "あちらの元のファイル");
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });

            assertTrue(ran[0], "消すと決めた後の口が走っていない。割り込みを組めていない");
            assertTrue(pinned[0] == null || Files.exists(other.replaced()), "別の窓が退避した元を、片づけが消している");
            assertNull(pinned[0], "片づける側が押さえないまま消し始めている。別の窓が押さえて退避できる");
            assertFalse(Files.exists(place), "押さえて片づけた作業場所が残っている");
        } finally {
            if (pinned[0] != null) {
                pinned[0].close();
            }
        }
    }

    @Test
    @DisplayName("書き込み先は、まだ存在しない")
    void offersAPathThatDoesNotExistYet(@TempDir Path directory) {
        // pdf-core は既存の出力を拒む（ErrorCode.OUTPUT_ALREADY_EXISTS）。
        try (OutputWorkspace workspace = OutputWorkspace.nextTo(directory.resolve("out.pdf"), found -> {})) {
            assertFalse(Files.exists(workspace.file()));
        }
    }

    @Test
    @DisplayName("書きかけで終わっても、出力先に何も残らない")
    void leavesNothingBehindWhenTheWriteFails(@TempDir Path directory) throws IOException {
        Path written;
        try (OutputWorkspace workspace = OutputWorkspace.nextTo(directory.resolve("out.pdf"), found -> {})) {
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
        // 保存中に JVM ごと落ち、後始末が走らないまま残った形を作る——開いて書き、閉じない。
        // ★ 名前も中身も手で組まない。片づけるのは pdfjig が作る名前だけなので（#125）、
        //   手で組んだ中身だと、残った理由が「片づけていない」のか「知らない名前だから」なのか読めない。
        OutputWorkspace left = leftBehind(directory.resolve("out.pdf"));
        Path abandoned = left.file().getParent();
        Files.writeString(left.file(), "書きかけ");

        try (OutputWorkspace workspace = OutputWorkspace.nextTo(directory.resolve("out.pdf"), found -> {})) {
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

        try (OutputWorkspace workspace = OutputWorkspace.nextTo(directory.resolve("out.pdf"), found -> {})) {
            assertTrue(Files.exists(otherDirectory));
            assertTrue(Files.exists(lookalike));
            assertTrue(Files.exists(workspace.file().getParent()));
        }
    }

    /**
     * 前の書き出しが落ちて、控えを抱えたまま残った作業場所を作る。
     *
     * <p><b>★ 本物で作る。</b>{@link OutputWorkspace#close} を呼ばなければ、それが
     * <b>JVM ごと落ちた状態そのもの</b>である。<b>名前を手で組み直さない</b>——
     * 組み直すと、{@link OutputWorkspace} が形を変えても気づかないまま緑になる
     * （{@code DocumentWriterTest} の {@code workspaceFor} と同じ理由）。
     *
     * @return 抱えられている控え
     */
    private static Path abandonedCopyIn(Path directory) throws IOException {
        OutputWorkspace abandoned = leftBehind(directory.resolve("out.pdf"));
        abandoned.holdOriginal();
        return Files.writeString(abandoned.replaced(), "元のファイル");
    }

    /**
     * 落ちた書き出しが残した作業場所を作る。<b>名前も中身も pdfjig が作るとおりにする。</b>
     *
     * <p><b>★ 押さえを放す</b>（#222）。プロセスが落ちれば開いていた手は OS が閉じるが、
     * テストは同じプロセスの中なので、放さないと次の書き出しがそれを片づけられない。
     */
    private static OutputWorkspace leftBehind(Path output) {
        OutputWorkspace workspace = OutputWorkspace.nextTo(output, found -> {});
        workspace.leaveBehind();
        return workspace;
    }

    /** フォルダの直下にある名前を並べる。 */
    private static List<String> namesIn(Path directory) throws IOException {
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.map(entry -> entry.getFileName().toString()).sorted().toList();
        }
    }

    /**
     * 元を抱えていない失敗でも、消し損ねた平文は伝える（#184 の門）。
     *
     * <p><b>元の控えがある回だけに載せると、巻き戻せた回や退避の前の失敗で、平文は黙って残る。</b>
     */
    @Test
    @DisplayName("元を抱えていなくても、消し損ねた平文は伝える")
    void reportsLeftoverPlaintextEvenWithoutAHeldOriginal(@TempDir Path directory) throws IOException {
        try (OutputWorkspace workspace = OutputWorkspace.nextTo(directory.resolve("out.pdf"), found -> {})) {
            Path plaintext = workspace.file();
            RuntimeException reported = workspace
                    .failing(new PdfjigException(ErrorCode.IO_FAILURE), plaintext)
                    .orElseThrow();

            PlaintextLeftException left = assertInstanceOf(PlaintextLeftException.class, reported);
            assertEquals(plaintext, left.plaintext());
            assertTrue(workspace
                    .failing(new PdfjigException(ErrorCode.IO_FAILURE), null)
                    .isEmpty());
        }
    }
}
