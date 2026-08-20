package io.github.propagandist.pdfjig.desktop;

import io.github.propagandist.pdfjig.ai.AiProvider;
import io.github.propagandist.pdfjig.core.ErrorCode;
import io.github.propagandist.pdfjig.core.MergeOptions;
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
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.Parent;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SelectionMode;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
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

    private static final String TITLE = "pdfjig";

    /** 分割の出力ファイル名。pdf-core の分割と同じ形にそろえる。 */
    private static final String SPLIT_NAME_FORMAT = "%s_%03d.pdf";

    private final Stage stage;

    private final AiProvider aiProvider;

    private final ListView<PageSelection> thumbnails = new ListView<>();

    private final Label status = new Label();

    /** 進行中の操作がある間は true。操作の重ね掛けを防ぐ。 */
    private final BooleanProperty busy = new SimpleBooleanProperty(false);

    private final BooleanProperty documentOpen = new SimpleBooleanProperty(false);

    /** ページ並びが変わるたびに表示を更新する。 */
    private final ListChangeListener<PageSelection> orderListener = change -> updateStatus();

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
        thumbnails.setPlaceholder(new Label("PDF を開いてください。"));
        thumbnails.getSelectionModel().setSelectionMode(SelectionMode.SINGLE);
        thumbnails.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.DELETE) {
                deleteSelected();
            }
        });

        HBox statusBar = new HBox(status);
        statusBar.setPadding(new Insets(6, 12, 6, 12));

        BorderPane root = new BorderPane();
        root.setTop(buildMenuBar());
        root.setCenter(thumbnails);
        root.setBottom(statusBar);

        updateTitle();
        updateStatus();
        return root;
    }

    /** ウィンドウを閉じるときに呼ぶ。開いている文書を解放する。 */
    public void dispose() {
        closeSession();
    }

    private MenuBar buildMenuBar() {
        MenuItem open = new MenuItem("開く…");
        open.setAccelerator(new KeyCodeCombination(KeyCode.O, KeyCombination.SHORTCUT_DOWN));
        open.setOnAction(event -> openDocument());
        open.disableProperty().bind(busy);

        MenuItem save = new MenuItem("名前を付けて保存…");
        save.setAccelerator(new KeyCodeCombination(KeyCode.S, KeyCombination.SHORTCUT_DOWN));
        save.setOnAction(event -> saveAs());
        save.disableProperty().bind(documentOpen.not().or(busy));

        MenuItem close = new MenuItem("閉じる");
        close.setOnAction(event -> closeSession());
        close.disableProperty().bind(documentOpen.not().or(busy));

        MenuItem quit = new MenuItem("終了");
        quit.setOnAction(event -> stage.close());

        MenuItem delete = new MenuItem("選択したページを削除");
        delete.setAccelerator(new KeyCodeCombination(KeyCode.DELETE));
        delete.setOnAction(event -> deleteSelected());
        delete.disableProperty().bind(documentOpen.not().or(busy));

        MenuItem rotateRight = new MenuItem("右に 90 度回転");
        rotateRight.setAccelerator(
                new KeyCodeCombination(KeyCode.RIGHT, KeyCombination.SHORTCUT_DOWN));
        rotateRight.setOnAction(event -> rotateSelected(Rotation.CLOCKWISE_90));
        rotateRight.disableProperty().bind(documentOpen.not().or(busy));

        MenuItem rotateLeft = new MenuItem("左に 90 度回転");
        rotateLeft.setAccelerator(
                new KeyCodeCombination(KeyCode.LEFT, KeyCombination.SHORTCUT_DOWN));
        rotateLeft.setOnAction(event -> rotateSelected(Rotation.COUNTERCLOCKWISE_90));
        rotateLeft.disableProperty().bind(documentOpen.not().or(busy));

        MenuItem keepRange = new MenuItem("範囲を指定して残す…");
        keepRange.setOnAction(event -> keepRange());
        keepRange.disableProperty().bind(documentOpen.not().or(busy));

        MenuItem reset = new MenuItem("並びと向きを元に戻す");
        reset.setOnAction(event -> resetOrder());
        reset.disableProperty().bind(documentOpen.not().or(busy));

        MenuItem merge = new MenuItem("複数の PDF を結合…");
        merge.setOnAction(event -> mergeDocuments());
        merge.disableProperty().bind(busy);

        MenuItem split = new MenuItem("この文書を分割…");
        split.setOnAction(event -> splitDocument());
        split.disableProperty().bind(documentOpen.not().or(busy));

        return new MenuBar(
                new Menu("ファイル", null, open, save, close, quit),
                new Menu("ページ", null, delete, rotateRight, rotateLeft, keepRange, reset),
                new Menu("ツール", null, merge, split));
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

        Path source = session.path();
        List<PageSelection> pages = session.order().toPageSelections();
        Path output = chosen.toPath();
        runAsync(() -> assemble(source, pages, output), this::showWarnings);
    }

    private void deleteSelected() {
        int index = thumbnails.getSelectionModel().getSelectedIndex();
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
        int index = thumbnails.getSelectionModel().getSelectedIndex();
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
     * 複数の PDF を 1 つに結合する。
     *
     * <p>選んだ順序はダイアログの実装と環境に左右されるため当てにできない。
     * 名前順に並べたうえで、その順序を見せて承認を求める。
     */
    private void mergeDocuments() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("結合する PDF を選ぶ");
        chooser.getExtensionFilters().add(new ExtensionFilter("PDF ファイル", "*.pdf"));

        List<File> chosen = chooser.showOpenMultipleDialog(stage);
        if (chosen == null) {
            return;
        }
        if (chosen.size() < 2) {
            show(AlertType.INFORMATION, "結合するには 2 つ以上のファイルを選んでください。");
            return;
        }

        List<Path> inputs = chosen.stream()
                .map(File::toPath)
                .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                .toList();
        if (!confirmMergeOrder(inputs)) {
            return;
        }

        FileChooser target = new FileChooser();
        target.setTitle("結合したファイルの保存先");
        target.getExtensionFilters().add(new ExtensionFilter("PDF ファイル", "*.pdf"));
        target.setInitialFileName("merged.pdf");

        File saveTo = target.showSaveDialog(stage);
        if (saveTo == null) {
            return;
        }
        Path output = saveTo.toPath();
        runAsync(() -> mergeInto(inputs, output), this::showWarnings);
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
        Optional<Integer> chunk = PageCountPrompt.ask(stage, session.order().size());
        if (chunk.isEmpty()) {
            return;
        }

        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("分割したファイルの保存先");
        File directory = chooser.showDialog(stage);
        if (directory == null) {
            return;
        }

        Path source = session.path();
        List<PageSelection> pages = session.order().toPageSelections();
        String baseName = baseNameOf(session.path());
        int pagesPerFile = chunk.get();
        Path outputDir = directory.toPath();

        runAsync(
                () -> splitInto(source, pages, pagesPerFile, outputDir, baseName),
                this::showSplitResult);
    }

    private boolean confirmMergeOrder(List<Path> inputs) {
        String order = IntStream.range(0, inputs.size())
                .mapToObj(i -> (i + 1) + ". " + inputs.get(i).getFileName())
                .collect(Collectors.joining(System.lineSeparator()));

        Alert alert = new Alert(
                AlertType.CONFIRMATION,
                "この順に結合します。" + System.lineSeparator() + System.lineSeparator() + order,
                ButtonType.OK,
                ButtonType.CANCEL);
        alert.setHeaderText("選んだ順序は環境によって変わるため、名前順に並べています。");
        alert.initOwner(stage);
        return alert.showAndWait().filter(ButtonType.OK::equals).isPresent();
    }

    private void resetOrder() {
        if (session != null) {
            session.order().reset();
        }
    }

    private void adopt(DocumentSession opened) {
        closeSession();
        session = opened;

        thumbnails.setCellFactory(
                view -> new ThumbnailCell(view, opened.order(), opened.thumbnails()));
        thumbnails.setItems(opened.order().pages());
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
        thumbnails.setItems(FXCollections.observableArrayList());

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
    private static List<Warning> assemble(Path source, List<PageSelection> pages, Path output) {
        List<Warning> warnings = Collections.synchronizedList(new ArrayList<>());
        PageOperations operations = new PdfBoxPageOperations(warnings::add);

        Path temporary = temporaryNextTo(output);
        try {
            operations.assemble(source, pages, temporary);
            move(temporary, output);
        } finally {
            deleteQuietly(temporary);
        }
        return List.copyOf(warnings);
    }

    private static List<Warning> mergeInto(List<Path> inputs, Path output) {
        List<Warning> warnings = Collections.synchronizedList(new ArrayList<>());
        PageOperations operations = new PdfBoxPageOperations(warnings::add);

        Path temporary = temporaryNextTo(output);
        try {
            operations.merge(inputs, temporary, MergeOptions.defaults());
            move(temporary, output);
        } finally {
            deleteQuietly(temporary);
        }
        return List.copyOf(warnings);
    }

    private static SplitResult splitInto(
            Path source,
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
            operations.assemble(source, pages.subList(from, to), outputs.get(i));
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
        stage.setTitle(session == null
                ? TITLE
                : TITLE + " — " + session.path().getFileName());
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
