package io.github.propagandist.pdfjig.desktop;

import io.github.propagandist.pdfjig.ai.AiProvider;
import io.github.propagandist.pdfjig.core.ErrorCode;
import io.github.propagandist.pdfjig.core.PageSelection;
import io.github.propagandist.pdfjig.core.PdfjigException;
import io.github.propagandist.pdfjig.core.Rotation;
import io.github.propagandist.pdfjig.core.Warning;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javafx.application.HostServices;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.value.ObservableValue;
import javafx.collections.ListChangeListener;
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
        //   ★ 組み立てではなく、ここで決める。build を 2 度呼べる形にすると、
        //     門が上書きされうる（上の★★と同じ理由）。
        stage.setOnCloseRequest(event -> {
            event.consume();
            requestQuit();
        });
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

    /** ウィンドウを閉じるときに呼ぶ。開いている文書を解放する。 */
    public void dispose() {
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
            tasks.whenIdle(stage::close);
            updateStatus();
            return;
        }
        stage.close();
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
        Optional<char[]> entered = PasswordPrompt.ask(stage, path, retry);
        if (entered.isEmpty()) {
            return;
        }
        // この配列は DocumentSession.open の中でゼロ埋めされる。
        char[] password = entered.get();
        boolean started = tasks.run(() -> DocumentSession.open(path, password), this::adopt, failure -> {
            if (errorCodeOf(failure) == ErrorCode.INVALID_PASSWORD) {
                askPasswordAndOpen(path, true);
            } else {
                messages.failure(failure);
            }
        });
        if (!started) {
            // ★★ 断られると仕事そのものが呼ばれない。ゼロ埋めはその中でしか起きないので、
            //   ここで消す（INV-5）。入力欄から出た平文を、そのまま置き去りにしない。
            Arrays.fill(password, '\0');
        }
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

    private void saveAs() {
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
        boolean started = run(
                () -> {
                    // ★ 書き出す前に見る。後では「これから何を置き換えるのか」が読めなくなる。
                    boolean replaced = DocumentWriter.replacesAnyOf(sources, output);
                    return new SaveOutcome(replaced, DocumentWriter.assemble(sources, pages, output));
                },
                outcome -> {
                    markSaved(saving, sources, pages);
                    // ★ 警告より先に寄せ直しを始める。messages.warnings はモーダルで、
                    //   出ている間は入れ子のイベントループに入る——後ろに置くと、
                    //   利用者が閉じるまで寄せ直しが始まらない。
                    //   複数の出どころから書き出すと文書情報の警告が必ず出るので、
                    //   これは例外的な経路ではない。
                    if (outcome.replacedASource()) {
                        reopenAt(saving, sources, output, breaks, selected);
                    }
                    messages.warnings(outcome.warnings());
                });
        // 書き出しは非同期で、成否は後から届く。始まったところで覚える——
        // 断られたときに覚えると、書いていない場所が「次に書き出す場所」になる。
        if (started) {
            folders.rememberWrittenFile(output);
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
     * <p><b>寄せ直しはふつうの「開く」である。</b>書き出したものは平文であり
     * （{@code EncryptionPropagation.NONE} しか対応していない）、<b>パスワードを訊かれることはない。</b>
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
            markStale();
            return;
        }
        boolean started = tasks.run(
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
                    markStale();
                    messages.failure(failure);
                });
        if (!started) {
            // ★★ 断られた。寄せ直していないのだから古いままである——
            //   黙って戻ると「寄せ直せた」と同じ見た目になり、次の保存で変換が二重に掛かる（#118）。
            markStale();
        }
    }

    /** 開いている文書が、書き出したファイルと食い違っていることを記す。 */
    private void markStale() {
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
        Optional<char[]> entered = PasswordPrompt.ask(stage, path, retry);
        if (entered.isEmpty()) {
            return;
        }
        try {
            // この配列は DocumentSession.add の中でゼロ埋めされる。
            session.add(path, entered.get());
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
        Optional<Path> directory = dialogs.chooseFolder(writingFolder().orElse(null));
        if (directory.isEmpty()) {
            return;
        }

        List<Path> sources = session.paths();
        Path outputDir = directory.get();

        if (run(() -> DocumentWriter.splitInto(sources, segments, outputDir), this::showSplitResult)) {
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

    private void showSplitResult(DocumentWriter.SplitResult result) {
        messages.information(result.fileCount() + " 個のファイルを書き出しました。");
        messages.warnings(result.warnings());
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
                text.append("（書き出したファイルを開き直せませんでした。開き直してください）");
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
