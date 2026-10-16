package io.github.propagandist.pdfjig.desktop;

import io.github.propagandist.pdfjig.ai.AiProvider;
import io.github.propagandist.pdfjig.ai.NoOpProvider;
import io.github.propagandist.pdfjig.core.PageText;
import io.github.propagandist.pdfjig.core.PdfBoxTextExtraction;
import io.github.propagandist.pdfjig.core.PdfDocument;
import io.github.propagandist.pdfjig.core.TestPdfs;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.stream.Stream;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
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
     * <p>読み込みも書き出しもバックグラウンドで走る（.claude/rules/desktop-ui.md「JavaFX」）。手元では
     * 1 秒とかからないが、CI のランナーは遅い。短くして落ちやすくする理由がない。
     */
    static final int TIMEOUT_SECONDS = 20;

    /** クリックが窓に届かないときに押し直す回数。 */
    private static final int CLICK_ATTEMPTS = 5;

    MainWindow window;

    StubFileDialogs dialogs;

    Stage stage;

    /** {@link #tasks()} が返したもの。上書き保存の後始末が済んだかを見るのに使う（{@link #saveOver}）。 */
    BackgroundTasks background;

    /**
     * 使う AI プロバイダ。
     *
     * <p>既定は {@link NoOpProvider}。AI が無い状態が既定であることは
     * {@link PdfjigApplication} と同じにしておく（CLAUDE.md INV-3）。
     */
    AiProvider aiProvider() {
        return new NoOpProvider();
    }

    /**
     * 使う非同期の実行の手段。
     *
     * <p><b>「操作が走っている間」を作りたいテストだけが差し替える</b>（{@link HeldTasks}）。
     * 実際の書き出しの速さで待ち合わせに行くと、落ちるかどうかが機械の速さで決まるテストになる。
     */
    BackgroundTasks tasks() {
        return new BackgroundTasks();
    }

    /**
     * 「更新を確認」が問い合わせる相手。
     *
     * <p><b>既定でも本物の {@link UpdateCheck#check()} を渡さない</b>（#176）。本物は GitHub へ出るので、
     * 答えが返るかどうかが CI ランナーの外向きの通信で決まる——<b>差分と関係なく赤くなる。</b>
     * 既定は「確認できなかった」をすぐに返す。押す筋は {@code UpdateCheckUiTest} が自分の相手を差す。
     */
    Supplier<UpdateStatus> updateCheck() {
        return UpdateStatus.Unavailable::new;
    }

    /** 各テストクラスの {@code @Start} から呼ぶ。 */
    void setUp(Stage stage) {
        this.stage = stage;
        dialogs = new StubFileDialogs();
        background = tasks();
        window = new MainWindow(stage, aiProvider(), null, dialogs, background, updateCheck());

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
     * 鍵の要るフィクスチャを、鍵を打って開く。
     *
     * <p><b>★ 押し直しを通すのは {@link #openFixture} と同じ理由である</b>
     * （{@link #clickUntilAccepted}）——素のクリックにすると、Windows の cold run で
     * <b>取りこぼしが「20 秒待っても出てこない」形で落ちる。</b>
     */
    void openProtectedFixture(FxRobot robot, Path fixture, String key) throws Exception {
        dialogs.willOpen(fixture);
        clickUntilAccepted(robot, "#tool-open", dialogs::openPending);
        waitForNode(robot, "#password-field");
        clickWhenReady(robot, "#password-field");
        robot.write(key);
        clickWhenReady(robot, "#password-unlock");
        waitForNode(robot, "#thumbnail-tile-0");
    }

    /**
     * 「追加」ボタンでフィクスチャを足す。
     *
     * <p><b>出そろうのを待つのは呼ぶ側である</b>——何を待てばよいかは足した中身に依る
     * （末尾のタイル、あるいは一覧が出ること）。<b>ここが持つのは、押すところまでである。</b>
     */
    void addFixtures(FxRobot robot, Path... fixtures) throws Exception {
        dialogs.willOpenMultiple(fixtures);
        clickUntilAccepted(robot, "#tool-add", dialogs::openMultiplePending);
    }

    /**
     * A（2 ページ）と B（1 ページ）を開き、ファイル一覧が出そろうまで待つ。
     *
     * <p><b>一覧は 2 ファイル以上でしか出ない</b>（{@code SourceLegend}）。
     * 「×」を通るテストはどれもまずここを通る。
     *
     * @return 開いた後の状態表示。呼ぶ側が assert に使う
     */
    String openTwoFiles(FxRobot robot, Path directory) throws Exception {
        openFixture(robot, TestPdfs.withText(directory.resolve("a.pdf"), "A1", "A2"));
        addFixtures(robot, TestPdfs.withText(directory.resolve("b.pdf"), "B1"));
        waitForNode(robot, "#source-remove-0");
        return statusText(robot);
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
     * 「保存」ボタンを押す。押した結果が起きるまで押し直す（{@link #clickUntilAccepted}）。
     *
     * <p><b>★★ 押した結果は 2 通りある。</b>保存先が使われるか、<b>その前に保護の窓が出る</b>か——
     * 保護が落ちるときは、保存先より先に「保護は引き継がれません」を出す（2026-09-26 に順番を変えた）。
     * <b>保存先だけを見て待つと、窓が出ている間も押し直し続けて落ちる。</b>
     * ★ <b>見るのはその窓の id である。</b>どの窓でもよいとすると、前の手順の窓が残っているだけで
     * 押せたと読み、取りこぼしたクリックを押し直さない（#172 の門）。
     */
    void pressSave(FxRobot robot) throws Exception {
        clickUntilAccepted(
                robot,
                "#tool-save",
                () -> dialogs.savePending()
                        && robot.lookup("#protection-dialog").tryQuery().isEmpty());
    }

    /**
     * 「保存」ボタンから書き出し、ファイルができるまで待つ。
     *
     * <p>押し直しを通すのは「開く」と同じ理由である（{@link #clickUntilAccepted}）。
     * ここを素のクリックにしていたため、Windows Sandbox の cold run で
     * 「20 秒待ってもファイルが出てこない」形で落ちた（2026-08-23）。
     * <b>取りこぼしたクリックは、待っても届かない。</b>上限を延ばしても直らない類である。
     *
     * @return 書き出されたファイル
     */
    Path saveAs(FxRobot robot, Path output) throws Exception {
        dialogs.willSaveTo(output);
        pressSave(robot);
        waitFor(() -> Files.exists(output) && Files.size(output) > 0);
        WaitForAsyncUtils.waitForFxEvents();
        return output;
    }

    /**
     * 既にあるファイルへ上書き保存し、書き終わるまで待つ。
     *
     * <p><b>★★ {@link #saveAs} は既存ファイルには使えない。</b>あちらは
     * {@code Files.exists(output) && size > 0} で待つので、<b>既にあるファイルを渡すと
     * 書き出す前に条件が満たされ、待てなくなる。</b>この塞がりのために「既存ファイルへの上書き」は
     * uiTest を一度も通っておらず、{@code docs/HANDOVER.md} 4-4 の 10 番が
     * 人が見る側に置いてある理由の 1 つになっていた。
     *
     * <p><b>更新時刻が進むのを待つ。</b>中身で比べる形は採れない——<b>2 回目の上書きは
     * 同じ内容を書くので、中身は変わらないのが正しい</b>（それがまさに #118 で直したことである）。
     *
     * <p><b>★★ 寄せ直しの終わりまで待つ</b>（#235）。上書き保存は書いた後にセッションを寄せ直す
     * （#118）。<b>ファイルができた時点で戻ると、画面はまだ保存の前の状態を出している</b>——
     * 状態行には「未保存」が残り、ボタンは押せない。<b>次の操作が「押せた」かを状態行で見ると、
     * 押せないボタンを押したのに届いたと読む。</b>CI の windows で、2 回目の回転が効かないまま
     * 保存した（{@code OverwriteSaveUiTest}。2026-09-26）。
     * ★ 以前は「押し直しながら待つので、ここでは待たない」としていた。押し直しが見る条件が
     * 寄せ直しの前と後で同じ答えを返すと、その待ちは効かない。
     *
     * <p><b>★ 終わりは JavaFX スレッドの上で見る</b>（{@link #idle}）。書き出しの受け手は
     * {@code busy} を下ろしてから寄せ直しを始めて立て直す。<b>その間は 1 つのイベントの中なので、
     * JavaFX スレッドからは下りた瞬間が見えない。</b>テストのスレッドから覗くと、そこを取り違える。
     *
     * <p><b>★ 警告の窓が出ていても戻る。</b>窓を閉じるのは、ここから戻った呼ぶ側である
     * （{@code OverwriteSaveUiTest} の「複数ファイルでも」）。{@code BackgroundTasks#whenIdle} で
     * 待つ形は採らない——あちらは<b>最後に終わった仕事の受け手が戻るまで</b>呼ばない。
     * 寄せ直しが走った回は、寄せ直しが警告の窓の入れ子のループの中で終わるので間に合うが、
     * <b>寄せ直しが走らなかった回</b>（{@code ReopenFailureUiTest}）は、書き出しの受け手が窓を出していれば
     * それが閉じるまで呼ばれない。
     *
     * @return 書き出されたファイル
     */
    Path saveOver(FxRobot robot, Path output) throws Exception {
        FileTime before = Files.getLastModifiedTime(output);
        dialogs.willSaveTo(output);
        pressSave(robot);
        // ★ 上限で落ちるのは理由の無い TimeoutException である。2 段のどちらで止まったかを書く。
        try {
            waitFor(() -> modifiedAfter(output, before));
        } catch (TimeoutException e) {
            throw new AssertionError("上書き保存の出力が書かれない: " + output, e);
        }
        try {
            waitFor(this::idle);
        } catch (TimeoutException e) {
            throw new AssertionError("書けたが、書き出しか寄せ直しが終わらない", e);
        }
        // 寄せ直しの受け手が積んだもの（選択位置へ戻す・状態行）を流してから読ませる。
        WaitForAsyncUtils.waitForFxEvents();
        return output;
    }

    /**
     * そのファイルの更新時刻が {@code before} より後か。<b>無いあいだは「まだ」と答える。</b>
     *
     * <p><b>★★ 置き換えの最中は、出力先が一瞬消える。</b>置き換えは「元をどけてから入れる」
     * 2 本の改名である（{@code DocumentWriter#move}。#119）。TestFX の {@code waitFor} は、
     * 条件が投げた例外を「まだ偽」ではなく失敗として投げ直す。<b>在ることを先に見ても足りない</b>
     * ——見てから時刻を読むまでの間に消えると {@code NoSuchFileException} で落ちる
     * （CI の windows で踏んだ。2026-09-26、{@code ReopenFailureUiTest}。#235）。
     */
    private static boolean modifiedAfter(Path file, FileTime before) throws IOException {
        try {
            return Files.getLastModifiedTime(file).compareTo(before) > 0;
        } catch (NoSuchFileException replacing) {
            return false;
        }
    }

    /**
     * 走っている仕事が無いか。<b>JavaFX スレッドの上で見る</b>（{@link #saveOver}）。
     *
     * <p><b>★★ {@code WaitForAsyncUtils#asyncFx} を使わない。</b>あちらは頼む前に、
     * TestFX が JavaFX スレッドで拾った例外を投げ直す（{@code autoCheckException} の既定は真。
     * TestFX 4.0.18 を逆アセンブルして確かめた）。{@code ReopenFailureUiTest} は寄せ直しの始め方に
     * わざと投げさせるので、その例外がここで出て、<b>押せないことを見る前に落ちる</b>（#235 の門の 2 段目）。
     *
     * <p>1 回の問いは {@link #GRACE_SECONDS} で諦め、「まだ」と答える。外側の {@link #waitFor} が
     * 上限を持つ——ここで投げると、その上限より前に落ちる。
     */
    private boolean idle() throws Exception {
        CompletableFuture<Boolean> answer = new CompletableFuture<>();
        Platform.runLater(() -> answer.complete(!background.busy().get()));
        try {
            return answer.get(GRACE_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException stillBusy) {
            return false;
        }
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

    /**
     * 見えていて、大きさを持っていて、その窓が出ていて、<b>scene の矩形の中にあるか。</b>
     *
     * <p><b>★★ 最後の 1 つを落とすと、待ったのに掴めない。</b>TestFX が見るのも
     * <b>節点が scene の矩形と重なるか</b>である（{@code NodeQueryUtils#isNodeWithinSceneBounds}）
     * ——そこを見ずに待つと、<b>窓からはみ出した節点を「押せる」と読んでしまい</b>、
     * {@code clickOn} が「1 nodes, but no nodes were visible」で落ちる。
     * <b>待ち合わせの条件が緩いと、落ちた理由が読めなくなる</b>
     * （2026-09-15 に CI windows で 2 度踏んだ。{@code EncryptionPrompt} の詳細）。
     *
     * <p><b>★ これは吸収ではない。</b>本当にはみ出したままなら上限まで待って落ちる——
     * <b>「待てば直る」形にはしていない</b>（{@code .claude/rules/ui-tests.md}）。
     */
    private static boolean clickable(Node node) {
        if (!node.isVisible() || node.getOpacity() <= 0) {
            return false;
        }
        Scene scene = node.getScene();
        if (scene == null || scene.getWindow() == null || !scene.getWindow().isShowing()) {
            return false;
        }
        Bounds bounds = node.localToScene(node.getBoundsInLocal());
        return bounds.getWidth() > 0
                && bounds.getHeight() > 0
                && scene.getWidth() > 0
                && scene.getHeight() > 0
                && bounds.intersects(0, 0, scene.getWidth(), scene.getHeight());
    }

    /**
     * 出ないはず・閉じないはずのものを、本当にそうだと見るための猶予。
     *
     * <p><b>「待てば直る」種類の待ちではない。</b>直っていれば<b>いつまで待っても起きない</b>ので、
     * 長く取っても偽の緑にならない。<b>短すぎると、始まる前を「起きていない」と読む。</b>
     */
    static final int GRACE_SECONDS = 2;

    /**
     * 書き出しを始めて、始まったところで止める。
     *
     * <p><b>止まったことをツールバーで確かめてから戻る。</b>見ているのは「保存」が無効になったこと、
     * つまり {@code editingBlocked} である——<b>{@code busy} そのものではない。</b>
     * ★ <b>文書が開かれていて、食い違ってもいない状態から呼ぶこと。</b>
     * そうでない状態から呼ぶと<b>何も走っていなくてもここを抜ける</b>ので、
     * この後の「効かない」が門のおかげなのか、はじめから何も無いだけなのか読めなくなる。
     *
     * @param held その書き出しを抱える手。{@code tasks()} で差し込んだものを渡すこと
     */
    void startHeldSave(FxRobot robot, HeldTasks held, Path output) throws Exception {
        held.hold();
        dialogs.willSaveTo(output);
        pressSave(robot);
        waitFor(() -> button(robot, "#tool-save").isDisabled());
    }

    static void waitFor(Callable<Boolean> condition) throws Exception {
        WaitForAsyncUtils.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS, condition);
    }

    static Button button(FxRobot robot, String id) {
        return robot.lookup(id).queryButton();
    }

    /**
     * メニューの項目を id で探す。
     *
     * <p><b>{@code MenuItem} は {@code Node} ではないので、{@code lookup} では掴めない</b>
     * （{@code .claude/rules/ui-tests.md}）。メニューバーから辿る。
     */
    static Optional<MenuItem> menuItem(FxRobot robot, String id) {
        MenuBar bar = robot.lookup(".menu-bar").queryAs(MenuBar.class);
        return bar.getMenus().stream()
                .flatMap(DesktopUiTest::withDescendants)
                .filter(item -> id.equals(item.getId()))
                .findFirst();
    }

    private static Stream<MenuItem> withDescendants(MenuItem item) {
        return item instanceof Menu menu
                ? Stream.concat(Stream.of(item), menu.getItems().stream().flatMap(DesktopUiTest::withDescendants))
                : Stream.of(item);
    }

    /**
     * ダイアログのボタンが出てくるなら掴む。
     *
     * <p><b>★★ 「出ない」を 1 回の lookup で決めない。</b>{@code Alert#showAndWait} は
     * 新しい窓を立てるので、<b>クリックの直後に見ると間に合わないことがある</b>——
     * そこで空を返すと、<b>門が漏れているのに緑になる</b>（{@link #GRACE_SECONDS}）。
     */
    static Optional<Node> dialogButton(FxRobot robot, String id) {
        try {
            WaitForAsyncUtils.waitFor(
                    GRACE_SECONDS,
                    TimeUnit.SECONDS,
                    () -> robot.lookup(id).tryQuery().isPresent());
        } catch (Exception neverShown) {
            return Optional.empty();
        }
        WaitForAsyncUtils.waitForFxEvents();
        return robot.lookup(id).tryQuery();
    }

    static String statusText(FxRobot robot) {
        return robot.lookup("#status-label").queryAs(Label.class).getText();
    }

    /**
     * 書き出された PDF の、ページごとの本文。
     *
     * <p><b>枚数だけを見ても、どのページが残ったかは分からない。</b>
     * {@code TestPdfs.withText} で作った出力はこれで中身まで確かめる。
     */
    static List<String> pageTexts(Path pdf) {
        try (PdfDocument document = PdfDocument.open(pdf)) {
            return new PdfBoxTextExtraction()
                    .extractByPage(document).stream()
                            .map(PageText::text)
                            .map(String::trim)
                            .toList();
        }
    }

    static String textOf(FxRobot robot, String id) {
        return robot.lookup(id).queryAs(Label.class).getText();
    }

    /**
     * フォルダの直下にある名前を並べる。
     *
     * <p><b>ここに置いてあるのは、見たいものが「出来たファイル」だけではないからである</b>——
     * 書き出しは作業場所（{@code .pdfjig-*}）も作るので、<b>片づいたかどうかは
     * 隣に何も残っていないことでしか見えない</b>（#119）。
     */
    static List<String> namesIn(Path directory) throws IOException {
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.map(entry -> entry.getFileName().toString()).sorted().toList();
        }
    }
}
