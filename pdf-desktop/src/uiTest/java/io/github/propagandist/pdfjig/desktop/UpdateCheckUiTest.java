package io.github.propagandist.pdfjig.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
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
     * 本物へ出ればどの答えになっても、この版の文言は出ない。
     */
    private static final UpdateStatus ANSWER = new UpdateStatus.Available(new ReleaseVersion(99, 0, 0, false));

    /** 偽物が返すもの。既定は {@link #ANSWER}。答えの形を変えたい筋だけが差し替える。 */
    private volatile Supplier<UpdateStatus> responding = () -> ANSWER;

    /** 偽物が呼ばれた回数。 */
    private final AtomicInteger asked = new AtomicInteger();

    /**
     * 開くまで偽物が答えを返さない掛け金。既定は開いている。
     *
     * <p>塞ぐのは「窓が固まらない」を見る筋と、押し直しを通す {@link #answerTo} である。
     */
    private volatile CountDownLatch answering = new CountDownLatch(0);

    @Override
    Supplier<UpdateStatus> updateCheck() {
        return () -> {
            asked.incrementAndGet();
            try {
                // ★ 上限を置く。開け忘れても、偽物の仕事が残り続けない。
                //   ★ 開かないまま上限に来たら投げる。黙って答えると、開け忘れが緑に紛れる。
                if (!answering.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("掛け金が開かれないまま上限に来た");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return responding.get();
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
        // ★ 1 回だけ見ない。裏で問い合わせ始める退行なら、数える前に見終わって緑になる（GRACE_SECONDS）。
        try {
            WaitForAsyncUtils.waitFor(GRACE_SECONDS, TimeUnit.SECONDS, () -> asked.get() > 0);
            throw new AssertionError("窓を開けただけで問い合わせている");
        } catch (TimeoutException neverAsked) {
            // 猶予のあいだ一度も呼ばれなかった。
        }

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

    /**
     * 新しい版が無ければ、リンクを出さない。
     *
     * <p><b>最新・開発版・確認できなかった人を Releases へ促さない</b>（{@code CLAUDE.md} 優先順位 2）。
     * 偽物の既定は新しい版を答えるので、出ない側の 3 つをここで通す。
     * ★ 本物へ出ていた間は、CI の開発版ビルドで「開発版です」の 1 つだけを通っていた。
     */
    @Test
    void 新しい版が無ければリンクを出さない(FxRobot robot) throws Exception {
        openAbout(robot);

        for (UpdateStatus status : List.of(
                new UpdateStatus.UpToDate(),
                new UpdateStatus.Development(new ReleaseVersion(98, 0, 0, false)),
                new UpdateStatus.Unavailable())) {
            assertEquals(UpdateCheck.describe(status), answerTo(robot, status), status.toString());
            assertFalse(link(robot).isVisible(), "新しい版が無いのに Releases へのリンクが出ている: " + status);
        }

        clickWhenReady(robot, "#about-close");
    }

    /**
     * 問い合わせる相手が投げても、「確認できませんでした」の 1 行を出してボタンを戻す。
     *
     * <p><b>{@link UpdateCheck#check()} は投げない約束だが、画面はそれに頼らず受けている</b>
     * （{@code AboutDialog#checkForUpdate}）。受けなければ「確認しています…」のまま押せない窓が残る。
     */
    @Test
    void 相手が投げても確認できなかったと出す(FxRobot robot) throws Exception {
        responding = () -> {
            throw new IllegalStateException("問い合わせに失敗した");
        };
        openAbout(robot);

        clickWhenReady(robot, "#about-check-update");
        waitForAnswer(robot);

        assertEquals(
                UpdateCheck.describe(new UpdateStatus.Unavailable()),
                result(robot).getText());
        assertFalse(link(robot).isVisible(), "確認できなかったのに Releases へのリンクが出ている");

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

        assertEquals(UpdateCheck.describe(ANSWER), answerTo(robot, ANSWER), "押し直した答えが出ていない");
        assertEquals(2, asked.get(), "押し直したのに問い合わせていない");

        clickWhenReady(robot, "#about-close");
    }

    /**
     * 答えを待っている間も、窓は応える。
     *
     * <p><b>★ 偽物の答えを止めて、その間に JavaFX スレッドへ仕事を頼む。</b>
     * 問い合わせが JavaFX スレッドに乗っていれば、答えが出るまで画面は何もできない。
     * ★ <b>そのときどの行で落ちるかは確かめていない</b>——押すクリック自体が答えを待って止まり、
     * 答えが出てから「ボタンが押せる」の判定で落ちることもありうる。どちらでも赤にはなる。
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
        assertTrue(disabled, "答えが出る前にボタンが押せる（連打すると問い合わせだけが増える）か、問い合わせが画面のスレッドに乗っている");
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
     * 答えを {@code status} にして「更新を確認」を押し、出た 1 行を返す。窓は開いていること。
     *
     * <p><b>★ 答えを止めてから押す。</b>押し直し（{@link #clickUntilAccepted}）は取りこぼしを補うので、
     * 重ねたクリックが届きうる。偽物がすぐ答えると、それが戻ったボタンに届いて
     * 答えを「確認しています…」へ書き戻す。止めている間はボタンが押せないので、届いても何もしない。
     *
     * <p><b>★★ 届いたかを答えの文字列では見分けられない</b>——同じ答えが続くことがある。
     * 答えの行が書き換わったかで見る。押した瞬間に JavaFX スレッドで「確認しています…」へ変わる。
     */
    private String answerTo(FxRobot robot, UpdateStatus status) throws Exception {
        responding = () -> status;
        int before = asked.get();
        CountDownLatch held = new CountDownLatch(1);
        answering = held;
        try {
            AtomicInteger rewrites = countRewrites(robot);
            clickUntilAccepted(robot, "#about-check-update", () -> rewrites.get() < 1);
            waitFor(() -> asked.get() > before);
        } finally {
            held.countDown();
        }
        waitForAnswer(robot);
        return result(robot).getText();
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
