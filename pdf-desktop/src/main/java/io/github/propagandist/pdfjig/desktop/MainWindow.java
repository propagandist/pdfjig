package io.github.propagandist.pdfjig.desktop;

import io.github.propagandist.pdfjig.ai.AiProvider;
import io.github.propagandist.pdfjig.core.ErrorCode;
import io.github.propagandist.pdfjig.core.PageOperations;
import io.github.propagandist.pdfjig.core.PdfBoxPageOperations;
import io.github.propagandist.pdfjig.core.PageSelection;
import io.github.propagandist.pdfjig.core.PdfjigException;
import io.github.propagandist.pdfjig.core.Rotation;
import io.github.propagandist.pdfjig.core.Warning;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.value.ObservableValue;
import javafx.collections.ListChangeListener;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.Parent;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Separator;
import javafx.scene.control.ToolBar;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.FileChooser.ExtensionFilter;
import javafx.stage.Stage;

/**
 * 主画面。サムネイル一覧と、そこに対する操作を持つ。
 *
 * <p><b>ファイル I/O を伴う操作はすべて非同期で行う</b>（CLAUDE.md JavaFX 節）。
 * 画面を止めないためであり、100 ページの文書でも開いた瞬間に固まらない。
 *
 * <p>並べ替えと削除はページ並びの上でだけ起き、ファイルには触れない。
 * 「名前を付けて保存」で初めて書き出す。
 */
public final class MainWindow {

    /** 画面上の表示名。コマンド名・パッケージ名の {@code pdfjig} とは別に、こちらで統一する。 */
    private static final String TITLE = "PDFjig";

    /** 分割の出力ファイル名。pdf-core の分割と同じ形にそろえる。 */
    private static final String SPLIT_NAME_FORMAT = "%s_%03d.pdf";

    private final Stage stage;

    private final AiProvider aiProvider;

    private final ThumbnailGrid thumbnails = new ThumbnailGrid();

    /** いま含んでいるファイルの一覧。1 ファイルのときは出ない。 */
    private final SourceLegend legend = new SourceLegend();

    private final Label status = new Label();

    /** 進行中の操作がある間は true。操作の重ね掛けを防ぐ。 */
    private final BooleanProperty busy = new SimpleBooleanProperty(false);

    private final BooleanProperty documentOpen = new SimpleBooleanProperty(false);

    /** ページ並びが変わるたびに表示を更新する。 */
    private final ListChangeListener<PageSelection> orderListener = change -> onOrderChanged();

    private DocumentSession session;

    public MainWindow(Stage stage, AiProvider aiProvider) {
        this.stage = stage;
        this.aiProvider = aiProvider;
    }

    /**
     * 画面を組み立てる。
     *
     * @return 画面の根
     */
    public Parent build() {
        thumbnails.setOnDelete(this::deleteSelected);

        Actions actions = buildActions();

        HBox statusBar = new HBox(status);
        statusBar.getStyleClass().add("status-bar");
        statusBar.setPadding(new Insets(6, 12, 6, 12));

        BorderPane root = new BorderPane();
        root.setTop(new VBox(buildMenuBar(actions), buildToolBar(actions), legend.node()));
        root.setCenter(thumbnails.node());
        root.setBottom(statusBar);

        updateTitle();
        updateStatus();
        return root;
    }

    /** ウィンドウを閉じるときに呼ぶ。開いている文書を解放する。 */
    public void dispose() {
        closeSession();
    }

    /**
     * メニューとツールバーに出す 1 つの操作。
     *
     * <p>文言・ショートカット・有効条件・処理をここにまとめてある。メニューとツールバーで
     * 別々に書くと、片方だけ直したときに挙動がずれる。
     *
     * @param menuText     メニューに出す文言
     * @param toolText     ツールバーに出す文言。{@code null} ならツールバーには出さない
     * @param icon         ツールバーのアイコン（{@link ToolIcons} の SVG パス）
     * @param accelerator  ショートカット。{@code null} なら割り当てない
     * @param handler      実行する処理
     * @param disabled     無効にする条件
     */
    private record Action(
            String menuText,
            String toolText,
            String icon,
            KeyCombination accelerator,
            Runnable handler,
            ObservableValue<Boolean> disabled) {
    }

    /** 画面が持つ操作一式。メニューとツールバーの双方がここから作られる。 */
    private record Actions(
            Action open,
            Action save,
            Action close,
            Action quit,
            Action delete,
            Action rotateRight,
            Action rotateLeft,
            Action keepRange,
            Action reset,
            Action add,
            Action split) {
    }

    private Actions buildActions() {
        // 文書が開かれていて、かつ操作が走っていないときだけ触れる。
        ObservableValue<Boolean> needsDocument = documentOpen.not().or(busy);

        return new Actions(
                new Action("開く…", "開く", ToolIcons.OPEN,
                        new KeyCodeCombination(KeyCode.O, KeyCombination.SHORTCUT_DOWN),
                        this::openDocument, busy),
                new Action("名前を付けて保存…", "保存", ToolIcons.SAVE,
                        new KeyCodeCombination(KeyCode.S, KeyCombination.SHORTCUT_DOWN),
                        this::saveAs, needsDocument),
                new Action("閉じる", null, null, null, this::closeSession, needsDocument),
                new Action("終了", null, null, null, stage::close, null),
                new Action("選択したページを削除", "削除", ToolIcons.DELETE,
                        new KeyCodeCombination(KeyCode.DELETE),
                        this::deleteSelected, needsDocument),
                new Action("右に 90 度回転", "右に回転", ToolIcons.ROTATE_RIGHT,
                        new KeyCodeCombination(KeyCode.RIGHT, KeyCombination.SHORTCUT_DOWN),
                        () -> rotateSelected(Rotation.CLOCKWISE_90), needsDocument),
                new Action("左に 90 度回転", "左に回転", ToolIcons.ROTATE_LEFT,
                        new KeyCodeCombination(KeyCode.LEFT, KeyCombination.SHORTCUT_DOWN),
                        () -> rotateSelected(Rotation.COUNTERCLOCKWISE_90), needsDocument),
                new Action("範囲を指定して残す…", "範囲", ToolIcons.RANGE,
                        null, this::keepRange, needsDocument),
                new Action("並びと向きを元に戻す", "元に戻す", ToolIcons.RESET,
                        null, this::resetOrder, needsDocument),
                new Action("PDF を追加…", "追加", ToolIcons.ADD,
                        null, this::addDocuments, needsDocument),
                new Action("この文書を分割…", "分割", ToolIcons.SPLIT,
                        null, this::splitDocument, needsDocument));
    }

    private MenuBar buildMenuBar(Actions actions) {
        return new MenuBar(
                new Menu("ファイル", null,
                        menuItem(actions.open()), menuItem(actions.save()),
                        menuItem(actions.close()), menuItem(actions.quit())),
                new Menu("ページ", null,
                        menuItem(actions.delete()),
                        menuItem(actions.rotateRight()), menuItem(actions.rotateLeft()),
                        menuItem(actions.keepRange()), menuItem(actions.reset())),
                new Menu("ツール", null,
                        menuItem(actions.add()), menuItem(actions.split())));
    }

    /**
     * ツールバーを組む。
     *
     * <p>置くのは繰り返し使う操作だけで、メニューは全部を持ったまま残す。
     * 区切りは「ファイル」「ページ」「文書」の 3 つのまとまりに対応させてある。
     */
    private ToolBar buildToolBar(Actions actions) {
        return new ToolBar(
                toolButton(actions.open()), toolButton(actions.save()),
                new Separator(),
                toolButton(actions.delete()),
                toolButton(actions.rotateLeft()), toolButton(actions.rotateRight()),
                new Separator(),
                toolButton(actions.keepRange()), toolButton(actions.reset()),
                new Separator(),
                toolButton(actions.add()), toolButton(actions.split()));
    }

    private MenuItem menuItem(Action action) {
        MenuItem item = new MenuItem(action.menuText());
        item.setOnAction(event -> action.handler().run());
        if (action.accelerator() != null) {
            item.setAccelerator(action.accelerator());
        }
        if (action.disabled() != null) {
            item.disableProperty().bind(action.disabled());
        }
        return item;
    }

    private Button toolButton(Action action) {
        Button button = new Button(action.toolText(), ToolIcons.of(action.icon()));
        button.getStyleClass().add("tool-button");
        button.setContentDisplay(ContentDisplay.TOP);
        // Tab の巡回はサムネイル一覧に集める。操作の対象はページであって、ボタンではない。
        button.setFocusTraversable(false);
        button.setOnAction(event -> action.handler().run());
        button.setTooltip(new Tooltip(tooltipText(action)));
        if (action.disabled() != null) {
            button.disableProperty().bind(action.disabled());
        }
        return button;
    }

    /** ツールチップにはメニューと同じ文言を出す。短縮した表示名だけでは意味が伝わらないため。 */
    private static String tooltipText(Action action) {
        return action.accelerator() == null
                ? action.menuText()
                : action.menuText() + "（" + action.accelerator().getDisplayText() + "）";
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
        runAsync(() -> DocumentSession.open(path), this::adopt, failure -> {
            if (errorCodeOf(failure) == ErrorCode.PASSWORD_REQUIRED) {
                askPasswordAndOpen(path, false);
            } else {
                showFailure(failure);
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
        runAsync(() -> DocumentSession.open(path, password), this::adopt, failure -> {
            if (errorCodeOf(failure) == ErrorCode.INVALID_PASSWORD) {
                askPasswordAndOpen(path, true);
            } else {
                showFailure(failure);
            }
        });
    }

    private static ErrorCode errorCodeOf(Throwable failure) {
        return failure instanceof PdfjigException pdfjig ? pdfjig.errorCode() : null;
    }

    private void openDocument() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("PDF を開く");
        chooser.getExtensionFilters().add(new ExtensionFilter("PDF ファイル", "*.pdf"));

        File chosen = chooser.showOpenDialog(stage);
        if (chosen == null) {
            return;
        }
        open(chosen.toPath());
    }

    private void saveAs() {
        if (session == null) {
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("名前を付けて保存");
        chooser.getExtensionFilters().add(new ExtensionFilter("PDF ファイル", "*.pdf"));
        chooser.setInitialFileName(suggestedFileName());

        File chosen = chooser.showSaveDialog(stage);
        if (chosen == null) {
            return;
        }

        List<Path> sources = session.paths();
        List<PageSelection> pages = session.order().toPageSelections();
        Path output = chosen.toPath();
        runAsync(() -> assemble(sources, pages, output), this::showWarnings);
    }

    private void deleteSelected() {
        int index = thumbnails.selectedIndex();
        if (session == null || index < 0) {
            return;
        }
        try {
            session.order().removeAt(index);
        } catch (PdfjigException e) {
            showFailure(e);
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
        FileChooser chooser = new FileChooser();
        chooser.setTitle("追加する PDF を選ぶ");
        chooser.getExtensionFilters().add(new ExtensionFilter("PDF ファイル", "*.pdf"));

        List<File> chosen = chooser.showOpenMultipleDialog(stage);
        if (chosen == null) {
            return;
        }

        chosen.stream()
                .map(File::toPath)
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
        try {
            session.add(path);
        } catch (PdfjigException e) {
            if (e.errorCode() == ErrorCode.PASSWORD_REQUIRED) {
                addWithPassword(path, false);
            } else {
                showFailure(e);
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
                showFailure(e);
            }
        }
    }

    /** 含んでいるファイルが増えると、表題も一覧も状態表示も変わる。 */
    private void afterOrderChanged() {
        updateTitle();
        onOrderChanged();
    }

    /** 並びが変わると、枚数の内訳も変わる。 */
    private void onOrderChanged() {
        legend.update(session);
        updateStatus();
    }

    /**
     * 現在の文書を分割する。
     *
     * <p>切り出すのは <b>編集中の並び</b> である。pdf-core の分割は元の並びを対象に
     * するためここでは使わない。並べ替えや削除をした後で分割したとき、それが
     * 反映されない結果を渡すほうが利用者を惑わせる。
     */
    private void splitDocument() {
        if (session == null) {
            return;
        }
        Optional<Integer> chunk = PageCountPrompt.ask(
                stage, session.order().size(), baseNameOf(session.path()));
        if (chunk.isEmpty()) {
            return;
        }

        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("分割したファイルの保存先");
        File directory = chooser.showDialog(stage);
        if (directory == null) {
            return;
        }

        List<Path> sources = session.paths();
        List<PageSelection> pages = session.order().toPageSelections();
        String baseName = baseNameOf(session.path());
        int pagesPerFile = chunk.get();
        Path outputDir = directory.toPath();

        runAsync(
                () -> splitInto(sources, pages, pagesPerFile, outputDir, baseName),
                this::showSplitResult);
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
        updateStatus();
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
        updateStatus();
    }

    /**
     * ページ列を書き出す。
     *
     * <p>一時ファイルに書いてから置き換える。保存先を選ぶダイアログは既存ファイルへの
     * 上書きを利用者に確認するが、pdf-core は既存の出力を拒む。先に消してしまうと
     * 書き込みに失敗したときに元のファイルが失われる。置き換えなら、失敗しても
     * 元のファイルはそのまま残る。
     */
    private static List<Warning> assemble(
            List<Path> sources, List<PageSelection> pages, Path output) {
        List<Warning> warnings = Collections.synchronizedList(new ArrayList<>());
        PageOperations operations = new PdfBoxPageOperations(warnings::add);

        Path temporary = temporaryNextTo(output);
        try {
            operations.assemble(sources, pages, temporary);
            move(temporary, output);
        } finally {
            deleteQuietly(temporary);
        }
        return List.copyOf(warnings);
    }

    private static SplitResult splitInto(
            List<Path> sources,
            List<PageSelection> pages,
            int pagesPerFile,
            Path outputDir,
            String baseName) {
        List<Warning> warnings = Collections.synchronizedList(new ArrayList<>());
        PageOperations operations = new PdfBoxPageOperations(warnings::add);

        int fileCount = (pages.size() + pagesPerFile - 1) / pagesPerFile;
        List<Path> outputs = IntStream.rangeClosed(1, fileCount)
                .mapToObj(number -> outputDir.resolve(
                        String.format(Locale.ROOT, SPLIT_NAME_FORMAT, baseName, number)))
                .toList();

        // 1 つでも書けないなら、何も書かずに失敗させる。pdf-core の分割と同じ約束にする。
        for (Path output : outputs) {
            if (Files.exists(output)) {
                throw new PdfjigException(ErrorCode.OUTPUT_ALREADY_EXISTS);
            }
        }
        for (int i = 0; i < fileCount; i++) {
            int from = i * pagesPerFile;
            int to = Math.min(from + pagesPerFile, pages.size());
            operations.assemble(sources, pages.subList(from, to), outputs.get(i));
        }
        return new SplitResult(fileCount, List.copyOf(warnings));
    }

    /** 分割の結果。書き出した数と、その途中で出た警告。 */
    private record SplitResult(int fileCount, List<Warning> warnings) {
    }

    private void showSplitResult(SplitResult result) {
        show(AlertType.INFORMATION, result.fileCount() + " 個のファイルを書き出しました。");
        showWarnings(result.warnings());
    }

    private static Path temporaryNextTo(Path output) {
        try {
            Path temporary = Files.createTempFile(
                    output.toAbsolutePath().getParent(), ".pdfjig-", ".tmp");
            // pdf-core は既存の出力を拒む。名前だけ押さえて実体は消しておく。
            Files.delete(temporary);
            return temporary;
        } catch (IOException e) {
            throw PdfjigException.wrapping(ErrorCode.IO_FAILURE, e);
        }
    }

    private static void move(Path from, Path to) {
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw PdfjigException.wrapping(ErrorCode.IO_FAILURE, e);
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            // 置き換えに成功していれば既に無い。残っていても保存の成否は変わらない。
        }
    }

    private String suggestedFileName() {
        return baseNameOf(session.path()) + "-edited.pdf";
    }

    private static String baseNameOf(Path path) {
        String name = path.getFileName().toString();
        int extension = name.lastIndexOf('.');
        return extension > 0 ? name.substring(0, extension) : name;
    }

    private <T> void runAsync(Supplier<T> work, Consumer<T> onSucceeded) {
        runAsync(work, onSucceeded, this::showFailure);
    }

    private <T> void runAsync(
            Supplier<T> work, Consumer<T> onSucceeded, Consumer<Throwable> onFailed) {
        Task<T> task = new Task<>() {
            @Override
            protected T call() {
                return work.get();
            }
        };
        task.setOnSucceeded(event -> {
            busy.set(false);
            onSucceeded.accept(task.getValue());
        });
        task.setOnFailed(event -> {
            busy.set(false);
            onFailed.accept(task.getException());
        });

        busy.set(true);
        Thread worker = new Thread(task, "pdfjig-operation");
        worker.setDaemon(true);
        worker.start();
    }

    private void showWarnings(List<Warning> warnings) {
        if (warnings.isEmpty()) {
            return;
        }
        String message = warnings.stream()
                .distinct()
                .map(Warning::defaultMessage)
                .collect(Collectors.joining("\n"));
        show(AlertType.WARNING, message);
    }

    /**
     * 失敗を伝える。
     *
     * <p>例外そのもののメッセージは決して出さない。依存ライブラリの例外には入力値が
     * 埋め込まれていることがあり、そこにパスワードが混ざりうる（CLAUDE.md INV-5）。
     * 出してよいのは {@link ErrorCode} の定型文だけである。
     */
    private void showFailure(Throwable failure) {
        String message = failure instanceof PdfjigException pdfjig
                ? pdfjig.errorCode().defaultMessage()
                : "操作に失敗しました。";
        show(AlertType.ERROR, message);
    }

    private void show(AlertType type, String message) {
        Alert alert = new Alert(type, message, ButtonType.OK);
        alert.setHeaderText(null);
        alert.initOwner(stage);
        alert.showAndWait();
    }

    private void updateTitle() {
        if (session == null) {
            stage.setTitle(TITLE);
            return;
        }
        // 何を編集しているのかは最初に開いたファイルで示し、足したぶんは数で添える。
        // 全部のファイル名を並べると表題に収まらない。
        String title = TITLE + " — " + session.path().getFileName();
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
            if (session.order().modified()) {
                text.append("（未保存の変更があります）");
            }
            if (session.encrypted()) {
                text.append("（暗号化されています）");
            }
        }
        // AI が無いことを隠さない。無いままで全機能が使えるのが前提である（INV-3）。
        text.append("　　AI 機能: ").append(aiProvider.isAvailable() ? "利用可能" : "無効");
        status.setText(text.toString());
    }
}
