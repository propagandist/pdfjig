package io.github.propagandist.pdfjig.desktop;

import io.github.propagandist.pdfjig.core.AccessPermissions;
import io.github.propagandist.pdfjig.core.EncryptionAlgorithm;
import java.util.List;
import javafx.geometry.Insets;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

/**
 * 8 つの権限フラグと、それを束ねた 3 つのプリセット。
 *
 * <p><b>★★ 正本は 8 つのほうである。</b>プリセットは<b>束ねた見え方</b>であり、
 * <b>押すと束の全部が動き、束の中身が変われば押された状態も変わる。</b>
 * <b>逆にすると、詳細を開いて 1 つだけ外した利用者に、プリセットが嘘をつく。</b>
 *
 * <p><b>2 段構成にする理由</b>（{@code docs/SPEC.md} §6.2、#30）——
 * <b>8 つ並べると、どれが実効性を持つのかがかえって分からなくなる。</b>
 *
 * <p><b>★★ 支援技術のための複製は束に入れない。</b>
 * <b>塞ぐと視覚障害者が読めなくなる</b>（同 §6.2）ので、<b>既定で許可し、
 * 詳細を開いた人だけが触れる。</b>「テキスト抽出を許可」を外しても<b>これは残る</b>
 * ——{@code AccessPermissions#none()} が同じ形をしている。
 */
final class Flags {

    private final CheckBox print = flag("encryption-flag-print", "印刷する");

    private final CheckBox printHighQuality = flag("encryption-flag-print-high-quality", "高品質で印刷する");

    private final CheckBox extractContent = flag("encryption-flag-extract-content", "テキストや図を複製する");

    private final CheckBox extractForAccessibility = flag("encryption-flag-extract-accessibility", "支援技術のために複製する");

    private final CheckBox modify = flag("encryption-flag-modify", "内容を変更する");

    private final CheckBox modifyAnnotations = flag("encryption-flag-modify-annotations", "注釈を変更する");

    private final CheckBox fillForms = flag("encryption-flag-fill-forms", "フォームに入力する");

    private final CheckBox assembleDocument = flag("encryption-flag-assemble", "ページを挿入・削除・回転する");

    private final CheckBox allowPrint = preset("encryption-allow-print", "印刷を許可", List.of(print, printHighQuality));

    private final CheckBox allowExtract = preset("encryption-allow-extract", "テキスト抽出を許可", List.of(extractContent));

    private final CheckBox allowModify =
            preset("encryption-allow-modify", "編集を許可", List.of(modify, modifyAnnotations, fillForms, assembleDocument));

    /**
     * 束と中身を結び直している最中か。
     *
     * <p><b>★ 無いと往復する。</b>束を押す → 中身が動く → 中身の変化が束を押し直す、
     * という循環になる。
     */
    private boolean syncing;

    /** プリセットの並び。畳んだ状態で見えるのはここだけである。 */
    VBox presetBox() {
        VBox box = new VBox(6, new Label("書き出したファイルで許可すること"), allowPrint, allowExtract, allowModify);
        return box;
    }

    /** 詳細の中身。8 つのフラグと、互換性のための方式の選択である。 */
    VBox detailPane(ChoiceBox<EncryptionAlgorithm> algorithm) {
        VBox box = new VBox(
                6,
                print,
                printHighQuality,
                extractContent,
                extractForAccessibility,
                modify,
                modifyAnnotations,
                fillForms,
                assembleDocument,
                new Label("暗号方式（互換性が要るときだけ変える）"),
                algorithm);
        box.setPadding(new Insets(8));
        return box;
    }

    /** いま選ばれている権限。 */
    AccessPermissions permissions() {
        return new AccessPermissions(
                print.isSelected(),
                modify.isSelected(),
                extractContent.isSelected(),
                modifyAnnotations.isSelected(),
                fillForms.isSelected(),
                assembleDocument.isSelected(),
                extractForAccessibility.isSelected(),
                printHighQuality.isSelected());
    }

    /**
     * 8 つのほうの 1 つ。
     *
     * <p><b>★ 既定はすべて許可である。</b>「保護する」は<b>パスワードを掛けること</b>であって、
     * <b>権限を塞ぐことではない</b>——塞ぐかどうかは利用者が決める。
     */
    private static CheckBox flag(String id, String text) {
        CheckBox box = new CheckBox(text);
        box.setId(id);
        box.setSelected(true);
        return box;
    }

    /** 束。押すと中身が全部動き、中身が変われば押された状態も変わる。 */
    private CheckBox preset(String id, String text, List<CheckBox> members) {
        CheckBox box = new CheckBox(text);
        box.setId(id);
        box.setSelected(true);
        box.selectedProperty().addListener((property, was, now) -> {
            if (syncing) {
                return;
            }
            syncing = true;
            members.forEach(member -> member.setSelected(now));
            syncing = false;
        });
        members.forEach(member -> member.selectedProperty().addListener((property, was, now) -> {
            if (syncing) {
                return;
            }
            syncing = true;
            box.setSelected(members.stream().allMatch(CheckBox::isSelected));
            syncing = false;
        }));
        return box;
    }
}
