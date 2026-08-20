package io.github.propagandist.pdfjig.desktop;

import java.util.Optional;
import javafx.geometry.Insets;
import javafx.scene.control.ButtonBar.ButtonData;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

/**
 * 1 ファイルあたりのページ数を尋ねるダイアログ。分割で使う。
 */
final class PageCountPrompt {

    private PageCountPrompt() {
    }

    /**
     * ページ数を尋ねる。
     *
     * @param owner     親ウィンドウ
     * @param pageCount 現在の枚数。これを超える指定はできない
     * @return 入力された枚数。取り消された場合は空
     */
    static Optional<Integer> ask(Stage owner, int pageCount) {
        Spinner<Integer> spinner = new Spinner<>(1, pageCount, 1);
        spinner.setEditable(true);

        VBox content = new VBox(
                8, new Label("1 ファイルあたりのページ数を指定します。"), spinner);
        content.setPadding(new Insets(12));

        ButtonType split = new ButtonType("分割", ButtonData.OK_DONE);

        Dialog<Integer> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("分割");
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().addAll(split, ButtonType.CANCEL);
        dialog.setResultConverter(button -> {
            if (button != split) {
                return null;
            }
            // 手入力は自動では取り込まれない。確定させてから読む。
            spinner.commitValue();
            return spinner.getValue();
        });

        return dialog.showAndWait();
    }
}
