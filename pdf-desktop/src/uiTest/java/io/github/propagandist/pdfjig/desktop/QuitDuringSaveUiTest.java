package io.github.propagandist.pdfjig.desktop;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.propagandist.pdfjig.core.TestPdfs;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.Start;
import org.testfx.framework.junit5.Stop;
import org.testfx.util.WaitForAsyncUtils;

/**
 * 書き出しの最中に終了を求められたら、書き終わってから終わる（#134）。
 *
 * <p><b>★★ 守っているのは利用者の文書そのものである。</b>置き換えは
 * <b>「元をどけてから入れる」2 本の改名</b>であり（#119）、<b>その 2 本の間で JVM が死ぬと、
 * 出力先には何も無く、元は作業場所の中にしか残らない</b>——書き出しは daemon スレッドで
 * 走るので、<b>止められずに消える。</b>
 *
 * <p><b>★ 確率は #114 で上がった。</b>門を入れたので、<b>書き出しの最中に押せるのは
 * 「終了」と「バージョン情報」だけ</b>になった——<b>固まったように見える窓で、
 * 利用者が押せるのは終了である。</b>
 *
 * <p><b>★ 実際にファイルが消えるところまでは、ここでは作れない。</b>JVM を殺す必要があり、
 * テストの中では起こせない。<b>ここが見るのは「終了の要求を、走っている間は通さないこと」まで</b>で、
 * <b>そこから先は「JVM が死なないなら 2 本の改名は必ず完了する」という自明な帰結である。</b>
 */
class QuitDuringSaveUiTest extends DesktopUiTest {

    /**
     * 閉じないはずの窓が本当に閉じないことを見るための猶予。
     *
     * <p><b>「待てば直る」種類の待ちではない。</b>直っていれば窓は<b>いつまで待っても閉じない</b>ので、
     * 長く取っても偽の緑にならない。<b>短すぎると、閉じる処理が始まる前を「閉じていない」と読む。</b>
     */
    private static final int GRACE_SECONDS = 2;

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

    /**
     * 書き出しが走っている間は、終了の要求で閉じない。
     *
     * <p><b>断るのではなく、覚えて待つ</b>——閉じられないまま何も言われないと、
     * 利用者は<b>固まったと読んで、より乱暴な終わらせ方をする</b>（{@code CLAUDE.md} 優先順位 2）。
     * <b>状態行に理由が出ることまで見る。</b>
     */
    @Test
    void 書き出しが走っている間はメニューの終了で閉じない(@TempDir Path dir, FxRobot robot) throws Exception {
        openFixture(robot, TestPdfs.plain(dir.resolve("doc.pdf"), 3));
        startHeldSave(robot, dir.resolve("out.pdf"));

        quitFromMenu(robot);

        assertStillShowing(robot);
    }

    /**
     * 窓の × でも閉じない。
     *
     * <p><b>★★ 入口を数え上げない</b>（#114）。メニューと × は<b>同じ 1 か所を通る</b>ように
     * してあるが、<b>そこへ繋ぐ口は 2 つある</b>——片方だけ繋いでも、もう片方は素通りする。
     */
    @Test
    void 書き出しが走っている間は窓の閉じるボタンでも閉じない(@TempDir Path dir, FxRobot robot) throws Exception {
        openFixture(robot, TestPdfs.plain(dir.resolve("doc.pdf"), 3));
        startHeldSave(robot, dir.resolve("out.pdf"));

        quitFromWindowButton();

        assertStillShowing(robot);
    }

    /**
     * 書き終わったら、頼まれていた終了が効く。
     *
     * <p><b>★ 断る形にしないのはここである。</b>覚えていなければ、
     * <b>利用者はもう一度押さなければならない</b>——押した意思は消えていない。
     */
    @Test
    void 書き終わったら頼まれていた終了が効く(@TempDir Path dir, FxRobot robot) throws Exception {
        openFixture(robot, TestPdfs.plain(dir.resolve("doc.pdf"), 3));
        startHeldSave(robot, dir.resolve("out.pdf"));
        quitFromMenu(robot);

        held.release();

        waitFor(() -> !stage.isShowing());
    }

    /** 走っていなければ、終了の要求はそのまま通る。 */
    @Test
    void 走っていなければ終了の要求はそのまま通る(@TempDir Path dir, FxRobot robot) throws Exception {
        openFixture(robot, TestPdfs.plain(dir.resolve("doc.pdf"), 3));

        quitFromMenu(robot);

        waitFor(() -> !stage.isShowing());
        assertFalse(stage.isShowing());
    }

    /**
     * メニューの「終了」を押す。
     *
     * <p><b>★ 文言で掴む。</b>{@code MenuItem} は {@code Node} ではないので id では掴めない
     * ——<b>文言で掴んでよい唯一の相手である</b>（{@code CLAUDE.md}「画面のテスト」）。
     *
     * <p><b>★★ これが直す前の欠陥をそのまま踏む道である。</b>直す前の「終了」は
     * {@code stage::close} を直に呼んでおり、<b>走っている最中でもその場で閉じる。</b>
     */
    private void quitFromMenu(FxRobot robot) {
        robot.clickOn("ファイル");
        robot.clickOn("終了");
        WaitForAsyncUtils.waitForFxEvents();
    }

    /**
     * 窓の × と同じ要求を出す。
     *
     * <p><b>★ ロボットでは押せない。</b>窓の飾りは場面の外にあり、TestFX から掴めない。
     * <b>OS が出すのと同じ事象を自分で流す</b>——受け口が無ければ何も起きないので、
     * <b>受けていないこと自体が赤になる。</b>
     */
    private void quitFromWindowButton() {
        Platform.runLater(() -> stage.fireEvent(new WindowEvent(stage, WindowEvent.WINDOW_CLOSE_REQUEST)));
        WaitForAsyncUtils.waitForFxEvents();
    }

    /** 閉じないはずの窓が、猶予を置いても閉じていないこと。 */
    private void assertStillShowing(FxRobot robot) {
        WaitForAsyncUtils.sleep(GRACE_SECONDS, TimeUnit.SECONDS);
        WaitForAsyncUtils.waitForFxEvents();
        assertTrue(
                stage.isShowing(), "書き出しの最中に窓が閉じている。退避と入れ替えの 2 本の改名の間で JVM が死ぬと、" + "出力先には何も無く、元は作業場所の中にしか残らない（#134）");
        assertTrue(statusText(robot).contains("終了します"), "閉じない理由が出ていない。利用者からは固まったようにしか見えない");
    }

    /** 書き出しを始めて、始まったところで止める。止まったことをツールバーで確かめてから戻る。 */
    private void startHeldSave(FxRobot robot, Path output) throws Exception {
        held.hold();
        dialogs.willSaveTo(output);
        clickUntilAccepted(robot, "#tool-save", dialogs::savePending);
        waitFor(() -> button(robot, "#tool-save").isDisabled());
    }
}
