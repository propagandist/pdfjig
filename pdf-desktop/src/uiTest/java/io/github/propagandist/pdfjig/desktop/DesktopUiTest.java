package io.github.propagandist.pdfjig.desktop;

import io.github.propagandist.pdfjig.ai.AiProvider;
import io.github.propagandist.pdfjig.ai.NoOpProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.util.WaitForAsyncUtils;

/**
 * 画面を操作するテストの土台。
 *
 * <p>{@link PdfjigApplication} は経由せず {@link MainWindow} を直に組み立てる。
 * {@link FileDialogs} を差し替えられるのはこの経路だけであり、それができないと
 * 「開く」「保存」を通る流れが試せない。
 *
 * <p>フィクスチャの PDF はその場で作る（CLAUDE.md INV-6）。作法は {@code pdf-core} と
 * 共有している（{@link io.github.propagandist.pdfjig.core.TestPdfs}）。
 *
 * <p><b>{@code @Start} / {@code @Stop} は各テストクラスが自分で持つこと。</b>
 * TestFX の {@code ApplicationExtension} はこの 2 つを宣言されたメソッドの中からしか探さず、
 * 継承しても拾われない。中身は {@link #setUp(Stage)} / {@link #tearDown()} を呼ぶだけでよい。
 */
@ExtendWith(ApplicationExtension.class)
abstract class DesktopUiTest {

    /**
     * 非同期の処理を待つ上限。
     *
     * <p>読み込みも書き出しもバックグラウンドで走る（CLAUDE.md JavaFX 節）。手元では
     * 1 秒とかからないが、CI のランナーは遅い。短くして落ちやすくする理由がない。
     */
    static final int TIMEOUT_SECONDS = 20;

    /** クリックが窓に届かないときに押し直す回数。 */
    private static final int CLICK_ATTEMPTS = 5;

    MainWindow window;

    StubFileDialogs dialogs;

    Stage stage;

    /**
     * 使う AI プロバイダ。
     *
     * <p>既定は {@link NoOpProvider}。AI が無い状態が既定であることは
     * {@link PdfjigApplication} と同じにしておく（CLAUDE.md INV-3）。
     */
    AiProvider aiProvider() {
        return new NoOpProvider();
    }

    /** 各テストクラスの {@code @Start} から呼ぶ。 */
    void setUp(Stage stage) {
        this.stage = stage;
        dialogs = new StubFileDialogs();
        window = new MainWindow(stage, aiProvider(), null, dialogs);

        Scene scene = new Scene(window.build(), 960, 720);
        scene.getStylesheets()
                .add(PdfjigApplication.class.getResource("pdfjig.css").toExternalForm());
        stage.setScene(scene);
        stage.show();
    }

    /**
     * 窓を前面に出してから始める。
     *
     * <p>Windows では、活性でない窓への最初のクリックは窓を活性にするだけで消え、
     * その下のボタンには届かない。これを踏むと「クリックしたのに何も起きない」形で
     * ときどき落ちるテストになり、原因が読めなくなる。
     */
    @BeforeEach
    void bringToFront() {
        WaitForAsyncUtils.waitForFxEvents();
        Platform.runLater(() -> {
            stage.setAlwaysOnTop(true);
            stage.toFront();
            stage.requestFocus();
        });
        WaitForAsyncUtils.waitForFxEvents();

        // 前面に出すのは OS の仕事であり、runLater が返った時点では終わっていない。
        // 取れないまま進むこともある（画面のない環境、他の窓が掴んでいる場合）。
        // ここで落とすより、押し直し（clickUntilAccepted）で吸収するほうが環境の差に強い。
        try {
            WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> stage.isFocused());
        } catch (TimeoutException e) {
            // 続ける。
        }
    }

    /** 各テストクラスの {@code @Stop} から呼ぶ。 */
    void tearDown() {
        window.dispose();
    }

    // ── 補助 ────────────────────────────────────────────────────────────────

    /** 「開く」ボタンからフィクスチャを開き、一覧に出るまで待つ。 */
    void openFixture(FxRobot robot, Path fixture) throws Exception {
        dialogs.willOpen(fixture);
        clickUntilAccepted(robot, "#tool-open", dialogs::openPending);
        waitForNode(robot, "#thumbnail-tile-0");
    }

    /**
     * 効くまで押す。
     *
     * <p>Windows は活性でない窓への 1 回目のクリックを窓の活性化に使い、その下の
     * コントロールへは届けない。{@link #bringToFront()} で前面に出してはいるが、
     * 反映は OS 側で非同期に起きるため、間に合わないことがまれにある。
     *
     * <p>そのまま進むと「20 秒待ってもサムネイルが出ない」という、原因の読めない形で
     * 落ちる。押した結果が起きたかどうかを見て、起きていなければ押し直す。
     *
     * @param stillPending まだ押した結果が起きていない間 {@code true} を返すもの
     */
    void clickUntilAccepted(FxRobot robot, String id, BooleanSupplier stillPending) throws Exception {
        for (int attempt = 1; attempt <= CLICK_ATTEMPTS; attempt++) {
            robot.clickOn(id);
            WaitForAsyncUtils.waitForFxEvents();
            if (!stillPending.getAsBoolean()) {
                return;
            }
        }
        throw new AssertionError(id + " を " + CLICK_ATTEMPTS + " 回押しても届かなかった" + "（窓が前面に出ていない可能性がある）");
    }

    /**
     * 「保存」ボタンから書き出し、ファイルができるまで待つ。
     *
     * @return 書き出されたファイル
     */
    Path saveAs(FxRobot robot, Path output) throws Exception {
        dialogs.willSaveTo(output);
        robot.clickOn("#tool-save");
        waitFor(() -> Files.exists(output) && Files.size(output) > 0);
        WaitForAsyncUtils.waitForFxEvents();
        return output;
    }

    /**
     * 節点が押せるようになるのを待ってから押す。
     *
     * <p>ダイアログの中身はこれを通すこと。窓が出る前の節点をクリックすると
     * 「1 nodes, but no nodes were visible」で落ちる。ときどきしか起きないため、
     * 待たずに押している箇所は不安定なテストとして後から現れる。
     */
    void clickWhenReady(FxRobot robot, String id) throws Exception {
        waitForNode(robot, id);
        robot.clickOn(id);
    }

    /**
     * ある節点が<b>押せる状態で</b>出てくるまで待つ。
     *
     * <p>「見つかる」だけでは足りない。ダイアログの節点は窓が出る前から要素としては
     * 存在しており、そこへクリックを送ると TestFX は
     * 「1 nodes, but no nodes were visible」で落ちる。窓が出て、割り付けが済んで、
     * 大きさが入るところまで待つ。
     */
    static void waitForNode(FxRobot robot, String id) throws Exception {
        waitFor(() -> robot.lookup(id).tryQuery().map(DesktopUiTest::clickable).orElse(false));
        WaitForAsyncUtils.waitForFxEvents();
    }

    /** 見えていて、大きさを持っていて、その窓が出ているか。 */
    private static boolean clickable(Node node) {
        if (!node.isVisible() || node.getOpacity() <= 0) {
            return false;
        }
        Scene scene = node.getScene();
        if (scene == null || scene.getWindow() == null || !scene.getWindow().isShowing()) {
            return false;
        }
        Bounds bounds = node.localToScene(node.getBoundsInLocal());
        return bounds.getWidth() > 0 && bounds.getHeight() > 0 && scene.getWidth() > 0 && scene.getHeight() > 0;
    }

    static void waitFor(Callable<Boolean> condition) throws Exception {
        WaitForAsyncUtils.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS, condition);
    }

    static Button button(FxRobot robot, String id) {
        return robot.lookup(id).queryButton();
    }

    static String statusText(FxRobot robot) {
        return robot.lookup("#status-label").queryAs(Label.class).getText();
    }

    static String textOf(FxRobot robot, String id) {
        return robot.lookup(id).queryAs(Label.class).getText();
    }
}
