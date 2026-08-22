package io.github.propagandist.pdfjig.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.propagandist.pdfjig.core.PageText;
import io.github.propagandist.pdfjig.core.PdfBoxTextExtraction;
import io.github.propagandist.pdfjig.core.PdfDocument;
import io.github.propagandist.pdfjig.core.TestPdfs;
import java.nio.file.Path;
import java.util.List;
import javafx.scene.input.MouseButton;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.Start;
import org.testfx.framework.junit5.Stop;
import org.testfx.util.WaitForAsyncUtils;

/**
 * サムネイルを掴んで並べ替える。
 *
 * <p><b>★ CI では走らせていない。</b> 手元では 2 秒で終わるが、GitHub の windows ランナーでは
 * <b>この 1 本だけで 19 分 20 秒かかった</b>（2026-08-22 実測。他の 24 本は合計 61 秒）。
 * 通ってはいるので、落とすためではなく、時間のために外してある。
 *
 * <p>理由は TestFX の造りにある。{@code MoveRobotImpl} はマウスの移動を距離ぶんのステップに
 * 刻み（既定で最大 200）、各ステップを {@code asyncFx} で JavaFX スレッドへ積む。
 * ドラッグの最中、そのスレッドは Windows のドラッグ&amp;ドロップのモーダルループにいて、
 * 積まれた移動を 1 つずつしか捌けない。ランナーでは 1 つあたり約 5.8 秒かかっていた
 * （1160 秒 ÷ 200 ステップ）。最後の {@code move()} が、その全部を待つ。
 *
 * <p><b>設定では回避できない</b>（どちらも 2026-08-22 に実測）。
 * {@code testfx.robot.move_max_count=1} にすると刻まれなくなるが、JavaFX はマウスの移動を
 * 連続したイベントとして見るため、クリックもドラッグも成立せず 22 本が落ちる。
 * {@code testfx.robot=awt}（JavaFX スレッドを経由しない）も手元で 22 本が落ちる。
 *
 * <p><b>腐ることを承知で外している。</b> ここが CI で守られていない以上、
 * 並べ替えは<b>リリース前に人が触って確かめる</b>（{@code HANDOVER.md} 4-4「人が見るもの」）。
 * 並びを動かす判断そのものは {@code PageOrderTest} が画面抜きで固めてあり、
 * このテストが見ているのは<b>画面の操作がその判断に繋がっているか</b>だけである。
 *
 * <p>手元で落ちたときは、<b>まず「並べ替えが壊れた」ではなく「ロボットで駆動できなかった」を
 * 疑うこと</b>。画面がロックされているだけでも落ちる。
 */
@DisabledIfEnvironmentVariable(
        named = "CI",
        matches = "true",
        disabledReason = "windows ランナーでは 1 本で 19 分かかる（クラスの Javadoc を読むこと）")
class ReorderUiTest extends DesktopUiTest {

    @Start
    void start(Stage stage) {
        setUp(stage);
    }

    @Stop
    void stop() {
        tearDown();
    }

    @Test
    void 掴んで落とすと並びが変わる(@TempDir Path dir, FxRobot robot) throws Exception {
        openFixture(robot, TestPdfs.withText(dir.resolve("doc.pdf"), "P1", "P2", "P3"));

        robot.drag("#thumbnail-tile-0", MouseButton.PRIMARY)
                .dropTo("#thumbnail-tile-2");
        WaitForAsyncUtils.waitForFxEvents();

        Path output = saveAs(robot, dir.resolve("out.pdf"));

        assertEquals(List.of("P2", "P3", "P1"), pageTexts(output));
    }

    /** 書き出された PDF の、ページごとの本文。 */
    private static List<String> pageTexts(Path pdf) {
        try (PdfDocument document = PdfDocument.open(pdf)) {
            return new PdfBoxTextExtraction().extractByPage(document).stream()
                    .map(PageText::text)
                    .map(String::trim)
                    .toList();
        }
    }
}
