package io.github.propagandist.pdfjig.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
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
 * <p><b>★★ 本物の GitHub へは出ない。問い合わせる相手を偽物に差し替えてある</b>（#176）。
 * 本物へ出ていた間は、<b>答えが返るかどうかが CI ランナーの外向きの通信で決まり</b>、
 * 差分と関係なく {@code TimeoutException} で落ちた（直近 30 run で 3 回。2026-09-26 実測）。
 * 偽物は<b>本物が返しようのない版</b>（{@link #ANSWER}）を答えるので、<b>差し替えが効かずに
 * 本物へ出ていれば、文言が合わずに落ちる。</b>
 *
 * <p>ここが見ているのは、単体テストでは通せない 3 つである。
 *
 * <ul>
 *   <li><b>押したときだけ問い合わせる。</b>窓を開けただけでは相手を呼ばず、答えの行も出ない
 *   <li><b>JavaFX スレッドを塞がない。</b>答えを止めたまま、窓が応えることを見る
 *   <li><b>答えが 1 行出て、ボタンが押せる状態へ戻る</b>
 * </ul>
 *
 * <p><b>★ 本物の通信はここでは通らない。</b>{@link UpdateCheck#check()} が GitHub から
 * 版数を読めることは、人が見る（{@code docs/HANDOVER.md} 4-4 の 6 番と 14 番）。
 * 文言と版の比べ方は {@code UpdateCheckTest} が縛っている。
 */
class UpdateCheckUiTest extends DesktopUiTest {

    /**
     * 偽物が返す答え。
     *
     * <p><b>本物が返しようのない版にしてある</b>——99.0.0 は公開されていないので、
     * 本物へ出れば「開発版です」か「確認できませんでした」になる。
     */
    private static final UpdateStatus ANSWER = new UpdateStatus.Available(new ReleaseVersion(99, 0, 0, false));

    /** 偽物が呼ばれた回数。 */
    private final AtomicInteger asked = new AtomicInteger();

    /**
     * 開くまで偽物が答えを返さない掛け金。既定は開いている。
     *
     * <p>塞ぐのは「窓が固まらない」を見る筋だけである。
     */
    private volatile CountDownLatch answering = new CountDownLatch(0);

    @Override
    Supplier<UpdateStatus> updateCheck() {
        return () -> {
            asked.incrementAndGet();
            try {
                // ★ 上限を置く。開け忘れても、偽物の仕事が残り続けない。
                answering.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return ANSWER;
        };
    }

    @Start
    void start(Stage stage) {
        setUp(stage);
    }

    @Stop
    void stop() {
        answering.countDown();
        tearDown();
    }

    @Test
    void 押すまでは何も確かめない(FxRobot robot) throws Exception {
        openAbout(robot);

        // 何も確かめていないのに行があると、起動しただけで確認されたように読める。
        assertFalse(result(robot).isVisible(), "押す前から答えの行が出ている");
        assertFalse(link(robot).isVisible(), "押す前からリンクが出ている");
        assertEquals(0, asked.get(), "窓を開けただけで問い合わせている");

        clickWhenReady(robot, "#about-close");
    }

    @Test
    void 押すと答えが1行出てボタンが戻る(FxRobot robot) throws Exception {
        openAbout(robot);

        clickWhenReady(robot, "#about-check-update");
        waitForAnswer(robot);

        // ★ 文言まで見る。違えば、差し替えた相手ではなく本物へ出ている。
        assertEquals(UpdateCheck.describe(ANSWER), result(robot).getText(), "差し替えた相手の答えが出ていない");
        assertEquals(1, asked.get(), "押したのは 1 回である");

        // リンクを添えるのは新しい版があったときだけである。
        assertTrue(link(robot).isVisible(), "新しい版があるのに Releases へのリンクが出ていない");

        clickWhenReady(robot, "#about-close");
    }

    @Test
    void 答えが出たら押し直せる(FxRobot robot) throws Exception {
        openAbout(robot);

        clickWhenReady(robot, "#about-check-update");
        waitForAnswer(robot);

        // 遮断は一時的なこともある。押せる状態に戻っていなければ、確認できなかった人は
        // 窓を閉じて開き直すしかない。
        assertFalse(button(robot, "#about-check-update").isDisable(), "答えが出たのにボタンが戻っていない");

        // ★★ 押し直しが届いたかを、答えの文字列では見分けられない——同じ答えが返るからである。
        //   答えの行が書き換わった回数を数える。1 回押すと必ず 2 回変わる（「確認しています…」→ 答え）。
        //   ★ 偽物の呼ばれた回数では見ない。呼ばれるのは背景スレッドで、押した直後にはまだ数えていない
        //   ——押し直しの判定に使うと、届いたクリックを取りこぼしたと読んで重ねて押す。
        AtomicInteger rewrites = countRewrites(robot);

        // 1 回目の書き換えは押した瞬間に JavaFX スレッドで起きる。届いていなければ押し直す
        // （窓は既に前面にあるが、取りこぼしは待っても届かない。DesktopUiTest#clickUntilAccepted）。
        clickUntilAccepted(robot, "#about-check-update", () -> rewrites.get() < 1);
        waitFor(() ->
                rewrites.get() >= 2 && !button(robot, "#about-check-update").isDisable());
        WaitForAsyncUtils.waitForFxEvents();

        assertEquals(UpdateCheck.describe(ANSWER), result(robot).getText(), "押し直した答えが出ていない");
        assertEquals(2, asked.get(), "押し直したのに問い合わせていない");

        clickWhenReady(robot, "#about-close");
    }

    /**
     * 答えを待っている間も、窓は応える。
     *
     * <p><b>★ 偽物の答えを止めて、その間に JavaFX スレッドへ仕事を頼む。</b>
     * 問い合わせが JavaFX スレッドに乗っていれば、頼んだ仕事は答えが出るまで走らず、ここが時間切れになる。
     * 本物へ出ていた間は、遮断された環境で通信が長く返らないことに頼っていた。
     */
    @Test
    void 答えを待つ間も窓が固まらない(FxRobot robot) throws Exception {
        openAbout(robot);
        answering = new CountDownLatch(1);

        clickWhenReady(robot, "#about-check-update");
        waitFor(() -> asked.get() == 1);

        boolean disabled;
        try {
            disabled = WaitForAsyncUtils.asyncFx(
                            () -> button(robot, "#about-check-update").isDisable())
                    .get(GRACE_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new AssertionError("答えを待つ間、JavaFX スレッドが応えない。問い合わせが画面のスレッドに乗っている", e);
        }
        assertTrue(disabled, "答えが出る前にボタンが押せる。連打すると問い合わせだけが増える");
        assertFalse(link(robot).isVisible(), "答えが出る前にリンクが出ている");

        answering.countDown();
        waitForAnswer(robot);
        assertEquals(UpdateCheck.describe(ANSWER), result(robot).getText());

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
     * <b>そこで読むと問い合わせの結果ではないものを読む。</b>文言で見分けないのは、
     * 文言を変えたときに黙って通るテストになるためである。
     *
     * <p><b>押せる状態に戻ったことを印にできるのは、{@code AboutDialog#settle} が
     * ボタンを最後に戻すからである。</b>逆順だった間、この待ちは「確認しています…」のまま
     * 抜けた（2026-08-30、遮断された Sandbox で実際に落ちた）。
     * <b>待ち時間を延ばして直すたぐいではない</b>——完了の条件が間違っていた
     * （{@code .claude/rules/ui-tests.md}「不安定なテストの扱い」）。
     */
    private void waitForAnswer(FxRobot robot) throws Exception {
        waitFor(() -> result(robot).isVisible()
                && !button(robot, "#about-check-update").isDisable());
        // 読むのは別のスレッドである。掛け金を通してから読む（waitForNode と同じ作法）。
        WaitForAsyncUtils.waitForFxEvents();
    }

    /**
     * 答えの行が書き換わった回数を数え始める。
     *
     * <p>監視の付け外しは JavaFX スレッドで行う。{@code FxRobot} が返す節点は
     * そちらが持ち主である。
     */
    private static AtomicInteger countRewrites(FxRobot robot) throws Exception {
        AtomicInteger rewrites = new AtomicInteger();
        WaitForAsyncUtils.asyncFx(() -> result(robot)
                        .textProperty()
                        .addListener((observable, before, after) -> rewrites.incrementAndGet()))
                .get();
        return rewrites;
    }

    private static Label result(FxRobot robot) {
        return robot.lookup("#about-update").queryAs(Label.class);
    }

    private static Hyperlink link(FxRobot robot) {
        return robot.lookup("#about-update-link").queryAs(Hyperlink.class);
    }
}
