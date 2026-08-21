package io.github.propagandist.pdfjig.desktop;

import io.github.propagandist.pdfjig.ai.AiProvider;
import io.github.propagandist.pdfjig.ai.NoOpProvider;
import java.nio.file.Path;
import java.util.List;
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

        Scene scene = new Scene(window.build(), INITIAL_WIDTH, INITIAL_HEIGHT);
        scene.getStylesheets().add(
                PdfjigApplication.class.getResource("pdfjig.css").toExternalForm());

        stage.getIcons().add(new Image(
                PdfjigApplication.class.getResourceAsStream("pdfjig-256.png")));

        stage.setScene(scene);
        stage.setOnHidden(event -> window.dispose());
        stage.show();

        // 起動引数でファイルを渡せる。ファイルの関連付けから開かれる経路でもある。
        List<String> arguments = getParameters().getRaw();
        if (!arguments.isEmpty()) {
            window.open(Path.of(arguments.get(0)));
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
