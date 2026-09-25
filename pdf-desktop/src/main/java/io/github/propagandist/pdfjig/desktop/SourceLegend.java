package io.github.propagandist.pdfjig.desktop;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;
import javafx.beans.binding.BooleanExpression;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.value.ObservableBooleanValue;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;

/**
 * いま含んでいるファイルの一覧。
 *
 * <p>複数のファイルを混ぜているときだけ出す。1 つしか開いていないなら表題で足りる。
 *
 * <p>色はサムネイルに付く帯と同じもので、どのページがどのファイルのものかを結ぶ手がかりになる。
 * 枚数は<b>いま並んでいる数</b>を出す。元のページ数ではなく、消したぶんが減る。
 * 「B から 3 枚だけ残した」といった今の状態がそのまま読める。
 */
final class SourceLegend {

    /** 色の見本の一辺。 */
    private static final double CHIP_SIZE = 10;

    /** ファイル名に許す幅。長い名前は中ほどを省く。 */
    private static final double NAME_WIDTH = 260;

    /**
     * ファイルが多いと 1 行に収まらない。切り捨てず折り返す。
     *
     * <p>横スクロールにすると、隠れているファイルがあること自体に気づけない。
     */
    private final FlowPane root = new FlowPane(16, 4);

    /** 「×」で呼ぶ処理。画面側が差す。 */
    private IntConsumer onRemove = sourceIndex -> {};

    /**
     * 「×」を押せなくする条件（#114）。
     *
     * <p><b>★★ 受け取らずには作れない。</b>差し忘れを既定値で埋めると<b>「押せる」側に倒れ</b>、
     * <b>それはこの門が作られた原因そのもの</b>——入口が条件を見ていないこと——を
     * <b>1 段上で作り直すことになる。</b>
     */
    private final ObservableBooleanValue removeBlocked;

    /**
     * いま出ている「×」。
     *
     * <p><b>★ 束ねずに書き換える。</b>この一覧は並びが変わるたびに作り直されるので、
     * <b>節点ごとに束ねると、捨てた節点のぶんの聞き手が条件の側に積まれていく</b>
     * （消えるのは次に条件が動いたときで、それはまさに保存を押した瞬間である）。
     */
    private final List<Button> removeButtons = new ArrayList<>();

    /**
     * 「×」と同じ操作をメニューから届かせる（#127）。
     *
     * <p><b>★ 「×」はフォーカスを受け取らない</b>（{@link #chip}）。<b>ほかの操作はどれもメニューにあり</b>、
     * Alt／F10 で辿れる。<b>ここだけがマウス専用だった。</b>巡回に戻すとファイルの数だけ Tab が増えるので、
     * <b>メニューのほうに揃える。</b>
     *
     * <p><b>★ 項目は「×」と同じ場所で作り直す。</b>番号も処理も押せない条件も「×」と同じものを使う——
     * 別に組むと、<b>片方だけ直した日に、同じ操作が経路によって違う文書に当たる。</b>
     */
    private final Menu removeMenu = new Menu("ファイルを外す");

    /** いま出ているメニューの項目。「×」と同じ理由で、束ねずに書き換える。 */
    private final List<MenuItem> removeItems = new ArrayList<>();

    /** 外せるファイルが並んでいるか。一覧を出しているのと同じ条件である。 */
    private final BooleanProperty shown = new SimpleBooleanProperty(false);

    /**
     * @param removeBlocked ファイルを外せない間 {@code true} になるもの。この一覧だけでは
     *                      決まらない（走っている仕事があるかを持っているのは画面の側である）
     */
    SourceLegend(ObservableBooleanValue removeBlocked) {
        this.removeBlocked = removeBlocked;
        removeBlocked.addListener((observable, was, blocked) -> {
            removeButtons.forEach(button -> button.setDisable(blocked));
            removeItems.forEach(item -> item.setDisable(blocked));
        });
        removeMenu.setId("menu-remove-source");
        // ★ サブメニューそのものも押させない。子だけを無効にすると、走っている間「ツール」の中で
        //   ここだけが押せる見た目のまま残る——押しても何も起きないのと、押せないのは違う（#114）。
        //   束ねてよいのはこれが 1 つしか無いからである（「×」と項目は作り直すので束ねない）。
        removeMenu.disableProperty().bind(shown.not().or(BooleanExpression.booleanExpression(removeBlocked)));
        root.getStyleClass().add("source-legend");
        root.setAlignment(Pos.CENTER_LEFT);
        hide();
    }

    /** 画面に置くための節点。 */
    Node node() {
        return root;
    }

    /** 「ファイルを外す」のメニュー。並べる場所は {@link Actions#menuBar()} が決める。 */
    Menu removeMenu() {
        return removeMenu;
    }

    /** ファイルを外すときに呼ぶ処理を差す。 */
    void setOnRemove(IntConsumer action) {
        this.onRemove = action;
    }

    /**
     * 表示を作り直す。
     *
     * <p><b>★ 節点ごと作り直す。</b>だから「×」の id を付け替える必要がない——
     * <b>サムネイルのタイルは行を使い回すので付け替えが要る</b>が（{@code .claude/rules/desktop-ui.md}「画面の id と JavaFX」）、
     * こちらは消えたぶんの節点ごと捨てられる。<b>使い回す形に変えるなら、そこで付け替えること。</b>
     *
     * @param session 表示中の編集セッション。{@code null} なら隠す
     */
    void update(DocumentSession session) {
        if (session == null || session.sourceCount() < 2) {
            hide();
            return;
        }

        int[] counts = countsPerSource(session);

        clear();
        for (int sourceIndex = 0; sourceIndex < session.sourceCount(); sourceIndex++) {
            String name = session.sourceName(sourceIndex);
            root.getChildren().add(chip(sourceIndex, name, counts[sourceIndex]));
            removeMenu.getItems().add(removeItem(sourceIndex, name));
        }
        shown.set(true);

        root.setVisible(true);
        root.setManaged(true);
    }

    private void hide() {
        clear();
        // 1 ファイルなら外すものが無い。一覧を出さないのと同じ条件で押させない。
        shown.set(false);
        root.setVisible(false);
        // 場所も空けない。1 ファイルのときに帯だけが残ると、何かがあると思わせる。
        root.setManaged(false);
    }

    /** 「×」とメニューの項目を捨てる。どちらも同じ並びから作るので、捨てるのも一緒にする。 */
    private void clear() {
        root.getChildren().clear();
        removeButtons.clear();
        removeMenu.getItems().clear();
        removeItems.clear();
    }

    private static int[] countsPerSource(DocumentSession session) {
        int[] counts = new int[session.sourceCount()];
        List<PageEntry> pages = session.order().pages();
        for (PageEntry page : pages) {
            counts[page.selection().sourceIndex()]++;
        }
        return counts;
    }

    private Node chip(int sourceIndex, String name, int pageCount) {
        Region swatch = new Region();
        swatch.getStyleClass().add("source-swatch");
        swatch.setMinSize(CHIP_SIZE, CHIP_SIZE);
        swatch.setPrefSize(CHIP_SIZE, CHIP_SIZE);
        swatch.setMaxSize(CHIP_SIZE, CHIP_SIZE);
        // 色は出どころごとに決まる値であり、見た目の方針ではない。ここで直接当てる。
        swatch.setStyle("-fx-background-color: " + SourceColors.of(sourceIndex) + ";");

        Label label = new Label(name);
        // 名前を全部出そうとすると、長いものが 1 つあるだけで一覧が破綻する。
        // 省いたぶんはツールチップで確かめられるようにする。
        label.setMaxWidth(NAME_WIDTH);
        label.setTextOverrun(OverrunStyle.CENTER_ELLIPSIS);
        label.setTooltip(new Tooltip(name));

        Label count = new Label(pageCount + " ページ");
        count.getStyleClass().add("source-count");

        Button remove = new Button();
        // ★★ この道具で唯一取り消せない操作の入口である（#115）。
        //   id はテストとの契約（.claude/rules/desktop-ui.md「画面の id と JavaFX」）、accessibleText は支援技術から見える
        //   唯一の手がかりである——Windows の UI Automation に setId は届かない。
        //   ★ 位置で区別するのは、ファイルごとに 1 つずつ増えるからである（ThumbnailTile と同じ形）。
        remove.setId("source-remove-" + sourceIndex);
        remove.getStyleClass().add("source-remove");
        remove.setGraphic(ToolIcons.of(ToolIcons.REMOVE));
        remove.setFocusTraversable(false);
        // ★ 名前を入れる。「外す」だけだと、ファイルが 3 つ並んだとき
        //   読み上げからは同じボタンが 3 つあるようにしか聞こえない——
        //   取り消せない操作でそれは危うい（CLAUDE.md 優先順位 2）。
        //   ★ 新しい漏れにはならない。この一覧は既に名前を出しており、
        //     ツールチップにも同じ文が入っている。
        String removeText = name + " をこの編集から外す";
        remove.setTooltip(new Tooltip(removeText));
        remove.setAccessibleText(removeText);
        remove.setOnAction(event -> onRemove.accept(sourceIndex));
        // 走っている間は押させない。押しても何も起きないのと、押せないのは違う（#114）。
        remove.setDisable(removeBlocked.get());
        removeButtons.add(remove);

        HBox chip = new HBox(6, swatch, label, count, remove);
        chip.setAlignment(Pos.CENTER_LEFT);
        return chip;
    }

    /**
     * 「×」と同じ操作のメニュー項目。
     *
     * <p><b>確認はここで取らない。</b>{@code onRemove} の先（{@code MainWindow#removeSource}）が
     * 消える量を見せて取る——「×」と同じ窓を通る。
     */
    private MenuItem removeItem(int sourceIndex, String name) {
        // ★ 名前を入れる。「×」の accessibleText と同じ理由である——同じ項目が並ぶと区別が付かない。
        MenuItem item = new MenuItem(name + " を外す…");
        // ★★ ファイル名をニーモニックとして読ませない。MenuItem は既定で "_" を印として食うので、
        //   scan_01.pdf が scan01.pdf に見え、別のファイルと同じ名前に化けうる——
        //   取り消せない操作の対象を取り違えさせる（CLAUDE.md 優先順位 2）。
        //   一覧の Label は既定で読まないので、あちらには起きない。
        item.setMnemonicParsing(false);
        item.setId("menu-remove-source-" + sourceIndex);
        item.setOnAction(event -> onRemove.accept(sourceIndex));
        item.setDisable(removeBlocked.get());
        removeItems.add(item);
        return item;
    }
}
