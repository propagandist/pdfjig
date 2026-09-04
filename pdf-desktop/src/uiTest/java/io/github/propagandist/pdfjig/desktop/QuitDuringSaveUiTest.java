package io.github.propagandist.pdfjig.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.propagandist.pdfjig.core.TestPdfs;
import java.nio.file.Path;
import java.util.List;
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

    private final HeldTasks held = new HeldTasks();

    /** 差し込んだ実行の手。<b>片づけのときに「走り終わったか」を訊くために持っておく。</b> */
    private final BackgroundTasks tasks = new BackgroundTasks(held);

    @Override
    BackgroundTasks tasks() {
        return tasks;
    }

    @Start
    void start(Stage stage) {
        setUp(stage);
    }

    /**
     * 抱えたまま終わらない。
     *
     * <p><b>★★ 放すだけでは足りない。</b>{@code release} は仕事を<b>始める</b>だけで、
     * 終わるのを待たない——そのまま {@code tearDown} すると、
     * <b>書いている最中の文書を閉じ、書いている最中のフォルダを JUnit が消す。</b>
     * ★ <b>このクラスでは 2 本が「抱えたまま終わる」筋である</b>ので、
     * <b>ここが受け皿ではなく通り道になっている</b>（{@code EditingGateUiTest} は
     * 各テストの中で放しており、ここは網だった）。
     */
    @Stop
    void stop() throws Exception {
        held.release();
        // 走り終わるまで待つ。頼まれていた終了もここで効くので、それも収まりきってから片づける。
        waitFor(() -> !tasks.busy().get());
        WaitForAsyncUtils.waitForFxEvents();
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
        startHeldSave(robot, held, dir.resolve("out.pdf"));

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
        startHeldSave(robot, held, dir.resolve("out.pdf"));

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
        Path output = dir.resolve("out.pdf");
        openFixture(robot, TestPdfs.withText(dir.resolve("doc.pdf"), "P1", "P2", "P3"));
        startHeldSave(robot, held, output);
        quitFromMenu(robot);
        assertTrue(stage.isShowing(), "放す前に閉じている。覚えて待つ形になっていない（#134）");

        held.release();

        waitFor(() -> !stage.isShowing());
        // ★ 画面が閉じたことだけを見ても、ファイルが正しい保証にはならない（CLAUDE.md「画面のテスト」）。
        //   守っているのはこの中身である——2 本の改名の途中で終わっていれば、ここが違う。
        assertEquals(List.of("P1", "P2", "P3"), pageTexts(output), "書き終わる前に終了している（#134）");
    }

    /**
     * 窓が出ている最中に閉じない。
     *
     * <p><b>★★ ここが「待ち方」を縛っている。</b>{@code busy} が下りたのを見て
     * {@code Platform#runLater} でずらす形にすると、<b>ずらしたものは
     * {@code Alert#showAndWait} の入れ子のイベントループで動く</b>——
     * <b>窓が出ている最中に主画面が閉じる。</b>
     *
     * <p><b>いちばん困るのは #124 の窓である</b>——「保存に失敗しました。元のファイルは
     * ここに残っています」を読んでいる最中に、その親の窓が消える。
     * <b>ここでは 2 ファイルから書き出して、必ず出る文書情報の警告で同じ形を作る。</b>
     */
    @Test
    void 窓が出ている最中には閉じない(@TempDir Path dir, FxRobot robot) throws Exception {
        openTwoFiles(robot, dir);
        // ★★ 出どころの 1 つへ上書きする。そうすると後始末が寄せ直しの 2 本目を始めてから
        //   警告を出すので（#118）、2 本目の完了が窓の入れ子のイベントループの中で起きる。
        //   別の名前へ書くと 2 本目が無く、この筋は作れない。
        startHeldSave(robot, held, dir.resolve("a.pdf"));
        quitFromMenu(robot);

        held.release();
        waitForNode(robot, "#message-ok");

        assertTrue(stage.isShowing(), "警告の窓が出ている最中に主画面が閉じている。読んでいるものの親が消える（#134）");
        clickWhenReady(robot, "#message-ok");
        waitFor(() -> !stage.isShowing());
    }

    /** 走っていなければ、メニューの終了はそのまま通る。 */
    @Test
    void 走っていなければメニューの終了はそのまま通る(@TempDir Path dir, FxRobot robot) throws Exception {
        openFixture(robot, TestPdfs.plain(dir.resolve("doc.pdf"), 3));

        quitFromMenu(robot);

        waitFor(() -> !stage.isShowing());
    }

    /**
     * 走っていなければ、窓の × もそのまま通る。
     *
     * <p><b>★★ ここを見ないと、× が二度と効かなくなっても全部緑になる。</b>
     * 受け口は事象を<b>必ず断ってから自分で閉じる</b>形なので（{@code MainWindow} の構築時）、
     * <b>閉じるほうを書き忘れると、押しても何も起きない窓ができる。</b>
     * <b>メニューの筋はこの受け口を通らない</b>ので、そちらでは気づけない。
     */
    @Test
    void 走っていなければ窓の閉じるボタンもそのまま通る(@TempDir Path dir, FxRobot robot) throws Exception {
        openFixture(robot, TestPdfs.plain(dir.resolve("doc.pdf"), 3));

        quitFromWindowButton();

        waitFor(() -> !stage.isShowing());
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
}
