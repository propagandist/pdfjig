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
 * @param open        開く
 * @param save        名前を付けて保存
 * @param close       閉じる
 * @param quit        終了
 * @param delete      選択したページを削除
 * @param rotateRight 右に 90 度回転
 * @param rotateLeft  左に 90 度回転
 * @param keepRange   範囲を指定して残す
 * @param toggleBreak ここで区切る / 区切りを外す
 * @param breakEveryN N ページごとに区切る
 * @param clearBreaks 区切りをすべて外す
 * @param reset       編集を元に戻す
 * @param add         PDF を追加
 * @param split       この文書を分割
 * @param splitPages  1 ページずつに分割
 * @param about       バージョン情報
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
