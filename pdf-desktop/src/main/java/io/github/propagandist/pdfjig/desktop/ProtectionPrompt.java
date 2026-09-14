package io.github.propagandist.pdfjig.desktop;

import java.util.List;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar.ButtonData;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

/**
 * 保護が引き継がれないことを、書き出す前に伝えて選ばせる窓。
 *
 * <p><b>★★ 書き出した後に伝えるのでは取り消せない</b>（{@code docs/SPEC.md} §4.3.1。#49）。
 * 出力を消しても<b>書かれたことは元に戻らず</b>、<b>作業場所は出力先のディレクトリに作られる</b>
 * ので、共有フォルダへ保存したなら<b>そこに平文が置かれる。</b>
 * 利用者は「保護されたまま保存された」と考えて操作している。
 *
 * <p><b>★★ 訊くのは「中止か、続行か」だけである</b>（#29 で決めた）。
 * <b>引き継ぐ道は出さない</b>——<b>理由の正本は {@code docs/SPEC.md} §4.3.1 と
 * {@code EncryptionPropagation.INHERIT} にある。</b>ここへ写さない。
 *
 * <p><b>★★ 最低条件が 4 つある</b>（同 §4.3.1）。<b>どれか 1 つ崩れると、
 * 窓を出したまま経路が開く</b>——<b>同意が成立したかどうかが、{@code SECURITY.md}
 * 「対象範囲」2 番目に当たるかを決める。</b>
 *
 * <p><b>このクラスが持つのは 3 つである。</b>
 *
 * <ul>
 *   <li><b>既定のボタンと初期フォーカスは中止側である</b>——Enter を続けて押しても
 *       続行に倒れない</li>
 *   <li><b>×・Esc・窓の外は中止である</b>（{@link ButtonData#CANCEL_CLOSE} が
 *       閉じる操作の行き先になる）</li>
 *   <li><b>「次回から表示しない」を置かない</b>——置いた瞬間に、
 *       <b>黙って平文が落ちる経路が戻る</b></li>
 * </ul>
 *
 * <p><b>★ 4 つ目「分割では操作ごとに 1 回だけ問う」は、ここでは守れない。</b>
 * <b>何回呼ぶかを決めるのは呼ぶ側である</b>（{@code MainWindow#writeSegments}）。
 *
 * <p><b>★ 窓を出したことは免責ではない。</b>続行を選べば<b>平文は従来どおり同じフォルダへ
 * 落ちる。</b>変わるのは<b>知らないまま落ちるか、選んだ結果として落ちるか</b>である。
 */
final class ProtectionPrompt {

    private ProtectionPrompt() {}

    /**
     * 保護が落ちることを伝えて、続けてよいか訊く。
     *
     * @param owner    親ウィンドウ
     * @param dropping 保護が落ちる出どころのファイル名。<b>空で呼ばないこと</b>
     * @return 続けるなら {@code true}
     */
    static boolean confirm(Stage owner, List<String> dropping) {
        if (dropping.isEmpty()) {
            throw new IllegalArgumentException("保護が落ちる出どころが無いのに問うことはできません。");
        }

        ButtonType proceed = new ButtonType("保護を外して書き出す", ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType("中止", ButtonData.CANCEL_CLOSE);

        Alert alert = new Alert(AlertType.WARNING);
        alert.initOwner(owner);
        // ★ 折り返した本文の高さは、窓の大きさが決まった後にしか分からない。指定しないと
        //   長い本文の末尾が切れる（#124。Messages#show と同じ理由）——ここは
        //   出どころの名前を並べるので、本文の長さが入力で決まる側である。
        alert.getDialogPane().setMinHeight(Region.USE_PREF_SIZE);
        alert.setResizable(true);
        alert.setTitle("保護は引き継がれません");
        alert.setHeaderText(headerFor(dropping));
        alert.getDialogPane().setId("protection-dialog");
        alert.getDialogPane().setContent(body(dropping));
        alert.getButtonTypes().setAll(proceed, cancel);

        Button proceedButton = (Button) alert.getDialogPane().lookupButton(proceed);
        Button cancelButton = (Button) alert.getDialogPane().lookupButton(cancel);
        proceedButton.setId("protection-proceed");
        cancelButton.setId("protection-cancel");

        // ★★ 既定は中止側にする。Enter を続けて押しても続行に倒れない（SPEC.md §4.3.1）。
        //   ButtonData.CANCEL_CLOSE を持つボタンは、× と Esc の行き先でもある。
        proceedButton.setDefaultButton(false);
        cancelButton.setDefaultButton(true);
        alert.setOnShown(event -> cancelButton.requestFocus());

        // ★ 閉じられた（× / Esc）ときも中止である。showAndWait が空を返す経路がそれで、
        //   ここで「続行」に倒すと、窓を出したまま経路が開く。
        return alert.showAndWait().filter(proceed::equals).isPresent();
    }

    private static String headerFor(List<String> dropping) {
        return dropping.size() == 1
                ? "書き出すファイルはパスワードで保護されません。"
                : "書き出すファイルはパスワードで保護されません（保護された入力が " + dropping.size() + " 件）。";
    }

    /**
     * 何が起きるのかを、読んで分かる形で並べる。
     *
     * <p><b>★ 出どころの名前を出す。</b>どのファイルの保護が落ちるのかは、
     * <b>数だけでは辿れない。</b>
     */
    private static VBox body(List<String> dropping) {
        Label sources = new Label(String.join(System.lineSeparator(), dropping));
        sources.setId("protection-sources");
        sources.setWrapText(true);

        Label consequence = new Label(
                "書き出したファイルは、パスワードなしで開けるようになります。" + System.lineSeparator() + "保護したまま渡すには、書き出したあとで改めてパスワードを設定してください。");
        consequence.setWrapText(true);

        VBox content = new VBox(8, sources, consequence);
        content.setPadding(new Insets(12));
        return content;
    }
}
