package io.github.propagandist.pdfjig.desktop;

import io.github.propagandist.pdfjig.ai.AiProvider;
import io.github.propagandist.pdfjig.ai.NoOpProvider;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;

/**
 * pdfjig デスクトップアプリのエントリポイント。
 *
 * <p>スレッド規約: {@code PDFRenderer} の呼び出しは必ずバックグラウンドスレッド
 * （{@code Task} / {@code Service}）で行う。JavaFX Application Thread では
 * レンダリング済みの {@code Image} の差し込みのみを行う（CLAUDE.md JavaFX 節）。
 */
public final class PdfjigApplication extends Application {

    private static final int INITIAL_WIDTH = 960;

    private static final int INITIAL_HEIGHT = 720;

    /**
     * 既定は {@link NoOpProvider}。API キー未設定でもここで例外にならないことが
     * CLAUDE.md INV-3 の起点になる。
     */
    private final AiProvider aiProvider = new NoOpProvider();

    @Override
    public void start(Stage stage) {
        MainWindow window = new MainWindow(stage, aiProvider, getHostServices());

        // 設定の読み書きはここが持つ。画面はフォルダを覚えるだけで、置き場も書き方も知らない。
        Optional<Path> settingsFile = UserDataDirectory.settingsFile();
        restore(window, settingsFile);

        Scene scene = new Scene(window.build(), INITIAL_WIDTH, INITIAL_HEIGHT);
        scene.getStylesheets()
                .add(PdfjigApplication.class.getResource("pdfjig.css").toExternalForm());

        stage.getIcons().add(new Image(PdfjigApplication.class.getResourceAsStream("pdfjig-256.png")));

        stage.setScene(scene);
        stage.setOnHidden(event -> {
            // 先に保存する。dispose は文書を閉じるだけだが、順序を決めておく。
            persist(window, settingsFile);
            window.dispose();
        });
        stage.show();

        // 起動引数でファイルを渡せる。ファイルの関連付けから開かれる経路でもある。
        List<String> arguments = getParameters().getRaw();
        if (!arguments.isEmpty()) {
            window.open(Path.of(arguments.get(0)));
        }
    }

    /**
     * 前回覚えたフォルダを画面へ戻す。
     *
     * <p>置き場が無い環境（{@code %LOCALAPPDATA%} を持たない）では何もしない。
     * <b>覚えられないことは、動かない理由ではない。</b>
     */
    private static void restore(MainWindow window, Optional<Path> settingsFile) {
        if (settingsFile.isEmpty()) {
            return;
        }
        Settings settings = Settings.load(settingsFile.get());
        window.folders()
                .restore(
                        settings.folder(Settings.READING_FOLDER).orElse(null),
                        settings.folder(Settings.WRITING_FOLDER).orElse(null));
    }

    /**
     * いま覚えているフォルダを書き出す。
     *
     * <p><b>閉じるときに一度だけ書く。</b>操作のたびに書くと、覚えているのがフォルダ 2 つだけの
     * ために I/O が増える。<b>異常終了すると失われる</b>が、次に選び直せば済むものである。
     *
     * <p>読み直してから書くのは、<b>この起動の間に別の窓が書いたものを踏まないため</b>ではない
     * （1 つの編集セッションしか持たない）。<b>将来ここへ別の項目が増えたときに、
     * 知らない鍵を消さないためである。</b>
     */
    private static void persist(MainWindow window, Optional<Path> settingsFile) {
        if (settingsFile.isEmpty()) {
            return;
        }
        Path file = settingsFile.get();
        Settings settings = Settings.load(file);
        RecentFolders folders = window.folders();
        settings.putFolder(Settings.READING_FOLDER, folders.rememberedReading().orElse(null));
        settings.putFolder(Settings.WRITING_FOLDER, folders.rememberedWriting().orElse(null));
        settings.save(file);
    }

    public static void main(String[] args) {
        launch(args);
    }
}
