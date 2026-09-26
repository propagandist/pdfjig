package io.github.propagandist.pdfjig.desktop;

import io.github.propagandist.pdfjig.core.Password;
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
 * 回避する方法はない（CLAUDE.md INV-5）。
 *
 * <p><b>★ 生成箇所は 2 つある。</b>ここと {@link EncryptionPrompt} で、
 * <b>後者は #30 で足した</b>——<b>「この 1 か所に閉じている」と書いてあったが、
 * その日から誤りになっていた</b>（{@code CLAUDE.md}「確かめていないことを、
 * 確かめたように書かない」）。<b>写し取りと消去の作法はどちらも同じ形にしてある</b>
 * ——{@code Password.copyOf(getCharacters())} で写し、{@code finally} で欄を消す。
 * <b>3 つ目を作るなら、そのとき初めて 1 か所へ寄せる</b>。
 */
final class PasswordPrompt {

    private PasswordPrompt() {}

    /**
     * 何のために訊いているか。
     *
     * <p><b>★★ 文言を分ける。</b>保存のときに「開く」と書いたボタンを出すと、
     * <b>何を押しているのか読んで分からない</b>（{@code CLAUDE.md} 優先順位 2）。
     * <b>窓の id は変えない</b>——あれはテストとの契約である（{@code desktop-ui.md}）。
     */
    enum Purpose {

        /** 文書を開くために訊く。 */
        OPEN("はパスワードで保護されています。", "開く"),

        /**
         * 書き出すために訊く。
         *
         * <p><b>開いたときの鍵は誰も持っていない</b>ので、書き出しのたびに訊く（#193）。
         */
        WRITE("はパスワードで保護されています。書き出しにも同じパスワードが要ります。", "続ける");

        private final String explanation;

        private final String action;

        Purpose(String explanation, String action) {
            this.explanation = explanation;
            this.action = action;
        }
    }

    /**
     * パスワードを尋ねる。
     *
     * <p><b>返された持ち主は、呼び出し側が try-with-resources に載せる</b>
     * （{@link Password} の Javadoc）。
     *
     * @param owner   親ウィンドウ
     * @param name    対象ファイルを画面に出す名前。<b>★ 同じ名前のファイルが開いていれば、
     *                区別の付いた名前を渡すこと</b>（{@link DocumentSession#sourceName}。#128）——
     *                どちらの鍵を訊かれているのか分からないと、別の文書の鍵を打たせる
     * @param purpose 何のために訊いているか
     * @param retry   入力し直しかどうか。true なら誤りである旨を添える
     * @return 入力されたパスワード。取り消された場合は空
     */
    static Optional<Password> ask(Stage owner, String name, Purpose purpose, boolean retry) {
        PasswordField field = new PasswordField();
        field.setId("password-field");
        field.setPromptText("パスワード");

        Label explanation = new Label(name + " " + purpose.explanation);
        explanation.setId("password-explanation");
        explanation.setWrapText(true);

        VBox content = new VBox(8, explanation, field);
        content.setPadding(new Insets(12));

        ButtonType unlock = new ButtonType(purpose.action, ButtonData.OK_DONE);

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
            try {
                // ★ 写し取りは Password の中で行う。素の char[] がここに出ないので、
                //   持ち主の無い配列を作れる場所がそもそも無い（INV-5）。
                return button != unlock ? null : Password.copyOf(field.getCharacters());
            } finally {
                // ★★ どの道を通っても消す。分けて書くと、写し取りが投げた回だけ欄が残る
                //   ——{@link EncryptionPrompt} と同じ形に揃える（#30 の門の 1 段目）。
                field.clear();
            }
        });

        // ★★ 作った {@link Password} が戻らない道を塞ぐ。{@link EncryptionPrompt} と同じ形である
        //   ——結果は押した時点で Dialog に載るが、showAndWait が戻るまでの間に投げると、
        //   持ち主の決まっていない平文の鍵が残る（INV-5。#30 の門の 1 段目）。
        try {
            return dialog.showAndWait();
        } catch (RuntimeException | Error failed) {
            Password orphan = dialog.getResult();
            if (orphan != null) {
                orphan.close();
            }
            throw failed;
        }
    }
}
