package io.github.propagandist.pdfjig.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.Start;
import org.testfx.framework.junit5.Stop;
import org.testfx.util.WaitForAsyncUtils;

/**
 * 更新の確認（#72）。
 *
 * <p><b>★ ネットワークの有無で結果を変えない書き方にしてある。</b>つながれば
 * 「開発版です」か「新しい版 … が公開されています」、遮断されていれば
 * 「更新を確認できませんでした」——<b>どれであれ、答えが 1 行出てボタンが押せる状態へ戻る</b>
 * ところまでを見る。文言そのものは {@code UpdateCheckTest} が縛っている。
 *
 * <p>ここが見ているのは、単体テストでは通せない 3 つである。
 *
 * <ul>
 *   <li><b>押したときだけ通信する。</b>窓を開けただけでは答えの行が出ない
 *   <li><b>JavaFX スレッドを塞がない。</b>遮断された環境では 10 秒返らないので、
 *       同期で呼んでいれば窓ごと固まり、ここが時間切れになる
 *   <li><b>失敗しても例外にならない。</b>投げれば
 *       {@code LogEvent.UNCAUGHT} まで抜け、答えの行は出ないままになる
 * </ul>
 */
class UpdateCheckUiTest extends DesktopUiTest {

    @Start
    void start(Stage stage) {
        setUp(stage);
    }

    @Stop
    void stop() {
        tearDown();
    }

    @Test
    void 押すまでは何も確かめない(FxRobot robot) throws Exception {
        openAbout(robot);

        // 何も確かめていないのに行があると、起動しただけで確認されたように読める。
        assertFalse(result(robot).isVisible(), "押す前から答えの行が出ている");
        assertFalse(link(robot).isVisible(), "押す前からリンクが出ている");

        clickWhenReady(robot, "#about-close");
    }

    @Test
    void 押すと答えが1行出てボタンが戻る(FxRobot robot) throws Exception {
        openAbout(robot);

        clickWhenReady(robot, "#about-check-update");
        waitForAnswer(robot);

        String answer = result(robot).getText();
        assertFalse(answer.isBlank(), "答えの行が空である");
        assertEquals(1, answer.lines().count(), answer);

        // リンクを添えるのは新しい版があったときだけである。無いのに Releases へ促さない。
        assertEquals(answer.contains("新しい版"), link(robot).isVisible(), answer);

        clickWhenReady(robot, "#about-close");
    }

    @Test
    void 答えが出たら押し直せる(FxRobot robot) throws Exception {
        openAbout(robot);

        clickWhenReady(robot, "#about-check-update");
        waitForAnswer(robot);
        String first = result(robot).getText();

        // 遮断は一時的なこともある。押せる状態に戻っていなければ、確認できなかった人は
        // 窓を閉じて開き直すしかない。
        assertFalse(button(robot, "#about-check-update").isDisable(), "答えが出たのにボタンが戻っていない");

        clickWhenReady(robot, "#about-check-update");
        waitForAnswer(robot);

        assertEquals(first, result(robot).getText());

        clickWhenReady(robot, "#about-close");
    }

    // ── 補助 ────────────────────────────────────────────────────────────────

    private void openAbout(FxRobot robot) throws Exception {
        // メニュー項目は Node ではないため id では掴めない。ここだけは文言で辿る。
        robot.clickOn("ヘルプ");
        robot.clickOn(AppInfo.NAME + " について");
        waitForNode(robot, "#about-check-update");
    }

    /**
     * 答えが出るまで待つ。
     *
     * <p><b>待つのは「行が出て、ボタンが押せる状態に戻る」ところまでである。</b>
     * 行が出ただけでは足りない——押した直後に出るのは「確認しています…」であり、
     * <b>そこで読むと通信の結果ではないものを読む。</b>文言で見分けないのは、
     * 文言を変えたときに黙って通るテストになるためである。
     *
     * <p>上限は {@link #TIMEOUT_SECONDS}。接続と読み取りの待ち（合わせて 10 秒）より長い。
     * <b>超えたら落とす</b>——遅いのではなく、答えが返る経路が壊れているということである。
     *
     * <p><b>押せる状態に戻ったことを印にできるのは、{@code AboutDialog#settle} が
     * ボタンを最後に戻すからである。</b>逆順だった間、この待ちは「確認しています…」のまま
     * 抜けた（2026-08-30、遮断された Sandbox で実際に落ちた）。
     * <b>待ち時間を延ばして直すたぐいではない</b>——完了の条件が間違っていた
     * （{@code CLAUDE.md}「不安定なテストの扱い」）。
     */
    private void waitForAnswer(FxRobot robot) throws Exception {
        waitFor(() -> result(robot).isVisible()
                && !button(robot, "#about-check-update").isDisable());
        // 読むのは別のスレッドである。掛け金を通してから読む（waitForNode と同じ作法）。
        WaitForAsyncUtils.waitForFxEvents();
    }

    private static Label result(FxRobot robot) {
        return robot.lookup("#about-update").queryAs(Label.class);
    }

    private static Hyperlink link(FxRobot robot) {
        return robot.lookup("#about-update-link").queryAs(Hyperlink.class);
    }
}
