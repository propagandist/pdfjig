package io.github.propagandist.pdfjig.desktop;

import io.github.propagandist.pdfjig.ai.AiProvider;
import io.github.propagandist.pdfjig.ai.NoOpProvider;
import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

/**
 * pdfjig デスクトップアプリのエントリポイント。
 *
 * <p>スレッド規約: {@code PDFRenderer} の呼び出しは必ずバックグラウンドスレッド
 * （{@code Task} / {@code Service}）で行う。JavaFX Application Thread では
 * レンダリング済みの {@code Image} の差し込みのみを行う（CLAUDE.md JavaFX 節）。
 */
public final class PdfjigApplication extends Application {

    /**
     * 既定は {@link NoOpProvider}。API キー未設定でもここで例外にならないことが
     * CLAUDE.md INV-3 の起点になる。
     */
    private final AiProvider aiProvider = new NoOpProvider();

    @Override
    public void start(Stage stage) {
        Label title = new Label("pdfjig");
        Label status = new Label(aiProvider.isAvailable()
                ? "AI 機能: 利用可能"
                : "AI 機能: 無効（AI なしで全機能が利用できます）");

        VBox root = new VBox(8, title, status);
        root.setPadding(new Insets(16));

        stage.setTitle("pdfjig");
        stage.setScene(new Scene(root, 480, 240));
        stage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
