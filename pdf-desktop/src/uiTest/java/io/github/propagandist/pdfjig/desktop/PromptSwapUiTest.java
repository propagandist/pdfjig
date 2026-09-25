package io.github.propagandist.pdfjig.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.propagandist.pdfjig.core.TestPdfs;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.DialogPane;
import javafx.scene.control.MenuItem;
import javafx.scene.input.KeyCode;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.Start;
import org.testfx.framework.junit5.Stop;

/**
 * 窓を挟む操作は、挟んだ後に「同じ文書か」を検め直してから当てる（#133）。
 *
 * <p><b>★★ 窓は入れ子のイベントループである。</b>{@code APPLICATION_MODAL} が止めるのは入力だけで、
 * <b>{@code Platform.runLater} に積まれたものは走る。</b>入れ替える手は {@link MainWindow#open} で、
 * 起動引数とファイルの関連付けの入口である。
 *
 * <p><b>入れ替えを挟む場所は 2 通りある。</b>JavaFX の窓（範囲・区切り・鍵）はそのまま挟め、
 * ファイルとフォルダの選択は {@link StubFileDialogs#whileNextDialogIsOpen} で挟む。
 *
 * <p><b>★ 食い違ったときの扱いは 2 つに分けてある</b>（{@code MainWindow.Held}）。
 * 画面の上の編集は黙って戻り、ファイルへ書く操作は断る。
 */
class PromptSwapUiTest extends DesktopUiTest {

    private static final String KEY = "swap-key";

    @Start
    void start(Stage stage) {
        setUp(stage);
    }

    @Stop
    void stop() {
        tearDown();
    }

    /**
     * <b>★ 入れ替わる先も 3 ページにしてある。</b>枚数が違うと、直す前も
     * {@code PAGE_OUT_OF_RANGE} が画面に出ないまま捨てられ、<b>直す前と後で同じ状態になる。</b>
     */
    @Test
    void 範囲の窓の最中に文書が入れ替わったら当てない(@TempDir Path dir, FxRobot robot) throws Exception {
        openFixture(robot, TestPdfs.withText(dir.resolve("a.pdf"), "A1", "A2", "A3"));
        Path other = TestPdfs.withText(dir.resolve("c.pdf"), "C1", "C2", "C3");

        robot.clickOn("#tool-keep-range");
        waitForNode(robot, "#range-first");
        clickWhenReady(robot, "#range-first");
        robot.press(KeyCode.CONTROL).press(KeyCode.A).release(KeyCode.A).release(KeyCode.CONTROL);
        robot.write("2");

        Platform.runLater(() -> window.open(other));
        waitFor(() -> stage.getTitle().contains("c.pdf"));

        clickWhenReady(robot, "#range-keep");

        assertEquals("3 / 3 ページ", statusText(robot), "確認していない文書の範囲が変わった（#133）");
        assertEquals(List.of("C1", "C2", "C3"), pageTexts(saveAs(robot, dir.resolve("out.pdf"))));
    }

    /**
     * <b>書いても中身は確認した文書のもの</b>だが、画面は別の文書を映しているので、
     * <b>「分割しました」はどちらを分割したのかを取り違えさせる</b>（優先順位 2）。書かずに断る。
     */
    @Test
    void 分割の鍵の窓の最中に文書が入れ替わったら書かずに断る(@TempDir Path dir, FxRobot robot) throws Exception {
        openProtectedFixture(robot, TestPdfs.encrypted(dir.resolve("locked.pdf"), KEY, 3), KEY);
        robot.clickOn("#thumbnail-tile-1");
        robot.clickOn("#tool-toggle-break");
        Path other = TestPdfs.withText(dir.resolve("c.pdf"), "C1", "C2", "C3");
        Path outputDir = Files.createDirectory(dir.resolve("out"));

        dialogs.willChooseFolder(outputDir);
        robot.clickOn("#tool-split");
        clickWhenReady(robot, "#protection-proceed");
        waitForNode(robot, "#password-field");

        Platform.runLater(() -> window.open(other));
        waitFor(() -> stage.getTitle().contains("c.pdf"));

        clickWhenReady(robot, "#password-field");
        robot.write(KEY);
        clickWhenReady(robot, "#password-unlock");

        Optional<Node> notice = dialogButton(robot, "#message-ok");
        assertTrue(notice.isPresent(), "書かなかったことを伝えていない");
        String text = robot.lookup("#message-dialog").queryAs(DialogPane.class).getContentText();
        robot.clickOn(notice.get());
        assertTrue(text.contains("分割しませんでした"), "断りの文言が違う: " + text);
        assertEquals(List.of(), namesIn(outputDir), "入れ替わった後に書き出した（#133）");
    }

    /**
     * <b>★ 2 つ目も足さない。</b>鍵の窓は 1 つごとに出るので、1 つ目の窓で入れ替わると、
     * <b>2 つ目が入れ替わった先へ足される</b>（#133 の門）。{@code locked} は名前順で先に来る。
     */
    @Test
    void 追加の鍵の窓の最中に文書が入れ替わったら残りも足さない(@TempDir Path dir, FxRobot robot) throws Exception {
        openFixture(robot, TestPdfs.withText(dir.resolve("a.pdf"), "A1", "A2"));
        Path other = TestPdfs.withText(dir.resolve("c.pdf"), "C1");

        addFixtures(
                robot,
                TestPdfs.encrypted(dir.resolve("locked.pdf"), KEY, 2),
                TestPdfs.withText(dir.resolve("z.pdf"), "Z1"));
        waitForNode(robot, "#password-field");

        Platform.runLater(() -> window.open(other));
        waitFor(() -> stage.getTitle().contains("c.pdf"));

        clickWhenReady(robot, "#password-field");
        robot.write(KEY);
        clickWhenReady(robot, "#password-unlock");

        assertEquals("1 / 1 ページ", statusText(robot), "確認していない文書に足した（#133）");
    }

    @Test
    void 追加のファイル選択の最中に文書が入れ替わったら足さない(@TempDir Path dir, FxRobot robot) throws Exception {
        openFixture(robot, TestPdfs.withText(dir.resolve("a.pdf"), "A1", "A2"));
        Path other = TestPdfs.withText(dir.resolve("c.pdf"), "C1");
        dialogs.whileNextDialogIsOpen(
                () -> window.open(other), () -> stage.getTitle().contains("c.pdf"));

        addFixtures(robot, TestPdfs.withText(dir.resolve("b.pdf"), "B1"));

        waitFor(() -> stage.getTitle().contains("c.pdf"));
        assertEquals("1 / 1 ページ", statusText(robot), "確認していない文書に足した（#133）");
    }

    /**
     * <b>★ 区切りは状態行にしか出ない</b>ので、区切りの数で見る。入れ替わった先に当てると、
     * 利用者が手で置いた区切りを確認なしに塗り替える。
     */
    @Test
    void 区切りの窓の最中に文書が入れ替わったら当てない(@TempDir Path dir, FxRobot robot) throws Exception {
        openFixture(robot, TestPdfs.plain(dir.resolve("a.pdf"), 4));
        Path other = TestPdfs.plain(dir.resolve("c.pdf"), 4);

        // メニューにしか無い操作である。開いて押す手順は環境で揺れるので、項目を直に実行する。
        MenuItem item = menuItem(robot, "menu-break-every-n").orElseThrow();
        Platform.runLater(item::fire);
        waitForNode(robot, "#page-count-apply");

        Platform.runLater(() -> window.open(other));
        waitFor(() -> stage.getTitle().contains("c.pdf"));

        clickWhenReady(robot, "#page-count-apply");

        assertEquals("4 / 4 ページ", statusText(robot), "確認していない文書に区切りを当てた（#133）");
    }

    /** <b>上書き保存なら、入れ替わった先の元のファイルを開いたまま置き換えうる</b>（優先順位 1）。 */
    @Test
    void 保存先の選択の最中に文書が入れ替わったら書かずに断る(@TempDir Path dir, FxRobot robot) throws Exception {
        openFixture(robot, TestPdfs.withText(dir.resolve("a.pdf"), "A1", "A2"));
        Path other = TestPdfs.withText(dir.resolve("c.pdf"), "C1");
        Path output = dir.resolve("out.pdf");
        dialogs.whileNextDialogIsOpen(
                () -> window.open(other), () -> stage.getTitle().contains("c.pdf"));

        dialogs.willSaveTo(output);
        clickUntilAccepted(robot, "#tool-save", dialogs::savePending);

        Optional<Node> notice = dialogButton(robot, "#message-ok");
        assertTrue(notice.isPresent(), "書かなかったことを伝えていない");
        String text = robot.lookup("#message-dialog").queryAs(DialogPane.class).getContentText();
        robot.clickOn(notice.get());
        assertTrue(text.contains("保存しませんでした"), "断りの文言が違う: " + text);
        assertTrue(Files.notExists(output), "入れ替わった後に書き出した（#133）");
    }
}
