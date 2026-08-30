package io.github.propagandist.pdfjig.desktop;

import javafx.application.HostServices;
import javafx.concurrent.Task;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
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
import javafx.stage.Window;

/**
 * バージョン情報のダイアログ。
 *
 * <p>出すのは「いま動いているのが何か」を答えるのに要る値だけである。版数・著作権・ライセンス・
 * 配布元と、実行中のランタイム。不具合の報告を受けたときに、利用者に環境を聞き直さずに済ませる。
 *
 * <p>「情報をコピー」は、その報告に貼るためのもの。開いている文書の名前や中身は<b>含めない</b>。
 *
 * <p><b>「更新を確認」だけが外へ出る。</b>押したときに 1 度 GitHub へ問い合わせるだけで、
 * この窓を開いても、アプリを起動しても、何も送らない（{@link UpdateCheck}、#72）。
 * <b>ここに置いたのは、版数のすぐ隣が答えの出る場所だからである</b>——
 * 「手元のこれは古いのか」を確かめに来る人は、まずこの窓を開く。
 */
final class AboutDialog {

    /** アイコンの表示サイズ。原寸 256px の画像を縮めて使う。 */
    private static final int ICON_SIZE = 64;

    private AboutDialog() {}

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
        ai.setId("about-ai");

        // 押すまでは無い行として扱う。何も確かめていないのに何かが書いてあると、
        // 起動しただけで確認されたように読める（CLAUDE.md 優先順位 2）。
        Label update = detail("");
        update.setId("about-update");
        hide(update);

        Hyperlink releases = new Hyperlink("Releases を開く");
        releases.setId("about-update-link");
        releases.getStyleClass().add("about-link");
        releases.setPadding(Insets.EMPTY);
        // 落とさない・実行しない。渡すのは既定のブラウザである（#16 / #72）。
        releases.setOnAction(event -> hostServices.showDocument(AppInfo.LATEST_RELEASE));
        hide(releases);

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
                update,
                releases,
                copyright,
                license,
                repository,
                java,
                detail(AppInfo.javafxRuntime()),
                detail(AppInfo.operatingSystem()));
        // 「名前と版数」「配布元とライセンス」「実行環境」の 3 つのまとまりに見せる。
        // AI の有無も更新の確認も版数の補足なので、1 つ目のまとまりに入れる。
        VBox.setMargin(copyright, new Insets(10, 0, 0, 0));
        VBox.setMargin(java, new Insets(10, 0, 0, 0));

        ImageView icon = new ImageView(
                new Image(AboutDialog.class.getResourceAsStream("pdfjig-256.png"), ICON_SIZE, ICON_SIZE, true, true));

        HBox content = new HBox(16, icon, details);
        content.setAlignment(Pos.TOP_LEFT);
        content.setPadding(new Insets(12));

        ButtonType check = new ButtonType("更新を確認", ButtonData.OTHER);
        ButtonType copy = new ButtonType("情報をコピー", ButtonData.OTHER);

        Dialog<Void> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("バージョン情報");
        dialog.setHeaderText(null);
        dialog.getDialogPane().setId("about-dialog");
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().addAll(check, copy, ButtonType.CLOSE);
        dialog.getDialogPane().lookupButton(ButtonType.CLOSE).setId("about-close");
        // ボタンで閉じるには結果を返す必要がある。見せるだけのダイアログなので値は持たない。
        dialog.setResultConverter(button -> null);

        Button checkButton = (Button) dialog.getDialogPane().lookupButton(check);
        checkButton.setId("about-check-update");
        checkButton.addEventFilter(ActionEvent.ACTION, event -> {
            // 押した結果としてダイアログが閉じては、答えを読めない（「情報をコピー」と同じ）。
            event.consume();
            checkForUpdate(dialog, checkButton, update, releases);
        });

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

    /**
     * 更新を確認する。
     *
     * <p><b>通信はバックグラウンドスレッドで行う</b>（{@code CLAUDE.md}「JavaFX」）。
     * 遮断された環境では 10 秒まで返らないため、JavaFX スレッドで待つと窓ごと固まる。
     *
     * <p><b>答えが出るまでボタンを押せなくする。</b>連打すると要求だけが増える。
     * 答えが出たら、失敗していても押せる状態へ戻す——遮断は一時的なこともある。
     */
    private static void checkForUpdate(Dialog<?> dialog, Button button, Label result, Hyperlink link) {
        button.setDisable(true);
        hide(link);
        report(dialog, result, "確認しています…");

        Task<UpdateStatus> task = new Task<>() {
            @Override
            protected UpdateStatus call() {
                return UpdateCheck.check();
            }
        };
        task.setOnSucceeded(event -> settle(dialog, button, result, link, task.getValue()));
        // UpdateCheck は投げない。それでも受けておく——ここで落とすと、押した人には
        // 「確認しています…」のまま何も起きない窓が残る（CLAUDE.md 優先順位 2）。
        task.setOnFailed(event -> settle(dialog, button, result, link, new UpdateStatus.Unavailable()));

        Thread worker = new Thread(task, "pdfjig-update-check");
        worker.setDaemon(true);
        worker.start();
    }

    /** 答えを出す。リンクを添えるのは、新しい版があったときだけである。 */
    private static void settle(Dialog<?> dialog, Button button, Label result, Hyperlink link, UpdateStatus status) {
        boolean available = status instanceof UpdateStatus.Available;
        link.setVisible(available);
        link.setManaged(available);
        button.setDisable(false);
        report(dialog, result, UpdateCheck.describe(status));
    }

    private static void report(Dialog<?> dialog, Label result, String text) {
        result.setText(text);
        result.setVisible(true);
        result.setManaged(true);
        fitToContent(dialog);
    }

    /**
     * 増えた行のぶん窓を広げる。
     *
     * <p>JavaFX は表示後の窓を自動では広げないため、書いた文が切れる。
     *
     * <p><b>閉じた後に届くことがある。</b>確認している間に閉じられると、答えは行き先を失った
     * 部品に届く。<b>そこで落とさない</b>——窓が無いことは、失敗ではない。
     */
    private static void fitToContent(Dialog<?> dialog) {
        Scene scene = dialog.getDialogPane().getScene();
        if (scene == null) {
            return;
        }
        Window window = scene.getWindow();
        if (window != null) {
            window.sizeToScene();
        }
    }

    /** 押すまで無い行として扱う。場所も取らせない。 */
    private static void hide(Node node) {
        node.setVisible(false);
        node.setManaged(false);
    }

    private static Label detail(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("about-detail");
        return label;
    }
}
