package io.github.propagandist.pdfjig.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.propagandist.pdfjig.core.ErrorCode;
import io.github.propagandist.pdfjig.core.PdfjigException;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.DosFileAttributeView;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * 出力先の見分け（{@link DocumentWriter#replacesAnyOf}）と、
 * 作業場所から出力先への置き換え（{@link DocumentWriter#move}）。
 *
 * <p>PDF は要らない——ここが扱うのはファイルの移動と見分けだけであり、中身は関与しない。
 *
 * <p><b>★ #113 の欠陥そのもの（Windows で「先に消してから動かす」になっていたこと）は、
 * ここでは赤にできない。</b> 2 段の間に割り込む再現を用意できないためであり、
 * <b>1 回の {@code MoveFileEx} になっていることは実装を読んで確かめる</b>
 * （#113 の受け入れ基準がそう定めている）。
 *
 * <p><b>★★ #119 の側は赤にできる。</b>「元をどけてから入れる」形は<b>退避したものが
 * 残ることで外から見える</b>ので、割り込みを再現しなくても
 * {@link #setsTheOriginalAsideInsteadOfReplacingIt} が直す前の実装で落ちる。
 * <b>置き換えで消してしまうなら、戻すものが無い。</b>
 */
class DocumentWriterTest {

    @Test
    @DisplayName("既にあるファイルを置き換える")
    void replacesWhatWasThere(@TempDir Path directory) throws IOException {
        Path target = Files.writeString(directory.resolve("out.pdf"), "元のファイル");
        Path source = Files.writeString(directory.resolve("new.pdf"), "新しいファイル");

        DocumentWriter.move(source, target, workspaceFor(target));

        assertEquals("新しいファイル", Files.readString(target));
        assertTrue(Files.notExists(source), "移した元が残るなら、それは移動ではなく複製である");
    }

    /**
     * 元をどけてから入れる。置き換えを通らない。
     *
     * <p><b>★★ これが #119 の直しそのものを見ている。</b>置き換え（{@code REPLACE_EXISTING}）で
     * 済ませていると<b>退避先には何も残らない</b>ので、直す前の実装ではここが落ちる。
     *
     * <p><b>控えが残ることは、実装の都合ではなく約束である。</b>2 本の改名の間で落ちても
     * 元が実体として残る、という保証はこれと同じものを見ている——<b>控えが無いなら、
     * 落ちたときに戻すものが無い。</b>
     */
    @Test
    @DisplayName("元をどけてから入れる。置き換えを通らない")
    void setsTheOriginalAsideInsteadOfReplacingIt(@TempDir Path directory) throws IOException {
        Path target = Files.writeString(directory.resolve("out.pdf"), "元のファイル");
        Path source = Files.writeString(directory.resolve("new.pdf"), "新しいファイル");
        OutputWorkspace workspace = workspaceFor(target);

        DocumentWriter.move(source, target, workspace);

        assertEquals("新しいファイル", Files.readString(target));
        assertEquals("元のファイル", Files.readString(workspace.replaced()), "元を退避せずに置き換えている。落ちたときに戻すものが無い（#119）");
        PdfjigException anyFailure = new PdfjigException(ErrorCode.IO_FAILURE);
        assertSame(anyFailure, workspace.failing(anyFailure), "置き換えが済んだのに抱えたままである。次の失敗で「元は作業場所にしか無い」と伝えることになる（#124）");
    }

    /**
     * 入れ替えに失敗したら、元が戻る。
     *
     * <p><b>書けたものが無い状態で頼む。</b>{@link Files#move} は原子的なほうも普通のほうも
     * {@code NoSuchFileException} で落ちるので、<b>退避まで済んで入れ替えだけが失敗した状態</b>を
     * そのまま作れる。<b>実際にそこへ落ちるのは、退避の後に出力先が書けなくなった場合である。</b>
     */
    @Test
    @DisplayName("入れ替えに失敗したら、元が戻る")
    void putsTheOriginalBackWhenTheSwapFails(@TempDir Path directory) throws IOException {
        Path target = Files.writeString(directory.resolve("out.pdf"), "元のファイル");
        OutputWorkspace workspace = workspaceFor(target);
        Path neverWritten = directory.resolve("nothing-was-written.pdf");

        assertThrows(PdfjigException.class, () -> DocumentWriter.move(neverWritten, target, workspace));

        assertEquals("元のファイル", Files.readString(target), "巻き戻していない。元は退避先にしか無い（#119）");
        assertTrue(Files.notExists(workspace.replaced()), "戻したのに控えが残るなら、それは移動ではなく複製である");
        PdfjigException anyFailure = new PdfjigException(ErrorCode.IO_FAILURE);
        assertSame(anyFailure, workspace.failing(anyFailure), "元の場所へ返したのに抱えたままである。出力先にあるものを「作業場所にしか無い」と伝えることになる（#124）");
    }

    @Test
    @DisplayName("出力先がまだ無ければ、退避するものが無い")
    void hasNothingToSetAsideWhenTheOutputIsNew(@TempDir Path directory) throws IOException {
        Path target = directory.resolve("out.pdf");
        Path source = Files.writeString(directory.resolve("new.pdf"), "新しいファイル");
        OutputWorkspace workspace = workspaceFor(target);

        DocumentWriter.move(source, target, workspace);

        assertEquals("新しいファイル", Files.readString(target));
        assertTrue(Files.notExists(workspace.replaced()), "退避するものが無いのに何かを置いている");
    }

    /**
     * 読み取り専用の出力先には手を出さない。
     *
     * <p><b>★★ 改名は読み取り専用属性を無視して通る。</b>見ずに退避すると、
     * <b>利用者が読み取り専用にした文書が警告なく置き換わり、属性まで落ちる</b>——
     * 2026-09-01 に Windows 10 / JDK 21.0.8 で実測した（退避も入れ替えも成功し、
     * 出来上がったファイルは読み取り専用ではなくなっていた）。
     * <b>#119 より前は置き換えが {@code AccessDenied} で断られ、保存そのものが失敗していた</b>ので、
     * これは直しが連れてきた振る舞いの変化である（{@code CLAUDE.md} 優先順位 2）。
     *
     * <p><b>★ 縛れるのは Windows でだけである。</b>POSIX の書き込み権限は
     * {@code @TempDir} の下では立て直せる（所有者は書き込み権を付け直せる）ため、
     * <b>読み取り専用属性を持つ側でしか同じ条件を作れない。</b>
     */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    @DisplayName("読み取り専用の出力先には手を出さない")
    void refusesToTouchAReadOnlyOutput(@TempDir Path directory) throws IOException {
        Path target = Files.writeString(directory.resolve("out.pdf"), "元のファイル");
        Files.getFileAttributeView(target, DosFileAttributeView.class).setReadOnly(true);
        Path source = Files.writeString(directory.resolve("new.pdf"), "新しいファイル");
        OutputWorkspace workspace = workspaceFor(target);

        assertThrows(PdfjigException.class, () -> DocumentWriter.move(source, target, workspace));

        assertEquals("元のファイル", Files.readString(target), "読み取り専用の文書が黙って置き換わっている");
        assertTrue(
                Files.getFileAttributeView(target, DosFileAttributeView.class)
                        .readAttributes()
                        .isReadOnly(),
                "読み取り専用のままでなければ、利用者の意思表示を落としている");
    }

    @Test
    @DisplayName("出どころそのものへ書き出すなら、置き換えである")
    void seesThatItReplacesTheSourceItself(@TempDir Path directory) throws IOException {
        Path source = Files.writeString(directory.resolve("doc.pdf"), "元のファイル");

        assertTrue(DocumentWriter.replacesAnyOf(List.of(source), source));
    }

    @Test
    @DisplayName("出どころが複数でも、どれか 1 つに当たれば置き換えである")
    void seesThatItReplacesOneOfSeveralSources(@TempDir Path directory) throws IOException {
        Path first = Files.writeString(directory.resolve("a.pdf"), "1 つ目");
        Path second = Files.writeString(directory.resolve("b.pdf"), "2 つ目");

        assertTrue(DocumentWriter.replacesAnyOf(List.of(first, second), second));
    }

    /**
     * 名前が違っても、同じ実体なら置き換えである。
     *
     * <p><b>★ ハードリンクで見る。</b>{@code sub/..} のような書き方では足りない——
     * {@link Path#normalize()} が字面だけで畳むので、<b>名前で比べる実装でも通ってしまい、
     * {@link Files#isSameFile} を選んだ理由を守れない。</b>
     * <b>ハードリンクは字面が一致しようがないので、実体で比べていなければ落ちる。</b>
     */
    @Test
    @DisplayName("名前が違っても、同じ実体なら置き換えである")
    void seesThroughADifferentNameForTheSameFile(@TempDir Path directory) throws IOException {
        Path source = Files.writeString(directory.resolve("doc.pdf"), "元のファイル");
        Path sameFileOtherName = Files.createLink(directory.resolve("link.pdf"), source);

        assertTrue(DocumentWriter.replacesAnyOf(List.of(source), sameFileOtherName));
    }

    /**
     * 実体で比べられなければ、置き換えであるとみなす。
     *
     * <p><b>★★ 迷ったら安全側に倒す。</b>外したときの損は「要らない開き直しが 1 回走る」だけで、
     * 取り違えたときの損は<b>同じ変換が二重に掛かって文書が壊れること</b>である（#118）。
     */
    @Test
    @DisplayName("実体で比べられなければ、置き換えであるとみなす")
    void treatsAnUncomparablePairAsAReplacement(@TempDir Path directory) throws IOException {
        Path output = Files.writeString(directory.resolve("out.pdf"), "書き出し先");
        // 出どころが消えていれば isSameFile は投げる。開いている文書では起きないが、
        // 起きたときにどちらへ倒すかがここの関心事である。
        Path vanished = directory.resolve("gone.pdf");

        assertTrue(DocumentWriter.replacesAnyOf(List.of(vanished), output));
    }

    @Test
    @DisplayName("別のファイルへ書き出すなら、置き換えではない")
    void seesThatANewNameIsNotAReplacement(@TempDir Path directory) throws IOException {
        Path source = Files.writeString(directory.resolve("doc.pdf"), "元のファイル");
        Path other = Files.writeString(directory.resolve("other.pdf"), "別のファイル");

        assertFalse(DocumentWriter.replacesAnyOf(List.of(source), other));
    }

    @Test
    @DisplayName("まだ無いファイルへ書き出すなら、置き換えではない")
    void seesThatAFileThatDoesNotExistYetIsNotAReplacement(@TempDir Path directory) throws IOException {
        Path source = Files.writeString(directory.resolve("doc.pdf"), "元のファイル");

        assertFalse(DocumentWriter.replacesAnyOf(List.of(source), directory.resolve("new.pdf")));
    }

    /**
     * 開いたままの文書へ上書き保存できる。
     *
     * <p><b>★★ この経路が #119 の中心である。</b>画面から上書き保存するとき、
     * <b>置き換え先を開いているのは pdfjig 自身である</b>——{@code DocumentSession} が持つ
     * {@code PdfDocument} は PDFBox の {@code RandomAccessReadBufferedFile} 経由で
     * {@link FileChannel} を握り続け、<b>書き出しの元になっている文書は、保存の間に閉じられない</b>
     * （{@code DocumentSession#remove} は外した文書を閉じるが、外したものはもう元ではない）。
     * そして {@code MoveFileEx(..., MOVEFILE_REPLACE_EXISTING)} は<b>置き換え先が開かれていると
     * {@code AccessDeniedException} で断る</b>（2026-08-30、Windows 10 / JDK 21.0.8 で実測）ので、
     * <b>#113 の直しはこの経路にだけ届いていなかった。</b>
     *
     * <p><b>★★ ここで使う {@link FileChannel#open} は、PDFBox 3.0.5 と同じ開き方である。</b>
     * <b>そこが要である</b>——{@link Files#move} での改名は
     * <b>{@code FILE_SHARE_DELETE} を持つ開き方でなければ共有違反で落ちる</b>。
     * <b>{@code java.io.RandomAccessFile} で開くと、退避そのものが落ちる</b>（2026-08-31 実測）。
     * <b>だから PDFBox が開き方を変えれば、上書き保存は壊れるのにここは緑のままである</b>
     * ——それを見るのは {@code OverwriteSaveUiTest} の側である。
     *
     * <p><b>★ POSIX の {@code rename(2)} は開かれていても通るので、CI の ubuntu 側では
     * どう書いても緑になる</b>——<b>この縛りを持っているのは windows 側だけである。</b>
     */
    @Test
    @DisplayName("開いたままの文書へ上書き保存できる")
    void replacesWhileTheTargetIsStillOpen(@TempDir Path directory) throws IOException {
        Path target = Files.writeString(directory.resolve("out.pdf"), "元のファイル");
        Path source = Files.writeString(directory.resolve("new.pdf"), "新しいファイル");

        try (FileChannel held = FileChannel.open(target, StandardOpenOption.READ)) {
            DocumentWriter.move(source, target, workspaceFor(target));

            // 掴んだ実体は置き換わらず、名前の指す先だけが変わる。サムネイルが古い絵を
            // 出し続けるのはこのためであり、保存が失敗したことを意味しない。
            assertEquals("元のファイル".getBytes(StandardCharsets.UTF_8).length, held.size());
        }

        assertEquals("新しいファイル", Files.readString(target), "開いている文書へ上書き保存できなくなっている");
    }

    /**
     * 原子的な移動を断られても書き出せる。
     *
     * <p><b>★ これは #113 の欠陥を見るテストではない。</b>修正前も緑になる——
     * {@code ATOMIC_MOVE} を頼んでいなければ断られようがないからである。
     * <b>見ているのは「頼むようにしたあとフォールバックを外す」変更</b>であり、
     * 外れると原子的な移動を支えないファイルシステムの上で<b>書き出しそのものが失敗する。</b>
     *
     * <p><b>★ 作り方は production と違う。</b>移動元と移動先の provider が違うと
     * {@code CopyMoveHelper#moveToForeignTarget} が必ず
     * {@link java.nio.file.AtomicMoveNotSupportedException} を投げるので、それで代えている。
     * <b>production ではこの例外は起きない</b>——{@code OutputWorkspace} が作業場所を
     * 出力先と同じフォルダに作るため、Windows で唯一の条件（{@code ERROR_NOT_SAME_DEVICE}）に
     * 当たりようがない。<b>だからここが守っているのは「フォールバックがあること」であって、
     * 「production のフォールバックが正しいこと」ではない。</b>
     */
    @Test
    @DisplayName("原子的な移動を断られても書き出せる")
    void movesEvenWhenAtomicIsRefused(@TempDir Path directory) throws IOException {
        Path target = Files.writeString(directory.resolve("out.pdf"), "元のファイル");

        Path zip = directory.resolve("foreign.zip");
        try (FileSystem foreign = FileSystems.newFileSystem(zip, Map.of("create", "true"))) {
            Path source = Files.writeString(foreign.getPath("/new.pdf"), "新しいファイル");

            DocumentWriter.move(source, target, workspaceFor(target));

            assertTrue(Files.notExists(source), "移した元が残るなら、それは移動ではなく複製である");
        }
        assertEquals("新しいファイル", Files.readString(target));
    }

    /**
     * 作業場所。
     *
     * <p><b>★ 本物を使う。</b>作業場所の名前も控えの名前も印の名前も {@link OutputWorkspace} の
     * private な決めごとであり、<b>ここで組み直すと、あちらが形を変えても気づかないまま緑になる。</b>
     *
     * <p><b>閉じない。</b>{@code @TempDir} が片づける。ここで見たいのは
     * {@link DocumentWriter#move} だけで、作業場所の後始末は別のテストが持つ
     * （{@code OutputWorkspaceTest}）。
     */
    private static OutputWorkspace workspaceFor(Path target) {
        return OutputWorkspace.nextTo(target);
    }
}
