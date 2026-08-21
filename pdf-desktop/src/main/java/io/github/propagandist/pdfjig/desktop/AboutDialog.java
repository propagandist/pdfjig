package io.github.propagandist.pdfjig.desktop;

import javafx.application.HostServices;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar.ButtonData;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

/**
 * バージョン情報のダイアログ。
 *
 * <p>出すのは「いま動いているのが何か」を答えるのに要る値だけである。版数・著作権・ライセンス・
 * 配布元と、実行中のランタイム。不具合の報告を受けたときに、利用者に環境を聞き直さずに済ませる。
 *
 * <p>「情報をコピー」は、その報告に貼るためのもの。開いている文書の名前や中身は<b>含めない</b>。
 */
final class AboutDialog {

    /** アイコンの表示サイズ。原寸 256px の画像を縮めて使う。 */
    private static final int ICON_SIZE = 64;

    private AboutDialog() {
    }

    /**
     * ダイアログを開き、閉じられるまで待つ。
     *
     * @param owner        親ウィンドウ
     * @param hostServices リンクを既定のブラウザに渡すために使う
     * @param aiAvailable  AI プロバイダが使える状態か。{@code AiProvider} をそのまま受けないのは、
     *                     この画面が要るのは可否だけであり、提案の仕組みまで知る必要がないため
     */
    static void show(Stage owner, HostServices hostServices, boolean aiAvailable) {
        Label name = new Label(AppInfo.NAME);
        name.getStyleClass().add("about-title");

        Label version = new Label("バージョン " + AppInfo.version());

        Label ai = detail(AppInfo.aiStatus(aiAvailable));

        Label copyright = new Label(AppInfo.COPYRIGHT);
        copyright.getStyleClass().add("about-detail");

        Label license = new Label("ライセンス: " + AppInfo.LICENSE);
        license.getStyleClass().add("about-detail");

        Hyperlink repository = new Hyperlink(AppInfo.REPOSITORY);
        repository.getStyleClass().add("about-link");
        // 既定の余白があると、上下の行と左端がそろわない。
        repository.setPadding(Insets.EMPTY);
        repository.setOnAction(event -> hostServices.showDocument(AppInfo.REPOSITORY));

        Label java = detail(AppInfo.javaRuntime());

        VBox details = new VBox(
                2,
                name,
                version,
                ai,
                copyright,
                license,
                repository,
                java,
                detail(AppInfo.javafxRuntime()),
                detail(AppInfo.operatingSystem()));
        // 「名前と版数」「配布元とライセンス」「実行環境」の 3 つのまとまりに見せる。
        // AI の有無は版数の補足なので、1 つ目のまとまりに入れる。
        VBox.setMargin(copyright, new Insets(10, 0, 0, 0));
        VBox.setMargin(java, new Insets(10, 0, 0, 0));

        ImageView icon = new ImageView(new Image(
                AboutDialog.class.getResourceAsStream("pdfjig-256.png"),
                ICON_SIZE, ICON_SIZE, true, true));

        HBox content = new HBox(16, icon, details);
        content.setAlignment(Pos.TOP_LEFT);
        content.setPadding(new Insets(12));

        ButtonType copy = new ButtonType("情報をコピー", ButtonData.OTHER);

        Dialog<Void> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("バージョン情報");
        dialog.setHeaderText(null);
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().addAll(copy, ButtonType.CLOSE);
        // ボタンで閉じるには結果を返す必要がある。見せるだけのダイアログなので値は持たない。
        dialog.setResultConverter(button -> null);

        Button copyButton = (Button) dialog.getDialogPane().lookupButton(copy);
        copyButton.addEventFilter(ActionEvent.ACTION, event -> {
            ClipboardContent clipboard = new ClipboardContent();
            clipboard.putString(AppInfo.diagnostics());
            Clipboard.getSystemClipboard().setContent(clipboard);
            copyButton.setText("コピーしました");
            // 押した結果としてダイアログが閉じては、コピーできたのかどうかが分からない。
            event.consume();
        });

        dialog.showAndWait();
    }

    private static Label detail(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("about-detail");
        return label;
    }
}
