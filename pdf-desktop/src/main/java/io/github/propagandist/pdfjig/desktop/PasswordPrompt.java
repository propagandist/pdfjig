package io.github.propagandist.pdfjig.desktop;

import java.nio.file.Path;
import java.util.Optional;
import javafx.geometry.Insets;
import javafx.scene.control.ButtonBar.ButtonData;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

/**
 * パスワードを尋ねるダイアログ。
 *
 * <p>入力は {@link PasswordField} で受け、{@code char[]} で返す。
 *
 * <p><b>既知の限界:</b> JavaFX の {@link PasswordField} は入力を {@code String} で
 * 保持しており、pdfjig 側からこれを消す手段はない。この {@code String} は GC されるまで
 * ヒープに残る。{@code char[]} への写し取りと入力欄の消去はここで行うが、
 * {@link PasswordField} 内部の複製までは追えない。JavaFX の実装に踏み込まずに
 * 回避する方法はないため、生成箇所をこの 1 か所に閉じて影響範囲を限っている
 * （CLAUDE.md INV-5）。
 */
final class PasswordPrompt {

    private PasswordPrompt() {
    }

    /**
     * パスワードを尋ねる。
     *
     * <p>返された配列は、文書を開く際に呼び出し先でゼロ埋めされる。
     *
     * @param owner  親ウィンドウ
     * @param path   対象ファイル
     * @param retry  入力し直しかどうか。true なら誤りである旨を添える
     * @return 入力されたパスワード。取り消された場合は空
     */
    static Optional<char[]> ask(Stage owner, Path path, boolean retry) {
        PasswordField field = new PasswordField();
        field.setPromptText("パスワード");

        Label explanation = new Label(path.getFileName() + " はパスワードで保護されています。");
        explanation.setWrapText(true);

        VBox content = new VBox(8, explanation, field);
        content.setPadding(new Insets(12));

        ButtonType unlock = new ButtonType("開く", ButtonData.OK_DONE);

        Dialog<char[]> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("パスワードの入力");
        dialog.setHeaderText(retry ? "パスワードが正しくありません。" : null);
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().addAll(unlock, ButtonType.CANCEL);
        dialog.setOnShown(event -> field.requestFocus());

        dialog.setResultConverter(button -> {
            if (button != unlock) {
                field.clear();
                return null;
            }
            char[] password = copyOf(field.getCharacters());
            field.clear();
            return password;
        });

        return dialog.showAndWait();
    }

    /**
     * 入力を {@code char[]} に写し取る。
     *
     * <p>{@code toString()} を経由しない。新たな {@code String} を作れば、
     * それもまたヒープに残る対象が増えるだけである。
     */
    private static char[] copyOf(CharSequence typed) {
        char[] password = new char[typed.length()];
        for (int i = 0; i < password.length; i++) {
            password[i] = typed.charAt(i);
        }
        return password;
    }
}
