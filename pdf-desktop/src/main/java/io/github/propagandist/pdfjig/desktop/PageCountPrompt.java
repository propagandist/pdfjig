package io.github.propagandist.pdfjig.desktop;

import java.util.Locale;
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
 * 1 ファイルあたりのページ数を尋ねるダイアログ。枚数で区切り直すときに使う。
 *
 * <p><b>ここで決めるのは区切りだけで、書き出しはしない。</b>区切った結果を画面で
 * 確かめてから「この文書を分割…」で書き出す。
 *
 * <p>入力に追従して<b>何個に分かれるか</b>を出す。枚数だけを尋ねると、実行するまで
 * 結果が分からず、確かめるには一度書き出すしかない。
 */
final class PageCountPrompt {

    /** 分割の出力ファイル名。pdf-core の分割と同じ形にそろえる。 */
    private static final String NAME_FORMAT = "%s_%03d.pdf";

    private PageCountPrompt() {}

    /**
     * ページ数を尋ねる。
     *
     * @param owner     親ウィンドウ
     * @param pageCount 現在の枚数。これを超える指定はできない
     * @param baseName  出力ファイル名の基準（拡張子を除いた入力名）
     * @return 入力された枚数。取り消された場合は空
     */
    static Optional<Integer> ask(Stage owner, int pageCount, String baseName) {
        Spinner<Integer> spinner = new Spinner<>(1, pageCount, 1);
        spinner.setId("page-count-input");
        spinner.setEditable(true);

        Label summary = new Label();
        summary.setWrapText(true);
        summary.getStyleClass().add("prompt-summary");

        // 手入力は確定するまで値に反映されない。両方を見て追従させる。
        spinner.valueProperty()
                .addListener(
                        (observable, previous, current) -> summary.setText(describe(pageCount, current, baseName)));
        spinner.getEditor()
                .textProperty()
                .addListener((observable, previous, current) ->
                        summary.setText(describe(pageCount, parse(current, pageCount), baseName)));
        summary.setText(describe(pageCount, spinner.getValue(), baseName));

        VBox content = new VBox(
                8,
                new Label("いま並んでいる " + pageCount + " ページを、先頭から順に区切ります。"),
                new Label("1 ファイルあたりのページ数"),
                spinner,
                summary);
        content.setPadding(new Insets(12));

        ButtonType apply = new ButtonType("区切る", ButtonData.OK_DONE);

        Dialog<Integer> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("N ページごとに区切る");
        dialog.getDialogPane().setId("page-count-dialog");
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().addAll(apply, ButtonType.CANCEL);
        dialog.getDialogPane().lookupButton(apply).setId("page-count-apply");
        dialog.setResultConverter(button -> {
            if (button != apply) {
                return null;
            }
            // 手入力は自動では取り込まれない。確定させてから読む。
            spinner.commitValue();
            return spinner.getValue();
        });

        return dialog.showAndWait();
    }

    /** 何個に分かれ、分割したときどんな名前で出るか。 */
    private static String describe(int pageCount, Integer pagesPerFile, String baseName) {
        if (pagesPerFile == null) {
            return "";
        }
        int fileCount = (pageCount + pagesPerFile - 1) / pagesPerFile;
        String first = nameOf(baseName, 1);
        String last = nameOf(baseName, fileCount);

        return fileCount == 1
                ? fileCount + " 個に分かれます（" + first + "）"
                : fileCount + " 個に分かれます（" + first + " 〜 " + last + "）";
    }

    private static String nameOf(String baseName, int number) {
        return String.format(Locale.ROOT, NAME_FORMAT, baseName, number);
    }

    /** 手入力の途中は数字でないことがある。読めなければ何も言わない。 */
    private static Integer parse(String text, int pageCount) {
        try {
            int value = Integer.parseInt(text.trim());
            return value >= 1 && value <= pageCount ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
