package io.github.propagandist.pdfjig.desktop;

import io.github.propagandist.pdfjig.core.ErrorCode;
import io.github.propagandist.pdfjig.core.PdfjigException;
import io.github.propagandist.pdfjig.core.Warning;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

/**
 * 利用者に伝える。
 *
 * <p><b>出してよいものと出してはならないものの線が、ここに 1 か所ある。</b>
 * 画面に散らしておくと、<b>次に窓を足す者がその線を知らないまま例外のメッセージを出す</b>。
 *
 * <p>取り消せない操作の確認もここが持つ。<b>問う文言と、その id が同じ場所にある</b>——
 * id はテストとの契約であり（{@code .claude/rules/desktop-ui.md}「画面の id と JavaFX」）、文言だけ直して id を置き去りにする
 * 形を作らない。
 *
 * <p><b>★★ 例外が 1 つある。</b>{@link ProtectionPrompt} は<b>ここを通らない</b>——
 * あちらは<b>既定のボタンと初期フォーカスを中止側に倒す</b>という
 * <b>仕様で定められた最低条件</b>を持っており（{@code docs/SPEC.md} §4.3.1）、
 * <b>崩れると窓を出したまま経路が開く。</b>{@link #confirm} の形（既定の
 * {@code ButtonType.OK} / {@code CANCEL}）へ畳むと<b>その条件が書けない。</b>
 * <b>確認の窓を新しく足すときは、まずここを見ること</b>——
 * <b>別に作ってよいのは、条件が型で表せないときだけである。</b>
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
     * 前の書き出しが残した控えを伝える（#138）。1 つも無ければ何も出さない。
     *
     * <p><b>★★ 見つけているのに黙ると、利用者から見えるのは出力先に増えた {@code .pdfjig-*} だけになる</b>
     * ——配った {@code v0.1.2} の {@code docs/RELEASE_NOTES.md}「既知の制限」が
     * 「次の版で伝える形にする」と約束した穴である。
     *
     * <p><b>★ 消すまで毎回出る。</b>黙らせる手は置かない——置くと、唯一の控えが見えなくなる形に戻る。
     *
     * <p><b>★ ログには書かない。</b>失敗ではなく（{@code docs/SPEC.md} §10.4）、消すまで毎回出るので、
     * <b>書くと同じ行が保存のたびに積まれ、本物の失敗を世代の外へ押し出す。</b>在り処は画面が持つ。
     *
     * @param copies 見つけた控え（{@code OutputWorkspace#nextTo(Path, java.util.function.Consumer)}）
     */
    void abandonedCopies(List<Path> copies) {
        if (copies.isEmpty()) {
            return;
        }
        show(AlertType.WARNING, describeAbandoned(copies));
    }

    /**
     * 前の書き出しが残した控えを、画面に出す文言に直す。
     *
     * <p><b>見つけたものを全部並べる。</b>選んで黙る理由が無い（#138）。
     *
     * <p><b>★★ どのファイルの控えかは言えない。</b>控えの名前は必ず {@code replaced.pdf} で、
     * 作業場所は出力先の名前を持たない（{@code OutputWorkspace} の {@code REPLACED}）。
     * <b>しかもこの窓は、いまの保存が出力先を書いた後に出る</b>——「出力先と比べて」と促すと、
     * <b>別のファイルの控えなのに「出力先はある」と読ませ、唯一の控えを消させうる。</b>
     * <b>だから開いて中身で確かめるよう促す。</b>{@link #describe} の回と文言が違うのは、
     * あちらは「いま保存しようとしたファイルの元」だと分かっているからである。
     *
     * <p><b>★★ 置き換えが済んだ後に落ちた回もある。</b>そのとき元の名前には<b>保存が済んだ新しいほう</b>が
     * 既にあり、控えは 1 世代前である。<b>名前を付け直せと言い切ると、新しいほうを上書きさせる。</b>
     *
     * <p><b>★★ 誰の控えかも分からない。</b>共有フォルダなら、別の利用者の唯一の控えでありうる。
     * <b>「要らなければ消してよい」とは言わない</b>——中身を見て要らないと思っても、
     * それは自分にとって要らないだけである。<b>消してよいのは、自分のものだと分かったときだけにする。</b>
     *
     * @param copies 見つけた控え。空でないこと
     * @return 画面に出す文言と、その中で出した在り処
     */
    static Notice describeAbandoned(List<Path> copies) {
        return new Notice(
                "前の保存が途中で終わったときの、保存する前のファイルが次の場所に残っています。\n\n"
                        + copies.stream().map(Path::toString).collect(Collectors.joining("\n"))
                        + "\n\nどのファイルのものかは、開いて中身で確かめてください。"
                        + "元の名前のファイルが既にあるなら、それは保存が済んだ新しいほうかもしれません。"
                        + "上書きする前に中身を比べてください。"
                        + "\n\n自分のものだと確かめて、要るものを取り出したら、そのフォルダは消してかまいません。"
                        + "自分のものでなければ、消さずにおいてください。",
                copies);
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
        show(AlertType.ERROR, notice(failure));
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
        return notice(failure).text();
    }

    /**
     * 失敗を、画面に出す文言と、その中で出した在り処に直す。
     *
     * <p><b>★★ 文言と在り処を 1 か所で組む</b>（#137）。分けると、<b>出したのに写せない</b>、
     * あるいは<b>読んでいないパスを貼らせる</b>形が、片方だけ直した日に黙って生まれる。
     *
     * @param failure 起きた失敗。{@code null} でもよい
     * @return 画面に出すもの
     */
    static Notice notice(Throwable failure) {
        if (failure instanceof ReplacedFileKeptException kept) {
            // 改行は "\n" で足りる。OS ごとの改行を持ち込む必要がない（warnings も同じ）。
            // ★ 1 段だけ解く。再帰にすると、入れ子が起きた日に同じ段落が重なって出る——
            //   包むのは OutputWorkspace#failing の 1 か所だけなので、深さは必ず 1 である。
            String text = stockPhrase(kept.getCause())
                    + "\n\n元のファイルは次の場所に残っています。\n"
                    + kept.kept()
                    // ★★ 消し損ねた平文は、片づけの案内より先に言う（#184 の門）。利用者は「保存に失敗した」と
                    //   読み、平文は書かれていないと信じている。後に置くと、元を取り出したところで読むのをやめる。
                    + kept.plaintext().map(Messages::plaintextNotice).orElse("")
                    // ★ 片づけまで案内する。この作業場所は控えを抱えた印が残ったままで、
                    //   pdfjig からはもう消せない——言わないと、利用者の隣に残り続ける。
                    + "\n\n取り出して、元の名前を付け直してください。そのあと、このフォルダは消してかまいません。";
            return new Notice(
                    text,
                    kept.plaintext()
                            .map(plaintext -> List.of(kept.kept(), plaintext))
                            .orElseGet(() -> List.of(kept.kept())));
        }
        if (failure instanceof PlaintextLeftException left) {
            return new Notice(
                    stockPhrase(left.getCause()) + plaintextNotice(left.plaintext()), List.of(left.plaintext()));
        }
        return new Notice(stockPhrase(failure), List.of());
    }

    /**
     * 窓に出す文言と、その中で出した在り処。
     *
     * @param text      画面に出す文言
     * @param locations 文言の中に出した在り処。<b>出した順に並べる</b>——写すボタンがその順に並ぶ
     */
    record Notice(String text, List<Path> locations) {

        Notice {
            locations = List.copyOf(locations);
        }

        /**
         * 写すフォルダ。在り処の入ったフォルダを、出した順に、重ねずに並べる。
         *
         * <p><b>★★ ファイルそのものは写さない。</b>エクスプローラのアドレス欄へ貼ると<b>ファイルが開く</b>——
         * 消し損ねた平文なら、<b>消せと言ったものを開かせてロックさせる。</b>
         * <b>控えと平文は同じ作業場所にある</b>（{@code OutputWorkspace#writtenBeside}）ので、
         * その窓ではボタンが 1 つになる。
         *
         * @return 写すフォルダ
         */
        List<Path> folders() {
            return locations.stream().map(Path::getParent).distinct().toList();
        }
    }

    /** 消し損ねた平文の在り処（#184）。 */
    private static String plaintextNotice(Path plaintext) {
        return "\n\n★ パスワードで保護されていない中身のファイルが残っています。先に消してください。\n" + plaintext;
    }

    /**
     * 何が起きたのかの定型文。
     *
     * <p><b>例外そのもののメッセージは決して出さない</b>（{@link #failure}）。
     * 出してよいのは {@link ErrorCode} の定型文だけである。
     */
    private static String stockPhrase(Throwable failure) {
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
        Button ok = (Button) alert.getDialogPane().lookupButton(ButtonType.OK);
        ok.setId("remove-source-ok");
        // ★ 断る側にも id が要る（#115）。確認を出しておいて「キャンセル」でも外れるなら
        //   確認は嘘になるので、そこを自動テストで確かめられなければならない。
        Button cancel = (Button) alert.getDialogPane().lookupButton(ButtonType.CANCEL);
        cancel.setId("remove-source-cancel");
        // ★★ 既定は断る側にする（#171 の対象範囲。直した経路の隣）。素の Alert は OK が既定で、
        //   Enter を続けて押すと取り消せない操作が通る。ProtectionPrompt と同じ形である。
        preferCancel(alert, ok, cancel);
        return alert.showAndWait().filter(ButtonType.OK::equals).isPresent();
    }

    /**
     * 既定のボタンと初期フォーカスを断る側に倒す。
     *
     * <p><b>Enter を続けて押しても、取り消せない側に倒れない</b>（{@link ProtectionPrompt} と同じ形）。
     */
    private static void preferCancel(Alert alert, Button proceed, Button cancel) {
        proceed.setDefaultButton(false);
        cancel.setDefaultButton(true);
        alert.setOnShown(event -> cancel.requestFocus());
    }

    private void show(AlertType type, String message) {
        show(type, new Notice(message, List.of()));
    }

    /**
     * 窓を出す。在り処があれば、その入ったフォルダを写すボタンを本文の下に添える（#137）。
     *
     * <p><b>★★ 本文の {@code Label} は選択できない。</b>作業場所の名前は乱数であり
     * （{@code .pdfjig-6521790852410932614}）、<b>1 桁でも書き違えれば辿り着けない。</b>
     * ログにはパスを書かない（{@code docs/SPEC.md} §10.4）ので、<b>伝える機会はこの窓 1 回だけである。</b>
     *
     * <p><b>★★ ボタンバーには足さない。</b>ボタンが 2 つになると、取り消し側のボタンが無い限り
     * <b>窓の × でも Esc でも閉じなくなる</b>（{@code Dialog} の「Dialog Closing Rules」。
     * {@code AbandonedCopyUiTest} が赤にした）。OK だけのボタンバーは、その規則に掛からない。
     *
     * <p><b>★ フォルダごとに 1 つ。</b>繋いで 1 つにすると、エクスプローラのアドレス欄へ貼れるのは
     * 先頭の 1 行だけになる。
     *
     * <p><b>在り処の無い窓は変えない。</b>
     */
    private void show(AlertType type, Notice notice) {
        Alert alert = new Alert(type, notice.text(), ButtonType.OK);
        alert.setHeaderText(null);
        alert.initOwner(owner);
        // ★ 折り返した本文の高さは、窓の大きさが決まった後にしか分からない。指定しないと
        //   長い本文の末尾が切れる——控えの在り処（#124）はまさにその長い本文である。
        alert.getDialogPane().setMinHeight(Region.USE_PREF_SIZE);
        // 切れても利用者が自分で広げられるようにしておく。読めなければ在り処を伝えたことにならない。
        alert.setResizable(true);
        alert.getDialogPane().setId("message-dialog");
        Node ok = alert.getDialogPane().lookupButton(ButtonType.OK);
        ok.setId("message-ok");
        if (!notice.locations().isEmpty()) {
            alert.getDialogPane().setContent(withCopyButtons(notice, ok));
        }
        alert.showAndWait();
    }

    /**
     * 本文の下に、フォルダを 1 つずつ写すボタンを並べる。
     *
     * <p>本文は {@code DialogPane} が作るものと同じ形にする（{@code createContentLabel}）——
     * 本文を差し替えると、あちらの折り返しと幅は付いてこない。
     *
     * <p><b>★ 「コピーしました」は、最後に押したボタンにだけ出す。</b>クリップボードが持つのは
     * 最後の 1 つだけであり、<b>前に押したボタンに残すと、もう入っていないものを入っていると言う。</b>
     * <b>書けなかったら、そう言う</b>——他のプログラムがクリップボードを掴んでいると失敗する。
     *
     * <p><b>★ 写したらフォーカスを OK へ戻す。</b>押したボタンに残すと、Windows では Enter が
     * <b>閉じるのではなく、もう一度写す</b>。
     */
    private static VBox withCopyButtons(Notice notice, Node ok) {
        Label text = new Label(notice.text());
        text.setId("message-text");
        text.getStyleClass().add("content");
        text.setMaxWidth(Double.MAX_VALUE);
        text.setMaxHeight(Double.MAX_VALUE);
        text.setWrapText(true);
        text.setPrefWidth(360);
        VBox content = new VBox(8, text);
        List<Path> folders = notice.folders();
        List<Button> buttons = new ArrayList<>();
        for (int i = 0; i < folders.size(); i++) {
            Path folder = folders.get(i);
            // 並びの中で区別が付くこと（desktop-ui.md）。1 つなら番号は要らない。
            String which = folders.size() == 1 ? "" : "（" + (i + 1) + " つ目）";
            String label = "フォルダの場所をコピー" + which;
            Button copy = new Button(label);
            copy.setId("message-copy-location-" + i);
            copy.setOnAction(event -> {
                ClipboardContent clipboard = new ClipboardContent();
                clipboard.putString(folder.toString());
                boolean copied = Clipboard.getSystemClipboard().setContent(clipboard);
                buttons.forEach(other -> other.setText(String.valueOf(other.getUserData())));
                copy.setText((copied ? "コピーしました" : "コピーできませんでした。もう一度押してください") + which);
                ok.requestFocus();
            });
            copy.setUserData(label);
            buttons.add(copy);
            content.getChildren().add(copy);
        }
        return content;
    }
}
