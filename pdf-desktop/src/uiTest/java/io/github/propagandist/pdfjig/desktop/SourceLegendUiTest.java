package io.github.propagandist.pdfjig.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.propagandist.pdfjig.core.TestPdfs;
import java.nio.file.Path;
import java.util.List;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.Start;
import org.testfx.framework.junit5.Stop;
import org.testfx.util.WaitForAsyncUtils;

/**
 * ファイル一覧の「×」から 1 ファイルを外す。
 *
 * <p><b>★★ この道具で唯一取り消せない操作である。</b>外したファイルに対して行った
 * 並べ替えや回転も一緒に消える（{@code Messages#confirmRemoveSource}）。
 *
 * <p><b>★★ #115 まで、その入口には id が 1 つも無く、自動テストが 1 本も通っていなかった。</b>
 * 確認ダイアログの側の id は #57 で付いていたが、<b>そこへ至る「×」を掴めなかったので、
 * ダイアログごと誰も通っていなかった。</b>触れる手順は
 * {@code docs/HANDOVER.md} 4-4 の 11 番にしかなかった。
 *
 * <p><b>★ 11 番が自動化されたわけではない。</b>あちらが見ているのは<b>サムネイルの描き直し</b>
 * （外した後に絵が入れ替わらないこと／空のタイルが残らないこと）であり、
 * <b>どちらもここでは見ていない。</b>「×」を押すという<b>動作が同じ</b>だけである。
 *
 * <p><b>★ 一覧は 2 ファイル以上でしか出ない。</b>1 つしか開いていなければ表題で足りる
 * （{@code SourceLegend}）ので、どのテストもまず「追加」でもう 1 つ開く。
 */
class SourceLegendUiTest extends DesktopUiTest {

    @Start
    void start(Stage stage) {
        setUp(stage);
    }

    @Stop
    void stop() {
        tearDown();
    }

    /**
     * 「×」を押して承認すると、そのファイルのページが外れる。
     *
     * <p><b>利用者と同じ道を通る</b>——「×」を id で掴み、確認ダイアログを
     * {@code #remove-source-ok} で承認する。<b>{@code Messages#confirmRemoveSource} の
     * 初めての自動テストである。</b>
     */
    @Test
    void 承認するとそのファイルのページが外れる(@TempDir Path dir, FxRobot robot) throws Exception {
        openTwo(robot, dir);

        robot.clickOn("#source-remove-1");
        clickWhenReady(robot, "#remove-source-ok");

        waitFor(() -> statusText(robot).equals("2 / 2 ページ"));

        // ★ 枚数だけを見ない。どのファイルのページが残ったかは、書き出して初めて確かめられる
        //   （CLAUDE.md「画面のテスト」）。1 ファイルに減っているので文書情報の警告は出ない。
        assertEquals(List.of("A1", "A2"), pageTexts(saveAs(robot, dir.resolve("out.pdf"))));
    }

    /**
     * 取り消すと何も起きない。
     *
     * <p><b>取り消せない操作なので、断る側も確かめる。</b>確認を出しておいて
     * 「キャンセル」でも外れるなら、確認は嘘になる（{@code CLAUDE.md} 優先順位 2）。
     */
    @Test
    void 取り消せば何も外れない(@TempDir Path dir, FxRobot robot) throws Exception {
        openTwo(robot, dir);

        robot.clickOn("#source-remove-1");
        clickWhenReady(robot, "#remove-source-cancel");
        WaitForAsyncUtils.waitForFxEvents();

        // 何も起きていないことを見るので、書き出しは要らない——枚数もファイル数も動いていない。
        assertEquals("3 / 3 ページ（2 ファイル）", statusText(robot));
        assertTrue(robot.lookup("#source-remove-1").tryQuery().isPresent(), "断ったのに一覧から消えている");
    }

    /**
     * 外したファイルの id が一覧に残らない。
     *
     * <p><b>★ サムネイルのタイルと同じ注意である</b>（{@code CLAUDE.md}「命名」）——
     * <b>残すと、同じ id の節点が一覧に 2 つ並ぶ。</b>
     *
     * <p><b>ここでは 3 ファイルから 1 つ外して 2 つにする。</b>2 ファイルから外すと
     * 一覧そのものが消えてしまい（1 ファイルでは出ない）、<b>「作り直したあとに残っていないか」を
     * 見たことにならない。</b>
     */
    @Test
    void 外したファイルのidが一覧に残らない(@TempDir Path dir, FxRobot robot) throws Exception {
        openFixture(robot, TestPdfs.withText(dir.resolve("a.pdf"), "A1"));
        addFiles(robot, TestPdfs.withText(dir.resolve("b.pdf"), "B1"), TestPdfs.withText(dir.resolve("c.pdf"), "C1"));
        assertEquals("3 / 3 ページ（3 ファイル）", statusText(robot));

        robot.clickOn("#source-remove-0");
        clickWhenReady(robot, "#remove-source-ok");

        waitFor(() -> statusText(robot).equals("2 / 2 ページ（2 ファイル）"));
        // 3 つ目の位置は空いた。残っていると、次に 3 ファイル開いたとき同じ id が 2 つ並ぶ。
        assertTrue(robot.lookup("#source-remove-2").tryQuery().isEmpty(), "外したぶんの id が一覧に残っている（#115）");

        // ★★ 位置で決まる id なので、繰り下がった先が別のファイルを指す。そこまで見ないと、
        //   この変更が防ごうとしている失敗——ある「×」が別のファイルを外すこと——が緑のまま通る。
        assertEquals("b.pdf をこの編集から外す", accessibleTextOf(robot, "#source-remove-0"), "繰り下がった先が元のファイルを指したままである");
        assertEquals("c.pdf をこの編集から外す", accessibleTextOf(robot, "#source-remove-1"));
    }

    /**
     * 「×」から名前が読める。
     *
     * <p><b>★★ 支援技術から見えるのは Name だけである</b>（{@code CLAUDE.md}「JavaFX」）。
     * {@code setId} は届かない——JavaFX は AutomationId に内部の連番を返す。
     *
     * <p><b>名前を入れてあるのは、ファイルが並んだときに区別が付かないと危ういからである</b>
     * ——取り消せない操作の入口が、読み上げからは同じボタンに聞こえる。
     */
    @Test
    void 外すボタンから名前が読める(@TempDir Path dir, FxRobot robot) throws Exception {
        openTwo(robot, dir);

        assertEquals("a.pdf をこの編集から外す", accessibleTextOf(robot, "#source-remove-0"));
        assertEquals("b.pdf をこの編集から外す", accessibleTextOf(robot, "#source-remove-1"));
    }

    /** A（2 ページ）と B（1 ページ）を開く。作法は {@link DesktopUiTest#openTwoFiles} が持つ。 */
    private void openTwo(FxRobot robot, Path dir) throws Exception {
        assertEquals("3 / 3 ページ（2 ファイル）", openTwoFiles(robot, dir));
    }

    /** 「追加」で足して、一覧が出そろうまで待つ。 */
    private void addFiles(FxRobot robot, Path... paths) throws Exception {
        addFixtures(robot, paths);
        waitForNode(robot, "#source-remove-0");
    }

    /** 節点に付いた、支援技術から読まれる名前。 */
    private static String accessibleTextOf(FxRobot robot, String id) {
        return button(robot, id).getAccessibleText();
    }
}
