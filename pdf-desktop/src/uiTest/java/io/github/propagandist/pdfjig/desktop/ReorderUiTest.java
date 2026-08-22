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
import org.junit.jupiter.api.io.TempDir;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.Start;
import org.testfx.framework.junit5.Stop;
import org.testfx.util.WaitForAsyncUtils;

/**
 * サムネイルを掴んで並べ替える。
 *
 * <p>JavaFX のドラッグ&amp;ドロップは Glass の土台を経由する。TestFX のロボットが出す
 * マウスの動きでその一連が最後まで通るかは環境に依るため、ここが落ちたときは
 * <b>まず「並べ替えが壊れた」ではなく「ロボットで駆動できなかった」を疑うこと</b>。
 * 並びを動かす判断そのものは {@code PageOrderTest} が画面抜きで固めてある。
 */
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
