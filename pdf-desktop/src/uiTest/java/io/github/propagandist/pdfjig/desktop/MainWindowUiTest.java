package io.github.propagandist.pdfjig.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import org.testfx.util.WaitForAsyncUtils;

/** 開く・回す・消す・足す・保存するという、日々の操作をひととおり通す。 */
class MainWindowUiTest extends DesktopUiTest {

    @Start
    void start(Stage stage) {
        setUp(stage);
    }

    @Stop
    void stop() {
        tearDown();
    }

    // ── 開いていないとき ────────────────────────────────────────────────────

    @Test
    void 文書を開いていなければページへの操作は押せない(FxRobot robot) {
        for (String id : new String[] {
            "#tool-save",
            "#tool-delete",
            "#tool-rotate-right",
            "#tool-rotate-left",
            "#tool-keep-range",
            "#tool-toggle-break",
            "#tool-reset",
            "#tool-add",
            "#tool-split",
            "#tool-split-pages"
        }) {
            assertTrue(button(robot, id).isDisabled(), id + " が押せてしまう");
        }
    }

    @Test
    void 文書がなくても開くボタンは押せる(FxRobot robot) {
        assertFalse(button(robot, "#tool-open").isDisabled());
    }

    @Test
    void 文書がなければその旨を出す(FxRobot robot) {
        assertEquals("文書が開かれていません。", statusText(robot));
    }

    // ── 開く ────────────────────────────────────────────────────────────────

    @Test
    void 開くとページが並び枚数が出る(@TempDir Path dir, FxRobot robot) throws Exception {
        openFixture(robot, TestPdfs.plain(dir.resolve("doc.pdf"), 3));

        assertEquals("3 / 3 ページ", statusText(robot));
        // 仮想化された一覧でも、先頭の行は必ず出ている。
        assertTrue(robot.lookup("#thumbnail-tile-0").tryQuery().isPresent());
        assertTrue(robot.lookup("#thumbnail-tile-2").tryQuery().isPresent());
    }

    @Test
    void 開くと操作が押せるようになる(@TempDir Path dir, FxRobot robot) throws Exception {
        openFixture(robot, TestPdfs.plain(dir.resolve("doc.pdf"), 2));

        assertFalse(button(robot, "#tool-save").isDisabled());
        assertFalse(button(robot, "#tool-delete").isDisabled());
        assertFalse(button(robot, "#tool-rotate-right").isDisabled());
    }

    // ── 編集して保存する ────────────────────────────────────────────────────

    /**
     * 入出力の往復をひととおり通す 1 本。
     *
     * <p>回した向きが実際に書き出された PDF に入っているところまで見る。画面の上で
     * 何かが変わったことだけを確かめても、ファイルが正しい保証にはならない。
     */
    @Test
    void 回転して保存すると向きがファイルに残る(@TempDir Path dir, FxRobot robot) throws Exception {
        openFixture(robot, TestPdfs.plain(dir.resolve("doc.pdf"), 3));

        robot.clickOn("#thumbnail-tile-0");
        robot.clickOn("#tool-rotate-right");

        Path output = saveAs(robot, dir.resolve("out.pdf"));

        assertEquals(List.of(90, 0, 0), TestPdfs.rotationsOf(output));
    }

    @Test
    void 左に回すと反対向きになる(@TempDir Path dir, FxRobot robot) throws Exception {
        openFixture(robot, TestPdfs.plain(dir.resolve("doc.pdf"), 1));

        robot.clickOn("#thumbnail-tile-0");
        robot.clickOn("#tool-rotate-left");

        Path output = saveAs(robot, dir.resolve("out.pdf"));

        assertEquals(List.of(270), TestPdfs.rotationsOf(output));
    }

    @Test
    void 削除して保存するとそのページが消える(@TempDir Path dir, FxRobot robot) throws Exception {
        openFixture(robot, TestPdfs.withText(dir.resolve("doc.pdf"), "P1", "P2", "P3"));

        robot.clickOn("#thumbnail-tile-1");
        robot.clickOn("#tool-delete");

        assertEquals("2 / 3 ページ（未保存の変更があります）", statusText(robot));

        Path output = saveAs(robot, dir.resolve("out.pdf"));

        try (PdfDocument saved = PdfDocument.open(output)) {
            assertEquals(2, saved.pageCount());
        }
    }

    @Test
    void 保存の既定名は開いたファイルから作る(@TempDir Path dir, FxRobot robot) throws Exception {
        openFixture(robot, TestPdfs.plain(dir.resolve("報告書.pdf"), 1));

        // 保存先を仕込まずに押す。取り消したのと同じ扱いになり、何も書き出されない。
        robot.clickOn("#tool-save");
        WaitForAsyncUtils.waitForFxEvents();

        assertEquals("報告書-edited.pdf", dialogs.lastSuggestedName());
    }

    @Test
    void 編集を元に戻すと開いた直後の並びに返る(@TempDir Path dir, FxRobot robot) throws Exception {
        openFixture(robot, TestPdfs.plain(dir.resolve("doc.pdf"), 3));

        robot.clickOn("#thumbnail-tile-0");
        robot.clickOn("#tool-delete");
        assertEquals("2 / 3 ページ（未保存の変更があります）", statusText(robot));

        robot.clickOn("#tool-reset");

        assertEquals("3 / 3 ページ", statusText(robot));
    }

    // ── ファイルを足す ──────────────────────────────────────────────────────

    @Test
    void 足したページは並びの末尾に付く(@TempDir Path dir, FxRobot robot) throws Exception {
        openFixture(robot, TestPdfs.plain(dir.resolve("doc.pdf"), 2));

        dialogs.willOpenMultiple(TestPdfs.plain(dir.resolve("more.pdf"), 3));
        robot.clickOn("#tool-add");
        WaitForAsyncUtils.waitForFxEvents();

        assertEquals("5 / 5 ページ（2 ファイル）", statusText(robot));
    }
}
