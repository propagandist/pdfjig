package io.github.propagandist.pdfjig.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
 * （#113 の受け入れ基準がそう定めている）。<b>ここが見ているのは、その直しが
 * 連れてきた 2 つの縛りである。</b>
 */
class DocumentWriterTest {

    @Test
    @DisplayName("既にあるファイルを置き換える")
    void replacesWhatWasThere(@TempDir Path directory) throws IOException {
        Path target = Files.writeString(directory.resolve("out.pdf"), "元のファイル");
        Path source = Files.writeString(directory.resolve("new.pdf"), "新しいファイル");

        DocumentWriter.move(source, target);

        assertEquals("新しいファイル", Files.readString(target));
        assertTrue(Files.notExists(source), "移した元が残るなら、それは移動ではなく複製である");
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
     * <p><b>★★ 原子的な移動だけにすると、ここが赤になる——ただし Windows でだけである。</b>
     * {@code MoveFileEx(..., MOVEFILE_REPLACE_EXISTING)} は<b>置き換え先が開かれていると
     * {@code AccessDeniedException} で断る</b>（2026-08-30、Windows 10 / JDK 21.0.8 で実測）。
     * <b>POSIX の {@code rename(2)} は開かれていても置き換えるので、CI の ubuntu 側では
     * 条件を絞っても緑のままである</b>——<b>この縛りを持っているのは windows 側だけである。</b>
     *
     * <p><b>そして画面から上書き保存するとき、その置き換え先を開いているのは pdfjig 自身である。</b>
     * {@code DocumentSession} が持つ {@code PdfDocument} は PDFBox の
     * {@code RandomAccessReadBufferedFile} 経由で {@link FileChannel} を握り続け、
     * <b>書き出しの元になっている文書は、保存の間に閉じられない</b>
     * （{@code DocumentSession#remove} は外した文書を閉じるが、外したものはもう元ではない）。
     * ここで使う {@link FileChannel#open} は、それと同じ開き方である。
     *
     * <p>だから {@link DocumentWriter#move} は
     * {@link java.nio.file.AtomicMoveNotSupportedException} だけに絞らず、
     * <b>断られた理由を問わず普通の置き換えに落とす。</b>絞ると「開いている文書へ
     * 上書き保存する」が必ず失敗する——<b>{@code docs/HANDOVER.md} 4-4 の 10 番そのものであり、
     * uiTest はその経路を一度も通っていない。</b>
     */
    @Test
    @DisplayName("開いたままの文書へ上書き保存できる")
    void replacesWhileTheTargetIsStillOpen(@TempDir Path directory) throws IOException {
        Path target = Files.writeString(directory.resolve("out.pdf"), "元のファイル");
        Path source = Files.writeString(directory.resolve("new.pdf"), "新しいファイル");

        try (FileChannel held = FileChannel.open(target, StandardOpenOption.READ)) {
            DocumentWriter.move(source, target);

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

            DocumentWriter.move(source, target);

            assertTrue(Files.notExists(source), "移した元が残るなら、それは移動ではなく複製である");
        }
        assertEquals("新しいファイル", Files.readString(target));
    }
}
