package io.github.propagandist.pdfjig.desktop;

import io.github.propagandist.pdfjig.ai.AiProvider;
import io.github.propagandist.pdfjig.core.ErrorCode;
import io.github.propagandist.pdfjig.core.PageSelection;
import io.github.propagandist.pdfjig.core.PdfjigException;
import io.github.propagandist.pdfjig.core.Rotation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javafx.application.HostServices;
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

    private final ThumbnailGrid thumbnails = new ThumbnailGrid();

    /** いま含んでいるファイルの一覧。1 ファイルのときは出ない。 */
    private final SourceLegend legend = new SourceLegend();

    /** ダイアログを始めるフォルダ。読む用と書く用を分けて覚える。 */
    private final RecentFolders folders = new RecentFolders();

    /** ファイルとフォルダを選ばせる手段。テストではここを差し替える。 */
    private final FileDialogs dialogs;

    /** 画面を止めずに走らせる手段。進行中かどうかもここが持つ。 */
    private final BackgroundTasks tasks = new BackgroundTasks();

    /** 利用者に伝える手段。 */
    private final Messages messages;

    private final Label status = new Label();

    private final BooleanProperty documentOpen = new SimpleBooleanProperty(false);

    /** 効いている区切りの数。操作の有効・無効と状態表示に使う。 */
    private final IntegerProperty breakCount = new SimpleIntegerProperty(0);

    /** いま並んでいるページ数。1 枚ずつの分割が使えるかの判定に使う。 */
    private final IntegerProperty pageCount = new SimpleIntegerProperty(0);

    /** ページ並びが変わるたびに表示を更新する。 */
    private final ListChangeListener<PageEntry> orderListener = change -> onOrderChanged();

    private DocumentSession session;

    public MainWindow(Stage stage, AiProvider aiProvider, HostServices hostServices) {
        this(stage, aiProvider, hostServices, new NativeFileDialogs(stage));
    }

    /**
     * ファイル選択の手段を指定して作る。
     *
     * <p>Windows の共通ダイアログは自動テストから操作できない。差し替えられるのは
     * この経路だけであり、画面の操作を試すテストはここから組み立てる。
     *
     * @param dialogs ファイルとフォルダを選ばせる手段
     */
    MainWindow(Stage stage, AiProvider aiProvider, HostServices hostServices, FileDialogs dialogs) {
        this.stage = stage;
        this.aiProvider = aiProvider;
        this.hostServices = hostServices;
        this.dialogs = dialogs;
        this.messages = new Messages(stage);
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
     * どの操作を持ち、それぞれが何をするかを決める。
     *
     * <p><b>並べ方は {@link Actions} が、節点の作り方は {@link Action} が持つ。</b>
     * ここが持つのは<b>処理と、無効にする条件</b>——どちらも画面の状態に依るものであり、
     * 外へ出せない。
     */
    private Actions buildActions() {
        // 走っている間は押させない。立てるのも下ろすのも BackgroundTasks だけである。
        ReadOnlyBooleanProperty busy = tasks.busy();

        // 文書が開かれていて、かつ操作が走っていないときだけ触れる。
        ObservableValue<Boolean> needsDocument = documentOpen.not().or(busy);

        // 先頭のページには区切りを付けられない。先頭は区切らなくてもファイルの始まりである。
        ObservableValue<Boolean> breakUnavailable = documentOpen
                .not()
                .or(busy)
                .or(thumbnails.selectedIndexProperty().lessThan(1));

        ObservableValue<Boolean> noBreaks = documentOpen.not().or(busy).or(breakCount.isEqualTo(0));

        // 1 ページしかなければ 1 枚ずつには分けられない。できるのは元と同じ 1 ファイルだけで、
        // 区切りが無いときと違って利用者に打つ手も無い。断るより初めから押させない。
        ObservableValue<Boolean> notSplittable = documentOpen.not().or(busy).or(pageCount.lessThan(2));

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
                        needsDocument),
                new Action("close", "閉じる", null, null, null, this::closeSession, needsDocument),
                new Action("quit", "終了", null, null, null, stage::close, null),
                new Action(
                        "delete",
                        "選択したページを削除",
                        "削除",
                        ToolIcons.DELETE,
                        new KeyCodeCombination(KeyCode.DELETE),
                        this::deleteSelected,
                        needsDocument),
                new Action(
                        "rotate-right",
                        "右に 90 度回転",
                        "右に回転",
                        ToolIcons.ROTATE_RIGHT,
                        new KeyCodeCombination(KeyCode.RIGHT, KeyCombination.SHORTCUT_DOWN),
                        () -> rotateSelected(Rotation.CLOCKWISE_90),
                        needsDocument),
                new Action(
                        "rotate-left",
                        "左に 90 度回転",
                        "左に回転",
                        ToolIcons.ROTATE_LEFT,
                        new KeyCodeCombination(KeyCode.LEFT, KeyCombination.SHORTCUT_DOWN),
                        () -> rotateSelected(Rotation.COUNTERCLOCKWISE_90),
                        needsDocument),
                new Action("keep-range", "範囲を指定して残す…", "範囲", ToolIcons.RANGE, null, this::keepRange, needsDocument),
                new Action(
                        "toggle-break",
                        "ここで区切る / 区切りを外す",
                        "区切り",
                        ToolIcons.BREAK,
                        new KeyCodeCombination(KeyCode.B, KeyCombination.SHORTCUT_DOWN),
                        this::toggleBreak,
                        breakUnavailable),
                new Action("break-every-n", "N ページごとに区切る…", null, null, null, this::breakEveryNPages, needsDocument),
                new Action("clear-breaks", "区切りをすべて外す", null, null, null, this::clearBreaks, noBreaks),
                new Action("reset", "編集を元に戻す", "元に戻す", ToolIcons.RESET, null, this::resetOrder, needsDocument),
                new Action("add", "PDF を追加…", "追加", ToolIcons.ADD, null, this::addDocuments, needsDocument),
                new Action("split", "この文書を分割…", "分割", ToolIcons.SPLIT, null, this::splitDocument, needsDocument),
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
     * @param path 開くファイル
     */
    public void open(Path path) {
        // 開くのに失敗しても覚える。パスワードが要る文書でも壊れた文書でも、
        // 次に PDF を探す場所は同じフォルダである。
        //
        // openDocument ではなくここに置くのは、起動引数から開く経路（PdfjigApplication、
        // ファイルの関連付け）も通すためである。
        folders.rememberReadFile(path);

        tasks.run(() -> DocumentSession.open(path), this::adopt, failure -> {
            if (errorCodeOf(failure) == ErrorCode.PASSWORD_REQUIRED) {
                askPasswordAndOpen(path, false);
            } else {
                messages.failure(failure);
            }
        });
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
        tasks.run(() -> DocumentSession.open(path, password), this::adopt, failure -> {
            if (errorCodeOf(failure) == ErrorCode.INVALID_PASSWORD) {
                askPasswordAndOpen(path, true);
            } else {
                messages.failure(failure);
            }
        });
    }

    private static ErrorCode errorCodeOf(Throwable failure) {
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
        Path output = chosen.get();
        // 書き出しは非同期で、成否は後から届く。選んだ時点で覚える。
        folders.rememberWrittenFile(output);
        run(() -> DocumentWriter.assemble(sources, pages, output), warnings -> {
            markSaved(saving, pages);
            messages.warnings(warnings);
        });
    }

    /**
     * 書き出しが済んだので、その並びを基準にする。状態行から「未保存の変更があります」が消える。
     *
     * <p>書き出し中に別の文書を開かれていることがある。始めたときと同じ文書のままでなければ、
     * 基準を動かしてはならない。渡すのは <b>書き出した並び</b> であって今の並びではない。
     * 書き出している間に並べ替えられていれば、その分はまだ書き出されていない。
     *
     * <p>完了は JavaFX スレッドで走るため、比べるだけなら同期は要らない。
     */
    private void markSaved(DocumentSession saving, List<PageSelection> pages) {
        if (session != saving) {
            return;
        }
        session.order().markSaved(pages);
        updateStatus();
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

    /** ファイル一覧から 1 つ外す。取り消せないので、消える量を見せて確認を取る。 */
    private void removeSource(int sourceIndex) {
        if (session == null || sourceIndex >= session.sourceCount()) {
            return;
        }
        String name = session.sourceName(sourceIndex);
        long pageCount = session.order().pages().stream()
                .filter(entry -> entry.selection().sourceIndex() == sourceIndex)
                .count();

        if (!messages.confirmRemoveSource(name, pageCount)) {
            return;
        }

        try {
            session.remove(sourceIndex);
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
     * 失敗する（{@link #splitInto}）。上書きするかどうかは利用者の判断である。
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
        folders.rememberWrittenFolder(outputDir);

        run(() -> DocumentWriter.splitInto(sources, segments, outputDir), this::showSplitResult);
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

        thumbnails.show(opened);
        opened.order().pages().addListener(orderListener);

        documentOpen.set(true);
        updateTitle();
        onOrderChanged();
    }

    private void closeSession() {
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

    /** 失敗の伝え方を既定にして走らせる。分けたい経路だけが {@link BackgroundTasks} を直に呼ぶ。 */
    private <T> void run(Supplier<T> work, Consumer<T> onSucceeded) {
        tasks.run(work, onSucceeded, messages::failure);
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
            if (session.encrypted()) {
                text.append("（暗号化されています）");
            }
            if (session.signed()) {
                // 編集を始める前に知らせる。保存後の警告では遅い。
                text.append("（電子署名があります）");
            }
        }
        // AI の有無はここには出さない。この行は開いている文書の状態を出す場所であり、
        // 版の性格を混ぜると読み分けられない。出す先はバージョン情報（AppInfo#aiStatus）。
        status.setText(text.toString());
    }
}
