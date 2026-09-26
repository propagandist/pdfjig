package io.github.propagandist.pdfjig.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.propagandist.pdfjig.core.AccessPermissions;
import io.github.propagandist.pdfjig.core.EncryptionAlgorithm;
import io.github.propagandist.pdfjig.core.EncryptionInfo;
import io.github.propagandist.pdfjig.core.ErrorCode;
import io.github.propagandist.pdfjig.core.Password;
import io.github.propagandist.pdfjig.core.PdfBoxEncryption;
import io.github.propagandist.pdfjig.core.PdfDocument;
import io.github.propagandist.pdfjig.core.PdfjigException;
import io.github.propagandist.pdfjig.core.TestPdfs;
import java.nio.file.Files;
import java.nio.file.Path;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Labeled;
import javafx.scene.control.TitledPane;
import javafx.scene.input.KeyCode;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.Start;
import org.testfx.framework.junit5.Stop;
import org.testfx.util.WaitForAsyncUtils;

/**
 * パスワードと権限フラグを設定して書き出す経路（#30）。
 *
 * <p><b>★★ 書き出しを伴うので、出力ファイルを開き直して中身まで確かめる</b>
 * （{@code .claude/rules/ui-tests.md}）——<b>画面の上で何かが変わったことだけを見ても、
 * ファイルが正しい保証にはならない。</b>
 *
 * <p><b>★ 目視でしか判定できないものは、ここでは縛らない。</b>
 * <b>オーナーパスワードのみのときの注意文が誤解を招かないか</b>と
 * <b>詳細が畳んだ状態で邪魔にならないか</b>は人が見る（{@code docs/HANDOVER.md} 4-4）。
 */
class EncryptionUiTest extends DesktopUiTest {

    private static final String USER = "correct-horse";

    private static final String OWNER = "owner-key";

    @Start
    void start(Stage stage) {
        setUp(stage);
    }

    @Stop
    void stop() {
        tearDown();
    }

    @Test
    void 保護して保存すると鍵の要るファイルができる(@TempDir Path dir, FxRobot robot) throws Exception {
        Path output =
                protectTo(robot, TestPdfs.withText(dir.resolve("doc.pdf"), "P1", "P2"), dir.resolve("protected.pdf"));

        // ★ 鍵なしでは開けない。画面が何を出したかではなく、出来たファイルを見る。
        assertEquals(
                ErrorCode.PASSWORD_REQUIRED,
                assertThrows(PdfjigException.class, () -> PdfDocument.open(output))
                        .errorCode());

        try (Password user = Password.copyOf(USER);
                PdfDocument written = PdfDocument.open(output, user)) {
            assertEquals(2, written.pageCount());
            assertTrue(written.encrypted(), "保護が掛かっていない");
        }
    }

    @Test
    void 既定は全部許可のAES256である(@TempDir Path dir, FxRobot robot) throws Exception {
        Path output = protectTo(robot, TestPdfs.withText(dir.resolve("doc.pdf"), "P1"), dir.resolve("protected.pdf"));

        EncryptionInfo info;
        try (Password user = Password.copyOf(USER)) {
            info = new PdfBoxEncryption().inspect(output, user);
        }
        // 既定は AES-256（docs/SPEC.md §6.2）。
        assertEquals(EncryptionAlgorithm.AES_256, info.algorithm());
        assertEquals(AccessPermissions.all(), info.permissions(), "既定で何かを塞いでいる");
    }

    @Test
    void 詳細を開いて権限を外すと出力に効く(@TempDir Path dir, FxRobot robot) throws Exception {
        Path output = dir.resolve("protected.pdf");
        openPrompt(robot, TestPdfs.withText(dir.resolve("doc.pdf"), "P1"), output);

        // ★★ プリセットを外すと、束の中身が全部外れる（SPEC.md §6.2 の 2 段構成）。
        clickWhenReady(robot, "#encryption-allow-print");
        // ★★ 詳細を開いても「保護して保存」は押せたままである。窓を測り直さないと、伸びたぶんが
        //   窓の外へ出てボタンが消える（2026-09-15、CI windows で実測。EncryptionPrompt）。
        clickWhenReady(robot, "#encryption-details");
        waitFor(() ->
                robot.lookup("#encryption-details").queryAs(TitledPane.class).isExpanded());
        assertFalse(checkBox(robot, "#encryption-flag-print").isSelected(), "束を外したのに中身が残っている");
        assertFalse(checkBox(robot, "#encryption-flag-print-high-quality").isSelected(), "束を外したのに中身が残っている");

        // ★★ 支援技術のための複製は束に入っていない。塞ぐと視覚障害者が読めなくなる（§6.2）。
        clickWhenReady(robot, "#encryption-allow-extract");
        assertTrue(
                checkBox(robot, "#encryption-flag-extract-accessibility").isSelected(), "テキスト抽出を外したら支援技術のための複製まで落ちている");

        typeKeys(robot);
        applyAndWaitFor(robot, output);

        EncryptionInfo info;
        try (Password user = Password.copyOf(USER)) {
            info = new PdfBoxEncryption().inspect(output, user);
        }
        // ★★ 8 つまとめて見る。1 つずつ見ると、並びを取り違えた実装が素通りする
        //   （AccessPermissions は同じ型の boolean が 8 つ並んだ record である）。
        assertEquals(
                new AccessPermissions(false, true, false, true, true, true, true, false),
                info.permissions(),
                "外した権限と残した権限が食い違っている");
    }

    /**
     * 窓の文字が読める色で出る。
     *
     * <p><b>★★ 注意文と権限の見出しが、白に近い色で出ていた</b>（2026-09-26、捨てタグ {@code v0.0.6} の
     * 実機確認で見つかった）。Modena は文字の色を {@code -fx-background} の明るさから決める
     * （{@code -fx-text-background-color: ladder(-fx-background, …)}）。そこへ透明（明るさ 0）を当てると、
     * <b>暗い地だと読んで明るい文字を選ぶ。</b>欄の中の文字は別の色から決まるので、<b>欄だけが普通に見えた。</b>
     *
     * <p><b>この窓は、読んで分からなければ守りが無い</b>（{@code docs/HANDOVER.md} 4-4 の 17〜19 番）。
     * 見るのは色の明るさであって、読みやすさそのものではない。
     */
    @Test
    void 窓の文字は読める色で出る(@TempDir Path dir, FxRobot robot) throws Exception {
        openPrompt(robot, TestPdfs.withText(dir.resolve("doc.pdf"), "P1"), dir.resolve("protected.pdf"));

        for (String id : new String[] {"#encryption-owner-only-warning", "#encryption-allow-print"}) {
            Color fill = (Color) ((Labeled) node(robot, id)).getTextFill();
            assertTrue(fill.getBrightness() < 0.5, id + " の文字が明るすぎて読めない: " + fill);
        }

        clickWhenReady(robot, "#encryption-cancel");
        WaitForAsyncUtils.waitForFxEvents();
    }

    @Test
    void ユーザーパスワードが空なら申告制であることを出す(@TempDir Path dir, FxRobot robot) throws Exception {
        openPrompt(robot, TestPdfs.withText(dir.resolve("doc.pdf"), "P1"), dir.resolve("protected.pdf"));

        // ★★ 空のまま出ている。利用者はほぼ確実に「コピー禁止にしたから安全」と誤解する
        //   （docs/SPEC.md §6.1）ので、何が起きるのかをそこに書く。
        assertTrue(node(robot, "#encryption-owner-only-warning").isVisible(), "申告制であることを出していない");

        clickWhenReady(robot, "#encryption-user-password");
        robot.write(USER);
        // 入れれば開くのに鍵が要るので、申告制という話は当たらなくなる。
        waitFor(() -> !node(robot, "#encryption-owner-only-warning").isVisible());

        clickWhenReady(robot, "#encryption-cancel");
        WaitForAsyncUtils.waitForFxEvents();
    }

    @Test
    void オーナーパスワードが空なら権限が効かないことを出す(@TempDir Path dir, FxRobot robot) throws Exception {
        openPrompt(robot, TestPdfs.withText(dir.resolve("doc.pdf"), "P1"), dir.resolve("protected.pdf"));

        // ★★ PDFBox は空のオーナーパスワードをユーザーパスワードで埋める
        //   （StandardSecurityHandler#prepareDocumentForEncryption。2026-09-15 実測）。
        //   開けた人がオーナー権限を持つので、外した権限フラグは規約どおりの閲覧ソフトでも無視される。
        clickWhenReady(robot, "#encryption-user-password");
        robot.write(USER);
        waitFor(() -> node(robot, "#encryption-no-owner-warning").isVisible());

        clickWhenReady(robot, "#encryption-owner-password");
        robot.write(OWNER);
        // 入れれば権限の鍵が別になるので、この話は当たらなくなる。
        waitFor(() -> !node(robot, "#encryption-no-owner-warning").isVisible());

        clickWhenReady(robot, "#encryption-cancel");
        WaitForAsyncUtils.waitForFxEvents();
    }

    @Test
    void 両方に同じ鍵を打っても権限が効かないことを出す(@TempDir Path dir, FxRobot robot) throws Exception {
        openPrompt(robot, TestPdfs.withText(dir.resolve("doc.pdf"), "P1"), dir.resolve("protected.pdf"));

        // ★★ 読む側は isOwnerPassword を先に試す（StandardSecurityHandler
        //   #prepareDocumentForDecryption。3.0.8 の実装を読んで確かめた）。
        //   同じ文字列なら、渡した 1 つの鍵でオーナー扱いになる
        //   ——空のときだけを見る形だと、こちらが素通りする。
        clickWhenReady(robot, "#encryption-user-password");
        robot.write(USER);
        clickWhenReady(robot, "#encryption-owner-password");
        robot.write(USER);
        waitFor(() -> node(robot, "#encryption-no-owner-warning").isVisible());

        clickWhenReady(robot, "#encryption-cancel");
        WaitForAsyncUtils.waitForFxEvents();
    }

    @Test
    void 印刷を外すと高品質も落ちて押せなくなる(@TempDir Path dir, FxRobot robot) throws Exception {
        openPrompt(robot, TestPdfs.withText(dir.resolve("doc.pdf"), "P1"), dir.resolve("protected.pdf"));

        clickWhenReady(robot, "#encryption-details");
        waitFor(() ->
                robot.lookup("#encryption-details").queryAs(TitledPane.class).isExpanded());

        // ★★ 高品質の印刷は、印刷を許しているときしか意味を持たない（PDF 32000-1 の表 22）。
        //   チェックを残したままにできると、画面は「高品質で印刷できる」と言いながら
        //   出力は印刷を一切許さない（優先順位 2）。
        // ★★ 束ではなく、印刷そのものを外す。束を押すと中身を 2 つとも外すので、
        //   「印刷を外したら高品質も落ちる」仕掛けを消しても赤くならない。
        //   ★ 押さずに、焦点を送って空白で切り替える——詳細の中は流れるので、
        //   座標で押すと流れた先の節点に当たる（2026-09-16 実測。CI windows）。
        toggle(robot, "#encryption-flag-print");
        assertFalse(checkBox(robot, "#encryption-flag-print").isSelected(), "印刷が外れていない");
        assertFalse(checkBox(robot, "#encryption-flag-print-high-quality").isSelected(), "印刷を外したのに高品質が残っている");
        assertTrue(checkBox(robot, "#encryption-flag-print-high-quality").isDisabled(), "印刷を外したのに高品質を押せる");

        // ★★ 戻したら戻る。外すだけにすると、本人が外していない権限が黙って残る。
        toggle(robot, "#encryption-flag-print");
        assertTrue(checkBox(robot, "#encryption-flag-print-high-quality").isSelected(), "印刷を戻したのに高品質が落ちたままである");
        assertFalse(checkBox(robot, "#encryption-flag-print-high-quality").isDisabled(), "印刷を戻したのに高品質を押せない");

        clickWhenReady(robot, "#encryption-cancel");
        WaitForAsyncUtils.waitForFxEvents();
    }

    @Test
    void 束を付け直すと中身が全部戻る(@TempDir Path dir, FxRobot robot) throws Exception {
        openPrompt(robot, TestPdfs.withText(dir.resolve("doc.pdf"), "P1"), dir.resolve("protected.pdf"));

        clickWhenReady(robot, "#encryption-details");
        waitFor(() ->
                robot.lookup("#encryption-details").queryAs(TitledPane.class).isExpanded());

        // ★★ 外して、付け直す。中身が 2 つ以上ある束でしか出ない壊れ方である——
        //   1 つ目を動かした時点で束が押し戻されると、2 つ目から先が逆の値になる。
        clickWhenReady(robot, "#encryption-allow-modify");
        clickWhenReady(robot, "#encryption-allow-modify");

        assertTrue(checkBox(robot, "#encryption-flag-modify").isSelected(), "束を付け直したのに中身が戻っていない");
        assertTrue(checkBox(robot, "#encryption-flag-modify-annotations").isSelected(), "束を付け直したのに中身が戻っていない");
        assertTrue(checkBox(robot, "#encryption-flag-fill-forms").isSelected(), "束を付け直したのに中身が戻っていない");
        assertTrue(checkBox(robot, "#encryption-flag-assemble").isSelected(), "束を付け直したのに中身が戻っていない");
        // ★ 束の表示も揃っていること。ここがずれると、画面と出力が食い違う。
        assertTrue(checkBox(robot, "#encryption-allow-modify").isSelected(), "中身は全部立っているのに束が外れている");

        clickWhenReady(robot, "#encryption-cancel");
        WaitForAsyncUtils.waitForFxEvents();
    }

    @Test
    void 鍵の要る入力を誰でも開ける形で書き出すなら問う(@TempDir Path dir, FxRobot robot) throws Exception {
        // ★★ ユーザーパスワードを空にすると、出力は誰でも開ける（docs/SPEC.md §6.1）。
        //   鍵の要る入力が、誰でも開ける出力になる——「保護を掛けたから黙る」にすると、
        //   窓も出ず pdf-core の警告も出ない形で、それが静かに起きる。
        Path fixture = TestPdfs.encrypted(dir.resolve("locked.pdf"), USER, 1);
        openProtectedFixture(robot, fixture, USER);

        dialogs.willSaveTo(dir.resolve("owner-only.pdf"));
        protectFromMenu(robot);
        waitForNode(robot, "#encryption-dialog");

        clickWhenReady(robot, "#encryption-owner-password");
        robot.write(OWNER);
        clickWhenReady(robot, "#encryption-owner-password-confirm");
        robot.write(OWNER);
        clickWhenReady(robot, "#encryption-apply");

        // 保護が落ちることを伝える窓が出る（#29 / #192 と同じ口）。
        waitForNode(robot, "#protection-dialog");
        // ★★ 文言はこちら向けである。保護を掛けている最中に「保護を外して書き出す」と
        //   出すと、何を押しているのかが読んで分からなくなる（優先順位 2）
        //   ——オーナーパスワードと権限フラグは、確かに出力へ載る。
        assertEquals("このまま書き出す", button(robot, "#protection-proceed").getText(), "保護を掛けているのに「外す」と出ている");

        clickWhenReady(robot, "#protection-cancel");
        WaitForAsyncUtils.waitForFxEvents();
    }

    @Test
    void 確認が一致しなければ押せない(@TempDir Path dir, FxRobot robot) throws Exception {
        openPrompt(robot, TestPdfs.withText(dir.resolve("doc.pdf"), "P1"), dir.resolve("protected.pdf"));

        // ★★ どちらの鍵も空なら押せない。通すと「保護した」と思わせながら誰でも開ける文書ができる。
        assertTrue(button(robot, "#encryption-apply").isDisabled(), "鍵が空なのに押せる");

        clickWhenReady(robot, "#encryption-user-password");
        robot.write(USER);
        clickWhenReady(robot, "#encryption-user-password-confirm");
        robot.write(USER + "-typo");
        // ★★ 打ち間違えると、開けない文書ができあがる（docs/SPEC.md §6）。
        assertTrue(button(robot, "#encryption-apply").isDisabled(), "確認が一致していないのに押せる");

        clickWhenReady(robot, "#encryption-cancel");
        WaitForAsyncUtils.waitForFxEvents();
    }

    // ── 補助 ────────────────────────────────────────────────────────────────

    /** 文書を開き、書き出し先を仕込んで、保護の窓を出す。 */
    private void openPrompt(FxRobot robot, Path fixture, Path output) throws Exception {
        openFixture(robot, fixture);
        dialogs.willSaveTo(output);
        protectFromMenu(robot);
        waitForNode(robot, "#encryption-dialog");
    }

    /** 開いて、既定のまま保護して書き出し、ファイルができるまで待つ。 */
    private Path protectTo(FxRobot robot, Path fixture, Path output) throws Exception {
        openPrompt(robot, fixture, output);
        typeKeys(robot);
        applyAndWaitFor(robot, output);
        return output;
    }

    /**
     * 「保護して保存」を押して、ファイルができるまで待つ。
     *
     * <p><b>★ 落ちる場所を 3 つに分けてある。</b>押せない状態のまま押しても何も起きないので、
     * <b>「ファイルができない」だけでは、押せていないのか書けなかったのかが読めない</b>
     * ——押す前に押せることを見て、押した後に窓が閉じたことを見てから、ファイルを待つ。
     */
    private void applyAndWaitFor(FxRobot robot, Path output) throws Exception {
        assertFalse(button(robot, "#encryption-apply").isDisabled(), "鍵を打ったのに「保護して保存」が押せないままである");
        clickWhenReady(robot, "#encryption-apply");
        waitFor(() -> robot.lookup("#encryption-dialog").tryQuery().isEmpty());
        waitForWritten(output);
    }

    /**
     * 書き出しが終わるのを待つ。
     *
     * <p><b>★ 新しく作られる出力にしか使えない</b>（{@code DesktopUiTest#saveAs} と同じ限界）。
     * <b>既にあるファイルを渡すと、書き出す前に条件が満たされる</b>——上書きを見たくなったら
     * {@code saveOver} と同じく更新時刻で待つこと。
     */
    private static void waitForWritten(Path output) throws Exception {
        waitFor(() -> Files.exists(output) && Files.size(output) > 0);
        WaitForAsyncUtils.waitForFxEvents();
    }

    /**
     * ツールメニューから「パスワードで保護して保存…」を選ぶ。
     *
     * <p><b>★ ここだけは文言で掴む</b>（{@code .claude/rules/ui-tests.md}）——
     * <b>{@code MenuItem} は {@code Node} ではなく、id では掴めない。</b>
     * ツールバーには出していない操作なので（{@code MainWindow#buildActions}）、
     * <b>画面から辿れる道はメニューだけである。</b>
     *
     * <p><b>★ 選べるようになるのを待つ。</b>「保護して保存」と「保存」は同じ
     * {@code editingBlocked} で縛られており、<b>読み込みが走っている間に選んでも何も起きない。</b>
     * 待たずに開くと「ダイアログが出てこない」形で落ち、原因が読めなくなる。
     */
    private void protectFromMenu(FxRobot robot) throws Exception {
        waitFor(() -> !button(robot, "#tool-save").isDisabled());
        robot.clickOn("ツール");
        robot.clickOn("パスワードで保護して保存…");
        WaitForAsyncUtils.waitForFxEvents();
    }

    /** 4 つの欄を埋める。確認の再入力まで含める（docs/SPEC.md §6）。 */
    private void typeKeys(FxRobot robot) throws Exception {
        clickWhenReady(robot, "#encryption-user-password");
        robot.write(USER);
        clickWhenReady(robot, "#encryption-user-password-confirm");
        robot.write(USER);
        clickWhenReady(robot, "#encryption-owner-password");
        robot.write(OWNER);
        clickWhenReady(robot, "#encryption-owner-password-confirm");
        robot.write(OWNER);
    }

    /**
     * 節点へ焦点を送って、空白で切り替える。
     *
     * <p><b>★★ 座標で押さない。</b>詳細の中身は {@code ScrollPane} の外へ流れるが、
     * <b>流れた節点も scene の矩形とは重なったまま</b>なので、
     * <b>待ち合わせは通るのに押した先が別の節点になる</b>
     * （<b>2026-09-16 実測</b>。CI windows で 2 度踏んだ）。
     *
     * <p><b>★ 吸収ではない。</b>焦点が入らなければ上限まで待って落ちる。
     * <b>人はキーボードでも同じことをする</b>ので、見ている仕掛けは変わらない。
     */
    private static void toggle(FxRobot robot, String id) throws Exception {
        Node node = node(robot, id);
        Platform.runLater(node::requestFocus);
        waitFor(node::isFocused);
        robot.type(KeyCode.SPACE);
        WaitForAsyncUtils.waitForFxEvents();
    }

    private static CheckBox checkBox(FxRobot robot, String id) {
        return robot.lookup(id).queryAs(CheckBox.class);
    }

    private static Node node(FxRobot robot, String id) {
        return robot.lookup(id).query();
    }
}
