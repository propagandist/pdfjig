package io.github.propagandist.pdfjig.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.propagandist.pdfjig.core.TestPdfs;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.Start;
import org.testfx.framework.junit5.Stop;
import org.testfx.util.WaitForAsyncUtils;

/**
 * 上書き保存のあとの寄せ直しが、<b>走り出しさえしなかった</b>とき。
 *
 * <p><b>★★ 見ているのは印が立つことではなく、次の保存で文書が壊れないことである</b>（#118）。
 * {@code markSaved} は既に済んでおり、並びの基準は書き出したものへ移っている。
 * <b>そこで印を立て損ねると、保存が押せたまま残り、同じ変換がもう一度当たる。</b>
 *
 * <p><b>★ {@code BackgroundTasks#run} には抜け方が 3 つある</b>——走り出す、断る、
 * <b>始め方が投げる</b>。3 つ目は戻り値が返らないので、{@code if (!started)} で受けると素通りする（#145）。
 * ここで作るのはその 3 つ目である。
 *
 * <p><b>★ 実行係が投げると、その例外は TestFX に拾われて次のテストで投げ直される</b>
 * （{@code BackgroundTasksUiTest} の Javadoc に経緯がある）。<b>各テストで消してから戻る。</b>
 */
class ReopenFailureUiTest extends DesktopUiTest {

    /**
     * 何本目の仕事で始め方が投げるか。
     *
     * <p><b>1 本目が「開く」、2 本目が「書き出し」、3 本目が「寄せ直し」である。</b>
     * 数え方に頼っているので、<b>本数そのものも確かめる</b>——内側が変わったら、
     * 別のものを壊しているのではなく数え方が古いのだと分かるようにしておく。
     */
    private static final int REOPEN = 3;

    private final AtomicInteger submissions = new AtomicInteger();

    @Override
    BackgroundTasks tasks() {
        return new BackgroundTasks(work -> {
            if (submissions.incrementAndGet() == REOPEN) {
                throw new IllegalStateException("始められない");
            }
            Thread worker = new Thread(work, "counted-operation");
            worker.setDaemon(true);
            worker.start();
        });
    }

    @Start
    void start(Stage stage) {
        setUp(stage);
    }

    @Stop
    void stop() {
        tearDown();
    }

    @Test
    void 寄せ直しが始まらなかったら保存を押せなくする(@TempDir Path dir, FxRobot robot) throws Exception {
        try {
            Path document = TestPdfs.plain(dir.resolve("doc.pdf"), 3);
            openFixture(robot, document);

            robot.clickOn("#thumbnail-tile-0");
            robot.clickOn("#tool-rotate-right");
            saveOver(robot, document);

            // 書き出し自体は成功している。失われたものは無い。
            assertEquals(List.of(90, 0, 0), TestPdfs.rotationsOf(document), "書き出しが当たっていない");

            // ★ 寄せ直しが投げられるまで待つ。saveOver が待つのは「ファイルができたこと」までで、
            //   後始末は書き上がってから JavaFX スレッドへ乗る——待たずに数えると 2 本で見てしまう。
            //   投げる／投げないに依らずここまでは同じなので、赤い側でも待たされない。
            waitFor(() -> submissions.get() == REOPEN);
            WaitForAsyncUtils.waitForFxEvents();

            // ★★ ここが本体である。印が立たなければ保存は押せたままで、次の保存で二重に当たる。
            assertTrue(button(robot, "#tool-save").isDisabled(), "保存が押せる。次に押すと同じ変換が二重に当たる（#118）");
            assertTrue(statusText(robot).contains("開き直せませんでした"), "押せないだけでは、なぜ押せないのかが伝わらない");
        } finally {
            // ★ 投げさせた例外を、次のテストへ持ち越さない。
            WaitForAsyncUtils.clearExceptions();
        }
    }
}
