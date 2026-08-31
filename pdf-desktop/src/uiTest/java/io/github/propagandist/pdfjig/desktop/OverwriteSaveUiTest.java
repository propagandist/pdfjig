package io.github.propagandist.pdfjig.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.propagandist.pdfjig.core.PdfDocument;
import io.github.propagandist.pdfjig.core.TestPdfs;
import java.nio.file.Path;
import java.util.List;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.Start;
import org.testfx.framework.junit5.Stop;

/**
 * 開いている文書へ、それ自身の名前で上書き保存する。
 *
 * <p><b>★★ この経路は #118 まで自動テストを 1 本も通っていなかった</b>——
 * {@code DesktopUiTest#saveAs} が既存ファイルを待てず（{@link DesktopUiTest#saveOver} の Javadoc）、
 * 呼び出し元 5 つはすべて新規パスを渡していた。<b>手元でも CI でも全部緑のまま、
 * 保存するたびに文書が壊れていた。</b>
 *
 * <p><b>見るのは「2 回保存しても結果が変わらないこと」である。</b>
 * {@code assemble} は毎回ディスクから読み直すので、出どころが書き出したものに入れ替わったまま
 * 同じ指定をもう一度当てると<b>同じ変換が二重に掛かる。</b>
 */
class OverwriteSaveUiTest extends DesktopUiTest {

    @Start
    void start(Stage stage) {
        setUp(stage);
    }

    @Stop
    void stop() {
        tearDown();
    }

    @Test
    void 上書き保存を繰り返しても回転が増えていかない(@TempDir Path dir, FxRobot robot) throws Exception {
        Path document = TestPdfs.plain(dir.resolve("doc.pdf"), 3);
        openFixture(robot, document);

        robot.clickOn("#thumbnail-tile-0");
        robot.clickOn("#tool-rotate-right");

        saveOver(robot, document);
        assertEquals(List.of(90, 0, 0), TestPdfs.rotationsOf(document), "1 回目が既に違う");

        // 何も操作せずにもう一度保存する。利用者から見て何も変わらないはずの操作である。
        saveOver(robot, document);
        assertEquals(List.of(90, 0, 0), TestPdfs.rotationsOf(document), "保存するたびに 90 度ずつ回っている（#118）");

        // 3 回目まで見る。2 回目だけを見ると「180 で止まる」と読めてしまう。
        saveOver(robot, document);
        assertEquals(List.of(90, 0, 0), TestPdfs.rotationsOf(document), "押すたびに回り続けている（#118）");
    }

    @Test
    void 削除したあと上書き保存を繰り返してもページが減っていかない(@TempDir Path dir, FxRobot robot) throws Exception {
        Path document = TestPdfs.withText(dir.resolve("doc.pdf"), "P1", "P2", "P3");
        openFixture(robot, document);

        robot.clickOn("#thumbnail-tile-1");
        robot.clickOn("#tool-delete");

        saveOver(robot, document);
        try (PdfDocument saved = PdfDocument.open(document)) {
            assertEquals(2, saved.pageCount(), "1 回目が既に違う");
        }

        // 寄せ直していないと、2 ページの文書に 3 番を要求して PAGE_OUT_OF_RANGE で止まる。
        // そのときは保存そのものが失敗するので、更新時刻が進まず saveOver が待ちきれずに落ちる。
        saveOver(robot, document);
        try (PdfDocument saved = PdfDocument.open(document)) {
            assertEquals(2, saved.pageCount(), "2 回目でページが減っている（#118）");
        }
    }

    /**
     * 別の名前へ保存したときは寄せ直さない。
     *
     * <p><b>元のファイルは変わっていないので、いまの並びが正しい。</b>
     * 寄せ直すと「名前を付けて保存」が作業対象を切り替えることになり、
     * <b>#118 の範囲を超えた挙動の変更になる。</b>
     */
    @Test
    void 別の名前へ保存したときは元の文書のままである(@TempDir Path dir, FxRobot robot) throws Exception {
        Path document = TestPdfs.plain(dir.resolve("doc.pdf"), 3);
        openFixture(robot, document);

        robot.clickOn("#thumbnail-tile-0");
        robot.clickOn("#tool-rotate-right");

        assertEquals(List.of(90, 0, 0), TestPdfs.rotationsOf(saveAs(robot, dir.resolve("out1.pdf"))));
        // 同じ並びのまま別の名前へもう一度。元の doc.pdf を見ているので結果は変わらない。
        assertEquals(List.of(90, 0, 0), TestPdfs.rotationsOf(saveAs(robot, dir.resolve("out2.pdf"))));
        assertEquals(List.of(0, 0, 0), TestPdfs.rotationsOf(document), "別の名前へ保存したのに元が書き換わっている");
    }
}
