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
 * 「対象範囲」2 番目に当たるかを決める。</b>
 *
 * <p><b>★ ここが縛るのは 4 点のうち 2 つである。</b>
 *
 * <ul>
 *   <li><b>既定のボタンと初期フォーカスが中止側であること</b> ✓</li>
 *   <li><b>分割で操作ごとに 1 回だけ問うこと</b> ✓</li>
 *   <li><b>×・Esc・窓の外が中止であること</b>——<b>縛っていない。</b>TestFX から
 *       {@code Alert} の × と Esc を安定して打てない。<b>{@code ButtonData#CANCEL_CLOSE} が
 *       付いていることは型で決まる</b>ので、<b>崩すには意図して外す必要がある</b></li>
 *   <li><b>「次回から表示しない」を置かないこと</b>——<b>縛っていない。</b>
 *       <b>無いものは掴めない。</b>置いた日に赤くする形が書けない</li>
 * </ul>
 *
 * <p><b>何を選ぶと何が起きるかが伝わるかは、人が見る</b>（{@code docs/HANDOVER.md} 4-4 の 17 番）。
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

        // ★★ 同意したことを、もう一度言わない。窓が 2 枚続くのは読まずに閉じる習慣を作る側である。
        assertTrue(robot.lookup("#message-dialog").tryQuery().isEmpty(), "同意したことをもう一度伝えている");

        // ★ 中身まで見る。選んだとおり平文になっている。
        try (PdfDocument written = PdfDocument.open(output)) {
            assertEquals(1, written.pageCount());
        }
    }

    @Test
    void 鍵の要らない文書では問わない(@TempDir Path dir, FxRobot robot) throws Exception {
        // ★★ オーナーパスワードだけの文書は、鍵を打たずに開けている。ここで止める窓を出すと、
        //   本物の機密文書に当たる前に「読まずに続行を押す」習慣ができる（SPEC.md §4.3.1）。
        openFixture(robot, TestPdfs.ownerProtected(dir.resolve("owner.pdf"), "owner", 1));
        WaitForAsyncUtils.waitForFxEvents();

        // ★★ 先に「出ていないこと」を見る。saveAs はファイルができるのを待つので、
        //   もし窓が出ていれば showAndWait が FX スレッドを握ったまま 20 秒で落ち、
        //   下の assert には一度も届かない——読めない落ち方になる（ui-tests.md）。
        Path output = dir.resolve("saved.pdf");
        dialogs.willSaveTo(output);
        clickUntilAccepted(robot, "#tool-save", dialogs::savePending);
        WaitForAsyncUtils.waitForFxEvents();
        assertTrue(robot.lookup("#protection-dialog").tryQuery().isEmpty(), "鍵の要らない文書で窓が出ている");

        waitFor(() -> Files.exists(output) && Files.size(output) > 0);

        // ★★ 問わなかったのだから、保護が落ちたことを伝える口はこれしか無い。
        //   ★ 閉じずに終わると、次のテストのクリックがこのモーダルに飲まれる。
        waitForNode(robot, "#message-dialog");
        clickWhenReady(robot, "#message-ok");
        WaitForAsyncUtils.waitForFxEvents();
    }

    @Test
    void 分割では出力の数によらず一度だけ問う(@TempDir Path dir, FxRobot robot) throws Exception {
        // ★★ N 回出ると「読まずに続行を押す」習慣ができる（SPEC.md §4.3.1）。
        //   3 ページを 1 枚ずつに分けるので、出力は 3 個である。
        dialogs.willOpen(TestPdfs.encrypted(dir.resolve("locked.pdf"), KEY, 3));
        clickUntilAccepted(robot, "#tool-open", dialogs::openPending);
        waitForNode(robot, "#password-field");
        clickWhenReady(robot, "#password-field");
        robot.write(KEY);
        clickWhenReady(robot, "#password-unlock");
        waitForNode(robot, "#thumbnail-tile-0");
        WaitForAsyncUtils.waitForFxEvents();

        Path outputDir = dir.resolve("out");
        dialogs.willChooseFolder(outputDir);
        robot.clickOn("#tool-split-pages");

        waitForNode(robot, "#protection-dialog");
        clickWhenReady(robot, "#protection-proceed");

        // 続行のあと、鍵は出どころごとに 1 回だけ訊かれる（出どころは 1 つ）。
        waitForNode(robot, "#password-field");
        clickWhenReady(robot, "#password-field");
        robot.write(KEY);
        clickWhenReady(robot, "#password-unlock");

        waitFor(() -> Files.isDirectory(outputDir) && namesIn(outputDir).size() == 3);
        WaitForAsyncUtils.waitForFxEvents();

        // ★ 2 度目は出ていない。出ていれば書き出しが止まり、上の待ちが落ちている。
        assertTrue(robot.lookup("#protection-dialog").tryQuery().isEmpty(), "分割で窓が 2 度出ている");

        // ★ 「N 個のファイルを書き出しました」を閉じる。開けたまま終わると、
        //   次のテストのクリックがこのモーダルに飲まれる。
        clickWhenReady(robot, "#message-ok");
        WaitForAsyncUtils.waitForFxEvents();

        // ★★ 同意したことを、もう一度言わない。pdf-core はかたまりの数だけ発するので、
        //   落とす数をそこに合わせていないとここが赤くなる（#29 の門の 2 段目）。
        assertTrue(robot.lookup("#message-dialog").tryQuery().isEmpty(), "同意したことをもう一度伝えている");
    }

    // ── 補助 ────────────────────────────────────────────────────────────────

    /** 鍵の要る文書を、正しい鍵で開くところまで進める。 */
    private void openProtected(Path dir, FxRobot robot) throws Exception {
        openProtectedFixture(robot, TestPdfs.encrypted(dir.resolve("locked.pdf"), KEY), KEY);
        WaitForAsyncUtils.waitForFxEvents();
    }
}
