package io.github.propagandist.pdfjig.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.propagandist.pdfjig.core.PdfDocument;
import io.github.propagandist.pdfjig.core.TestPdfs;
import java.nio.file.Files;
import java.nio.file.Path;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.Start;
import org.testfx.framework.junit5.Stop;
import org.testfx.util.WaitForAsyncUtils;

/**
 * 保護が引き継がれないことを、書き出す前に伝えて選ばせる窓（#29 / #192）。
 *
 * <p><b>★★ 最低条件はどれか 1 つ崩れると、窓を出したまま経路が開く</b>
 * （{@code docs/SPEC.md} §4.3.1）——<b>同意が成立したかどうかが、{@code SECURITY.md}
 * 「対象範囲」2 番目に当たるかを決める。</b>ここで縛るのはその 4 点である。
 */
class ProtectionPromptUiTest extends DesktopUiTest {

    private static final String KEY = "correct-horse";

    @Start
    void start(Stage stage) {
        setUp(stage);
    }

    @Stop
    void stop() {
        tearDown();
    }

    @Test
    void 保護が落ちるなら書き出す前に問う(@TempDir Path dir, FxRobot robot) throws Exception {
        openProtected(dir, robot);

        Path output = dir.resolve("saved.pdf");
        dialogs.willSaveTo(output);
        clickUntilAccepted(robot, "#tool-save", dialogs::savePending);

        waitForNode(robot, "#protection-dialog");

        // ★ どのファイルの保護が落ちるのかを出す。数だけでは辿れない。
        assertTrue(textOf(robot, "#protection-sources").contains("locked.pdf"), "保護が落ちる出どころの名前が出ていない");

        clickWhenReady(robot, "#protection-cancel");
        WaitForAsyncUtils.waitForFxEvents();

        // ★★ 中止したら、鍵も訊かれない。1 文字も打たせずに済む。
        assertTrue(robot.lookup("#password-field").tryQuery().isEmpty(), "中止したのに鍵を訊いている");
        assertTrue(Files.notExists(output), "中止したのに書き出されている");
    }

    @Test
    void 既定のボタンと初期フォーカスは中止側である(@TempDir Path dir, FxRobot robot) throws Exception {
        openProtected(dir, robot);

        dialogs.willSaveTo(dir.resolve("saved.pdf"));
        clickUntilAccepted(robot, "#tool-save", dialogs::savePending);
        waitForNode(robot, "#protection-dialog");

        // ★★ Enter を続けて押しても続行に倒れない（SPEC.md §4.3.1）。
        assertTrue(button(robot, "#protection-cancel").isDefaultButton(), "中止が既定のボタンになっていない");
        assertFalse(button(robot, "#protection-proceed").isDefaultButton(), "続行が既定のボタンになっている");
        waitFor(() -> button(robot, "#protection-cancel").isFocused());

        clickWhenReady(robot, "#protection-cancel");
        WaitForAsyncUtils.waitForFxEvents();
    }

    @Test
    void 続行を選べば平文で書き出される(@TempDir Path dir, FxRobot robot) throws Exception {
        openProtected(dir, robot);

        Path output = dir.resolve("saved.pdf");
        dialogs.willSaveTo(output);
        clickUntilAccepted(robot, "#tool-save", dialogs::savePending);

        waitForNode(robot, "#protection-dialog");
        clickWhenReady(robot, "#protection-proceed");

        // 続行のあとで鍵を訊かれる（#193）。
        waitForNode(robot, "#password-field");
        clickWhenReady(robot, "#password-field");
        robot.write(KEY);
        clickWhenReady(robot, "#password-unlock");

        waitFor(() -> Files.exists(output) && Files.size(output) > 0);
        WaitForAsyncUtils.waitForFxEvents();

        // ★ 中身まで見る。選んだとおり平文になっている。
        try (PdfDocument written = PdfDocument.open(output)) {
            assertEquals(1, written.pageCount());
        }
    }

    @Test
    void 鍵の要らない文書では問わない(@TempDir Path dir, FxRobot robot) throws Exception {
        // ★★ オーナーパスワードだけの文書は、鍵を打たずに開けている。ここで止める窓を出すと、
        //   本物の機密文書に当たる前に「読まずに続行を押す」習慣ができる（SPEC.md §4.3.1）。
        dialogs.willOpen(TestPdfs.ownerProtected(dir.resolve("owner.pdf"), "owner", 1));
        robot.clickOn("#tool-open");
        waitForNode(robot, "#thumbnail-tile-0");
        WaitForAsyncUtils.waitForFxEvents();

        Path output = saveAs(robot, dir.resolve("saved.pdf"));

        assertTrue(Files.exists(output), "書き出されていない");
        assertTrue(robot.lookup("#protection-dialog").tryQuery().isEmpty(), "鍵の要らない文書で窓が出ている");
    }

    // ── 補助 ────────────────────────────────────────────────────────────────

    /** 鍵の要る文書を、正しい鍵で開くところまで進める。 */
    private void openProtected(Path dir, FxRobot robot) throws Exception {
        dialogs.willOpen(TestPdfs.encrypted(dir.resolve("locked.pdf"), KEY));
        robot.clickOn("#tool-open");
        waitForNode(robot, "#password-field");
        clickWhenReady(robot, "#password-field");
        robot.write(KEY);
        clickWhenReady(robot, "#password-unlock");
        waitForNode(robot, "#thumbnail-tile-0");
        WaitForAsyncUtils.waitForFxEvents();
    }
}
