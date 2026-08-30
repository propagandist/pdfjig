package io.github.propagandist.pdfjig.desktop;

import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.Separator;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.ToolBar;

/**
 * 画面が持つ操作一式。<b>メニューとツールバーの双方がここから作られる。</b>
 *
 * <p>並べ方だけを持ち、<b>それぞれの操作が何をするかは持たない</b>——
 * 中身は {@link Action} が、処理の実体は {@code MainWindow} が持つ。
 * ここを読めば「どの操作がどのメニューに入り、どれがツールバーに出るか」だけが分かる。
 *
 * <p><b>★ ツールバーに出るのは一部である。</b>メニューは全部を持ったまま残す。
 *
 * <p><b>★ 成分の説明は書かない。</b>文言も処理も {@code MainWindow#buildActions()} が持っており、
 * ここへ写せば必ず片方が腐る（実際、最初に書いたとき「バージョン情報」が
 * {@code AppInfo.NAME + " について"} とずれた）。<b>成分の名前が指しているものは、
 * あちらを 1 度読めば分かる。</b>
 */
record Actions(
        Action open,
        Action save,
        Action close,
        Action quit,
        Action delete,
        Action rotateRight,
        Action rotateLeft,
        Action keepRange,
        Action toggleBreak,
        Action breakEveryN,
        Action clearBreaks,
        Action reset,
        Action add,
        Action split,
        Action splitPages,
        Action about) {

    /** メニューバーを組む。ここに全部の操作が出る。 */
    MenuBar menuBar() {
        return new MenuBar(
                new Menu("ファイル", null, open.menuItem(), save.menuItem(), close.menuItem(), quit.menuItem()),
                new Menu(
                        "ページ",
                        null,
                        delete.menuItem(),
                        rotateRight.menuItem(),
                        rotateLeft.menuItem(),
                        keepRange.menuItem(),
                        new SeparatorMenuItem(),
                        toggleBreak.menuItem(),
                        breakEveryN.menuItem(),
                        clearBreaks.menuItem(),
                        new SeparatorMenuItem(),
                        reset.menuItem()),
                new Menu("ツール", null, add.menuItem(), split.menuItem(), splitPages.menuItem()),
                new Menu("ヘルプ", null, about.menuItem()));
    }

    /**
     * ツールバーを組む。
     *
     * <p>置くのは繰り返し使う操作だけで、メニューは全部を持ったまま残す。
     * 区切りは「ファイル」「ページ」「文書」の 3 つのまとまりに対応させてある。
     */
    ToolBar toolBar() {
        return new ToolBar(
                open.toolButton(),
                save.toolButton(),
                new Separator(),
                delete.toolButton(),
                rotateLeft.toolButton(),
                rotateRight.toolButton(),
                new Separator(),
                keepRange.toolButton(),
                toggleBreak.toolButton(),
                reset.toolButton(),
                new Separator(),
                add.toolButton(),
                split.toolButton(),
                splitPages.toolButton());
    }
}
