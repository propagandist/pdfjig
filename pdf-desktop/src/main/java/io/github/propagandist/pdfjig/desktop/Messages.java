package io.github.propagandist.pdfjig.desktop;

import io.github.propagandist.pdfjig.core.ErrorCode;
import io.github.propagandist.pdfjig.core.PdfjigException;
import io.github.propagandist.pdfjig.core.Warning;
import java.util.List;
import java.util.stream.Collectors;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.ButtonType;
import javafx.stage.Stage;

/**
 * 利用者に伝える。
 *
 * <p><b>出してよいものと出してはならないものの線が、ここに 1 か所ある。</b>
 * 画面に散らしておくと、<b>次に窓を足す者がその線を知らないまま例外のメッセージを出す</b>。
 *
 * <p>取り消せない操作の確認もここが持つ。<b>問う文言と、その id が同じ場所にある</b>——
 * id はテストとの契約であり（{@code CLAUDE.md}「命名」）、文言だけ直して id を置き去りにする
 * 形を作らない。
 */
final class Messages {

    /** 出す窓の親。これを渡さないと、窓が主画面の裏へ回り込む。 */
    private final Stage owner;

    Messages(Stage owner) {
        this.owner = owner;
    }

    /**
     * 済んだこと、あるいは<b>できない理由</b>を伝える。
     *
     * <p><b>失敗ではないものをここへ通す。</b>区切りが 1 つも無いのに分割を押した、のような
     * <b>利用者に打つ手がある断り</b>がそれである——{@link #failure} へ流すと、
     * 何も壊れていないのに {@code OPERATION_FAILED} が記録に残る。
     */
    void information(String message) {
        show(AlertType.INFORMATION, message);
    }

    /**
     * 気をつけるべき点を伝える。
     *
     * <p>1 つも無ければ何も出さない。<b>「警告はありません」と出す窓は、
     * 押させるだけで何も伝えていない。</b>
     *
     * <p>同じ警告は 1 度しか出さない。ページごとに出ると、20 ページの文書で 20 行になる。
     */
    void warnings(List<Warning> warnings) {
        if (warnings.isEmpty()) {
            return;
        }
        String message =
                warnings.stream().distinct().map(Warning::defaultMessage).collect(Collectors.joining("\n"));
        show(AlertType.WARNING, message);
    }

    /**
     * 失敗を伝える。
     *
     * <p><b>例外そのもののメッセージは決して出さない。</b>依存ライブラリの例外には入力値が
     * 埋め込まれていることがあり、そこにパスワードが混ざりうる（{@code CLAUDE.md} INV-5）。
     * 出してよいのは {@link ErrorCode} の定型文だけである。
     *
     * <p><b>同じものを {@link Logs} にも残す。</b>画面の定型文は「何が起きたか」までしか言わず、
     * <b>利用者が窓を閉じた時点で消える</b>。後から報告を受ける側には型と行が要る。
     */
    void failure(Throwable failure) {
        Logs.warn(LogEvent.OPERATION_FAILED, failure);
        show(AlertType.ERROR, describe(failure));
    }

    /**
     * 失敗を、画面に出す文言に直す。
     *
     * <p><b>{@code static} なのは、画面を立てずに確かめるためである</b>（{@code MessagesTest}）。
     * <b>ここが「出してよいものの線」そのもの</b>であり、窓の出し方とは別に見られなければならない。
     *
     * <p><b>★★ 控えが残ったなら、その場所まで言う</b>（{@link ReplacedFileKeptException}。#124）。
     * <b>出力先には何も無く、元は作業場所の中にしか無い</b>ので、定型文だけでは
     * 利用者から見えるのは「ファイルが消えた」である。
     * <b>画面には出してよく、記録には書かない理由は {@code docs/SPEC.md} §10.4 が持つ。
     * ここへ写さない。</b>
     *
     * <p><b>原因の文言はそのまま前に置く。</b>場所を足すために、
     * <b>何が起きたのかを落とさない。</b>
     *
     * @param failure 起きた失敗。{@code null} でもよい
     * @return 画面に出す文言
     */
    static String describe(Throwable failure) {
        if (failure instanceof ReplacedFileKeptException kept) {
            // 改行は "\n" で足りる。Alert の本文は JavaFX が折り返すので、
            // OS ごとの改行を持ち込む必要がない（warnings も同じ）。
            return describe(kept.getCause()) + "\n\n元のファイルは次の場所に残っています。\n" + kept.kept() + "\n\n取り出して、元の名前を付け直してください。";
        }
        return failure instanceof PdfjigException pdfjig ? pdfjig.errorCode().defaultMessage() : "操作に失敗しました。";
    }

    /**
     * ファイルを 1 つ外してよいかを尋ねる。
     *
     * <p><b>取り消せない。</b>そのファイルに対して行った並べ替えや回転も一緒に消えるため、
     * <b>何ページ消えるのかを見せてから</b>確認を取る。
     *
     * @param name      外すファイルの名前
     * @param pageCount 消えるページ数
     * @return 外してよければ {@code true}
     */
    boolean confirmRemoveSource(String name, long pageCount) {
        Alert alert = new Alert(
                AlertType.CONFIRMATION, name + " の " + pageCount + " ページを取り除きます。", ButtonType.OK, ButtonType.CANCEL);
        alert.setHeaderText("このファイルに対して行った並べ替えや回転も消えます。");
        alert.initOwner(owner);
        alert.getDialogPane().setId("remove-source-dialog");
        alert.getDialogPane().lookupButton(ButtonType.OK).setId("remove-source-ok");
        // ★ 断る側にも id が要る（#115）。確認を出しておいて「キャンセル」でも外れるなら
        //   確認は嘘になるので、そこを自動テストで確かめられなければならない。
        alert.getDialogPane().lookupButton(ButtonType.CANCEL).setId("remove-source-cancel");
        return alert.showAndWait().filter(ButtonType.OK::equals).isPresent();
    }

    private void show(AlertType type, String message) {
        Alert alert = new Alert(type, message, ButtonType.OK);
        alert.setHeaderText(null);
        alert.initOwner(owner);
        alert.getDialogPane().setId("message-dialog");
        alert.getDialogPane().lookupButton(ButtonType.OK).setId("message-ok");
        alert.showAndWait();
    }
}
