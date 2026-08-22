package io.github.propagandist.pdfjig.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.propagandist.pdfjig.core.TestPdfs;
import java.nio.file.Path;
import javafx.scene.control.DialogPane;
import javafx.scene.control.PasswordField;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.Start;
import org.testfx.framework.junit5.Stop;
import org.testfx.util.WaitForAsyncUtils;

/**
 * 暗号化された文書を開く経路。
 *
 * <p>パスワードは {@code char[]} でしか扱わない（CLAUDE.md INV-5）。ここで見るのは、
 * その約束が画面の操作を通したときにも守られているかである。入力欄が空になること、
 * 失敗しても入力した値がどこにも出ないことを確かめる。
 */
class PasswordUiTest extends DesktopUiTest {

    private static final String CORRECT = "correct-horse";

    @Start
    void start(Stage stage) {
        setUp(stage);
    }

    @Stop
    void stop() {
        tearDown();
    }

    @Test
    void 暗号化された文書を開くとパスワードを尋ねる(@TempDir Path dir, FxRobot robot) throws Exception {
        askFor(dir, robot);

        assertEquals("パスワードの入力", dialogTitle(robot));
        // 初回は誤りの断りを出さない。まだ何も間違えていない。
        assertEquals(null, dialogPane(robot).getHeaderText());

        clickWhenReady(robot, "#password-cancel");
        WaitForAsyncUtils.waitForFxEvents();

        assertEquals("文書が開かれていません。", statusText(robot));
    }

    @Test
    void 正しいパスワードを入れれば開ける(@TempDir Path dir, FxRobot robot) throws Exception {
        askFor(dir, robot);

        clickWhenReady(robot, "#password-field");
        robot.write(CORRECT);
        clickWhenReady(robot, "#password-unlock");

        waitForNode(robot, "#thumbnail-tile-0");
        WaitForAsyncUtils.waitForFxEvents();

        // 開けたことと、保護されている文書であることの両方を出す。
        assertEquals("1 / 1 ページ（暗号化されています）", statusText(robot));
    }

    @Test
    void 誤ったパスワードならもう一度尋ねる(@TempDir Path dir, FxRobot robot) throws Exception {
        askFor(dir, robot);

        clickWhenReady(robot, "#password-field");
        robot.write("wrong");
        clickWhenReady(robot, "#password-unlock");

        // 開き直しからやらせず、誤りである旨を添えてその場でもう一度尋ねる。
        waitFor(() -> "パスワードが正しくありません。".equals(headerTextOrNull(robot)));

        clickWhenReady(robot, "#password-cancel");
        WaitForAsyncUtils.waitForFxEvents();

        assertEquals("文書が開かれていません。", statusText(robot));
    }

    @Test
    void 尋ね直すとき入力欄は空になっている(@TempDir Path dir, FxRobot robot) throws Exception {
        askFor(dir, robot);

        clickWhenReady(robot, "#password-field");
        robot.write("wrong");
        clickWhenReady(robot, "#password-unlock");
        waitFor(() -> "パスワードが正しくありません。".equals(headerTextOrNull(robot)));

        // 打ち直しは 1 からになる。前の入力が残っていると、消したつもりの文字が混ざる。
        PasswordField field = robot.lookup("#password-field").queryAs(PasswordField.class);
        assertTrue(field.getCharacters().isEmpty(), "入力欄に前の値が残っている");

        clickWhenReady(robot, "#password-cancel");
        WaitForAsyncUtils.waitForFxEvents();
    }

    /**
     * 失敗を伝える文言にパスワードが混ざらないこと。
     *
     * <p>依存ライブラリの例外には入力値が埋め込まれていることがある。画面に出してよいのは
     * {@code ErrorCode} の定型文だけである（CLAUDE.md INV-5）。
     */
    @Test
    void 画面のどこにも入力したパスワードは出ない(@TempDir Path dir, FxRobot robot) throws Exception {
        askFor(dir, robot);

        clickWhenReady(robot, "#password-field");
        robot.write(CORRECT + "-typo");
        clickWhenReady(robot, "#password-unlock");
        waitFor(() -> "パスワードが正しくありません。".equals(headerTextOrNull(robot)));

        DialogPane pane = dialogPane(robot);
        assertTrue(visibleText(pane).stream().noneMatch(text -> text.contains(CORRECT)), "画面に出ている文言にパスワードが混ざっている");

        clickWhenReady(robot, "#password-cancel");
        WaitForAsyncUtils.waitForFxEvents();
    }

    // ── 補助 ────────────────────────────────────────────────────────────────

    /** 暗号化されたフィクスチャを開こうとして、尋ねられるところまで進める。 */
    private void askFor(Path dir, FxRobot robot) throws Exception {
        dialogs.willOpen(TestPdfs.encrypted(dir.resolve("locked.pdf"), CORRECT));
        robot.clickOn("#tool-open");
        waitForNode(robot, "#password-field");
    }

    private static DialogPane dialogPane(FxRobot robot) {
        return robot.lookup("#password-dialog").queryAs(DialogPane.class);
    }

    private static String dialogTitle(FxRobot robot) {
        return ((Stage) dialogPane(robot).getScene().getWindow()).getTitle();
    }

    /** 尋ね直しの見出し。ダイアログが出ていない一瞬もあるので、無ければ {@code null}。 */
    private static String headerTextOrNull(FxRobot robot) {
        return robot.lookup("#password-dialog")
                .tryQuery()
                .map(node -> ((DialogPane) node).getHeaderText())
                .orElse(null);
    }

    /** ダイアログに出ている文字列をすべて集める。 */
    private static java.util.List<String> visibleText(DialogPane pane) {
        return pane.lookupAll(".label, .button").stream()
                .map(node -> node instanceof javafx.scene.control.Labeled labeled ? labeled.getText() : "")
                .filter(text -> text != null && !text.isEmpty())
                .toList();
    }
}
