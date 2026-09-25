package io.github.propagandist.pdfjig.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * 書いて、ディスクへ届いたかを返す（#219）。
 *
 * <p><b>★ 届いたかどうかそのものは、電源を落とさないと見えない。</b>ここが見るのは
 * <b>「届いたと確かめられた／確かめられなかった」を正しく返すこと</b>と、
 * <b>確かめられなかったときに黙らないこと</b>である。書き出しが届けさせていることは
 * {@code pdf-archtest} が縛る。
 */
class DurableSaveTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("普通のファイルへ書けば、届いたと返す")
    void reportsDurableForAnOrdinaryFile() throws Exception {
        Path output = tempDir.resolve("out.pdf");
        try (PDDocument document =
                Loader.loadPDF(TestPdfs.plain(tempDir.resolve("in.pdf"), 2).toFile())) {
            assertTrue(DurableSave.write(document, output), "普通のファイルへ書いたのに、届いたか確かめられなかったと返している");
        }
        try (PDDocument written = Loader.loadPDF(output.toFile())) {
            assertEquals(2, written.getNumberOfPages(), "書いたものが読めない");
        }
    }

    /**
     * 吐き出しを断る先へ書いても、投げずに「確かめられなかった」と返す。
     *
     * <p><b>Windows の {@code NUL} は、書き込みは受け付けて吐き出しを断る</b>
     * （{@code FlushFileBuffers} が {@code ERROR_INVALID_FUNCTION} を返す。#219 の門で実測）。
     * 吐き出しを受け付けない場所と同じ形である。<b>投げると、以前は通った書き出しが必ず失敗する</b>
     * ——黙って {@code true} を返すと、書けていなかったものを届いたと言う。どちらでもないことを見る。
     */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    @DisplayName("吐き出しを断る先へ書いても投げず、確かめられなかったと返す")
    void reportsNotDurableWhenTheFlushIsRefused() throws Exception {
        try (PDDocument document =
                Loader.loadPDF(TestPdfs.plain(tempDir.resolve("in.pdf"), 1).toFile())) {
            assertFalse(DurableSave.write(document, Path.of("NUL")), "吐き出しを断られたのに、届いたと返している");
        }
    }

    /**
     * 届いたか確かめられなかった書き出しは、警告を出す。出力は残す。
     *
     * <p><b>★★ 黙らない。</b>「確かめられなかった」は「本当に書けていなかった」でもありうる
     * （容量不足の共有フォルダ・傷んだディスク）。<b>置き換える呼ぶ側は、この警告を見て置き換えを止める</b>
     * （{@code pdf-desktop} の {@code DocumentWriter}）。
     */
    @Test
    @DisplayName("届いたか確かめられなかった書き出しは、警告を出す")
    void warnsWhenTheOutputIsNotDurable() throws Exception {
        List<Warning> warnings = new ArrayList<>();
        PdfBoxPageOperations.DocumentSaver unconfirmed = (document, output) -> {
            PdfBoxPageOperations.saveDocument(document, output);
            return false;
        };
        PageOperations operations = new PdfBoxPageOperations(warnings::add, unconfirmed);
        Path input = TestPdfs.plain(tempDir.resolve("in.pdf"), 2);
        Path output = tempDir.resolve("out.pdf");

        operations.rotate(Source.of(input), Map.of(1, Rotation.CLOCKWISE_90), output);

        assertEquals(List.of(Warning.NOT_DURABLE), warnings, "届いたか確かめられなかったのに、黙っている（#219）");
        assertTrue(Files.exists(output), "出力を消している。書けたものを捨てるのは、置き換える呼ぶ側の判断である");
    }
}
