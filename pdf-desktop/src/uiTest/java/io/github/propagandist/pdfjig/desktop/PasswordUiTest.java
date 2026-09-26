package io.github.propagandist.pdfjig.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.propagandist.pdfjig.core.PdfDocument;
import io.github.propagandist.pdfjig.core.TestPdfs;
import java.nio.file.Files;
import java.nio.file.Path;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
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
        openWithKey(dir, robot);

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

    @Test
    void 保存のたびに鍵を訊く(@TempDir Path dir, FxRobot robot) throws Exception {
        openWithKey(dir, robot);

        Path output = dir.resolve("saved.pdf");
        dialogs.willSaveTo(output);
        pressSave(robot);

        // ★ 先に保護の窓が出る（#29 / #192）。鍵を訊くより前である——中止されたら
        //   1 文字も打たせずに済むため。あちらは ProtectionPromptUiTest が縛る。
        clickWhenReady(robot, "#protection-proceed");

        // ★★ ここでもう一度訊かれる。セッションは鍵を抱えないので、書き出しに要る鍵は
        //   そのたびに打つ（#193）。抱えると、文書を開いている間ずっと平文の鍵が
        //   ヒープに残る——docs/RELEASE_NOTES.md がその形の破れを 1 本配っている。
        waitForNode(robot, "#password-field");
        clickWhenReady(robot, "#password-field");
        robot.write(CORRECT);
        clickWhenReady(robot, "#password-unlock");

        waitFor(() -> Files.exists(output) && Files.size(output) > 0);
        WaitForAsyncUtils.waitForFxEvents();

        // ★ 中身まで見る。画面が変わったことは、ファイルが正しい保証にならない（ui-tests.md）。
        //   ★★ 書き出したものは平文である（EncryptionPropagation.NONE）ので、鍵なしで開ける。
        try (PdfDocument written = PdfDocument.open(output)) {
            assertEquals(1, written.pageCount());
        }
    }

    @Test
    void 保存のときに取り消せば何も書かれない(@TempDir Path dir, FxRobot robot) throws Exception {
        openWithKey(dir, robot);

        Path output = dir.resolve("saved.pdf");
        dialogs.willSaveTo(output);
        pressSave(robot);
        clickWhenReady(robot, "#protection-proceed");

        waitForNode(robot, "#password-field");
        clickWhenReady(robot, "#password-cancel");
        WaitForAsyncUtils.waitForFxEvents();

        // ★ 書き出しに入る前に止まる。作業場所も作らない。
        assertTrue(Files.notExists(output), "取り消したのに書き出されている");
        waitFor(() -> !button(robot, "#tool-save").isDisabled());
    }

    /**
     * 足すファイルの鍵を訊く窓は、既に開いている同じ名前のファイルと区別が付く（#128）。
     *
     * <p><b>★★ 窓は足す前に出る。</b>名前だけだと、<b>どちらの鍵を訊かれているのか分からず、
     * 別の文書の鍵を打たせる。</b>
     */
    @Test
    void 足すファイルの鍵を訊く窓は同じ名前のファイルと区別が付く(@TempDir Path dir, FxRobot robot) throws Exception {
        openFixture(
                robot,
                TestPdfs.withText(Files.createDirectory(dir.resolve("work")).resolve("r.pdf"), "A1"));
        addFixtures(
                robot,
                TestPdfs.encrypted(Files.createDirectory(dir.resolve("archive")).resolve("r.pdf"), CORRECT));

        waitForNode(robot, "#password-field");
        String asked =
                robot.lookup("#password-explanation").queryAs(Label.class).getText();
        assertTrue(asked.startsWith("r.pdf（archive） "), "どちらの r.pdf の鍵を訊いているのかを言っていない: " + asked);
        clickWhenReady(robot, "#password-cancel");
        WaitForAsyncUtils.waitForFxEvents();
    }

    /**
     * 1 度に選んだ中の同じ名前のファイルとも、鍵を訊く窓で区別が付く（#128 の門）。
     *
     * <p><b>鍵の窓は、まだ足していない相手がいるうちに出る。</b>足し終えたものとだけ比べると、
     * <b>一緒に選んだもう 1 つの r.pdf と区別が付かない。</b>
     */
    @Test
    void 一緒に選んだ同じ名前のファイルとも区別が付く(@TempDir Path dir, FxRobot robot) throws Exception {
        openFixture(robot, TestPdfs.withText(dir.resolve("a.pdf"), "A1"));
        // 名前が同じなので、並べ替えても選んだ順のまま残る。鍵の要るほうを先に足させる。
        addFixtures(
                robot,
                TestPdfs.encrypted(Files.createDirectory(dir.resolve("archive")).resolve("r.pdf"), CORRECT),
                TestPdfs.withText(Files.createDirectory(dir.resolve("work")).resolve("r.pdf"), "W1"));

        waitForNode(robot, "#password-field");
        String asked =
                robot.lookup("#password-explanation").queryAs(Label.class).getText();
        assertTrue(asked.startsWith("r.pdf（archive） "), "一緒に選んだ r.pdf と区別が付かない: " + asked);
        clickWhenReady(robot, "#password-cancel");
        WaitForAsyncUtils.waitForFxEvents();
    }

    /**
     * 書き出しの鍵を訊く窓でも、同じ名前のファイルは区別が付く（#128 の門）。
     *
     * <p><b>★★ 鍵は出どころごとに違いうる。</b>どちらの鍵かが読めないと、<b>別の文書の鍵を打たせる。</b>
     */
    @Test
    void 書き出しの鍵を訊く窓でも同じ名前のファイルは区別が付く(@TempDir Path dir, FxRobot robot) throws Exception {
        dialogs.willOpen(
                TestPdfs.encrypted(Files.createDirectory(dir.resolve("work")).resolve("r.pdf"), CORRECT));
        robot.clickOn("#tool-open");
        enterKey(robot);
        waitForNode(robot, "#thumbnail-tile-0");
        addFixtures(
                robot,
                TestPdfs.encrypted(Files.createDirectory(dir.resolve("archive")).resolve("r.pdf"), CORRECT));
        enterKey(robot);
        waitForNode(robot, "#source-remove-1");

        dialogs.willSaveTo(dir.resolve("saved.pdf"));
        pressSave(robot);
        clickWhenReady(robot, "#protection-proceed");

        waitForNode(robot, "#password-field");
        assertTrue(
                explanation(robot).startsWith("r.pdf（work） "), "1 つ目の鍵がどちらの r.pdf のものか言っていない: " + explanation(robot));
        enterKey(robot);
        waitFor(() -> explanation(robot).startsWith("r.pdf（archive） "));
        clickWhenReady(robot, "#password-cancel");
        WaitForAsyncUtils.waitForFxEvents();
    }

    // ── 補助 ────────────────────────────────────────────────────────────────

    /** 出ている鍵の窓に、正しい鍵を打って進める。 */
    private void enterKey(FxRobot robot) throws Exception {
        waitForNode(robot, "#password-field");
        clickWhenReady(robot, "#password-field");
        robot.write(CORRECT);
        clickWhenReady(robot, "#password-unlock");
        WaitForAsyncUtils.waitForFxEvents();
    }

    /** 出ている鍵の窓の説明。出ていなければ空の文字列。 */
    private static String explanation(FxRobot robot) {
        return robot.lookup("#password-explanation")
                .tryQuery()
                .map(node -> ((Label) node).getText())
                .orElse("");
    }

    /** 暗号化されたフィクスチャを、正しい鍵で開くところまで進める。 */
    private void openWithKey(Path dir, FxRobot robot) throws Exception {
        askFor(dir, robot);
        clickWhenReady(robot, "#password-field");
        robot.write(CORRECT);
        clickWhenReady(robot, "#password-unlock");
        waitForNode(robot, "#thumbnail-tile-0");
        WaitForAsyncUtils.waitForFxEvents();
    }

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
