package io.github.propagandist.pdfjig.desktop;

import io.github.propagandist.pdfjig.ai.AiProvider;
import io.github.propagandist.pdfjig.core.ErrorCode;
import io.github.propagandist.pdfjig.core.PageOperations;
import io.github.propagandist.pdfjig.core.PdfBoxPageOperations;
import io.github.propagandist.pdfjig.core.PdfjigException;
import io.github.propagandist.pdfjig.core.Warning;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;
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

    private final Stage stage;

    private final AiProvider aiProvider;

    private final ListView<Integer> thumbnails = new ListView<>();

    private final Label status = new Label();

    /** 進行中の操作がある間は true。操作の重ね掛けを防ぐ。 */
    private final BooleanProperty busy = new SimpleBooleanProperty(false);

    private final BooleanProperty documentOpen = new SimpleBooleanProperty(false);

    /** ページ並びが変わるたびに表示を更新する。 */
    private final ListChangeListener<Integer> orderListener = change -> updateStatus();

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

        MenuItem reset = new MenuItem("並びを元に戻す");
        reset.setOnAction(event -> resetOrder());
        reset.disableProperty().bind(documentOpen.not().or(busy));

        return new MenuBar(
                new Menu("ファイル", null, open, save, close, quit),
                new Menu("ページ", null, delete, reset));
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
        runAsync(() -> DocumentSession.open(path), this::adopt);
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
        List<Integer> pages = session.order().toPageNumbers();
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
    private static List<Warning> assemble(Path source, List<Integer> pages, Path output) {
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
        String name = session.path().getFileName().toString();
        int extension = name.lastIndexOf('.');
        String base = extension > 0 ? name.substring(0, extension) : name;
        return base + "-edited.pdf";
    }

    private <T> void runAsync(Supplier<T> work, Consumer<T> onSucceeded) {
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
            showFailure(task.getException());
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
