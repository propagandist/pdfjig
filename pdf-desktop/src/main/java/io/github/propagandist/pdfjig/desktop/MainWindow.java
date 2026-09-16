package io.github.propagandist.pdfjig.desktop;

import io.github.propagandist.pdfjig.ai.AiProvider;
import io.github.propagandist.pdfjig.core.ErrorCode;
import io.github.propagandist.pdfjig.core.PageSelection;
import io.github.propagandist.pdfjig.core.Password;
import io.github.propagandist.pdfjig.core.PdfjigException;
import io.github.propagandist.pdfjig.core.Protection;
import io.github.propagandist.pdfjig.core.Rotation;
import io.github.propagandist.pdfjig.core.Source;
import io.github.propagandist.pdfjig.core.Sources;
import io.github.propagandist.pdfjig.core.Warning;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javafx.application.HostServices;
import javafx.application.Platform;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.collections.ListChangeListener;
import javafx.event.EventHandler;
import javafx.geometry.Insets;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.stage.WindowEvent;

/**
 * 主画面。サムネイル一覧と、そこに対する操作を持つ。
 *
 * <p><b>持つのは画面の組み立てと状態の同期、そして操作が何をするかである</b>（#57）。
 * それ以外は外へ出してある。
 *
 * <ul>
 *   <li>{@link Action} / {@link Actions} — メニューとツールバーの並べ方
 *   <li>{@link BackgroundTasks} — 非同期の実行
 *   <li>{@link DocumentWriter} — ファイルの書き出し
 *   <li>{@link Messages} — 利用者に伝えること
 * </ul>
 *
 * <p><b>ファイル I/O を伴う操作はすべて非同期で行う</b>（CLAUDE.md JavaFX 節）。
 * 画面を止めないためであり、100 ページの文書でも開いた瞬間に固まらない。
 *
 * <p>並べ替えと削除はページ並びの上でだけ起き、ファイルには触れない。
 * 「名前を付けて保存」で初めて書き出す。
 */
public final class MainWindow {

    private final Stage stage;

    private final AiProvider aiProvider;

    /** リンクを既定のブラウザに渡すために使う。バージョン情報のダイアログで使う。 */
    private final HostServices hostServices;

    private final ThumbnailGrid thumbnails;

    /** いま含んでいるファイルの一覧。1 ファイルのときは出ない。 */
    private final SourceLegend legend;

    /** ダイアログを始めるフォルダ。読む用と書く用を分けて覚える。 */
    private final RecentFolders folders = new RecentFolders();

    /** ファイルとフォルダを選ばせる手段。テストではここを差し替える。 */
    private final FileDialogs dialogs;

    /** 画面を止めずに走らせる手段。進行中かどうかもここが持つ。 */
    private final BackgroundTasks tasks;

    /** 利用者に伝える手段。 */
    private final Messages messages;

    private final Label status = new Label();

    private final BooleanProperty documentOpen = new SimpleBooleanProperty(false);

    /**
     * 開いている文書が、ディスクの中身と食い違っている。
     *
     * <p><b>★★ 立つと保存を押せなくする。</b>上書き保存の後にセッションを寄せ直せなかったとき、
     * <b>並びは書き出す前のファイルに対する指定のまま</b>で、出どころの中身は書き出したものに
     * 入れ替わっている。<b>そのまま保存すると同じ変換が二重に掛かる</b>（#118）。
     *
     * <p><b>失うものは無い。</b>書き出し自体は成功しておりファイルはできている。
     * <b>開き直せば続けられる。</b>
     */
    private final BooleanProperty stale = new SimpleBooleanProperty(false);

    /**
     * 何があって食い違ったのか。<b>場を塞ぐかどうかは変わらない。変わるのは文言だけである。</b>
     *
     * <p><b>★★ 分けないと嘘を言う。</b>保護して書き出した回は<b>何も失敗していない</b>
     * ——<b>鍵が要るので開き直さなかっただけである。</b>
     * <b>そこで「開き直せませんでした」と出すと、本当に落ちた回と見分けが付かなくなる</b>
     * （{@code CLAUDE.md} 優先順位 2。#30 の門の 2 段目）。
     */
    private StaleReason staleReason = StaleReason.REOPEN_FAILED;

    /** 食い違いの理由と、そのとき状態行に出す文言。 */
    private enum StaleReason {

        /** 上書きの後で寄せ直せなかった（#118）。 */
        REOPEN_FAILED("（書き出したファイルを開き直せませんでした。開き直してください）"),

        /**
         * 出どころを置き換えたが、その出力は開くのに鍵が要る。
         *
         * <p><b>★ 鍵は書き出しが終わった時点で閉じている</b>（INV-5）ので、
         * <b>こちらからは開き直せない。失敗ではない。</b>
         */
        OUTPUT_NEEDS_A_KEY("（書き出したファイルには鍵が要るため、開き直していません。開き直してください）");

        final String text;

        StaleReason(String text) {
            this.text = text;
        }
    }

    /**
     * 文書の中身を変える操作を通してはならない条件。
     *
     * <p><b>★★ ここが唯一の門である</b>（#114）。以前は {@link Action} から作られた節点だけが
     * {@code busy} を見ており、<b>一覧の「×」・サムネイルの DELETE キー・タイルのドラッグは
     * 素通りしていた</b>——{@code busy} を入口ごとに書くと、<b>入口が増えた日にまた漏れる。</b>
     *
     * <p>成り立ちは 3 つ。<b>文書が開かれていること</b>、<b>操作が走っていないこと</b>、
     * <b>書き出したものと食い違っていないこと</b>（{@link #stale}。#118）——
     * 食い違っている間は並びが書き出す前のファイルに対する指定のままなので、
     * そこから何を書き出しても同じ変換が二重に掛かる。
     *
     * <p><b>★ 「閉じる」と「開く」はここで縛らない。</b>「開き直してください」と出しておいて
     * 閉じられないのでは、利用者に打つ手が無くなる（開き直すと印は下りる）。
     */
    private final BooleanBinding editingBlocked;

    /**
     * 文書が開かれていて、かつ操作が走っていないか。
     *
     * <p><b>★ {@link #editingBlocked} の土台でもある。</b>同じ 2 項を別々に組むと、
     * {@code documentOpen} と {@code busy} が動くたびに<b>同じ計算を 2 本の鎖が繰り返す。</b>
     */
    private final BooleanBinding needsDocument;

    /** 効いている区切りの数。操作の有効・無効と状態表示に使う。 */
    private final IntegerProperty breakCount = new SimpleIntegerProperty(0);

    /** いま並んでいるページ数。1 枚ずつの分割が使えるかの判定に使う。 */
    private final IntegerProperty pageCount = new SimpleIntegerProperty(0);

    /** ページ並びが変わるたびに表示を更新する。 */
    private final ListChangeListener<PageEntry> orderListener = change -> onOrderChanged();

    /**
     * 終了を頼まれたが、走っている仕事があるので待っている。
     *
     * <p><b>一度立ったら下ろさない</b>（#134）。取り消す手を用意しない——
     * <b>押した意思を打ち消す操作は、押した本人にしか意味が無く、それを表す入口が無い。</b>
     */
    private boolean quitWhenIdle;

    /**
     * 窓の × を受ける口。<b>外せるように持っておく</b>（{@link #dispose}）。
     *
     * <p><b>必ず断ってから自分で閉じる。</b>OS に閉じさせる道を残すと、
     * <b>そちらだけが門を通らない</b>（#114 と同じ形の漏れ）。
     */
    private final EventHandler<WindowEvent> closeRequested = event -> {
        event.consume();
        requestQuit();
    };

    private DocumentSession session;

    public MainWindow(Stage stage, AiProvider aiProvider, HostServices hostServices) {
        this(stage, aiProvider, hostServices, new NativeFileDialogs(stage), new BackgroundTasks());
    }

    /**
     * 差し替えられるものを指定して作る。<b>これを呼ぶのはテストだけである。</b>
     *
     * <p>Windows の共通ダイアログは自動テストから操作できない（{@link FileDialogs}）。
     * <b>「操作が走っている間」も、実際の書き出しの速さでは狙って作れない</b>
     * （{@link BackgroundTasks#BackgroundTasks(java.util.concurrent.Executor)}）——
     * 待ち合わせに行くと、落ちるかどうかが機械の速さで決まるテストになる。
     *
     * @param dialogs ファイルとフォルダを選ばせる手段
     * @param tasks   画面を止めずに走らせる手段
     */
    MainWindow(
            Stage stage, AiProvider aiProvider, HostServices hostServices, FileDialogs dialogs, BackgroundTasks tasks) {
        this.stage = stage;
        this.aiProvider = aiProvider;
        this.hostServices = hostServices;
        this.dialogs = dialogs;
        this.tasks = tasks;
        this.messages = new Messages(stage);
        this.needsDocument = documentOpen.not().or(tasks.busy());
        this.editingBlocked = needsDocument.or(stale);
        // ★★ 門は組み立てる前に決まっていなければならない（#114）。この 2 つは Action を
        //   通らない入口を持っており、後から差す形にすると「差し忘れると通る」を作る。
        this.thumbnails = new ThumbnailGrid(editingBlocked);
        this.legend = new SourceLegend(editingBlocked);
        // ★★ 窓の × も「終了」と同じ道を通す（#134）。必ず断ってから自分で閉じる形にする——
        //   OS に閉じさせる道を残すと、そちらだけが門を通らない（#114 と同じ形の漏れ）。
        //   ★ 組み立てではなく、ここで決める。build を 2 度呼べる形にすると門が二重になる。
        //   ★★ setOnCloseRequest ではなく addEventHandler を使う。あちらは値が 1 つの property で、
        //     誰かが後から差すと黙って置き換わる——門が消えたことに誰も気づかない。
        //   ★★ そのぶん、手放すときに外すこと（dispose）。足すだけにすると同じ窓へ溜まり、
        //     破棄済みの MainWindow の受け口まで発火する——あちらは走っていないので、窓を閉じる。
        //     2026-09-04 に CI で実際に起きた（uiTest は 1 つの主ステージを使い回す）。
        stage.addEventHandler(WindowEvent.WINDOW_CLOSE_REQUEST, closeRequested);
    }

    /**
     * 画面を組み立てる。
     *
     * @return 画面の根
     */
    public Parent build() {
        thumbnails.setOnDelete(this::deleteSelected);
        legend.setOnRemove(this::removeSource);

        Actions actions = buildActions();

        status.setId("status-label");

        HBox statusBar = new HBox(status);
        statusBar.getStyleClass().add("status-bar");
        statusBar.setPadding(new Insets(6, 12, 6, 12));

        BorderPane root = new BorderPane();
        root.setTop(new VBox(actions.menuBar(), actions.toolBar(), legend.node()));
        root.setCenter(thumbnails.node());
        root.setBottom(statusBar);

        updateTitle();
        updateStatus();
        return root;
    }

    /**
     * ダイアログを始めるフォルダ。
     *
     * <p><b>再起動をまたいで保つのは呼び出し側の仕事である</b>（{@link PdfjigApplication}）。
     * ここは置き場も書き方も知らない——{@code Settings} を持ち込むと、画面が
     * ファイルの読み書きを抱えることになる（#57）。
     */
    RecentFolders folders() {
        return folders;
    }

    /**
     * ウィンドウを閉じるときに呼ぶ。開いている文書を解放する。
     *
     * <p><b>★ 窓に差した受け口も外す</b>（#134）。窓はこちらのものではないので、
     * <b>手放したあとも自分の受け口を残すと、破棄済みのこちらが呼ばれ続ける。</b>
     */
    public void dispose() {
        stage.removeEventHandler(WindowEvent.WINDOW_CLOSE_REQUEST, closeRequested);
        closeSession();
    }

    /**
     * 終了の要求を受ける。<b>メニューの「終了」も窓の × もここを通る</b>（#134）。
     *
     * <p><b>★★ 走っている間は閉じない。</b>置き換えは<b>「元をどけてから入れる」2 本の改名</b>
     * であり（#119）、<b>その 2 本の間で JVM が死ぬと、出力先には何も無く、
     * 元は作業場所の中にしか残らない</b>——書き出しは daemon スレッドで走るので、
     * <b>止められずに消える。</b>
     *
     * <p><b>★ 断るのではなく、覚えて待つ。</b>断ると<b>利用者は押し直さなければならない</b>——
     * 押した意思は消えていない。<b>「閉じられない」と「あとで閉じる」は違う。</b>
     *
     * <p><b>★★ 待たせる上限は置かない。</b>置いて切ると、<b>切った先はまさにこの不具合である</b>
     * ——守るために作った仕組みが、上限のところで守らなくなる。<b>待っている間、窓は固まらない</b>
     * （仕事は背景スレッドで走る）ので、<b>状態行に理由を出せば、なぜ閉じないかは伝わる</b>
     * （{@code CLAUDE.md} 優先順位 2）。それでも待てないなら OS から終わらせる手が残っており、
     * <b>それは今日と同じで、悪くならない。</b>
     *
     * <p><b>★ 走っている仕事の種類で分けない。</b>{@code busy} が立つのは書き出しだけではないが、
     * <b>分けるとその判断が 2 か所になる</b>し、書き出し以外は 1 秒前後で終わる。
     */
    private void requestQuit() {
        if (tasks.busy().get()) {
            quitWhenIdle = true;
            // ★★ 待ち方は BackgroundTasks が持つ（whenIdle）。busy を自分で見張る形にすると、
            //   印が下りた瞬間——後始末より前——に閉じることになり、
            //   しかも runLater でずらしても窓が出ている最中に動く。
            tasks.whenIdle(this::closeWhenNoOtherWindowIsUp);
            updateStatus();
            return;
        }
        stage.close();
    }

    /**
     * 出ている窓が無くなってから閉じる。
     *
     * <p><b>★★ 「仕事が終わった」だけでは足りない。</b>後始末が<b>次の仕事を始めてから</b>
     * 窓を出すことがあり（上書き保存の寄せ直し。#118）、<b>その 2 本目が終わるのは
     * 窓の入れ子のイベントループの中である</b>——そこで閉じると、
     * <b>利用者が読んでいる窓の親が消える。</b>いちばん困るのは
     * 「保存に失敗しました。元のファイルはここに残っています」である（#124）。
     *
     * <p><b>★ 仕事とは無関係に出ている窓もある。</b>「バージョン情報」は走っていても開ける
     * （{@link #buildActions}）ので、<b>それが出ている間に仕事が終わることがある。</b>
     * <b>だから見るのは「窓が出ているか」であって、「この後始末が窓を出したか」ではない。</b>
     *
     * <p><b>閉じたらまた見にくる。</b>窓が閉じたときにもう一度ここへ来る——
     * <b>そのとき別の窓が出ていれば、また待つ。</b>
     */
    private void closeWhenNoOtherWindowIsUp() {
        Window blocking = otherShowingWindow();
        if (blocking == null) {
            stage.close();
            return;
        }
        blocking.showingProperty().addListener(new ChangeListener<Boolean>() {
            @Override
            public void changed(ObservableValue<? extends Boolean> observable, Boolean was, Boolean showing) {
                if (!showing) {
                    observable.removeListener(this);
                    // ★ その場では閉じない。窓は閉じる途中であり、入れ子のイベントループも
                    //   まだ抜けていない。1 拍ずらしてから、もう一度どの窓が出ているかを見る。
                    Platform.runLater(MainWindow.this::closeWhenNoOtherWindowIsUp);
                }
            }
        });
    }

    /**
     * 主画面のほかに出ている窓。無ければ {@code null}。
     *
     * <p><b>★ 見るのは {@link Stage} だけである。</b>ツールチップやポップアップも
     * {@link Window} だが、<b>あれは利用者が読んで閉じるものではなく、放っておけば消える</b>
     * ——数えると、<b>ボタンの上にカーソルが載っているだけで終了が遅れる。</b>
     * <b>ダイアログはどれも {@code Stage} である</b>（{@code Alert} も {@code Dialog} も）。
     */
    private Window otherShowingWindow() {
        for (Window window : Window.getWindows()) {
            if (window != stage && window instanceof Stage && window.isShowing()) {
                return window;
            }
        }
        return null;
    }

    /**
     * どの操作を持ち、それぞれが何をするかを決める。
     *
     * <p><b>並べ方は {@link Actions} が、節点の作り方は {@link Action} が持つ。</b>
     * ここが持つのは<b>処理と、無効にする条件</b>——どちらも画面の状態に依るものであり、
     * 外へ出せない。
     */
    private Actions buildActions() {
        // 走っている間は押させない。立てるのも下ろすのも BackgroundTasks だけである。
        ReadOnlyBooleanProperty busy = tasks.busy();

        // 先頭のページには区切りを付けられない。先頭は区切らなくてもファイルの始まりである。
        ObservableValue<Boolean> breakUnavailable =
                editingBlocked.or(thumbnails.selectedIndexProperty().lessThan(1));

        ObservableValue<Boolean> noBreaks = editingBlocked.or(breakCount.isEqualTo(0));

        // 1 ページしかなければ 1 枚ずつには分けられない。できるのは元と同じ 1 ファイルだけで、
        // 区切りが無いときと違って利用者に打つ手も無い。断るより初めから押させない。
        ObservableValue<Boolean> notSplittable = editingBlocked.or(pageCount.lessThan(2));

        return new Actions(
                new Action(
                        "open",
                        "開く…",
                        "開く",
                        ToolIcons.OPEN,
                        new KeyCodeCombination(KeyCode.O, KeyCombination.SHORTCUT_DOWN),
                        this::openDocument,
                        busy),
                new Action(
                        "save",
                        "名前を付けて保存…",
                        "保存",
                        ToolIcons.SAVE,
                        new KeyCodeCombination(KeyCode.S, KeyCombination.SHORTCUT_DOWN),
                        this::saveAs,
                        editingBlocked),
                new Action("close", "閉じる", null, null, null, this::closeSession, needsDocument),
                new Action("quit", "終了", null, null, null, this::requestQuit, null),
                new Action(
                        "delete",
                        "選択したページを削除",
                        "削除",
                        ToolIcons.DELETE,
                        new KeyCodeCombination(KeyCode.DELETE),
                        this::deleteSelected,
                        editingBlocked),
                new Action(
                        "rotate-right",
                        "右に 90 度回転",
                        "右に回転",
                        ToolIcons.ROTATE_RIGHT,
                        new KeyCodeCombination(KeyCode.RIGHT, KeyCombination.SHORTCUT_DOWN),
                        () -> rotateSelected(Rotation.CLOCKWISE_90),
                        editingBlocked),
                new Action(
                        "rotate-left",
                        "左に 90 度回転",
                        "左に回転",
                        ToolIcons.ROTATE_LEFT,
                        new KeyCodeCombination(KeyCode.LEFT, KeyCombination.SHORTCUT_DOWN),
                        () -> rotateSelected(Rotation.COUNTERCLOCKWISE_90),
                        editingBlocked),
                new Action("keep-range", "範囲を指定して残す…", "範囲", ToolIcons.RANGE, null, this::keepRange, editingBlocked),
                new Action(
                        "toggle-break",
                        "ここで区切る / 区切りを外す",
                        "区切り",
                        ToolIcons.BREAK,
                        new KeyCodeCombination(KeyCode.B, KeyCombination.SHORTCUT_DOWN),
                        this::toggleBreak,
                        breakUnavailable),
                new Action("break-every-n", "N ページごとに区切る…", null, null, null, this::breakEveryNPages, editingBlocked),
                new Action("clear-breaks", "区切りをすべて外す", null, null, null, this::clearBreaks, noBreaks),
                new Action("reset", "編集を元に戻す", "元に戻す", ToolIcons.RESET, null, this::resetOrder, editingBlocked),
                new Action("add", "PDF を追加…", "追加", ToolIcons.ADD, null, this::addDocuments, editingBlocked),
                new Action("split", "この文書を分割…", "分割", ToolIcons.SPLIT, null, this::splitDocument, editingBlocked),
                new Action(
                        "split-pages",
                        "1 ページずつに分割…",
                        "1 枚ずつ",
                        ToolIcons.SPLIT_PAGES,
                        null,
                        this::splitIntoSinglePages,
                        notSplittable),
                // ★ ツールバーには出さない。繰り返し使う操作ではなく、
                //   ツールバーの文言は起動スモークとの契約でもある（desktop-ui.md）。
                new Action("protect", "パスワードで保護して保存…", null, null, null, this::protectAndSave, editingBlocked),
                // 常に開ける。いま何版が動いているのかを確かめるのに、文書は要らない。
                new Action("about", AppInfo.NAME + " について", null, null, null, this::showAbout, null));
    }

    /**
     * 指定したファイルを開く。
     *
     * <p>起動引数やファイルの関連付けから呼ばれる。読み込みは非同期に行うため、
     * このメソッドは待たずに戻る。
     *
     * <p><b>★ 走っている間は {@link BackgroundTasks} が断る</b>（#114）。
     * <b>断られたときは「次に探す場所」も覚えない</b>——開かなかったものを覚えると、
     * 次のダイアログが利用者の知らない場所から始まる。
     *
     * <p><b>★★ ここが通ると文書が入れ替わる。</b>窓（確認・入力・ファイル選択）が出ている間も
     * {@code Platform.runLater} は回るので、<b>入れ替わりは窓の内側でも起きる</b>——
     * <b>確認や入力を挟む操作は、挟んだ後に自分で検め直すこと。</b>
     * <b>いま検め直しているのは {@link #removeSource} だけである</b>——
     * {@code keepRange} / {@code addDocument} / {@code addWithPassword} / {@code writeSegments} は
     * まだであり、#133 が持つ。<b>今日の呼び出し元は起動引数だけなので届かない。</b>
     *
     * @param path 開くファイル
     */
    public void open(Path path) {
        boolean started = tasks.run(() -> DocumentSession.open(path), this::adopt, failure -> {
            if (errorCodeOf(failure) == ErrorCode.PASSWORD_REQUIRED) {
                askPasswordAndOpen(path, false);
            } else {
                messages.failure(failure);
            }
        });

        // 開くのに失敗しても覚える。パスワードが要る文書でも壊れた文書でも、
        // 次に PDF を探す場所は同じフォルダである。
        //
        // openDocument ではなくここに置くのは、起動引数から開く経路（PdfjigApplication、
        // ファイルの関連付け）も通すためである。
        if (started) {
            folders.rememberReadFile(path);
        }
    }

    /**
     * パスワードを尋ねてから開く。
     *
     * <p>入力が誤っていれば、誤りである旨を添えてもう一度尋ねる。打ち間違いは
     * 起きるものであり、開き直しからやらせる理由がない。取り消せば終わる。
     */
    private void askPasswordAndOpen(Path path, boolean retry) {
        Optional<Password> entered = PasswordPrompt.ask(stage, path, PasswordPrompt.Purpose.OPEN, retry);
        if (entered.isEmpty()) {
            return;
        }
        // ★★ 持ち主ごと渡す。走り出したら仕事の枠が閉じ、走り出さなかったら向こうが閉じる
        //   ——ここには片づけを書く場所が無い（BackgroundTasks#run。#146）。
        Password password = entered.get();
        tasks.run(password, () -> DocumentSession.open(path, password), this::adopt, failure -> {
            if (errorCodeOf(failure) == ErrorCode.INVALID_PASSWORD) {
                askPasswordAndOpen(path, true);
            } else {
                messages.failure(failure);
            }
        });
    }

    /**
     * 届いた失敗の分類。分からなければ {@code null}。
     *
     * <p><b>★ 包みを解いてから見る。</b>書き出しの経路は控えの在り処を載せて包むことがある
     * （{@code OutputWorkspace#failing}。#124）。<b>解かないと、そこから来た失敗だけが
     * 黙って {@code null} になる</b>——いま呼んでいるのは「開く」の 2 か所だけで届かないが、
     * <b>書き出しの側で分類を見たくなった日に、分岐が 1 つも当たらない形で壊れる。</b>
     * <b>注意書きで残さず、ここで解く。</b>
     */
    private static ErrorCode errorCodeOf(Throwable failure) {
        if (failure instanceof ReplacedFileKeptException kept) {
            return errorCodeOf(kept.getCause());
        }
        return failure instanceof PdfjigException pdfjig ? pdfjig.errorCode() : null;
    }

    private void openDocument() {
        dialogs.openPdf(readingFolder().orElse(null)).ifPresent(this::open);
    }

    /** 名前を付けて書き出す。保護は掛けない。 */
    private void saveAs() {
        save(false);
    }

    /**
     * パスワードで保護して書き出す。
     *
     * <p><b>★★ 組み立てと保護が 1 回の書き出しで済む</b>（#199）——<b>平文は 1 バイトも
     * ディスクに現れない。</b>{@code Encryption#protect} を通す形では、組み立てた平文が
     * 一度作業場所へ落ちる（{@code SECURITY.md}「対象範囲」2 番目）。
     */
    private void protectAndSave() {
        save(true);
    }

    /**
     * 書き出す。<b>保護を掛けるかどうかだけが違う。</b>
     *
     * <p><b>★★ 2 本に分けない。</b>写せば<b>順序と後始末の理由が 2 か所に散る</b>——
     * ここが持っているのは、<b>置き換えの検めを書き出しより前に置くこと</b>（#118）、
     * <b>鍵を訊くのは {@code run} の直前であること</b>（INV-5）、
     * <b>警告より先に寄せ直しを始めること</b>の 3 つで、<b>どれも片方だけ直すと壊れる。</b>
     *
     * <p><b>★★ 分かれ目は「保護を掛けたか」ではなく、「開くのに鍵が要るか」である</b>
     * （{@code Protection#userPasswordRequired}）。<b>ユーザーパスワードを空にすると、
     * 保護を掛けても出力は誰でも開ける</b>——<b>そこで「掛けたか」で分けると、
     * 鍵の要る入力が誰でも開ける出力になっても、窓も出ず警告も出ない</b>（#30 の門の 2 段目）。
     *
     * <p><b>★ 鍵が要る出力になるときだけ、問う窓を出さない</b>（#192）。
     * <b>引き継がれないのは「入力の鍵」であって、利用者が受け取るのは鍵の要る出力である。</b>
     * <b>そうでない回は従来どおり問う</b>——<b>文言だけを分ける</b>
     * （{@link ProtectionPrompt.Outcome}）。
     *
     * <p><b>★★ 鍵が要る出力になったときは寄せ直さず、食い違いの印を立てる</b>
     * （{@link #markStale}）。<b>開き直すにはいま使った鍵が要るが、
     * 鍵は書き出しが終わった時点で閉じている</b>（INV-5）。
     * <b>もう一度訊いて開き直す形は採らない</b>：保存のたびに 2 度訊くことになり、
     * <b>「開き直してください」のほうが短い。</b>
     * ★ <b>鍵が要らない出力なら、寄せ直せるので寄せ直す。</b>
     *
     * @param asksForProtection 保護の指定を尋ねるか。<b>分かれ目には使わない</b>——使うのは尋ねた後の
     *                          {@code needsAKey} である
     */
    private void save(boolean asksForProtection) {
        if (session == null) {
            return;
        }
        Optional<Path> chosen = dialogs.savePdf(writingFolder().orElse(null), suggestedFileName());
        if (chosen.isEmpty()) {
            return;
        }

        DocumentSession saving = session;
        List<Path> sources = saving.paths();
        List<PageSelection> pages = saving.order().toPageSelections();
        // 区切りと選択位置は書き出しに関与しないが、寄せ直すと消える。持ち越すために控える（#118）。
        List<Boolean> breaks = saving.order().breaks();
        int selected = thumbnails.selectedIndex();
        Path output = chosen.get();

        // ★ 書き出し先を決めた後に訊く。先に訊くと、行き先を取り消しただけで打った鍵が捨てられる。
        Optional<Protection> requested = asksForProtection ? EncryptionPrompt.ask(stage) : Optional.empty();
        if (asksForProtection && requested.isEmpty()) {
            return;
        }
        Protection protection = requested.orElse(null);

        // ★★ 渡しきるまでの持ち主はここである（INV-5。askKeys と同じ規律）。
        //   ★★ 掛ける側だけでは足りない。askKeys が返した入力の鍵も、渡すまではここのものである
        //   ——間に投げるものがあると、そこを通った平文の配列は二度と消されない
        //   （#135 / #144 / #145 と同じ類型。#30 の門の 2 段目）。
        List<Password> owned = new ArrayList<>();
        if (protection != null) {
            owned.addAll(protection.keys());
        }
        boolean handedOver = false;
        try {
            // ★★ 保護が落ちるなら、書き出す前に伝えて選ばせる（docs/SPEC.md §4.3.1。#29 / #192）。
            //   ★★ 問わずに済むのは「鍵が要る出力になる」ときだけである（#30 の門の 2 段目）。
            //   「保護を掛けた」で分けると、ユーザーパスワードを空にした回に穴が開く——
            //   出力は誰でも開けるのに、窓も出ず pdf-core の警告も出ない。
            //   ★ 文言は分ける。保護を掛けている最中に「保護を外して書き出す」と出すと、
            //   何を押しているのかが読んで分からなくなる（ProtectionPrompt.Outcome）。
            boolean needsAKey = protection != null && protection.userPasswordRequired();
            ProtectionPrompt.Outcome consequence =
                    protection == null ? ProtectionPrompt.Outcome.PLAIN : ProtectionPrompt.Outcome.OPENS_WITHOUT_A_KEY;
            int asked = needsAKey ? 0 : saving.keyedContributors(pages).size();
            if (!needsAKey && !consentsToDroppingProtection(saving, pages, consequence)) {
                return;
            }

            // ★★ 鍵は保存のたびに訊く。セッションは抱えない（#193）。
            //   ★ 訊くのは run の直前である。ここから run までの間に投げるものがあると、
            //   持ち主の決まっていない平文の鍵がそこに残る（INV-5）——渡せば向こうが必ず閉じる。
            //   取り消されたら何も書かない——ここまでに入力された鍵は askKeys が閉じている。
            Optional<Sources> keyed = askKeys(saving);
            if (keyed.isEmpty()) {
                return;
            }
            Sources inputs = keyed.get();

            // ★★ 入力の鍵と、掛ける側の鍵の両方を渡す。どちらも仕事の枠が閉じる。
            owned.addAll(keysOf(inputs.all()));

            handedOver = true;
            boolean started = run(
                    owned,
                    () -> {
                        // ★ 書き出す前に見る。後では「これから何を置き換えるのか」が読めなくなる。
                        boolean replaced = DocumentWriter.replacesAnyOf(sources, output);
                        return new SaveOutcome(replaced, DocumentWriter.assemble(inputs, pages, output, protection));
                    },
                    outcome -> {
                        markSaved(saving, sources, pages);
                        // ★ 警告より先に寄せ直しを始める。messages.warnings はモーダルで、
                        //   出ている間は入れ子のイベントループに入る——後ろに置くと、
                        //   利用者が閉じるまで寄せ直しが始まらない。
                        //   複数の出どころから書き出すと文書情報の警告が必ず出るので、
                        //   これは例外的な経路ではない。
                        try {
                            if (outcome.replacedASource()) {
                                // ★★ 分かれ目は「掛けたか」ではない。鍵が要らない出力なら
                                //   寄せ直せる——そこで印を立てると、直せたのに編集を塞ぐ。
                                if (needsAKey) {
                                    markStale(StaleReason.OUTPUT_NEEDS_A_KEY);
                                } else {
                                    reopenAt(saving, sources, output, breaks, selected);
                                }
                            }
                        } finally {
                            // ★★ 寄せ直しが投げても警告を落とさない。書き出しは済んでおり、
                            //   文書情報が落ちたことは伝えなければならない——出どころが 2 つ以上あれば
                            //   必ず出る警告であり、例外的な経路ではない。
                            messages.warnings(exceptWhatWasAsked(outcome.warnings(), consequence.preempts, asked));
                        }
                    });
            // 書き出しは非同期で、成否は後から届く。始まったところで覚える——
            // 断られたときに覚えると、書いていない場所が「次に書き出す場所」になる。
            if (started) {
                folders.rememberWrittenFile(output);
            }
        } finally {
            if (!handedOver) {
                owned.forEach(Password::close);
            }
        }
    }

    /**
     * 書き出しの結果。
     *
     * @param replacedASource 開いている出どころのどれかを置き換えたか（#118）
     * @param warnings        途中で出た警告
     */
    private record SaveOutcome(boolean replacedASource, List<Warning> warnings) {}

    /**
     * 書き出したファイルへセッションを寄せ直す。
     *
     * <p><b>★★ 出どころを置き換えたときだけ呼ぶ。</b>置き換えた後の出どころは書き出したものに
     * なっており、<b>いまの並び（元のファイルに対する指定）をもう一度当てると同じ変換が二重に掛かる</b>
     * ——回転は保存のたびに 90 度ずつ回り、削除は 2 回目に止まる（#118）。
     * <b>別の名前へ保存したときは呼ばない。</b>元のファイルは変わっておらず、いまの並びが正しい。
     *
     * <p><b>寄せ直しはふつうの「開く」である。</b><b>パスワードを訊かれることはない</b>
     * ——<b>鍵の要る出力を作った回は、ここを通らず印を立てる</b>（{@link #save}）。
     * ★ <b>「書き出したものは平文である」と書いてあったが、#30 で偽になった。</b>
     *
     * <p><b>★ 区切りは持ち越す。</b>書き出しに関与しないので寄せ直すと消えるが、
     * <b>並びは書き出したものと同じなので、位置はそのまま通じる。</b>
     *
     * <p><b>★★ 寄せ直せなかったときは、保存を押せなくする</b>（{@link #stale}）。
     * 起きるのは 2 通り——<b>書き出している間に編集されていた</b>か、
     * <b>書き出したファイルを開き直せなかった</b>かである。
     * どちらでもセッションは古いままで、<b>続けて保存すると同じ変換が二重に掛かる。</b>
     * <b>書き出し自体は成功しておりファイルはできているので、失うものは無い</b>——開き直せば続けられる。
     *
     * <p><b>★ 書き出している間の編集を捨てない。</b>そこで並べ替えや削除がされていたら、
     * <b>寄せ直すとその編集ごと消える</b>——直しながら別のものを壊すことになる（優先順位 1）。
     * <b>いまは門があるので、利用者の操作からはそこへ届かない</b>（{@link #editingBlocked}。#114）
     * ——<b>それでも見るのは、門が漏れた日にここが最後の砦になるからである。</b>
     *
     * <p><b>★★ 出どころが外れたことは {@code modified()} には出ない</b>（#114）。
     * {@code PageOrder#removeSource} は<b>並びと基準を同じだけずらす</b>ので、
     * <b>編集していない文書からファイルを 1 つ外しても「変わっていない」と答える。</b>
     * <b>出どころ一覧まで見る</b>——{@link #markSaved} と同じ検め方である。
     */
    private void reopenAt(DocumentSession saving, List<Path> sources, Path output, List<Boolean> breaks, int selected) {
        if (session != saving) {
            // 書き出している間に別の文書を開かれていた。そちらを置き換えてはならない。
            return;
        }
        if (!stillHolds(saving, sources) || saving.order().modified()) {
            // ★★ 書き出している間に並べ替え・削除・ファイルの解除がされていた。
            //   寄せ直すとその編集ごと消える——直す前はそれが生き残っていたので、
            //   直しながら別のものを壊すことになる（CLAUDE.md 優先順位 1）。
            //   寄せないので古いままである。保存を押せなくして、そこで止める。
            markStale(StaleReason.REOPEN_FAILED);
            return;
        }
        boolean started = false;
        try {
            started = tasks.run(
                    () -> DocumentSession.open(output),
                    opened -> {
                        if (session != saving) {
                            // 開いている間に別の文書を開かれた／窓が閉じられた。
                            // ここで入れ替えると、そちらを黙って捨てることになる。
                            opened.close();
                            return;
                        }
                        adopt(opened);
                        opened.order().applyBreaks(breaks);
                        // 先頭へ戻されているので、控えておいた位置へ返す。
                        thumbnails.selectAndReveal(selected);
                    },
                    failure -> {
                        // 開き直せなかった。書き出しは成功しておりファイルはできているが、
                        // セッションは古いままである。押せなくして止める。
                        markStale(StaleReason.REOPEN_FAILED);
                        messages.failure(failure);
                    });
        } finally {
            if (!started) {
                // ★★ 走り出さなかった。寄せ直していないのだから古いままである——
                //   黙って戻ると「寄せ直せた」と同じ見た目になり、次の保存で変換が二重に掛かる（#118）。
                //   ★★ finally で見る。断られる（false）だけでなく、始め方が投げることもある
                //   ——代入が済まないので、if だけでは素通りする（#145 と同じ形）。
                //   ★ ここは markSaved が済んだ後である。印を立て損ねると、押せてしまう。
                //   ★ Password#close と違い、これは投げうる（束縛が連なり、状態行を組み直す）。
                //   投げれば飛んでいる失敗を置き換えるが、倒れる先は押せなくなる側なので受ける。
                markStale(StaleReason.REOPEN_FAILED);
            }
        }
    }

    /** 開いている文書が、書き出したファイルと食い違っていることを記す。 */
    private void markStale(StaleReason reason) {
        staleReason = reason;
        stale.set(true);
        updateStatus();
    }

    /**
     * 書き出しが済んだので、その並びを基準にする。状態行から「未保存の変更があります」が消える。
     *
     * <p>書き出し中に別の文書を開かれていることがある。始めたときと同じ文書のままでなければ、
     * 基準を動かしてはならない。渡すのは <b>書き出した並び</b> であって今の並びではない。
     * 書き出している間に並べ替えられていれば、その分はまだ書き出されていない。
     *
     * <p><b>★ 並べ替えや回転は見なくてよい</b>（{@link #stillHolds} が見るのは出どころだけである）。
     * 基準は書き出した並びで正しく、
     * <b>いまの並びと食い違えば「未保存の変更があります」が出るのが正しい。</b>
     * 壊れるのは<b>出どころ番号の意味が変わるとき</b>だけである。
     *
     * <p>完了は JavaFX スレッドで走るため、比べるだけなら同期は要らない。
     *
     * @param saving  書き出しを始めたときの文書
     * @param sources そのときの出どころ一覧
     * @param pages   書き出した並び
     */
    private void markSaved(DocumentSession saving, List<Path> sources, List<PageSelection> pages) {
        if (!stillHolds(saving, sources)) {
            return;
        }
        session.order().markSaved(pages);
        updateStatus();
    }

    /**
     * 掴んでおいた文書が、いまも同じ出どころを同じ順で持っているか。
     *
     * <p><b>★★ 同一性だけでは足りない</b>（#114）。{@code session != saving} が捕まえるのは
     * <b>入れ替わりだけ</b>で、<b>{@link DocumentSession#remove} は同じオブジェクトを書き換える。</b>
     * 出どころが 1 つ外れると<b>後ろの番号が繰り下がる</b>ので、
     * <b>掴んでおいた番号も並びも、いまの一覧に対しては別のファイルを指す。</b>
     *
     * <p><b>★ 名前ではなくパスの並びで見る。</b>{@code sourceName} はファイル名しか返さないので、
     * <b>別のフォルダにある同じ名前のファイルを見分けられない</b>——
     * <b>取り消せない操作の番人がそこで通ると、確認していないファイルが外れる。</b>
     *
     * @param target  掴んでおいた文書
     * @param sources 掴んだときの出どころ一覧
     */
    private boolean stillHolds(DocumentSession target, List<Path> sources) {
        return session == target && target.paths().equals(sources);
    }

    private void deleteSelected() {
        int index = thumbnails.selectedIndex();
        if (session == null || index < 0) {
            return;
        }
        try {
            session.order().removeAt(index);
        } catch (PdfjigException e) {
            messages.failure(e);
        }
    }

    private void rotateSelected(Rotation additional) {
        int index = thumbnails.selectedIndex();
        if (session == null || index < 0) {
            return;
        }
        session.order().rotateAt(index, additional);
    }

    private void keepRange() {
        if (session == null) {
            return;
        }
        PageRangePrompt.ask(stage, session.order().size())
                .ifPresent(range -> session.order().keepOnly(range));
    }

    /**
     * 開いている文書に、他の PDF のページを足す。
     *
     * <p>足したページは並びの末尾に付き、以後は元からあったページと区別なく
     * 並べ替え・回転・削除ができる。ファイルが書き出されるのは「名前を付けて保存」のときだけで、
     * 他の操作と同じ流れになる。
     *
     * <p>並べる順序は名前順にする。ファイル選択ダイアログが返す順序は環境によって変わり、
     * 選んだ順に並ぶと思い込ませてしまうため。順序が違えばサムネイルの上でドラッグして
     * 直せるので、確認は求めない。
     */
    private void addDocuments() {
        if (session == null) {
            return;
        }
        Optional<List<Path>> chosen = dialogs.openPdfs(readingFolder().orElse(null));
        if (chosen.isEmpty()) {
            return;
        }

        chosen.get().stream()
                .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                .forEach(this::addDocument);
    }

    /**
     * 1 つのファイルを足す。
     *
     * <p>読み込みは短く、足した結果は画面にすぐ出したい。ここは同期で行う。
     * ページの描画は今までどおりサムネイル側が非同期で受け持つ。
     */
    private void addDocument(Path path) {
        folders.rememberReadFile(path);
        try {
            session.add(path);
        } catch (PdfjigException e) {
            if (e.errorCode() == ErrorCode.PASSWORD_REQUIRED) {
                addWithPassword(path, false);
            } else {
                messages.failure(e);
            }
        }
        afterOrderChanged();
    }

    /** パスワードを尋ねて足す。誤っていれば、誤りである旨を添えてもう一度尋ねる。 */
    private void addWithPassword(Path path, boolean retry) {
        Optional<Password> entered = PasswordPrompt.ask(stage, path, PasswordPrompt.Purpose.OPEN, retry);
        if (entered.isEmpty()) {
            return;
        }
        // ★★ ここは同じスレッドの中で終わるので、持ち主のまま閉じる。中まで届かずに投げることが
        //   あり（session は null になりうるし、窓を挟んだ後の検め直しを足せば早く戻る経路も
        //   増える）、そこを通ってもこの close が消す（INV-5。#145）。
        try (Password password = entered.get()) {
            session.add(path, password);
        } catch (PdfjigException e) {
            if (e.errorCode() == ErrorCode.INVALID_PASSWORD) {
                addWithPassword(path, true);
            } else {
                messages.failure(e);
            }
        }
    }

    /**
     * ファイル一覧から 1 つ外す。取り消せないので、消える量を見せて確認を取る。
     *
     * <p><b>★★ 確認の窓は入れ子のイベントループである</b>（#114）。{@code Alert#showAndWait} は
     * {@code Platform.runLater} を回し続け、<b>{@code Task} の完了はそこに乗る</b>——
     * <b>{@code APPLICATION_MODAL} が止めるのは入力だけで、積まれたものは止めない。</b>
     * 検めたときと当てるときの間に文書が入れ替われば、
     * <b>利用者が説明されたのとは違う文書からファイルが外れる。</b>
     */
    private void removeSource(int sourceIndex) {
        if (session == null || sourceIndex >= session.sourceCount()) {
            return;
        }
        // ★ 検めた相手を掴んでおく。番号だけでは、入れ替わった先の別のファイルを指しうる。
        DocumentSession target = session;
        List<Path> sources = target.paths();
        String name = target.sourceName(sourceIndex);
        long pageCount = target.order().pages().stream()
                .filter(entry -> entry.selection().sourceIndex() == sourceIndex)
                .count();

        if (!messages.confirmRemoveSource(name, pageCount)) {
            return;
        }
        if (!stillHolds(target, sources)) {
            // 確認の最中に入れ替わった。黙って戻る——利用者が見た説明はもう成り立たず、
            // ここで何かを外せば、確認していない文書に当たる。
            return;
        }

        try {
            target.remove(sourceIndex);
        } catch (PdfjigException e) {
            messages.failure(e);
            return;
        }
        afterOrderChanged();
    }

    /** 含んでいるファイルが増えると、表題も一覧も状態表示も変わる。 */
    private void afterOrderChanged() {
        updateTitle();
        onOrderChanged();
    }

    /** 並びが変わると、枚数の内訳も変わる。 */
    private void onOrderChanged() {
        breakCount.set(session == null ? 0 : session.order().breakCount());
        pageCount.set(session == null ? 0 : session.order().size());
        legend.update(session);
        updateStatus();
    }

    /**
     * 区切りに従って分割する。
     *
     * <p>切り出すのは <b>編集中の並び</b> である。pdf-core の分割は元の並びを対象に
     * するためここでは使わない。並べ替えや削除をした後で分割したとき、それが
     * 反映されない結果を渡すほうが利用者を惑わせる。
     *
     * <p>区切りが 1 つも無いときは何もしない。全ページを 1 ファイルに書き出しても
     * 分割にならず、黙ってそうするより、区切りが要ることを伝えるほうが正直である。
     */
    private void splitDocument() {
        if (session == null) {
            return;
        }
        PageOrder order = session.order();
        if (order.breakCount() == 0) {
            messages.information("区切りが指定されていません。"
                    + System.lineSeparator()
                    + "新しいファイルの先頭にするページを選び、「ここで区切る」を押してください。"
                    + System.lineSeparator()
                    + "枚数で機械的に区切るなら「N ページごとに区切る…」を使います。"
                    + System.lineSeparator()
                    + "1 枚ずつバラすなら「1 ページずつに分割…」を使います。");
            return;
        }
        writeSegments(order.toSegments());
    }

    /**
     * すべてのページを 1 枚ずつのファイルにする。
     *
     * <p><b>区切りは見ない。</b>切れ目に判断の余地が無く、確かめるべきものが無いためである。
     * 「区切りを入れる操作と書き出す操作を分ける」判断（HANDOVER.md）は、どこで切るかに
     * 選択の余地がある場合のものであり、ここには当たらない。
     *
     * <p>画面の区切りも変えない。付けてある区切りを黙って消さないためである。
     *
     * <p>1 ページの文書では呼ばれない。1 ファイルができるだけで分割にならず、
     * 区切りのときと違って利用者に打つ手も無いので、操作そのものを無効にしてある。
     */
    private void splitIntoSinglePages() {
        if (session == null) {
            return;
        }
        writeSegments(session.order().toSinglePageSegments());
    }

    /**
     * 切り分けたページ列を書き出す。
     *
     * <p>保存先を尋ねてから非同期で書く。既に同名のファイルがあれば 1 つも書かずに
     * 失敗する（{@link DocumentWriter#splitInto}）。上書きするかどうかは利用者の判断である。
     *
     * @param segments かたまりごとのページ指定。先頭から順に連番で書き出す
     */
    private void writeSegments(List<List<PageSelection>> segments) {
        // ★★ 窓より先に控える。segments は呼ぶ側が既に確定させたものであり、
        //   窓（フォルダ選択・鍵の入力）が出ている間も Platform.runLater は回るので、
        //   その間に文書が入れ替わりうる（#133）。★ 後で控えると、入れ替わった後の
        //   出どころ一覧へ入れ替わる前の出どころ番号を当てることになる——
        //   そこは素の IndexOutOfBoundsException になり、画面に何も出ない（#29 の門の 2 段目）。
        //   ★ 見張る形にはしていない。#133 が 4 つまとめて持つ。
        DocumentSession writing = session;

        Optional<Path> directory = dialogs.chooseFolder(writingFolder().orElse(null));
        if (directory.isEmpty()) {
            return;
        }

        // ★★ 分割は操作ごとに 1 回だけ問う（docs/SPEC.md §4.3.1。#29）。
        //   N 回出すと「読まずに続行を押す」習慣ができる。
        List<PageSelection> allPages = segments.stream().flatMap(List::stream).toList();
        if (!consentsToDroppingProtection(writing, allPages, ProtectionPrompt.Outcome.PLAIN)) {
            return;
        }
        // ★★ 数えるのはかたまりごとである。pdf-core は assembleEach でかたまりの数だけ
        //   warnAboutContributing を通すので、同じ出どころについて N 回発する
        //   （2026-09-14 実測。#29 の門の 2 段目）。問うたのは 1 回でも、落とす数はそこに合わせる
        //   ——合わせないと、同意したことをもう一度伝える窓が続く。
        int asked = segments.stream()
                .mapToInt(segment -> writing.keyedContributors(segment).size())
                .sum();

        Optional<Sources> keyed = askKeys(writing);
        if (keyed.isEmpty()) {
            return;
        }
        Sources sources = keyed.get();
        Path outputDir = directory.get();

        if (run(
                sources,
                () -> DocumentWriter.splitInto(sources, segments, outputDir),
                result -> showSplitResult(result, asked))) {
            folders.rememberWrittenFolder(outputDir);
        }
    }

    /** 選択中のページの区切りを付け外しする。 */
    private void toggleBreak() {
        int index = thumbnails.selectedIndex();
        if (session == null || index <= 0) {
            return;
        }
        session.order().toggleBreakAt(index);
    }

    /** 枚数で機械的に区切り直す。書き出しはせず、画面で確かめてから分割する。 */
    private void breakEveryNPages() {
        if (session == null) {
            return;
        }
        PageCountPrompt.ask(stage, session.order().size(), session.baseName())
                .ifPresent(session.order()::applyEveryNPages);
    }

    private void clearBreaks() {
        if (session != null) {
            session.order().clearBreaks();
        }
    }

    private void resetOrder() {
        if (session != null) {
            session.order().reset();
        }
    }

    private void adopt(DocumentSession opened) {
        closeSession();
        session = opened;
        // 開き直したので食い違いは無い。
        stale.set(false);

        thumbnails.show(opened);
        opened.order().pages().addListener(orderListener);

        documentOpen.set(true);
        updateTitle();
        onOrderChanged();
    }

    private void closeSession() {
        // 閉じたのだから食い違いようが無い。次に開くまで印は要らない。
        stale.set(false);
        if (session == null) {
            return;
        }
        session.order().pages().removeListener(orderListener);
        thumbnails.clear();

        // 描画スレッドの停止を待つ。1 枚分の描画が終わるまでなので、ここでの待ちは短い。
        session.close();
        session = null;

        documentOpen.set(false);
        updateTitle();
        onOrderChanged();
    }

    private void showSplitResult(DocumentWriter.SplitResult result, int asked) {
        messages.information(result.fileCount() + " 個のファイルを書き出しました。");
        messages.warnings(exceptWhatWasAsked(result.warnings(), ProtectionPrompt.Outcome.PLAIN.preempts, asked));
    }

    /**
     * 書き出す前に同意を取ったぶんを、後からもう一度出さない。
     *
     * <p><b>★★ {@code pdf-core} は書き出しの後に必ず警告を発する</b>
     * （{@code docs/SPEC.md} §4.3。{@code Warning#ENCRYPTION_NOT_PROPAGATED}）。
     * <b>そこは変えない</b>——{@code pdf-core} は<b>誰が呼んでいるかを知らない。</b>
     *
     * <p><b>★★ 落とすのは、画面が既に問うて同意を得た場合だけである。</b>
     * <b>「保護を外して書き出す」を押した直後に「保護されていません」と出すのは、
     * いま選ばせたことをもう一度言っているだけであり</b>、<b>窓が 2 枚続く</b>
     * ——<b>読まずに閉じる習慣を作る側である</b>（{@code CLAUDE.md} 優先順位 2）。
     *
     * <p><b>★★ 落とすのは、問うた数だけである。</b>全部落としてはならない
     * ——{@code pdf-core} は<b>寄与する入力のうち暗号化されているものの数だけ</b>発するが
     * （{@code PdfBoxPageOperations#warnAboutContributing}）、<b>問うたのはそのうち
     * 鍵を渡して開いたものだけ</b>である。<b>オーナーパスワードだけの文書は
     * 窓に名前が出ていない</b>ので、<b>あのぶんは残さなければ、保護が落ちたことを
     * 伝える口が 1 つも無くなる</b>（2026-09-14 に実測して直した。#29 の門の 2 段目）。
     *
     * <p><b>★★ 何を問うたのかを呼ぶ側が渡す。</b>落とす値をここへ書き込むと、
     * <b>問う窓を 1 つ足した日に、その分が黙って素通りする</b>——<b>同じ文を窓と警告で 2 度読ませる</b>
     * のは、<b>読まずに閉じる習慣を作る側である</b>（#30 の門の 1 段目）。
     * <b>{@link ProtectionPrompt.Outcome} が、問う文言と対になる値を持っている。</b>
     *
     * <p><b>★ どれを落とすかは選べない。</b>{@link Warning} は<b>どの出どころのものかを
     * 持っていない</b>——同じ値が並ぶだけである。<b>だから数で引く。</b>
     * 残った数が、<b>問わずに保護を落とした入力の数になる。</b>
     *
     * @param warnings 書き出しで出た警告
     * @param asked    窓が既に言った値。{@link ProtectionPrompt.Outcome} が問う文言と対で持っている
     * @param count    その値を落とす上限。<b>窓で名前を出して同意を得た出どころの数である</b>
     * @return 残す警告
     */
    static List<Warning> exceptWhatWasAsked(List<Warning> warnings, Warning asked, int count) {
        List<Warning> remaining = new ArrayList<>(warnings.size());
        int dropped = 0;
        for (Warning warning : warnings) {
            if (warning == asked && dropped < count) {
                dropped++;
                continue;
            }
            remaining.add(warning);
        }
        return List.copyOf(remaining);
    }

    private String suggestedFileName() {
        return session.baseName() + "-edited.pdf";
    }

    /**
     * PDF を選ぶダイアログを始めるフォルダ。
     *
     * <p>まだ読んでいなければ、いま開いている文書の隣から始める。どちらも無ければ渡さない。
     * 未指定のときに出るのは Windows が覚えている場所であり、ホームに固定するより馴染みがある。
     */
    private Optional<Path> readingFolder() {
        return folders.reading().or(this::documentFolder);
    }

    /**
     * 書き出し先を選ぶダイアログを始めるフォルダ。
     *
     * <p>まだ書き出していなければ、いま開いている文書の隣から始める。
     * 読む用とは分けてある。PDF を取ってくる場所と、整理した結果を置く場所は違うことが多い。
     */
    private Optional<Path> writingFolder() {
        return folders.writing().or(this::documentFolder);
    }

    /** いま開いている文書のあるフォルダ。開いた後に消えていることもあるので確かめる。 */
    private Optional<Path> documentFolder() {
        if (session == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(session.path().getParent()).filter(Files::isDirectory);
    }

    /**
     * 失敗の伝え方を既定にして走らせる。分けたい経路だけが {@link BackgroundTasks} を直に呼ぶ。
     *
     * @return 走り出したなら {@code true}
     */
    private <T> boolean run(Supplier<T> work, Consumer<T> onSucceeded) {
        return tasks.run(work, onSucceeded, messages::failure);
    }

    /**
     * 鍵を抱えた仕事を頼む。
     *
     * <p><b>★★ 持ち主ごと渡す。</b>走り出したら仕事の枠が閉じ、走り出さなかったら向こうが閉じる
     * ——<b>ここには片づけを書く場所が無い</b>（{@link BackgroundTasks#run(List, Supplier,
     * Consumer, Consumer)}。#146 / #193）。
     */
    private <T> boolean run(Sources owned, Supplier<T> work, Consumer<T> onSucceeded) {
        return run(keysOf(owned.all()), work, onSucceeded);
    }

    /**
     * 鍵を抱えた仕事を頼む。<b>鍵の出どころが 1 つとは限らない経路のためにある</b>——
     * 書き出しは<b>入力の鍵と、掛ける側の鍵の両方</b>を抱える（{@link #save}）。
     */
    private <T> boolean run(List<Password> owned, Supplier<T> work, Consumer<T> onSucceeded) {
        return tasks.run(owned, work, onSucceeded, messages::failure);
    }

    /**
     * その入力が抱えている鍵。
     *
     * <p><b>★★ 数え上げて別に持たない。</b>持つと<b>「入力に鍵を足したが、片づける一覧へは
     * 足さなかった」形が書ける</b>——そこを通った平文の配列は<b>二度と消されない</b>
     * （INV-5。#135 / #144 / #145 で 3 度破れたのと同じ類型である）。
     * <b>1 つの正本から引けば、書き忘れる場所が無い。</b>
     */
    private static List<Password> keysOf(List<Source> inputs) {
        return inputs.stream().map(Source::password).filter(Objects::nonNull).toList();
    }

    /**
     * 保護が落ちるなら、書き出す前に伝えて選ばせる。
     *
     * <p><b>何を訊くのかは {@link DocumentSession#keyedContributors} が持つ</b>
     * ——<b>あれは画面を出さないので、素の {@code test} から縛れる。</b>
     * <b>ここが持つのは「出すか出さないか」だけである。</b>
     *
     * @param saving 書き出すセッション
     * @param pages  出力に含めるページ
     * @return 続けてよいなら {@code true}
     */
    private boolean consentsToDroppingProtection(
            DocumentSession saving, List<PageSelection> pages, ProtectionPrompt.Outcome outcome) {
        List<String> dropping = saving.keyedContributors(pages);
        return dropping.isEmpty() || ProtectionPrompt.confirm(stage, dropping, outcome);
    }

    /**
     * 書き出しに要る鍵を訊く。
     *
     * <p><b>★★ 保存のたびに訊く。</b>セッションは鍵を抱えない（{@link DocumentSession}。#193）
     * ——抱えると<b>文書を開いている間ずっと平文の鍵がヒープに残る。</b>
     * <b>少し不便だが正直な側を選ぶ</b>（{@code CLAUDE.md} の優先順位）。
     *
     * <p><b>★ 訊くのは、開くとき鍵が要った出どころだけである。</b>
     * オーナーパスワードだけが掛かった文書は<b>鍵なしで開けており、書き出しも鍵なしで通る。</b>
     *
     * <p><b>★★ 渡しきるまでの持ち主はここである。</b>取り消されても、途中で投げても、
     * <b>そこまでに入力された鍵はここで閉じる</b>——渡した後の片づけは
     * {@link BackgroundTasks} が持つ。
     *
     * <p><b>★ 取り消しだけを見る形では足りない</b>（#196 の門の 2 段目）。
     * <b>窓の中で投げる経路は別に在り</b>、そこを通ると<b>持ち主の決まっていない平文が残る</b>
     * ——{@code addWithPassword} が 1 本ぶんについて同じ形を持っている（INV-5。#145）。
     * <b>渡しきったかどうかで分ける形は {@link BackgroundTasks#run(List, Supplier,
     * Consumer, Consumer)} と同じである。</b>
     *
     * @param saving 書き出すセッション
     * @return 書き出しに使う入力。取り消されたら空
     */
    private Optional<Sources> askKeys(DocumentSession saving) {
        List<Path> paths = saving.paths();
        List<Source> inputs = new ArrayList<>(paths.size());
        boolean handedOver = false;
        try {
            for (int sourceIndex = 0; sourceIndex < paths.size(); sourceIndex++) {
                Path path = paths.get(sourceIndex);
                if (!saving.keyed(sourceIndex)) {
                    inputs.add(Source.of(path));
                    continue;
                }
                Optional<Password> entered = PasswordPrompt.ask(stage, path, PasswordPrompt.Purpose.WRITE, false);
                if (entered.isEmpty()) {
                    return Optional.empty();
                }
                inputs.add(Source.of(path, entered.get()));
            }
            Optional<Sources> asked = Optional.of(new Sources(inputs));
            handedOver = true;
            return asked;
        } finally {
            if (!handedOver) {
                keysOf(inputs).forEach(Password::close);
            }
        }
    }

    /** 版数と実行環境を出す。文書を開いていなくても呼べる。 */
    private void showAbout() {
        AboutDialog.show(stage, hostServices, aiProvider.isAvailable());
    }

    private void updateTitle() {
        if (session == null) {
            stage.setTitle(AppInfo.NAME);
            return;
        }
        // 何を編集しているのかは最初に開いたファイルで示し、足したぶんは数で添える。
        // 全部のファイル名を並べると表題に収まらない。
        String title = AppInfo.NAME + " — " + session.path().getFileName();
        if (session.sourceCount() > 1) {
            title += " ほか " + (session.sourceCount() - 1) + " ファイル";
        }
        stage.setTitle(title);
    }

    private void updateStatus() {
        StringBuilder text = new StringBuilder();
        if (session == null) {
            text.append("文書が開かれていません。");
        } else {
            text.append(session.order().size())
                    .append(" / ")
                    .append(session.sourcePageCount())
                    .append(" ページ");
            if (session.sourceCount() > 1) {
                text.append("（").append(session.sourceCount()).append(" ファイル）");
            }
            if (session.order().breakCount() > 0) {
                text.append("　区切り ")
                        .append(session.order().breakCount())
                        .append(" か所 → ")
                        .append(session.order().segmentCount())
                        .append(" ファイルに分かれます");
            }
            if (session.order().modified()) {
                text.append("（未保存の変更があります）");
            }
            if (stale.get()) {
                // 書き出したファイルはできている。開き直せば続けられる。
                text.append(staleReason.text);
            }
            if (session.encrypted()) {
                text.append("（暗号化されています）");
            }
            if (session.signed()) {
                // 編集を始める前に知らせる。保存後の警告では遅い。
                text.append("（電子署名があります）");
            }
        }
        if (quitWhenIdle) {
            // ★★ なぜ閉じないのかを出す（#134）。何も言わずに閉じないと、利用者は
            //   固まったと読んで、より乱暴な終わらせ方をする——それがまさに守りたい場面である。
            //   ★ 文書が開かれていなくても出す。走っているのは書き出しだけではない。
            text.append("　処理が終わったら終了します。");
        }
        // AI の有無はここには出さない。この行は開いている文書の状態を出す場所であり、
        // 版の性格を混ぜると読み分けられない。出す先はバージョン情報（AppInfo#aiStatus）。
        status.setText(text.toString());
    }
}
