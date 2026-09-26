package io.github.propagandist.pdfjig.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.propagandist.pdfjig.core.TestPdfs;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.input.Clipboard;
import javafx.scene.input.KeyCode;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.stage.WindowEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.Start;
import org.testfx.framework.junit5.Stop;
import org.testfx.util.WaitForAsyncUtils;

/**
 * 前の書き出しがアプリごと落ちて残した控えを、次の保存で伝える（#138）。
 *
 * <p><b>★★ 電源断・強制終了・ログオフでは、失敗に在り処を載せる形（#124）が届かない。</b>
 * {@code close} も {@code catch} も走らないためである。<b>見つけられるのは次に同じフォルダへ
 * 書き出すときで、そこで黙ると、利用者から見えるのは出力先に増えた {@code .pdfjig-*} だけになる。</b>
 * 配った {@code v0.1.2} の {@code docs/RELEASE_NOTES.md}「既知の制限」が「次の版で伝える形にする」と
 * 約束した穴である。
 *
 * <p><b>落ちた状態は手で作る。</b>印（{@code held}）と控え（{@code replaced.pdf}）を抱えた
 * 作業場所を出力先の隣に置く——アプリごと落ちた後に残るものと同じ形である
 * （{@code OutputWorkspace}）。
 *
 * <p><b>★ 在り処を写すボタン（#137）もここで見る。</b>在り処を出す窓のうち、画面のテストから
 * 狙って出せるのはこの窓だけである（失敗の窓は {@code KeptCopyReportTest} が文言まで見る）。
 * <b>写すと、手元の実行ではクリップボードを上書きする。</b>
 */
class AbandonedCopyUiTest extends DesktopUiTest {

    @Start
    void start(Stage stage) {
        setUp(stage);
    }

    @Stop
    void stop() {
        tearDown();
    }

    /**
     * 控えの在り処を窓で伝える。書き出しも控えも変えない。
     *
     * <p><b>★ 窓が出る時機（書き出しの後か最中か）は、ここでは見分けられない。</b>書き出しは
     * 窓を待たずに進むので、最中に出す形に変えても同じように通る。<b>時機は人が見る</b>
     * （{@code docs/HANDOVER.md} 4-4 の 10 番の ③）。
     *
     * <p><b>控えそのものにも触らない</b>——伝えるだけで、消すのは利用者である。
     */
    @Test
    void 前の書き出しが残した控えの在り処を伝える(@TempDir Path dir, FxRobot robot) throws Exception {
        Path kept = crashedBeside(dir);

        openFixture(robot, TestPdfs.withText(dir.resolve("a.pdf"), "A1"));
        Path output = saveAs(robot, dir.resolve("out.pdf"));

        waitForNode(robot, "#message-ok");
        // ★ 画面に出ている本文を読む。在り処を出す窓は本文を差し替えている（#137）ので、
        //   DialogPane#getContentText は画面に出ていない文字列でも通ってしまう。
        String message = robot.lookup("#message-text").queryAs(Label.class).getText();
        assertTrue(message.contains(kept.toString()), "控えの在り処を伝えていない（#138）: " + message);
        clickWhenReady(robot, "#message-ok");

        assertEquals(List.of("A1"), pageTexts(output), "書き出しそのものが邪魔されている");
        assertEquals(List.of("OLD"), pageTexts(kept), "伝えただけでなく、控えに触っている");
    }

    /**
     * 在り処の入ったフォルダを写せる。写しても窓は閉じず、フォーカスは OK へ戻る（#137）。
     *
     * <p><b>★★ 作業場所の名前は乱数であり、1 桁でも書き違えれば辿り着けない。</b>
     * <b>ファイルそのものは写さない</b>——アドレス欄へ貼るとファイルが開く。
     * <b>★ フォーカスが写したボタンに残ると、Windows では Enter がもう一度写す</b>——閉じるつもりの
     * Enter が効かない。
     */
    @Test
    void 在り処のフォルダを写せる(@TempDir Path dir, FxRobot robot) throws Exception {
        Path kept = crashedBeside(dir);

        openFixture(robot, TestPdfs.withText(dir.resolve("a.pdf"), "A1"));
        saveAs(robot, dir.resolve("out.pdf"));

        // ★ 押した結果（文言が変わること）を見て押し直す。窓が前面に出る前の 1 回目は取りこぼされうる
        //   （DesktopUiTest#clickUntilAccepted）——見ずにクリップボードを読むと、前に写したものが残っている。
        waitForNode(robot, "#message-copy-location-0");
        Button copy = button(robot, "#message-copy-location-0");
        clickUntilAccepted(
                robot, "#message-copy-location-0", () -> copy.getText().startsWith("フォルダ"));
        assertEquals("コピーしました", copy.getText(), "クリップボードへ書けなかった");
        assertEquals(
                kept.getParent().toString(),
                WaitForAsyncUtils.asyncFx(() -> Clipboard.getSystemClipboard().getString())
                        .get(),
                "在り処を写せない");
        assertTrue(messageWindow(robot).isShowing(), "写しただけで窓が閉じた。本文を読み終える前に消える");
        Node ok = robot.lookup("#message-ok").query();
        assertTrue(ok.isFocused(), "写したボタンにフォーカスが残っている。Enter で閉じない");
        clickWhenReady(robot, "#message-ok");
    }

    /**
     * 「コピーしました」は、最後に押したボタンにだけ出る（#137）。
     *
     * <p><b>★ クリップボードが持つのは最後の 1 つだけである。</b>前に押したボタンに残すと、
     * <b>もう入っていないものを入っていると言う</b>——戻ってきて貼った利用者は、別の作業場所へ行く。
     */
    @Test
    void コピーしましたは最後に押したボタンにだけ出る(@TempDir Path dir, FxRobot robot) throws Exception {
        crashedBeside(dir, ".pdfjig-1111111111");
        crashedBeside(dir, ".pdfjig-2222222222");

        openFixture(robot, TestPdfs.withText(dir.resolve("a.pdf"), "A1"));
        saveAs(robot, dir.resolve("out.pdf"));

        waitForNode(robot, "#message-copy-location-1");
        Button first = button(robot, "#message-copy-location-0");
        Button second = button(robot, "#message-copy-location-1");
        String firstLabel = first.getText();
        clickUntilAccepted(
                robot, "#message-copy-location-0", () -> first.getText().equals(firstLabel));
        clickUntilAccepted(
                robot, "#message-copy-location-1", () -> second.getText().startsWith("フォルダ"));

        assertEquals(firstLabel, first.getText(), "もう入っていないものを「コピーしました」と言っている");
        assertTrue(second.getText().startsWith("コピーしました"), "最後に押したほうが写したと言っていない");
        clickWhenReady(robot, "#message-ok");
    }

    /**
     * 在り処を出した窓も、窓の × で閉じる（#137）。
     *
     * <p><b>★★ JavaFX のダイアログは、ボタンバーにボタンが 2 つ以上あると、取り消し側のボタンが無い限り
     * × でも Esc でも閉じない</b>（{@code Dialog} の「Dialog Closing Rules」）。
     * <b>写すボタンをボタンバーへ足した形が、実際にこれで赤になった。</b>
     *
     * <p>× を押す代わりに、閉じる要求をその窓へ送る。OS の × が送るのと同じ出来事である。
     */
    @Test
    void 在り処を出した窓も閉じる要求で閉じる(@TempDir Path dir, FxRobot robot) throws Exception {
        crashedBeside(dir);

        openFixture(robot, TestPdfs.withText(dir.resolve("a.pdf"), "A1"));
        saveAs(robot, dir.resolve("out.pdf"));
        waitForNode(robot, "#message-copy-location-0");

        Window window = messageWindow(robot);
        robot.interact(() -> window.fireEvent(new WindowEvent(window, WindowEvent.WINDOW_CLOSE_REQUEST)));
        WaitForAsyncUtils.waitForFxEvents();

        assertFalse(window.isShowing(), "在り処を出した窓が × で閉じない");
    }

    /**
     * 在り処を出した窓も、Esc で閉じる（#137）。
     *
     * <p><b>★★ Esc は × と別の経路である</b>（{@code HeavyweightDialog} がキーを直に受け、
     * 閉じる要求を出さない）。<b>× だけを直しても Esc は閉じないまま残る</b>——門の 2 段目がそこを出した。
     */
    @Test
    void 在り処を出した窓もEscで閉じる(@TempDir Path dir, FxRobot robot) throws Exception {
        crashedBeside(dir);

        openFixture(robot, TestPdfs.withText(dir.resolve("a.pdf"), "A1"));
        saveAs(robot, dir.resolve("out.pdf"));
        waitForNode(robot, "#message-copy-location-0");

        Window window = messageWindow(robot);
        robot.type(KeyCode.ESCAPE);
        WaitForAsyncUtils.waitForFxEvents();

        assertFalse(window.isShowing(), "在り処を出した窓が Esc で閉じない");
    }

    /**
     * アプリごと落ちた後に残るものと同じ形の作業場所を、出力先の隣に置く。
     *
     * @return 控えのファイル
     */
    private static Path crashedBeside(Path dir) throws Exception {
        return crashedBeside(dir, ".pdfjig-1234567890");
    }

    private static Path crashedBeside(Path dir, String name) throws Exception {
        Path crashed = Files.createDirectory(dir.resolve(name));
        Files.createFile(crashed.resolve("held"));
        // ★ 本文は ASCII にする。TestPdfs は標準フォントで書くので、日本語を渡すと投げる。
        return TestPdfs.withText(crashed.resolve("replaced.pdf"), "OLD");
    }

    private static Window messageWindow(FxRobot robot) {
        return robot.lookup("#message-dialog")
                .queryAs(DialogPane.class)
                .getScene()
                .getWindow();
    }
}
