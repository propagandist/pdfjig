package io.github.propagandist.pdfjig.desktop;

import io.github.propagandist.pdfjig.ai.AiProvider;
import io.github.propagandist.pdfjig.ai.NoOpProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import javafx.application.Application;
import javafx.application.Platform;
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
            // ★ finally で閉じる。保存が投げても文書を開いたままにしない——
            //   開いたままだと元の PDF を掴み続け、利用者は掴んでいる犯人が分からない。
            try {
                persist(window, settingsFile);
            } finally {
                window.dispose();
            }
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
     *
     * <p><b>★ 存在の確認は背景スレッドで行う。</b>覚えていたのは何か月も前のパスでありうる。
     * 切れたネットワーク共有を JavaFX スレッドで叩くと、<b>SMB のタイムアウトぶん画面が固まる</b>
     * （CLAUDE.md「JavaFX」）。<b>セッションの中で覚えたフォルダとはここが違う</b>——
     * あちらは直前に実際へ届いている。
     *
     * <p>覚えていたフォルダが 1 拍遅れて効くのは、<b>いま（そもそも覚えない）と比べて悪くならない。</b>
     *
     * <p><b>★ 遅れて届くので、届いた先が空とは限らない。</b>{@code start} は復元を待たずに
     * 起動引数のファイルを開く（ファイルの関連付けの経路）。だから
     * {@link RecentFolders#restoreUnused} は<b>まだ使われていない側だけを戻す。</b>
     */
    private static void restore(MainWindow window, Optional<Path> settingsFile) {
        if (settingsFile.isEmpty()) {
            return;
        }
        Settings settings = Settings.load(settingsFile.get());
        Path reading = settings.folder(Settings.READING_FOLDER).orElse(null);
        Path writing = settings.folder(Settings.WRITING_FOLDER).orElse(null);
        if (reading == null && writing == null) {
            return;
        }
        Thread worker = new Thread(
                () -> {
                    Path reachableReading = reachable(reading);
                    Path reachableWriting = reachable(writing);
                    Platform.runLater(() -> window.folders().restoreUnused(reachableReading, reachableWriting));
                },
                "restore-folders");
        worker.setDaemon(true);
        worker.start();
    }

    /** いま届くフォルダだけを返す。届かなければ覚えていなかったことにする。 */
    private static Path reachable(Path folder) {
        return folder != null && Files.isDirectory(folder) ? folder : null;
    }

    /**
     * いま覚えているフォルダを書き出す。
     *
     * <p><b>閉じるときに一度だけ書く。</b>操作のたびに書くと、覚えているのがフォルダ 2 つだけの
     * ために I/O が増える。<b>異常終了すると失われる</b>が、次に選び直せば済むものである。
     *
     * <p><b>書くかどうかの判断は {@link Settings#store} が持つ。</b>ここは JavaFX の側から
     * 値を取り出すだけである——判断をここに置くと、画面を起こさないと確かめられなくなる。
     */
    private static void persist(MainWindow window, Optional<Path> settingsFile) {
        if (settingsFile.isEmpty()) {
            return;
        }
        RecentFolders folders = window.folders();
        Settings.store(settingsFile.get(), folders.rememberedReading(), folders.rememberedWriting());
    }

    public static void main(String[] args) {
        launch(args);
    }
}
