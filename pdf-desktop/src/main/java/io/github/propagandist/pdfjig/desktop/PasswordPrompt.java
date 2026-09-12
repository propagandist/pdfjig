package io.github.propagandist.pdfjig.desktop;

import io.github.propagandist.pdfjig.core.Password;
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
 * <p>入力は {@link PasswordField} で受け、{@link Password} で返す。
 *
 * <p><b>既知の限界:</b> JavaFX の {@link PasswordField} は入力を {@code String} で
 * 保持しており、pdfjig 側からこれを消す手段はない。この {@code String} は GC されるまで
 * ヒープに残る。{@link Password} への写し取りと入力欄の消去はここで行うが、
 * {@link PasswordField} 内部の複製までは追えない。JavaFX の実装に踏み込まずに
 * 回避する方法はないため、生成箇所をこの 1 か所に閉じて影響範囲を限っている
 * （CLAUDE.md INV-5）。
 */
final class PasswordPrompt {

    private PasswordPrompt() {}

    /**
     * パスワードを尋ねる。
     *
     * <p><b>返された持ち主は、呼び出し側が try-with-resources に載せる</b>
     * （{@link Password} の Javadoc）。
     *
     * @param owner  親ウィンドウ
     * @param path   対象ファイル
     * @param retry  入力し直しかどうか。true なら誤りである旨を添える
     * @return 入力されたパスワード。取り消された場合は空
     */
    static Optional<Password> ask(Stage owner, Path path, boolean retry) {
        PasswordField field = new PasswordField();
        field.setId("password-field");
        field.setPromptText("パスワード");

        Label explanation = new Label(path.getFileName() + " はパスワードで保護されています。");
        explanation.setWrapText(true);

        VBox content = new VBox(8, explanation, field);
        content.setPadding(new Insets(12));

        ButtonType unlock = new ButtonType("開く", ButtonData.OK_DONE);

        Dialog<Password> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("パスワードの入力");
        dialog.setHeaderText(retry ? "パスワードが正しくありません。" : null);
        dialog.getDialogPane().setId("password-dialog");
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().addAll(unlock, ButtonType.CANCEL);
        // ボタンは ButtonType から自動で作られる。id を付けられるのは追加した後だけ。
        dialog.getDialogPane().lookupButton(unlock).setId("password-unlock");
        dialog.getDialogPane().lookupButton(ButtonType.CANCEL).setId("password-cancel");
        dialog.setOnShown(event -> field.requestFocus());

        dialog.setResultConverter(button -> {
            if (button != unlock) {
                field.clear();
                return null;
            }
            // ★ 写し取りは Password の中で行う。素の char[] がここに出ないので、
            //   持ち主の無い配列を作れる場所がそもそも無い（INV-5）。
            Password password = Password.copyOf(field.getCharacters());
            field.clear();
            return password;
        });

        return dialog.showAndWait();
    }
}
