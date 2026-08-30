package io.github.propagandist.pdfjig.desktop;

import javafx.beans.value.ObservableValue;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCombination;

/**
 * メニューとツールバーに出す 1 つの操作。
 *
 * <p>文言・ショートカット・有効条件・処理をここにまとめてある。<b>メニューとツールバーで
 * 別々に書くと、片方だけ直したときに挙動がずれる。</b>節点を作る手も自分で持つ
 * （{@link #menuItem()} / {@link #toolButton()}）——<b>id の付け方が定義の隣にある</b>ほうが、
 * 「テストが掴む名前」と「その名前が付く節点」を同時に読める。
 *
 * <p><b>★ 作りは変えない。</b>{@code MainWindow} から出したのは置き場所だけである（#57）。
 * JavaFX の {@code Action} 相当のクラス階層にはしない——型を増やすと、
 * <b>メニューとツールバーが同じ定義から作られていることが読みにくくなる</b>。
 *
 * @param id           節点に付ける識別子。{@code menu-} / {@code tool-} を冠して使う。
 *                     テストが文言ではなくこれで節点を掴めるようにするためのもので、
 *                     文言を変えてもテストが落ちないための逃げ道である
 * @param menuText     メニューに出す文言
 * @param toolText     ツールバーに出す文言。{@code null} ならツールバーには出さない
 * @param icon         ツールバーのアイコン（{@link ToolIcons} の SVG パス）
 * @param accelerator  ショートカット。{@code null} なら割り当てない
 * @param handler      実行する処理
 * @param disabled     無効にする条件
 */
record Action(
        String id,
        String menuText,
        String toolText,
        String icon,
        KeyCombination accelerator,
        Runnable handler,
        ObservableValue<Boolean> disabled) {

    /** メニュー項目にする。 */
    MenuItem menuItem() {
        MenuItem item = new MenuItem(menuText);
        item.setId("menu-" + id);
        item.setOnAction(event -> handler.run());
        if (accelerator != null) {
            item.setAccelerator(accelerator);
        }
        if (disabled != null) {
            item.disableProperty().bind(disabled);
        }
        return item;
    }

    /** ツールバーのボタンにする。 */
    Button toolButton() {
        Button button = new Button(toolText, ToolIcons.of(icon));
        button.setId("tool-" + id);
        button.getStyleClass().add("tool-button");
        button.setContentDisplay(ContentDisplay.TOP);
        // Windows の UI Automation から見えるのはこの名前だけで、setId は届かない
        // （JavaFX は AutomationId に内部の連番を返す）。Labeled の既定でも同じ値になるが、
        // 明示しておかないと「文言を変えると外側の起動確認が壊れる」ことが読めない。
        button.setAccessibleText(toolText);
        // Tab の巡回はサムネイル一覧に集める。操作の対象はページであって、ボタンではない。
        button.setFocusTraversable(false);
        button.setOnAction(event -> handler.run());
        button.setTooltip(new Tooltip(tooltipText()));
        if (disabled != null) {
            button.disableProperty().bind(disabled);
        }
        return button;
    }

    /** ツールチップにはメニューと同じ文言を出す。短縮した表示名だけでは意味が伝わらないため。 */
    private String tooltipText() {
        return accelerator == null ? menuText : menuText + "（" + accelerator.getDisplayText() + "）";
    }
}
