package io.github.propagandist.pdfjig.desktop;

import java.util.List;
import java.util.function.IntConsumer;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
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
    private IntConsumer onRemove = sourceIndex -> { };

    SourceLegend() {
        root.getStyleClass().add("source-legend");
        root.setAlignment(Pos.CENTER_LEFT);
        hide();
    }

    /** 画面に置くための節点。 */
    Node node() {
        return root;
    }

    /** ファイルを外すときに呼ぶ処理を差す。 */
    void setOnRemove(IntConsumer action) {
        this.onRemove = action;
    }

    /**
     * 表示を作り直す。
     *
     * @param session 表示中の編集セッション。{@code null} なら隠す
     */
    void update(DocumentSession session) {
        if (session == null || session.sourceCount() < 2) {
            hide();
            return;
        }

        int[] counts = countsPerSource(session);

        root.getChildren().clear();
        for (int sourceIndex = 0; sourceIndex < session.sourceCount(); sourceIndex++) {
            root.getChildren().add(
                    chip(sourceIndex, session.sourceName(sourceIndex), counts[sourceIndex]));
        }

        root.setVisible(true);
        root.setManaged(true);
    }

    private void hide() {
        root.getChildren().clear();
        root.setVisible(false);
        // 場所も空けない。1 ファイルのときに帯だけが残ると、何かがあると思わせる。
        root.setManaged(false);
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
        remove.getStyleClass().add("source-remove");
        remove.setGraphic(ToolIcons.of(ToolIcons.REMOVE));
        remove.setFocusTraversable(false);
        remove.setTooltip(new Tooltip(name + " をこの編集から外す"));
        remove.setOnAction(event -> onRemove.accept(sourceIndex));

        HBox chip = new HBox(6, swatch, label, count, remove);
        chip.setAlignment(Pos.CENTER_LEFT);
        return chip;
    }
}
