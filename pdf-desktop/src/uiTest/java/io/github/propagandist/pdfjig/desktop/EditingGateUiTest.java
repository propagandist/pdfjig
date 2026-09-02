package io.github.propagandist.pdfjig.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.propagandist.pdfjig.core.TestPdfs;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.input.KeyCode;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.Start;
import org.testfx.framework.junit5.Stop;
import org.testfx.util.WaitForAsyncUtils;

/**
 * 走っている間、文書を変える操作が 1 つも通らないこと（#114）。
 *
 * <p><b>★★ {@code busy} を見ているのは {@link Action} から作られた節点だけだった。</b>
 * <b>一覧の「×」・サムネイルの DELETE キー・タイルのドラッグは素通りしており</b>、
 * 確認された壊れ方が 2 つ出ていた。
 *
 * <ul>
 *   <li><b>保存中にファイルを外すと、保存後も「未保存」のままになる。</b>
 *       {@code markSaved} の番人は {@code session != saving} しか見ておらず、
 *       <b>{@code DocumentSession#remove} は同じオブジェクトを書き換える</b>ので通ってしまう。
 *       基準が外す前のものに戻り、<b>「編集を元に戻す」で
 *       {@code ArrayIndexOutOfBoundsException} が飛ぶ</b>（外した出どころ番号が残るため）
 *   <li><b>確認ダイアログの最中に文書が入れ替わると、確認していない文書から外れる。</b>
 *       {@code Alert#showAndWait} は入れ子のイベントループであり、
 *       <b>{@code APPLICATION_MODAL} は入力を止めるだけで、積まれた {@code runLater} は止めない</b>
 * </ul>
 *
 * <p><b>★★ 「走っている間」は {@link HeldTasks} で作る。</b>実際の書き出しの速さで
 * 待ち合わせに行くと、<b>落ちるかどうかが機械の速さで決まるテストになる</b>
 * （{@code CLAUDE.md}「不安定なテストの扱い」）。<b>放すまで書き出しは始まらない</b>ので、
 * その間に何を押しても取りこぼしにならない。
 *
 * <p><b>★★ 2 ファイルから書き出すと、文書情報の警告が必ず出る</b>（{@code Messages#warnings}。
 * {@link OverwriteSaveUiTest} と同じ）。<b>モーダルなので、閉じるまで主画面へのクリックが
 * 1 つも届かない</b>——{@link #finishHeldSave} で閉じている。
 * <b>これを閉じ忘れると、押したつもりの操作が届かないまま「何も起きなかった」が緑になる。</b>
 *
 * <p><b>★ ドラッグはここでは見ない。</b>TestFX の robot でドラッグすると
 * windows ランナーで 1 本 19 分かかり（{@link ReorderUiTest}）、CI から外すことになる。
 * <b>落とし先が呼ぶ道（{@code ThumbnailGrid#move}）は
 * {@link ThumbnailGridGateUiTest} が画面抜きで縛ってある。</b>
 */
class EditingGateUiTest extends DesktopUiTest {

    /** 2 ファイルを開いた直後の状態表示。 */
    private static final String TWO_FILES = "3 / 3 ページ（2 ファイル）";

    /** 2 ファイルを開いた直後の中身。書き出しを伴う筋は、ここまで確かめる。 */
    private static final List<String> TWO_FILE_PAGES = List.of("A1", "A2", "B1");

    /**
     * 出ないはずの窓が本当に出ないことを見るための猶予。
     *
     * <p><b>「待てば直る」種類の待ちではない。</b>直っていれば窓は<b>いつまで待っても出ない</b>ので、
     * 長く取っても偽の緑にならない。短すぎると、窓が立ち上がりきっていないだけの状態を
     * 「出ていない」と読みうるので、そちら側に倒してある
     * （{@link ThumbnailSourceUiTest} の猶予と同じ考え方である）。
     */
    private static final int GRACE_SECONDS = 2;

    /** 放すまで仕事を持ったままにできる実行係。 */
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
        // ★ 抱えたまま落ちたときのために放す。assert が途中で倒れると finishHeldSave まで
        //   行かず、捨てる文書を掴んだ仕事が残る。
        held.release();
        tearDown();
    }

    /**
     * 保存が走っている間は、一覧からファイルを外せない。
     *
     * <p><b>押せないことと、押しても何も起きないことの両方を見る。</b>
     * 見えているのは前者だけで（{@code CLAUDE.md} 優先順位 2）、
     * <b>後者が守られていなければ「押せないように見えるボタン」でしかない。</b>
     */
    @Test
    void 保存が走っている間はファイル一覧から外せない(@TempDir Path dir, FxRobot robot) throws Exception {
        Path output = dir.resolve("out.pdf");
        openTwo(robot, dir);
        startHeldSave(robot, output);

        assertTrue(button(robot, "#source-remove-0").isDisabled(), "保存が走っている間に「×」が押せる");

        robot.clickOn("#source-remove-0");
        assertTrue(dialogButton(robot, "#remove-source-ok").isEmpty(), "押せてしまい、確認まで出ている");
        assertEquals(TWO_FILES, statusText(robot));

        finishHeldSave(robot, output);
        assertEquals(TWO_FILE_PAGES, pageTexts(output));
    }

    /**
     * 保存が走っている間は、DELETE キーでページが消えない。
     *
     * <p><b>メニューの「選択したページを削除」は無効になっているのに、同じキーが効いていた。</b>
     * {@code ThumbnailGrid} の {@code handleKey} が {@code MenuItem} を見ずに
     * 処理を直に呼ぶためである。
     */
    @Test
    void 保存が走っている間はDELETEキーでページが消えない(@TempDir Path dir, FxRobot robot) throws Exception {
        Path output = dir.resolve("out.pdf");
        openTwo(robot, dir);
        startHeldSave(robot, output);

        robot.clickOn("#thumbnail-tile-0");
        robot.type(KeyCode.DELETE);
        WaitForAsyncUtils.waitForFxEvents();

        assertEquals(TWO_FILES, statusText(robot), "DELETE キーが素通りしてページが消えた");

        finishHeldSave(robot, output);
        // ★ 画面が変わっていないことだけでは足りない（CLAUDE.md「画面のテスト」）。
        //   書き出しの最中に並びが削られていれば、それはファイルの側に出る。
        assertEquals(TWO_FILE_PAGES, pageTexts(output));
    }

    /**
     * 保存の最中に外しても、書き出した内容と画面が食い違わない。
     *
     * <p><b>★★ 確認済みの不具合その 1 である。</b>外れてしまうと
     * <b>{@code markSaved} が外す前の並びを基準に戻す</b>ので、
     * <b>保存直後なのに「（未保存の変更があります）」が消えない。</b>
     *
     * <p><b>「元に戻す」まで押す。</b>基準が外す前のものに戻っていると、そこに
     * <b>もう存在しない出どころ番号が残っており</b>、{@code SourceLegend} が数えるところで
     * {@code ArrayIndexOutOfBoundsException} が飛ぶ——<b>捕まえていないので画面には何も出ない。</b>
     *
     * <p><b>★★ 確認まで進む。</b>「×」を押したところで止めると、<b>確認の窓が開いたまま
     * 主画面へのクリックが届かなくなり、「何も起きなかった」が緑になる</b>
     * ——2026-09-02 の CI で実際にそうなった（<b>直す前なのに通っていた</b>）。
     */
    @Test
    void 保存の最中に外しても保存済みの印が正しく付く(@TempDir Path dir, FxRobot robot) throws Exception {
        Path output = dir.resolve("out.pdf");
        openTwo(robot, dir);
        startHeldSave(robot, output);

        robot.clickOn("#source-remove-0");
        // 門が漏れていれば確認が出る。出たら承認まで進む——直っていれば、そもそも出ない。
        dialogButton(robot, "#remove-source-ok").ifPresent(robot::clickOn);
        WaitForAsyncUtils.waitForFxEvents();

        finishHeldSave(robot, output);

        assertEquals(TWO_FILES, statusText(robot), "書き出したものと画面が食い違っている");
        assertEquals(TWO_FILE_PAGES, pageTexts(output));

        robot.clickOn("#tool-reset");
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals(TWO_FILES, statusText(robot), "元に戻したら並びが変わった");
    }

    /**
     * 確認の窓の中で文書が入れ替わったら、外さない。
     *
     * <p><b>★★ 確認済みの不具合その 2 である。</b>{@code removeSource} は
     * <b>文書と出どころ番号を確認の前に検め、確認の後に使う。</b>その間に
     * {@code Platform.runLater} で積まれた {@code adopt} が走ると、
     * <b>利用者が説明されたのとは違う文書から外れる。</b>
     *
     * <p><b>入れ替える手は {@code MainWindow#open} である。</b>起動引数と
     * ファイルの関連付けの入口で、<b>窓が出ている間も走り出せる。</b>
     *
     * <p><b>★ ここで見えるのは「失敗が出ること」までである。</b>入れ替わった先が
     * <b>1 ファイルしか含まないので、外すとページが 1 枚も残らず {@code EMPTY_RESULT} になる。</b>
     * <b>別のファイルが黙って外れる形</b>——確認していない文書が 2 ファイル以上のとき——は、
     * <b>確認の窓が出ている間に「追加」を押せないので、この道具立てでは作れない。</b>
     * 直しは同じ 1 か所である。
     */
    @Test
    void 確認の最中に文書が入れ替わったら外さない(@TempDir Path dir, FxRobot robot) throws Exception {
        openTwo(robot, dir);
        Path other = TestPdfs.withText(dir.resolve("c.pdf"), "C1");

        robot.clickOn("#source-remove-0");
        waitForNode(robot, "#remove-source-ok");

        // ★ 確認の窓は入力を止めるが、積まれた runLater は止めない。ここが通ることそのものが、
        //   この不具合の前提である。
        Platform.runLater(() -> window.open(other));
        waitFor(() -> statusText(robot).equals("1 / 1 ページ"));

        clickWhenReady(robot, "#remove-source-ok");

        // 出ていたら閉じてから落とす。開いたままにすると、この後のテストがクリックを奪われる。
        Optional<Node> failure = dialogButton(robot, "#message-ok");
        failure.ifPresent(robot::clickOn);
        assertTrue(failure.isEmpty(), "確認していない文書から外そうとして失敗した（#114）");

        assertEquals("1 / 1 ページ", statusText(robot));
        assertEquals(List.of("C1"), pageTexts(saveAs(robot, dir.resolve("out.pdf"))));
    }

    /**
     * 走り終われば元どおり効く。
     *
     * <p><b>止めたままにする形でも上の 3 本は緑になる。</b>下ろすところまで見ないと、
     * 「二度と押せない」を通してしまう。
     */
    @Test
    void 保存が済めば入口は元どおり効く(@TempDir Path dir, FxRobot robot) throws Exception {
        Path output = dir.resolve("out.pdf");
        openTwo(robot, dir);
        startHeldSave(robot, output);
        finishHeldSave(robot, output);
        assertEquals(TWO_FILE_PAGES, pageTexts(output));

        assertFalse(button(robot, "#source-remove-0").isDisabled(), "保存が済んでも「×」が押せない");

        robot.clickOn("#thumbnail-tile-0");
        robot.type(KeyCode.DELETE);
        waitFor(() -> statusText(robot).equals("2 / 3 ページ（2 ファイル）（未保存の変更があります）"));
    }

    // ── 補助 ────────────────────────────────────────────────────────────────

    /** A（2 ページ）と B（1 ページ）を開く。作法は {@link DesktopUiTest#openTwoFiles} が持つ。 */
    private void openTwo(FxRobot robot, Path dir) throws Exception {
        assertEquals(TWO_FILES, openTwoFiles(robot, dir));
    }

    /**
     * ダイアログのボタンが出てくるなら掴む。
     *
     * <p><b>★★ 「出ない」を 1 回の lookup で決めない。</b>{@code Alert#showAndWait} は
     * 新しい窓を立てるので、<b>クリックの直後に見ると間に合わないことがある</b>——
     * そこで空を返すと、<b>門が漏れているのに緑になる</b>（{@link #GRACE_SECONDS}）。
     */
    private static Optional<Node> dialogButton(FxRobot robot, String id) {
        try {
            WaitForAsyncUtils.waitFor(
                    GRACE_SECONDS,
                    TimeUnit.SECONDS,
                    () -> robot.lookup(id).tryQuery().isPresent());
        } catch (Exception neverShown) {
            return Optional.empty();
        }
        WaitForAsyncUtils.waitForFxEvents();
        return robot.lookup(id).tryQuery();
    }

    /**
     * 書き出しを始めて、始まったところで止める。
     *
     * <p><b>止まったことをツールバーで確かめてから戻る。</b>{@code busy} が立っていなければ、
     * この後の「効かない」は門ではなく別の理由になる。
     */
    private void startHeldSave(FxRobot robot, Path output) throws Exception {
        held.hold();
        dialogs.willSaveTo(output);
        clickUntilAccepted(robot, "#tool-save", dialogs::savePending);
        waitFor(() -> button(robot, "#tool-save").isDisabled());
    }

    /**
     * 止めてある書き出しを放し、書き終わって窓が戻るまで待つ。
     *
     * <p><b>★★ 警告を閉じるところまでやる。</b>2 ファイルから書き出すと文書情報の警告が
     * 必ず出て、<b>モーダルなので閉じるまで主画面へのクリックが 1 つも届かない</b>——
     * <b>閉じ忘れると、後続の操作が届かないまま「何も起きなかった」が緑になる。</b>
     */
    private void finishHeldSave(FxRobot robot, Path output) throws Exception {
        held.release();
        waitFor(() -> Files.exists(output) && Files.size(output) > 0);
        clickWhenReady(robot, "#message-ok");
        waitFor(() -> !button(robot, "#tool-save").isDisabled());
    }

    /**
     * 放すまで仕事を持ったままにできる {@link Executor}。
     *
     * <p><b>止めるのは頼まれた時点で決まる。</b>{@link #hold()} を呼んでから頼んだものだけを
     * 抱え、それ以外は既定どおりすぐ走らせる——<b>「開く」「追加」まで止めると、
     * そもそも文書を用意できない。</b>
     */
    private static final class HeldTasks implements Executor {

        private final List<Runnable> waiting = new ArrayList<>();

        private boolean holding;

        /** これ以降に頼まれた仕事を抱える。 */
        synchronized void hold() {
            holding = true;
        }

        /** 抱えている仕事を放し、以降は抱えない。何も抱えていなければ何もしない。 */
        synchronized void release() {
            holding = false;
            waiting.forEach(HeldTasks::start);
            waiting.clear();
        }

        @Override
        public synchronized void execute(Runnable work) {
            if (holding) {
                waiting.add(work);
            } else {
                start(work);
            }
        }

        private static void start(Runnable work) {
            Thread worker = new Thread(work, "held-operation");
            worker.setDaemon(true);
            worker.start();
        }
    }
}
