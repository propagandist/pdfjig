package io.github.propagandist.pdfjig.desktop;

import io.github.propagandist.pdfjig.core.PageRange;
import java.util.Optional;
import javafx.beans.binding.Bindings;
import javafx.geometry.Insets;
import javafx.scene.control.ButtonBar.ButtonData;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

/**
 * ページ範囲を尋ねるダイアログ。
 *
 * <p>指定できるのは一覧の中での位置であり、元文書のページ番号ではない。
 * 並べ替えた後に「今見えている 3 枚目から 5 枚目」と言えるほうが素直なため。
 */
final class PageRangePrompt {

    private PageRangePrompt() {
    }

    /**
     * 範囲を尋ねる。
     *
     * @param owner     親ウィンドウ
     * @param pageCount 現在の枚数
     * @return 入力された範囲。取り消された場合は空
     */
    static Optional<PageRange> ask(Stage owner, int pageCount) {
        Spinner<Integer> first = new Spinner<>(1, pageCount, 1);
        Spinner<Integer> last = new Spinner<>(1, pageCount, pageCount);
        first.setId("range-first");
        last.setId("range-last");
        first.setEditable(true);
        last.setEditable(true);

        HBox fields = new HBox(8, first, new Label("枚目 から"), last, new Label("枚目 まで"));
        VBox content = new VBox(8, new Label("残す範囲を指定します。"), fields);
        content.setPadding(new Insets(12));

        ButtonType keep = new ButtonType("この範囲を残す", ButtonData.OK_DONE);

        Dialog<PageRange> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("範囲の指定");
        dialog.getDialogPane().setId("range-dialog");
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().addAll(keep, ButtonType.CANCEL);
        dialog.getDialogPane().lookupButton(keep).setId("range-keep");

        // 始点が終点を越えた指定は、そもそも押せないようにする。
        dialog.getDialogPane()
                .lookupButton(keep)
                .disableProperty()
                .bind(Bindings.createBooleanBinding(
                        () -> first.getValue() > last.getValue(),
                        first.valueProperty(),
                        last.valueProperty()));

        dialog.setResultConverter(button -> {
            if (button != keep) {
                return null;
            }
            first.commitValue();
            last.commitValue();
            return PageRange.of(first.getValue(), last.getValue());
        });

        return dialog.showAndWait();
    }
}
