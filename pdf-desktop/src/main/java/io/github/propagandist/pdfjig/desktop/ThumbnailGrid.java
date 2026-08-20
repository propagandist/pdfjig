package io.github.propagandist.pdfjig.desktop;

import io.github.propagandist.pdfjig.core.PageSelection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javafx.beans.property.ReadOnlyIntegerProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.collections.ListChangeListener;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.SelectionMode;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;

/**
 * ページのサムネイルを格子状に並べる一覧。
 *
 * <p>幅に応じて 1 行あたりの枚数を決め、<b>1 セル = 1 行</b>として
 * {@link ListView} に載せる。素の {@code ListView} は 1 次元なので格子にはできないが、
 * 行を単位にすれば仮想化はそのまま効く。可視範囲の外にある行はセルごと使い回され、
 * サムネイルの描画依頼も発生しない（HANDOVER.md 3-1）。
 *
 * <p>{@code GridView} を使わないのは ControlsFX への依存が要るためで、
 * この判断は Phase 3 から変えていない。
 *
 * <p>選択は {@code ListView} の選択モデルではなく、このクラスがページ位置として持つ。
 * 行を選ぶのではなく 1 枚のページを選ぶ操作であり、両者は一致しないため。
 */
final class ThumbnailGrid {

    /** タイルの間隔。 */
    private static final double GAP = 6;

    /** 一覧の左右の余白。 */
    private static final double SIDE_PADDING = 10;

    /** 縦スクロールバーの見込み幅。桁数を決めるときに引いておく。 */
    private static final double SCROLLBAR_ALLOWANCE = 18;

    private final ListView<List<PageSelection>> rows = new ListView<>();

    private final SimpleIntegerProperty selectedIndex = new SimpleIntegerProperty(-1);

    /** ページ並びが変わったら行を組み直す。 */
    private final ListChangeListener<PageSelection> pagesListener = change -> rebuild();

    /** 1 行あたりの枚数。 */
    private int columns = 1;

    private PageOrder order;

    private ThumbnailSource thumbnails;

    /** Delete キーで呼ぶ処理。画面側が差す。 */
    private Runnable onDelete = () -> { };

    ThumbnailGrid() {
        rows.getStyleClass().add("thumbnail-list");
        rows.setPlaceholder(new Label("PDF を開いてください。"));
        rows.setCellFactory(view -> new RowCell(this));
        rows.setFocusTraversable(true);

        // 行の高さはタイルの外形そのもの。上下の余白はタイル側の padding が作る。
        // 固定にしておくと、ListView が中身を測らずにスクロール位置を出せる。
        rows.setFixedCellSize(ThumbnailTile.TILE_HEIGHT);

        // ListView 自身の選択と矢印キーは使わない。選ぶのは行ではなくページであり、
        // 行選択の枠が出ると何を選んでいるのか分からなくなる。
        rows.getSelectionModel().setSelectionMode(SelectionMode.SINGLE);
        rows.addEventFilter(KeyEvent.KEY_PRESSED, this::handleKey);

        rows.widthProperty().addListener((observable, previous, current) ->
                applyColumns(current.doubleValue()));

        // 選択が動いたら、見えている行だけ描き直して選択枠を移す。
        selectedIndex.addListener((observable, previous, current) -> {
            rows.refresh();
            scrollToSelection(current.intValue());
        });
    }

    /** 画面に置くための節点。 */
    Node node() {
        return rows;
    }

    /** 選択中のページ位置（0 始まり）。選択が無いときは -1。 */
    ReadOnlyIntegerProperty selectedIndexProperty() {
        return selectedIndex;
    }

    /** 選択中のページ位置（0 始まり）。選択が無いときは -1。 */
    int selectedIndex() {
        return selectedIndex.get();
    }

    /** Delete キーで呼ぶ処理を差す。 */
    void setOnDelete(Runnable action) {
        this.onDelete = action;
    }

    /**
     * 文書を表示する。
     *
     * @param pageOrder 編集中のページ並び
     * @param source    サムネイルの供給元
     */
    void show(PageOrder pageOrder, ThumbnailSource source) {
        clear();

        this.order = pageOrder;
        this.thumbnails = source;
        pageOrder.pages().addListener(pagesListener);

        applyColumns(rows.getWidth());
        rebuild();
        select(pageOrder.size() > 0 ? 0 : -1);
    }

    /** 表示を空にする。 */
    void clear() {
        if (order != null) {
            order.pages().removeListener(pagesListener);
        }
        order = null;
        thumbnails = null;
        selectedIndex.set(-1);
        rows.getItems().clear();
    }

    /** サムネイルの供給元。タイルから使う。 */
    ThumbnailSource thumbnails() {
        return thumbnails;
    }

    /** 1 行あたりの枚数。行セルから使う。 */
    int columns() {
        return columns;
    }

    /** ページを選ぶ。 */
    void select(int index) {
        selectedIndex.set(index);
    }

    /** ページを動かす。タイルのドロップから呼ぶ。 */
    void move(int fromIndex, int toIndex) {
        if (order == null || fromIndex == toIndex) {
            return;
        }
        order.move(fromIndex, toIndex);
        select(toIndex);
    }

    /** その節点がこの一覧の中のものか。文書間のドラッグを弾くために使う。 */
    boolean owns(Object node) {
        return node instanceof Node candidate && isDescendant(candidate);
    }

    private boolean isDescendant(Node node) {
        for (Node current = node; current != null; current = current.getParent()) {
            if (current == rows) {
                return true;
            }
        }
        return false;
    }

    /** 幅から 1 行あたりの枚数を決める。変われば行を組み直す。 */
    private void applyColumns(double width) {
        double available = width - SCROLLBAR_ALLOWANCE - 2 * SIDE_PADDING;
        int next = (int) Math.floor((available + GAP) / (ThumbnailTile.TILE_WIDTH + GAP));
        next = Math.max(1, next);

        if (next != columns) {
            columns = next;
            rebuild();
        }
    }

    /** ページ並びを行に切り分ける。 */
    private void rebuild() {
        if (order == null) {
            rows.getItems().clear();
            return;
        }

        List<PageSelection> pages = order.pages();
        List<List<PageSelection>> next = new ArrayList<>();
        for (int from = 0; from < pages.size(); from += columns) {
            next.add(List.copyOf(pages.subList(from, Math.min(from + columns, pages.size()))));
        }
        rows.getItems().setAll(next);

        // ページが減ると選択が並びの外に出る。
        if (selectedIndex.get() >= pages.size()) {
            select(pages.size() - 1);
        }
    }

    private void scrollToSelection(int index) {
        if (index >= 0 && columns > 0) {
            rows.scrollTo(index / columns);
        }
    }

    /**
     * 矢印キーでページ単位に選択を動かす。
     *
     * <p>{@link ListView} の既定は行単位で動くため、握り潰して置き換える。
     */
    private void handleKey(KeyEvent event) {
        if (order == null) {
            return;
        }
        if (event.getCode() == KeyCode.DELETE) {
            onDelete.run();
            event.consume();
            return;
        }

        if (event.isShortcutDown() || event.isAltDown() || event.isShiftDown()) {
            // 修飾キーが付いた矢印は選択の移動には使わない。
            //
            // ただし素通しもできない。ListView は修飾付きの矢印を自分の操作として
            // 握り潰してしまい、その先で走るはずのメニューのアクセラレータ（Ctrl+→ の回転など）
            // まで届かなくなる。ここで先に走らせる。
            runAccelerator(event);
            return;
        }

        int size = order.size();
        int current = selectedIndex.get();

        int next = switch (event.getCode()) {
            case LEFT -> current - 1;
            case RIGHT -> current + 1;
            case UP -> current - columns;
            case DOWN -> current + columns;
            case HOME -> 0;
            case END -> size - 1;
            default -> current;
        };

        if (next != current && next >= 0 && next < size) {
            select(next);
        }
        if (next != current) {
            // 端でも握り潰す。ここで通すと ListView が行選択を始める。
            event.consume();
        }
    }

    /**
     * この打鍵に対応するアクセラレータがあれば走らせる。
     *
     * <p>登録元はメニュー項目であり、キーの組み合わせの定義はそちらに一本化してある。
     * ここでは照合して呼ぶだけで、独自の割り当ては持たない。
     */
    private void runAccelerator(KeyEvent event) {
        Scene scene = rows.getScene();
        if (scene == null) {
            return;
        }
        for (Map.Entry<KeyCombination, Runnable> accelerator : scene.getAccelerators().entrySet()) {
            if (accelerator.getKey().match(event)) {
                accelerator.getValue().run();
                event.consume();
                return;
            }
        }
    }

    /**
     * 1 行分のセル。
     *
     * <p>タイルは行あたり固定本数を作って使い回す。桁数が変わったときだけ作り足す。
     */
    private static final class RowCell extends ListCell<List<PageSelection>> {

        private final ThumbnailGrid grid;

        private final List<ThumbnailTile> tiles = new ArrayList<>();

        private final HBox row = new HBox(GAP);

        RowCell(ThumbnailGrid grid) {
            this.grid = grid;
            row.setAlignment(Pos.TOP_LEFT);
            setGraphic(row);
        }

        @Override
        protected void updateItem(List<PageSelection> pages, boolean empty) {
            super.updateItem(pages, empty);

            if (empty || pages == null) {
                tiles.forEach(ThumbnailTile::clear);
                row.setVisible(false);
                return;
            }

            row.setVisible(true);
            ensureTiles(grid.columns());

            int base = getIndex() * grid.columns();
            int selected = grid.selectedIndex();

            for (int column = 0; column < tiles.size(); column++) {
                ThumbnailTile tile = tiles.get(column);
                if (column < pages.size()) {
                    int index = base + column;
                    tile.show(index, pages.get(column), index == selected);
                } else {
                    // 最終行の余りは空にする。桁を保って左詰めの見た目を崩さない。
                    tile.clear();
                }
            }
        }

        private void ensureTiles(int count) {
            while (tiles.size() < count) {
                ThumbnailTile tile = new ThumbnailTile(grid);
                tiles.add(tile);
                row.getChildren().add(tile.node());
            }
            while (tiles.size() > count) {
                ThumbnailTile removed = tiles.remove(tiles.size() - 1);
                removed.clear();
                row.getChildren().remove(removed.node());
            }
        }
    }
}
