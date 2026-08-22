package io.github.propagandist.pdfjig.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
 * AI が無い状態で画面が成り立つこと（CLAUDE.md INV-3）。
 *
 * <p>既定は {@link io.github.propagandist.pdfjig.ai.NoOpProvider} であり、API キーが
 * 未設定でも起動し、AI 以外のすべての機能が使えなければならない。
 *
 * <p><b>この版には AI 機能そのものが入っていない。</b> したがってここで確かめられるのは
 * 「AI 無しで一連の操作が通ること」と「無いことを画面が正直に伝えること」の 2 つである。
 * AI を使う画面が入った時点で、その入口が {@code isAvailable()} で隠れることを
 * ここに足すこと。
 *
 * <p>使える側の表示は {@link AiPresenceUiTest} が見る。プロバイダは画面を組み立てる前に
 * 決まるため、1 つのテストクラスの中では切り替えられない。
 */
class AiAbsenceUiTest extends DesktopUiTest {

    @Start
    void start(Stage stage) {
        setUp(stage);
    }

    @Stop
    void stop() {
        tearDown();
    }

    @Test
    void AIが無くても開いて編集して保存できる(@TempDir Path dir, FxRobot robot) throws Exception {
        openFixture(robot, TestPdfs.plain(dir.resolve("doc.pdf"), 3));

        robot.clickOn("#thumbnail-tile-0");
        robot.clickOn("#tool-rotate-right");
        robot.clickOn("#thumbnail-tile-2");
        robot.clickOn("#tool-delete");

        Path output = saveAs(robot, dir.resolve("out.pdf"));

        assertEquals(List.of(90, 0), TestPdfs.rotationsOf(output));
    }

    @Test
    void AIが無いことをバージョン情報に出す(FxRobot robot) throws Exception {
        openAbout(robot);

        // 「無効」とは書かない。無効は「有効にできるが今は切ってある」と読め、
        // 利用者は在りもしない設定を探すことになる（AppInfo#aiStatus）。
        assertEquals("AI 機能: この版には含まれていません", textOf(robot, "#about-ai"));

        clickWhenReady(robot, "#about-close");
    }

    // ── 補助 ────────────────────────────────────────────────────────────────

    private void openAbout(FxRobot robot) throws Exception {
        // メニュー項目は Node ではないため id では掴めない。ここだけは文言で辿る。
        robot.clickOn("ヘルプ");
        robot.clickOn(AppInfo.NAME + " について");
        waitForNode(robot, "#about-ai");
    }
}
