package io.github.propagandist.pdfjig.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.propagandist.pdfjig.core.TestPdfs;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeoutException;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.Start;
import org.testfx.framework.junit5.Stop;

/**
 * 開いている文書へ、それ自身の名前で上書き保存する。
 *
 * <p><b>★★ この経路は #118 まで自動テストを 1 本も通っていなかった</b>——
 * {@code DesktopUiTest#saveAs} が既存ファイルを待てず（{@link DesktopUiTest#saveOver} の Javadoc）、
 * 呼び出し元 5 つはすべて新規パスを渡していた。<b>手元でも CI でも全部緑のまま、
 * 保存するたびに文書が壊れていた。</b>
 *
 * <p><b>★ 並べ替えの筋はここに無い。</b>{@code ReorderUiTest} に置いてある——
 * ドラッグは windows ランナーで 1 本 19 分かかり、あちらは既にその費用を払って CI から外れている。
 * <b>ここへ持ってくると、この 4 本ごと CI から落ちる。</b>
 *
 * <p><b>見るのは「2 回保存しても結果が変わらないこと」である。</b>
 * {@code assemble} は毎回ディスクから読み直すので、出どころが書き出したものに入れ替わったまま
 * 同じ指定をもう一度当てると<b>同じ変換が二重に掛かる。</b>
 */
class OverwriteSaveUiTest extends DesktopUiTest {

    @Start
    void start(Stage stage) {
        setUp(stage);
    }

    @Stop
    void stop() {
        tearDown();
    }

    @Test
    void 上書き保存を繰り返しても回転が増えていかない(@TempDir Path dir, FxRobot robot) throws Exception {
        Path document = TestPdfs.plain(dir.resolve("doc.pdf"), 3);
        openFixture(robot, document);

        robot.clickOn("#thumbnail-tile-0");
        robot.clickOn("#tool-rotate-right");

        saveOver(robot, document);
        assertEquals(List.of(90, 0, 0), TestPdfs.rotationsOf(document), "1 回目が既に違う");

        // 何も操作せずにもう一度保存する。利用者から見て何も変わらないはずの操作である。
        saveOver(robot, document);
        assertEquals(List.of(90, 0, 0), TestPdfs.rotationsOf(document), "保存するたびに 90 度ずつ回っている（#118）");

        // 3 回目まで見る。2 回目だけを見ると「180 で止まる」と読めてしまう。
        saveOver(robot, document);
        assertEquals(List.of(90, 0, 0), TestPdfs.rotationsOf(document), "押すたびに回り続けている（#118）");
    }

    @Test
    void 削除したあと上書き保存を繰り返してもページが減っていかない(@TempDir Path dir, FxRobot robot) throws Exception {
        Path document = TestPdfs.withText(dir.resolve("doc.pdf"), "P1", "P2", "P3");
        openFixture(robot, document);

        robot.clickOn("#thumbnail-tile-1");
        robot.clickOn("#tool-delete");

        saveOver(robot, document);
        assertEquals(List.of("P1", "P3"), pageTexts(document), "1 回目が既に違う");

        // 寄せ直していないと、2 ページの文書に 3 番を要求して PAGE_OUT_OF_RANGE で止まる。
        // そのときは保存そのものが失敗するので、更新時刻が進まず saveOver が待ちきれずに落ちる。
        saveOver(robot, document);
        // 枚数だけを見ない。どのページが残ったかまで見ないと、入れ替わりを見逃す。
        assertEquals(List.of("P1", "P3"), pageTexts(document), "2 回目で中身が変わっている（#118）");
    }

    /**
     * 上書き保存のあとも、選んでいたページと区切りが残る。
     *
     * <p><b>★ 寄せ直しは「開く」なので、放っておくと先頭へ戻って区切りも消える。</b>
     * 消すと、#118 を直しながら別の使い勝手を壊すことになる。
     *
     * <p><b>選んでいるページは、保存のあとにもう一度回してどれが回ったかで見る</b>——
     * 選択そのものは画面の外から掴めないが、<b>次の操作がどこに当たるかが利用者にとっての意味である。</b>
     */
    @Test
    void 上書き保存のあとも選んでいたページと区切りが残る(@TempDir Path dir, FxRobot robot) throws Exception {
        Path document = TestPdfs.plain(dir.resolve("doc.pdf"), 4);
        openFixture(robot, document);

        robot.clickOn("#thumbnail-tile-2");
        robot.clickOn("#tool-toggle-break");
        robot.clickOn("#tool-rotate-right");

        saveOver(robot, document);
        assertEquals(List.of(0, 0, 90, 0), TestPdfs.rotationsOf(document), "1 回目が既に違う");

        // 区切りが残っていることは状態行で見る。寄せ直しが終わるまでの待ちも兼ねる。
        waitFor(() -> statusText(robot).contains("区切り 1 か所"));

        // 選び直さずにもう一度回す。押せるようになるまで押し直す（寄せ直しの間は押せない）。
        clickUntilAccepted(robot, "#tool-rotate-right", () -> !statusText(robot).contains("未保存"));
        saveOver(robot, document);

        assertEquals(List.of(0, 0, 180, 0), TestPdfs.rotationsOf(document), "選んでいたページが先頭に戻っている——次の操作が別のページに当たる");
    }

    /**
     * 複数のファイルを開いていても、どれか 1 つへ上書き保存すればその 1 つに寄る。
     *
     * <p><b>★ 書き出したファイルには全ページが入っている。</b>
     * だから以後の出どころが 1 つになるのは意味的に正しい——
     * <b>ただし題名から「ほか N ファイル」が消え、凡例も出なくなる。</b>
     * <b>誰もそう決めていなかったので、ここで固める</b>（#118 のレビュー）。
     *
     * <p><b>★ 2 つ以上の出どころから書き出すと、文書情報の警告が必ず出る。</b>
     * モーダルなので<b>閉じるまで画面は何も進まない</b>——閉じ忘れるとテストごとハングする
     * （2026-08-31 に実際に 30 分ハングさせた）。
     */
    @Test
    void 複数ファイルでも上書き保存すればその1つに寄る(@TempDir Path dir, FxRobot robot) throws Exception {
        Path document = TestPdfs.withText(dir.resolve("doc.pdf"), "A1", "A2");
        openFixture(robot, document);

        addFixtures(robot, TestPdfs.withText(dir.resolve("more.pdf"), "B1"));
        waitForNode(robot, "#thumbnail-tile-2");
        assertEquals("3 / 3 ページ（2 ファイル）", statusText(robot));

        saveOver(robot, document);

        // ★★ 出どころが 2 つ以上あると、文書情報の出どころについて必ず警告が出る。
        //   モーダルなので、閉じるまで画面は何も進まない——閉じないとテストごとハングする。
        clickWhenReady(robot, "#message-ok");

        // 寄せ直しが終わると出どころは 1 つになる。この変化が、待ちの条件でもある。
        waitFor(() -> statusText(robot).equals("3 / 3 ページ"));
        assertEquals(List.of("A1", "A2", "B1"), pageTexts(document));

        // もう一度保存しても増えも減りもしない。出どころは 1 つになったので警告も出ない。
        saveOver(robot, document);
        assertEquals(List.of("A1", "A2", "B1"), pageTexts(document), "2 回目で中身が変わっている（#118）");
    }

    /**
     * 別の名前へ保存したときは寄せ直さない。
     *
     * <p><b>元のファイルは変わっていないので、いまの並びが正しい。</b>
     * 寄せ直すと「名前を付けて保存」が作業対象を切り替えることになり、
     * <b>#118 の範囲を超えた挙動の変更になる。</b>
     */
    @Test
    void 別の名前へ保存したときは元の文書のままである(@TempDir Path dir, FxRobot robot) throws Exception {
        Path document = TestPdfs.plain(dir.resolve("doc.pdf"), 3);
        openFixture(robot, document);

        robot.clickOn("#thumbnail-tile-0");
        robot.clickOn("#tool-rotate-right");

        assertEquals(List.of(90, 0, 0), TestPdfs.rotationsOf(saveAs(robot, dir.resolve("out1.pdf"))));
        // 同じ並びのまま別の名前へもう一度。元の doc.pdf を見ているので結果は変わらない。
        assertEquals(List.of(90, 0, 0), TestPdfs.rotationsOf(saveAs(robot, dir.resolve("out2.pdf"))));
        assertEquals(List.of(0, 0, 0), TestPdfs.rotationsOf(document), "別の名前へ保存したのに元が書き換わっている");
    }

    /**
     * 上書き保存のあと、フォルダに隠しものが残らない。
     *
     * <p><b>★★ 「置き換えが済んだら控えを捨てる」という後始末を見る</b>（#119）。
     * 退避した控えが残っていると {@code OutputWorkspace} は<b>作業場所ごと残す</b>——
     * <b>「元がここにしか無い」の印だからである。</b>捨て忘れると、
     * <b>保存が成功するたびに {@code .pdfjig-*} が 1 つずつ増え、二度と消えない。</b>
     *
     * <p><b>★ ここでしか見られない。</b>捨てるのは {@code DocumentWriter#assemble} で、
     * <b>そこを通すには実物の PDF が要る</b>——{@code DocumentWriterTest} は
     * {@code move} までしか呼べない（あちらは PDF を持たない側である）。
     *
     * <p><b>待つのは、後始末が書き出しの後ろで続くからである。</b>{@code saveOver} が
     * 見ているのは出力先の更新時刻までで、作業場所を片づけるのはその先にある。
     */
    @Test
    void 上書き保存のあとフォルダに隠しものが残らない(@TempDir Path dir, FxRobot robot) throws Exception {
        Path document = TestPdfs.plain(dir.resolve("doc.pdf"), 2);
        openFixture(robot, document);

        robot.clickOn("#thumbnail-tile-0");
        robot.clickOn("#tool-rotate-right");
        saveOver(robot, document);

        // ★ 中身も見る。片づいたことだけを見ると、何も書けていない保存でも緑になる。
        assertEquals(List.of(90, 0), TestPdfs.rotationsOf(document), "書き出せていない");

        // ★ 待つのは後始末が書き出しの後ろで続くからである。saveOver が見ているのは
        //   出力先の更新時刻までで、作業場所を片づけるのはその先にある。
        //   ★★ 上限で落ちるのは TimeoutException であり、理由は出ない。捕まえて書く。
        try {
            waitFor(() -> namesIn(dir).equals(List.of("doc.pdf")));
        } catch (TimeoutException e) {
            throw new AssertionError("置き換えの控えが残ると、作業場所ごと二度と片づかない（#119）。残っているもの: " + namesIn(dir), e);
        }
    }
}
