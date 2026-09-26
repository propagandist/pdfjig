package io.github.propagandist.pdfjig.desktop;

import io.github.propagandist.pdfjig.core.EncryptionAlgorithm;
import io.github.propagandist.pdfjig.core.Password;
import io.github.propagandist.pdfjig.core.Protection;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javafx.beans.property.StringProperty;
import javafx.geometry.Insets;
import javafx.scene.control.ButtonBar.ButtonData;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.ScrollPane.ScrollBarPolicy;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.StringConverter;

/**
 * パスワードと権限フラグを尋ねるダイアログ。
 *
 * <p><b>★★ 権限フラグは暗号学的に強制されない</b>（{@code docs/SPEC.md} §6.1）。
 * <b>ユーザーパスワードが空なら出力は誰でも開ける</b>ので、権限フラグは
 * <b>閲覧ソフトが自主的に従っているだけの申告制である。</b>
 * <b>利用者はほぼ確実に「コピー禁止にしたから安全」と誤解する</b>ので、
 * <b>オーナーパスワードだけを設定しようとしたときに明示する</b>（同 §6.1 の要件）。
 *
 * <p><b>★ 出す条件は「ユーザーパスワードが空であること」だけにしてある——要件より広い。</b>
 * <b>空のまま開いた時点で出ているので、権限フラグと同時に目に入る。</b>
 * <b>オーナー欄に打ち始めてから出す形にすると、フラグを触っている間は見えない</b>
 * ——<b>誤解が生まれるのはまさにそこである。</b>
 *
 * <p><b>★★ 逆向きの穴も告げる。</b><b>オーナーパスワードが空だと、PDFBox は
 * ユーザーパスワードをそのままオーナーパスワードとして書く</b>
 * （{@code StandardSecurityHandler#prepareDocumentForEncryption}。2026-09-15 実測）——
 * <b>文書を開けた人がオーナー権限を持つので、外した権限フラグは
 * 規約どおりの閲覧ソフトでも無視される。</b>
 * <b>これも禁じず、何が起きるかを書く</b>（同じ理由）。
 *
 * <p><b>★★ 禁じはしない。</b>既存の文書に合わせる用途を塞ぐことになる——
 * <b>禁止ではなく、何が起きるかを書く</b>（#30 の却下した案）。
 *
 * <p><b>2 段構成である</b>（同 §6.2）。<b>3 つに束ねたプリセットを先に見せ、
 * 8 フラグの詳細は畳んでおく</b>——<b>8 つ並べると、どれが実効性を持つのかが
 * かえって分からなくなる</b>（#30）。
 *
 * <p><b>★ 確認用の再入力欄を置く</b>（同 §6）。<b>打ち間違えると、開けない文書ができあがる。</b>
 *
 * <p><b>既知の限界:</b> JavaFX の {@link PasswordField} は入力を {@code String} で保持しており、
 * pdfjig 側からこれを消す手段はない（{@link PasswordPrompt} と同じ）。
 */
final class EncryptionPrompt {

    private EncryptionPrompt() {}

    /**
     * 保護の指定を尋ねる。
     *
     * <p><b>返された {@link Protection} が抱える鍵は、受け取った側が閉じる。</b>
     * <b>ここが作って渡すまでの持ち主であり、渡した後は受け取った側である</b>
     * ——INV-5 の「作った場所で try-with-resources に載せる」は<b>枠を越えて生きる仕事へ渡すとき
     * 枠ごと渡す</b>とも定めている（{@code .claude/rules/modules-and-invariants.md}）。
     * <b>書き出しへ渡すなら枠ごと渡すこと</b>（{@code BackgroundTasks#run(List, …)}）。
     *
     * @param owner 親ウィンドウ
     * @return 入力された指定。取り消された場合は空
     */
    static Optional<Protection> ask(Stage owner) {
        PasswordField userPassword = passwordField("encryption-user-password");
        PasswordField userConfirm = passwordField("encryption-user-password-confirm");
        PasswordField ownerPassword = passwordField("encryption-owner-password");
        PasswordField ownerConfirm = passwordField("encryption-owner-password-confirm");

        // 打たれている中身。束縛はこちらで組む——欄そのものは焦点と写し取りに使う。
        StringProperty typedUser = userPassword.textProperty();
        StringProperty typedUserAgain = userConfirm.textProperty();
        StringProperty typedOwner = ownerPassword.textProperty();
        StringProperty typedOwnerAgain = ownerConfirm.textProperty();

        Flags flags = new Flags();

        Label ownerOnly =
                new Label("この設定は閲覧ソフトの自主的な遵守に依存します。" + System.lineSeparator() + "確実に保護するにはユーザーパスワードを設定してください。");
        ownerOnly.setId("encryption-owner-only-warning");
        ownerOnly.setWrapText(true);
        // ★★ ユーザーパスワードが空のときだけ出す。空でなければ開くのに鍵が要るので、
        //   権限フラグの申告制という話は当たらない（SPEC.md §6.1 の表）。
        ownerOnly.visibleProperty().bind(typedUser.isEmpty());
        ownerOnly.managedProperty().bind(ownerOnly.visibleProperty());

        Label noOwner =
                new Label("権限の鍵が、文書を開く鍵と同じになります。" + System.lineSeparator() + "文書を開けた人は権限も変更できるため、下の権限の設定は効きません。");
        noOwner.setId("encryption-no-owner-warning");
        noOwner.setWrapText(true);
        // ★★ PDFBox は空のオーナーパスワードをユーザーパスワードで埋める
        //   （StandardSecurityHandler#prepareDocumentForEncryption。3.0.8 の実装を読んで確かめた。
        //   2026-09-15 実測）——開けた人がオーナー権限を持つので、外した権限フラグは
        //   規約どおりの閲覧ソフトでも無視される。
        //   ★★ 同じ文字列を両方に打っても同じことが起きる。読む側は isOwnerPassword を
        //   先に試すので（同 StandardSecurityHandler#prepareDocumentForDecryption）、渡した 1 つの鍵で
        //   オーナー扱いになる——空のときだけを見ると、こちらが素通りする（#30 の門の 2 段目）。
        //   ★ 上の注意と同時には当たらない（あちらはユーザー側が空のとき）。
        noOwner.visibleProperty()
                .bind(typedUser.isNotEmpty().and(typedOwner.isEmpty().or(typedOwner.isEqualTo(typedUser))));
        noOwner.managedProperty().bind(noOwner.visibleProperty());

        ChoiceBox<EncryptionAlgorithm> algorithm = new ChoiceBox<>();
        algorithm.setId("encryption-algorithm");
        // ★ 定数名をそのまま出さない。日本語の窓に AES_256 と並ぶし、
        //   どれが既定でどれが互換性のためのものかが読んで分からない（SPEC.md §6.2）。
        algorithm.setConverter(new StringConverter<>() {
            @Override
            public String toString(EncryptionAlgorithm value) {
                return value == null ? "" : labelFor(value);
            }

            @Override
            public EncryptionAlgorithm fromString(String text) {
                throw new UnsupportedOperationException("選ぶだけで、打ち込む形は無い。");
            }
        });
        // ★ 並びは手で写さない。書ける方式が増えた日に、ここだけが黙って古いまま残る。
        algorithm.getItems().setAll(EncryptionAlgorithm.writable());
        // ★ 既定は名前で指定する（SPEC.md §6.2）。並びの先頭ではない
        //   ——あちらは弱いほうから並んでおり、selectFirst だと RC4-40 になる（2026-09-16 実測）。
        //   RC4 系と AES-128 は互換性が要るときだけの選択肢である。
        algorithm.getSelectionModel().select(EncryptionAlgorithm.AES_256);

        // ★ 方式の選択は「8 つの権限」ではない。ここで組む——Flags へ渡すと、
        //   詳細に何が入るのかが 2 つのファイルに分かれる。
        VBox detailPane = new VBox(6, flags.rows(), new Label("暗号方式（互換性が要るときだけ変える）"), algorithm);
        detailPane.setPadding(new Insets(8));

        TitledPane details = new TitledPane("詳細（8 つの権限）", detailPane);
        details.setId("encryption-details");
        details.setExpanded(false);
        // ★ 畳み開きは即座に終わらせる。伸びきる前に窓を測り直すと、足りない高さで止まる。
        details.setAnimated(false);

        VBox content = new VBox(
                10,
                new Label("書き出すファイルにパスワードを設定します。"),
                passwordGrid(userPassword, userConfirm, ownerPassword, ownerConfirm),
                ownerOnly,
                noOwner,
                flags.presetBox(),
                details);
        content.setPadding(new Insets(12));

        // ★★ 伸びるぶんはここが飲む。詳細を開くと中身は伸びるが窓は伸びないので、
        //   飲む場所が無いと下の「保護して保存」が窓の外へ押し出される
        //   ——2026-09-15 / 16 に CI（windows）で 3 度実測した：#encryption-apply が
        //   「1 nodes, but no nodes were visible」で掴めない（TestFX が見るのは
        //   節点が scene の矩形と重なるかである。NodeQueryUtils#isNodeWithinSceneBounds）。
        //   ★ 縦だけ流す。横に流すと、折り返す注意文が読めなくなる。
        ScrollPane scroller = new ScrollPane(content);
        scroller.setFitToWidth(true);
        scroller.setHbarPolicy(ScrollBarPolicy.NEVER);
        // 枠を消す。中身は窓そのものの続きであって、囲まれた別の面ではない。
        // ★★ Modena の組み込みの edge-to-edge を使う。-fx-background を自分で書き換えない——
        //   Modena は文字の色を -fx-background の明るさから決める（ladder）ので、透明を当てると
        //   白い文字になり、注意文も権限の見出しも読めなくなった（2026-09-26、v0.0.6 の実機確認で見つかった）。
        scroller.getStyleClass().add("edge-to-edge");

        // ★ 「…」を付ける。この後に保存先の窓が出る——押した時点ではまだ書かない（#172 の門）。
        ButtonType apply = new ButtonType("保護して保存…", ButtonData.OK_DONE);

        Dialog<Protection> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("パスワードで保護");
        dialog.getDialogPane().setId("encryption-dialog");
        dialog.getDialogPane().setContent(scroller);
        // ★★ ここに Region.USE_PREF_SIZE を置かない（#124 の Messages#show とは事情が違う）。
        //   あれは「いちばん低くてもこの高さは要る」という指定なので、中身が伸びると
        //   DialogPane が scene より高くなり、ボタン列が窓の下へはみ出す——これが上の
        //   3 度の赤の正体だった（2026-09-16 実測。35037324528）。
        //   ★ あちらに要るのは中身の高さが動かないからである。こちらは詳細の開閉で動く。
        //   出したときの大きさは Dialog が中身に合わせて決めるので、畳んだ状態は
        //   これまでどおり過不足なく収まる。
        dialog.setResizable(true);
        dialog.getDialogPane().getButtonTypes().addAll(apply, ButtonType.CANCEL);
        dialog.getDialogPane().lookupButton(apply).setId("encryption-apply");
        dialog.getDialogPane().lookupButton(ButtonType.CANCEL).setId("encryption-cancel");
        dialog.setOnShown(event -> userPassword.requestFocus());

        // ★★ 押せない条件は 2 つ。① 確認が一致しない ② どちらの鍵も空である。
        //   ★ ② を通すと「保護した」と思わせながら誰でも開ける文書ができる（優先順位 2）。
        //   ★ 依存を数え上げる形（Bindings#createBooleanBinding）にしない——欄を足して
        //   引数を書き忘れると、ボタンが黙って測り直されなくなる（コンパイルは通る）。
        dialog.getDialogPane()
                .lookupButton(apply)
                .disableProperty()
                .bind(typedUser
                        .isNotEqualTo(typedUserAgain)
                        .or(typedOwner.isNotEqualTo(typedOwnerAgain))
                        .or(typedUser.isEmpty().and(typedOwner.isEmpty())));

        dialog.setResultConverter(button -> {
            try {
                return button != apply ? null : protectionFrom(userPassword, ownerPassword, flags, algorithm);
            } finally {
                // ★★ どの道を通っても欄を消す。投げて出る道もここを通る。
                clear(userPassword, userConfirm, ownerPassword, ownerConfirm);
            }
        });

        // ★★ 作った {@link Protection} が戻らない道を塞ぐ。結果は押した時点で Dialog に
        //   載るが、showAndWait が戻るまでの間に投げると、呼ぶ側は受け取らないままになる
        //   ——持ち主の決まっていない平文の鍵が 2 本残る（INV-5。#30 の門の 2 段目）。
        try {
            return dialog.showAndWait();
        } catch (RuntimeException | Error failed) {
            Protection orphan = dialog.getResult();
            if (orphan != null) {
                orphan.keys().forEach(Password::close);
            }
            throw failed;
        }
    }

    /**
     * 打たれたものから保護を組む。
     *
     * <p><b>★ 写し取りは {@link Password} の中で行う。</b>素の {@code char[]} がここに出ない（INV-5）。
     *
     * <p><b>★★ 渡しきる前に投げたら、そこまでに写し取った鍵を閉じる。</b>
     * <b>{@code new Protection(...)} の引数の途中で投げる道が実際にある</b>——
     * {@link Protection} は書けない方式を値の段で拒む（#199）。
     * <b>そこを素通しにすると、持ち主の決まっていない平文の配列が残る</b>
     * （{@code MainWindow#askKeys} と同じ規律。#135 / #144 / #145）。
     * <b>いまは選ばせる方式に書けないものが無いので届かないが、届かないことに寄りかからない</b>
     * ——<b>選択肢を 1 つ足した日に、静かに開く。</b>
     */
    private static Protection protectionFrom(
            PasswordField userPassword,
            PasswordField ownerPassword,
            Flags flags,
            ChoiceBox<EncryptionAlgorithm> algorithm) {
        List<Password> taken = new ArrayList<>(2);
        try {
            // ★ 名前を付けて渡す。並びから引く形にすると、ユーザーとオーナーを
            //   入れ替えてもコンパイルは通る——同じ型だからである（passwordGrid と同じ理由）。
            Password user = Password.copyOf(userPassword.getCharacters());
            taken.add(user);
            Password owner = Password.copyOf(ownerPassword.getCharacters());
            taken.add(owner);
            Protection protection = new Protection(user, owner, flags.permissions(), algorithm.getValue());
            // 渡しきった。ここからは呼ぶ側が持ち主である。
            taken.clear();
            return protection;
        } finally {
            taken.forEach(Password::close);
        }
    }

    /**
     * 方式の見せ方。
     *
     * <p><b>★★ RC4 には弱いと書く。</b>40 ビットは<b>総当たりで破れる</b>のに
     * 名前だけではそれが読み取れず、<b>「パスワードを掛けたから安全」と誤解される</b>
     * （{@code CLAUDE.md} 優先順位 2。#30 の門の 2 段目）。<b>選べなくはしない</b>——
     * <b>古い閲覧ソフトに合わせる用途を塞ぐことになるので、何が起きるかを書くだけである。</b>
     */
    private static String labelFor(EncryptionAlgorithm algorithm) {
        return switch (algorithm) {
            case AES_256 -> "AES-256（推奨）";
            case AES_128 -> "AES-128（古い閲覧ソフト向け）";
            case RC4_128 -> "RC4-128（古い閲覧ソフト向け。強度は低い）";
            case RC4_40 -> "RC4-40（古い閲覧ソフト向け。短時間で破られる）";
            // ★ 読んだ結果を表す値であり、選ばせる並びには入れない（Protection が拒む。#199）。
            case NONE, UNKNOWN -> algorithm.name();
        };
    }

    private static PasswordField passwordField(String id) {
        PasswordField field = new PasswordField();
        field.setId(id);
        return field;
    }

    /**
     * 4 つの欄を並べる。
     *
     * <p><b>★ 欄と見出しを 1 行ずつ組にする。</b>並びだけを渡す形にすると、
     * <b>見出しと欄がずれても何も落ちない</b>——型が同じなので、入れ替えてもコンパイルは通る。
     */
    private static GridPane passwordGrid(
            PasswordField userPassword,
            PasswordField userConfirm,
            PasswordField ownerPassword,
            PasswordField ownerConfirm) {
        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(6);
        row(grid, 0, "ユーザーパスワード（開くとき）", userPassword);
        row(grid, 1, "　同じものをもう一度", userConfirm);
        row(grid, 2, "オーナーパスワード（権限を変えるとき）", ownerPassword);
        row(grid, 3, "　同じものをもう一度", ownerConfirm);
        return grid;
    }

    private static void row(GridPane grid, int index, String label, PasswordField field) {
        grid.addRow(index, new Label(label), field);
    }

    private static void clear(PasswordField... fields) {
        for (PasswordField field : fields) {
            field.clear();
        }
    }
}
