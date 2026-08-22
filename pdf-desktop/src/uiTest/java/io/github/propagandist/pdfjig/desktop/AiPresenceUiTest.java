package io.github.propagandist.pdfjig.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.propagandist.pdfjig.ai.AiProvider;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.Start;
import org.testfx.framework.junit5.Stop;

/**
 * AI が使える状態のときの表示。
 *
 * <p>可否の判定が {@code NoOpProvider} 固定になっていないことを見る。この版に AI の
 * 入口は無いが、{@link AiProvider#isAvailable()} を見て表示を変えるところまでは通っている。
 *
 * <p>プロバイダは {@link MainWindow} を組み立てる時点で決まる。1 つのテストクラスの中で
 * 切り替えることはできないため、無い側（{@link AiAbsenceUiTest}）とはクラスを分けてある。
 */
class AiPresenceUiTest extends DesktopUiTest {

    @Start
    void start(Stage stage) {
        setUp(stage);
    }

    @Stop
    void stop() {
        tearDown();
    }

    @Override
    AiProvider aiProvider() {
        return new StubAiProvider(true);
    }

    @Test
    void 使えるならその旨を出す(FxRobot robot) throws Exception {
        robot.clickOn("ヘルプ");
        robot.clickOn(AppInfo.NAME + " について");
        waitForNode(robot, "#about-ai");

        assertEquals("AI 機能: 利用可能", textOf(robot, "#about-ai"));

        clickWhenReady(robot, "#about-close");
    }
}
