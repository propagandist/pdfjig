package io.github.propagandist.pdfjig.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.propagandist.pdfjig.core.TestPdfs;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.Start;
import org.testfx.framework.junit5.Stop;
import org.testfx.util.WaitForAsyncUtils;

/**
 * 閉じたら消えるものがあるとき、消える前に一度そう言う（#171）。
 *
 * <p><b>★★ 入口は 3 つある</b>——終了（メニューと窓の ×）、閉じる、別の PDF を開く。
 * <b>確認は 3 か所が持ち</b>、{@code adopt} と {@code closeSession} には置かない
 * （あちらは窓が消えた後の片づけからも呼ばれる）。
 *
 * <p><b>★★ 判定の穴が 3 つあった</b>——区切り、ファイルの追加、ファイルの取り外しは
 * {@code PageOrder#modified()} に出ない。<b>1 つずつ縛る。</b>
 *
 * <p><b>未編集で閉じる筋は {@code QuitDuringSaveUiTest} が持つ</b>（「走っていなければ……そのまま通る」の 2 本）。
 * <b>窓が出るとあちらが待ちきれずに落ちる</b>ので、「毎回訊く」に化けたらそこで分かる。
 */
class DiscardConfirmUiTest extends DesktopUiTest {

    private final HeldTasks held = new HeldTasks();

    @Override
    BackgroundTasks tasks() {
        return new BackgroundTasks(held);
    }

    @Start
    void start(Stage stage) {
        setUp(stage);
    }

    @Stop
    void stop() {
        held.release();
        tearDown();
    }

    /** 並べ替えて「終了」すると訊く。キャンセルすれば閉じず、状態行も変わらない。 */
    @Test
    void 編集して終了すると訊きキャンセルなら閉じない(@TempDir Path dir, FxRobot robot) throws Exception {
        openFixture(robot, TestPdfs.plain(dir.resolve("doc.pdf"), 3));
        rotate(robot);
        String before = statusText(robot);

        quitFromMenu(robot);
        clickWhenReady(robot, "#discard-cancel");

        assertStillShowing();
        assertEquals(before, statusText(robot), "断ったのに状態が変わっている");
    }

    /** 「保存せずに終了」を選べば閉じる。 */
    @Test
    void 保存せずに終了を選べば閉じる(@TempDir Path dir, FxRobot robot) throws Exception {
        openFixture(robot, TestPdfs.plain(dir.resolve("doc.pdf"), 3));
        rotate(robot);

        quitFromMenu(robot);
        clickWhenReady(robot, "#discard-ok");

        waitFor(() -> !stage.isShowing());
    }

    /**
     * 窓の × でも訊く。
     *
     * <p><b>★ 入口を数え上げる</b>（#134 と同じ）。メニューと × は同じ 1 か所を通るが、
     * <b>そこへ繋ぐ口は 2 つある。</b>
     */
    @Test
    void 窓の閉じるボタンでも訊く(@TempDir Path dir, FxRobot robot) throws Exception {
        openFixture(robot, TestPdfs.plain(dir.resolve("doc.pdf"), 3));
        rotate(robot);

        quitFromWindowButton();
        clickWhenReady(robot, "#discard-cancel");

        assertStillShowing();
    }

    /**
     * 既定のボタンと初期フォーカスは断る側である。Enter を続けて押しても捨てない。
     *
     * <p><b>★★ 素の {@code Alert} は OK が既定である</b>——Enter で捨てる側が押せる。
     */
    @Test
    void 既定のボタンと初期フォーカスは断る側である(@TempDir Path dir, FxRobot robot) throws Exception {
        openFixture(robot, TestPdfs.plain(dir.resolve("doc.pdf"), 3));
        rotate(robot);

        quitFromMenu(robot);
        waitForNode(robot, "#discard-cancel");
        Button cancel = button(robot, "#discard-cancel");
        assertTrue(cancel.isDefaultButton(), "断る側が既定のボタンになっていない");
        assertFalse(button(robot, "#discard-ok").isDefaultButton(), "捨てる側が既定のボタンになっている");
        assertTrue(cancel.isFocused(), "初めのフォーカスが断る側に無い");

        robot.type(javafx.scene.input.KeyCode.ENTER);
        assertStillShowing();
    }

    /** メニューの「閉じる」でも訊く。キャンセルなら文書が残り、捨てれば閉じる。 */
    @Test
    void 閉じるでも訊く(@TempDir Path dir, FxRobot robot) throws Exception {
        openFixture(robot, TestPdfs.plain(dir.resolve("doc.pdf"), 3));
        rotate(robot);

        closeFromMenu(robot);
        clickWhenReady(robot, "#discard-cancel");
        WaitForAsyncUtils.waitForFxEvents();
        assertTrue(robot.lookup("#thumbnail-tile-0").tryQuery().isPresent(), "断ったのに文書が閉じている");

        closeFromMenu(robot);
        clickWhenReady(robot, "#discard-ok");
        waitFor(() -> statusText(robot).equals("文書が開かれていません。"));
    }

    /**
     * 別の PDF を開くときも訊く。キャンセルなら元の文書のまま。
     *
     * <p><b>★ 訊くのはファイルを選んだ後である。</b>選ぶ窓を取り消しただけなら何も失わないので、
     * <b>先に訊くと、答えさせた意味が無い回が生まれる。</b>
     */
    @Test
    void 別のPDFを開くときも訊く(@TempDir Path dir, FxRobot robot) throws Exception {
        openFixture(robot, TestPdfs.plain(dir.resolve("doc.pdf"), 3));
        rotate(robot);
        String before = statusText(robot);

        dialogs.willOpen(TestPdfs.plain(dir.resolve("other.pdf"), 5));
        clickUntilAccepted(robot, "#tool-open", dialogs::openPending);
        clickWhenReady(robot, "#discard-cancel");
        WaitForAsyncUtils.sleep(GRACE_SECONDS, TimeUnit.SECONDS);
        WaitForAsyncUtils.waitForFxEvents();

        assertEquals(before, statusText(robot), "断ったのに別の文書へ入れ替わっている");
    }

    /** 区切りだけでも訊く。{@code modified()} には出ない（書き出す内容を変えない）。 */
    @Test
    void 区切りだけでも訊く(@TempDir Path dir, FxRobot robot) throws Exception {
        openFixture(robot, TestPdfs.plain(dir.resolve("doc.pdf"), 3));
        robot.clickOn("#thumbnail-tile-1");
        robot.clickOn("#tool-toggle-break");
        WaitForAsyncUtils.waitForFxEvents();

        quitFromMenu(robot);
        clickWhenReady(robot, "#discard-cancel");
        assertStillShowing();
    }

    /** ファイルを足しただけでも訊く。{@code PageOrder} が並びと基準を同じだけ伸ばす。 */
    @Test
    void ファイルを足しただけでも訊く(@TempDir Path dir, FxRobot robot) throws Exception {
        openTwoFiles(robot, dir);

        quitFromMenu(robot);
        clickWhenReady(robot, "#discard-cancel");
        assertStillShowing();
    }

    /** ファイルを外しただけでも訊く。{@code PageOrder} が並びと基準を同じだけずらす。 */
    @Test
    void ファイルを外しただけでも訊く(@TempDir Path dir, FxRobot robot) throws Exception {
        openFixture(robot, TestPdfs.plain(dir.resolve("a.pdf"), 2));
        addFixtures(robot, TestPdfs.plain(dir.resolve("b.pdf"), 1));
        waitForNode(robot, "#source-remove-1");
        // 足したところまでを保存して済みにし、外すことだけを残す。
        saveAs(robot, dir.resolve("both.pdf"));
        dismissMessages(robot);
        robot.clickOn("#source-remove-1");
        clickWhenReady(robot, "#remove-source-ok");
        waitFor(() -> robot.lookup("#source-remove-1").tryQuery().isEmpty());

        quitFromMenu(robot);
        clickWhenReady(robot, "#discard-cancel");
        assertStillShowing();
    }

    /**
     * 区切りに従って分割した直後は訊かない。
     *
     * <p><b>区切りを済みにするのは、区切りを実際に書き出した分割だけである</b>——保存は区切りを書かない。
     */
    @Test
    void 分割した直後は訊かない(@TempDir Path dir, FxRobot robot) throws Exception {
        openFixture(robot, TestPdfs.plain(dir.resolve("doc.pdf"), 3));
        robot.clickOn("#thumbnail-tile-1");
        robot.clickOn("#tool-toggle-break");
        Path out = Files.createDirectory(dir.resolve("out"));
        dialogs.willChooseFolder(out);
        robot.clickOn("#tool-split");
        clickWhenReady(robot, "#message-ok");
        WaitForAsyncUtils.waitForFxEvents();

        quitFromMenu(robot);

        waitFor(() -> !stage.isShowing());
    }

    /**
     * 書き出しを待って終了する筋で、書き出しが失敗したら訊く。キャンセルなら閉じず、待っていた印も下りる。
     *
     * <p><b>★★ ここがこの変更の核心である。</b>成功していれば {@code markSaved} が済むので訊かない
     * （{@code QuitDuringSaveUiTest} の「書き終わったら頼まれていた終了が効く」）。
     * <b>失敗したときだけ、約束が成り立たなくなったことを言う。</b>
     * 存在しないフォルダへ保存させて、決定的に失敗させる。
     */
    @Test
    void 書き出しに失敗したら待っていた終了の前に訊く(@TempDir Path dir, FxRobot robot) throws Exception {
        openFixture(robot, TestPdfs.plain(dir.resolve("doc.pdf"), 3));
        rotate(robot);
        startHeldSave(robot, held, dir.resolve("missing").resolve("out.pdf"));
        quitFromMenu(robot);

        held.release();
        clickWhenReady(robot, "#message-ok");
        clickWhenReady(robot, "#discard-cancel");

        assertStillShowing();
        assertFalse(statusText(robot).contains("終了します"), "閉じないと決めたのに、状態行が終了を待っていると言い続けている");
    }

    // ── 補助 ────────────────────────────────────────────────────────────────

    /** 選んでいるページを回す。並びの内容が変わるので {@code modified()} が立つ。 */
    private void rotate(FxRobot robot) throws Exception {
        robot.clickOn("#thumbnail-tile-0");
        robot.clickOn("#tool-rotate-right");
        waitFor(() -> statusText(robot).contains("未保存"));
    }

    /** 保存の後に出る窓（文書情報の警告など）を、出ていれば閉じる。 */
    private void dismissMessages(FxRobot robot) throws Exception {
        while (dialogButton(robot, "#message-ok").isPresent()) {
            clickWhenReady(robot, "#message-ok");
            WaitForAsyncUtils.waitForFxEvents();
        }
    }

    /** メニューの「終了」を押す。{@code MenuItem} は文言で掴む（{@code .claude/rules/ui-tests.md}）。 */
    private void quitFromMenu(FxRobot robot) {
        robot.clickOn("ファイル");
        robot.clickOn("終了");
        WaitForAsyncUtils.waitForFxEvents();
    }

    /** メニューの「閉じる」を押す。 */
    private void closeFromMenu(FxRobot robot) {
        robot.clickOn("ファイル");
        robot.clickOn("閉じる");
        WaitForAsyncUtils.waitForFxEvents();
    }

    /** 窓の × と同じ要求を出す（{@code QuitDuringSaveUiTest} と同じ理由で、ロボットでは押せない）。 */
    private void quitFromWindowButton() {
        Platform.runLater(() -> stage.fireEvent(new WindowEvent(stage, WindowEvent.WINDOW_CLOSE_REQUEST)));
        WaitForAsyncUtils.waitForFxEvents();
    }

    /** 閉じないはずの窓が、猶予を置いても閉じていないこと。 */
    private void assertStillShowing() {
        WaitForAsyncUtils.sleep(GRACE_SECONDS, TimeUnit.SECONDS);
        WaitForAsyncUtils.waitForFxEvents();
        assertTrue(stage.isShowing(), "断ったのに閉じている");
    }
}
