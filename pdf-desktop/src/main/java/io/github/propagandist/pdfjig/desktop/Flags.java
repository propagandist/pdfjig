package io.github.propagandist.pdfjig.desktop;

import io.github.propagandist.pdfjig.core.AccessPermissions;
import java.util.List;
import javafx.scene.control.CheckBox;
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

    private final CheckBox allowPrint;

    private final CheckBox allowExtract;

    private final CheckBox allowModify;

    /**
     * 束はここで組む。
     *
     * <p><b>★ 初期化子の並びに依存させない。</b>束の宣言を 8 つの上へ移すだけで
     * <b>{@code List.of} が {@code null} を拒んで投げ</b>、メニューを押しても窓が出ない
     * ——<b>コンパイルは通る。</b>
     */
    Flags() {
        // ★★ 高品質は印刷の下にある（permissions の註）。印刷を外したら外して押せなくし、
        //   戻したら戻す。
        //   ★ 片道にしない。外すだけにすると、印刷を戻した人の手元で高品質だけが
        //   落ちたままになる——本人が外していない権限が黙って残る（#30 の門の 2 段目）。
        printHighQuality.disableProperty().bind(print.selectedProperty().not());
        print.selectedProperty().addListener((property, was, now) -> {
            if (!now) {
                printHighQuality.setSelected(false);
            }
        });
        allowPrint = preset("encryption-allow-print", "印刷を許可", List.of(print, printHighQuality));
        allowExtract = preset("encryption-allow-extract", "テキスト抽出を許可", List.of(extractContent));
        allowModify = preset(
                "encryption-allow-modify", "編集を許可", List.of(modify, modifyAnnotations, fillForms, assembleDocument));
    }

    /** プリセットの並び。畳んだ状態で見えるのはここだけである。 */
    VBox presetBox() {
        return new VBox(6, new Label("書き出したファイルで許可すること"), allowPrint, allowExtract, allowModify);
    }

    /** 8 つのフラグの並び。<b>持つのはフラグだけである</b>——詳細に何を並べるかは呼ぶ側が決める。 */
    VBox rows() {
        return new VBox(
                6,
                print,
                printHighQuality,
                extractContent,
                extractForAccessibility,
                modify,
                modifyAnnotations,
                fillForms,
                assembleDocument);
    }

    /**
     * いま選ばれている権限。
     *
     * <p><b>★★ 高品質の印刷は、印刷を許しているときしか意味を持たない</b>
     * （PDF 32000-1 の表 22。ビット 12 はビット 3 を修飾する）。
     * <b>印刷を外したまま高品質を残すと、画面は「高品質で印刷できる」と言いながら
     * 出力は印刷を一切許さない</b>（{@code CLAUDE.md} 優先順位 2。#30 の門の 2 段目）。
     *
     * <p><b>★ 画面で縛る。</b>印刷を外したら<b>高品質も押せなくする</b>——
     * <b>ここで黙って落とす形にすると、チェックが入ったまま効かないという同じ誤解が残る。</b>
     */
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

    /**
     * 束。押すと中身が全部動き、中身が変われば押された状態も変わる。
     *
     * <p><b>★★ 押す側は {@code setOnAction} で受ける。</b>{@code selectedProperty} で受けると
     * <b>中身から押し直したぶんにも反応して往復し</b>、それを止めるための旗が要る——
     * <b>{@code setSelected} は {@code ActionEvent} を出さないので、戻りの道が初めから無い。</b>
     */
    private static CheckBox preset(String id, String text, List<CheckBox> members) {
        CheckBox box = new CheckBox(text);
        box.setId(id);
        // ★ 初期値も受け口も同じ式を呼ぶ。書き分けると、フラグの既定を変えた日に
        //   束だけが古い値を持って残る——受け口は変化でしか発火しないので、直る契機が無い。
        Runnable follow = () -> box.setSelected(members.stream().allMatch(CheckBox::isSelected));
        follow.run();
        box.setOnAction(event -> {
            // ★★ 押された値を先に控える。box.isSelected() をそのまま読むと、1 つ目を動かした
            //   時点で下の受け口が「全部は揃っていない」と見て box を押し戻すので、
            //   2 つ目から先が逆の値になる——束を付け直すと 1 つしか戻らない（#30 の門の 2 段目）。
            boolean selected = box.isSelected();
            members.forEach(member -> member.setSelected(selected));
        });
        members.forEach(member -> member.selectedProperty().addListener(ignored -> follow.run()));
        return box;
    }
}
