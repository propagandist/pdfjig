package io.github.propagandist.pdfjig.desktop;

import io.github.propagandist.pdfjig.core.PageSelection;

/**
 * 編集中の並びに置かれた 1 ページ。
 *
 * <p>出力の指定（{@link PageSelection}）に、分割のための区切りを添えたもの。
 * 区切りは pdf-core に渡す値ではなく画面上の編集状態であり、
 * 保存では無視され、分割のときだけ意味を持つ。
 *
 * <p><b>区切りは位置ではなくページに持たせる。</b> 位置に持たせると、ページを動かしたときに
 * 「A と B の間で切る」が「A と C の間」に化ける。ページに付けておけば、
 * ページを動かせば区切りも一緒に動く。
 *
 * @param selection     出力に含める 1 ページの指定
 * @param startsNewFile このページから新しいファイルが始まるか
 */
record PageEntry(PageSelection selection, boolean startsNewFile) {

    PageEntry {
        if (selection == null) {
            throw new IllegalArgumentException("selection は null にできません。");
        }
    }

    /** 区切りを持たないページとして作る。 */
    static PageEntry of(PageSelection selection) {
        return new PageEntry(selection, false);
    }

    /** 区切りの有無を変えたものを返す。 */
    PageEntry withBreak(boolean startsNewFile) {
        return new PageEntry(selection, startsNewFile);
    }

    /** 出力の指定を変えたものを返す。区切りは保つ。 */
    PageEntry withSelection(PageSelection replacement) {
        return new PageEntry(replacement, startsNewFile);
    }
}
