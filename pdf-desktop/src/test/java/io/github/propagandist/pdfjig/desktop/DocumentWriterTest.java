package io.github.propagandist.pdfjig.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 作業場所から出力先への置き換え（{@link DocumentWriter#move}）。
 *
 * <p>PDF は要らない——ここが扱うのはファイルの移動だけであり、中身は関与しない。
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
