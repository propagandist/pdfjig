package io.github.propagandist.pdfjig.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.propagandist.pdfjig.core.TestPdfs;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javafx.scene.control.DialogPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.Start;
import org.testfx.framework.junit5.Stop;

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
        Path crashed = Files.createDirectory(dir.resolve(".pdfjig-1234567890"));
        Files.createFile(crashed.resolve("held"));
        // ★ 本文は ASCII にする。TestPdfs は標準フォントで書くので、日本語を渡すと投げる。
        Path kept = TestPdfs.withText(crashed.resolve("replaced.pdf"), "OLD");

        openFixture(robot, TestPdfs.withText(dir.resolve("a.pdf"), "A1"));
        Path output = saveAs(robot, dir.resolve("out.pdf"));

        waitForNode(robot, "#message-ok");
        String message =
                robot.lookup("#message-dialog").queryAs(DialogPane.class).getContentText();
        assertTrue(message.contains(kept.toString()), "控えの在り処を伝えていない（#138）: " + message);
        clickWhenReady(robot, "#message-ok");

        assertEquals(List.of("A1"), pageTexts(output), "書き出しそのものが邪魔されている");
        assertEquals(List.of("OLD"), pageTexts(kept), "伝えただけでなく、控えに触っている");
    }
}
