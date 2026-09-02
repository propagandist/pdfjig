package io.github.propagandist.pdfjig.desktop;

import io.github.propagandist.pdfjig.core.PageSelection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javafx.beans.property.ReadOnlyIntegerProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.value.ObservableValue;
import javafx.collections.ListChangeListener;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.skin.VirtualFlow;
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

    private final ListView<List<PageEntry>> rows = new ListView<>();

    /**
     * 選択中のページ位置。
     *
     * <p>ここに変更リスナを足して {@code ListView#refresh} を呼んではならない。
     * <b>あれは全セルを作り直す</b>（{@code VirtualFlow} が sheet を空にし、各セルを
     * {@code updateIndex(-1)} してから組み直す）。ドラッグは MOUSE_PRESSED →
     * DRAG_DETECTED → DRAG_DROPPED と続く一連のジェスチャで、その間にレイアウトパスが
     * 挟まるため、作り直すとタイルが別のページを受け持ち、掴んだのと違うページが動く。
     *
     * <p>選択が動いたときの見た目は {@link ThumbnailTile} が自分で当てる。
     * 更新が要るのは旧選択と新選択の 2 枚だけである。
     */
    private final SimpleIntegerProperty selectedIndex = new SimpleIntegerProperty(-1);

    /** ページ並びが変わったら行を組み直す。 */
    private final ListChangeListener<PageEntry> pagesListener = change -> rebuild();

    /** 1 行あたりの枚数。 */
    private int columns = 1;

    /** 直前に出どころの帯を出していたか。含むファイルが 1 つと複数の間で切り替わると変わる。 */
    private boolean accentsShown;

    /** 表示中の編集セッション。ツールチップに出どころのファイル名を出すために持つ。 */
    private DocumentSession document;

    private PageOrder order;

    private ThumbnailSource thumbnails;

    /** Delete キーで呼ぶ処理。画面側が差す。 */
    private Runnable onDelete = () -> {};

    /**
     * 文書を変える操作を通してはならない条件。画面側が差す（#114）。
     *
     * <p>差されるまでは通す。<b>この一覧だけで判断できるものではない</b>——
     * 走っている仕事があるか、書き出したものと食い違っているかを持っているのは画面の側である。
     */
    private ObservableValue<Boolean> editingBlocked = new SimpleBooleanProperty(false);

    ThumbnailGrid() {
        rows.setId("thumbnail-list");
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

        rows.widthProperty().addListener((observable, previous, current) -> applyColumns(current.doubleValue()));
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
     * 文書を変える操作を通してはならない条件を差す。
     *
     * <p><b>ここを通る入口は {@link Action} を通らない</b>——DELETE キーは処理を直に呼び、
     * ドラッグの落とし先は {@link #move} を直に呼ぶ。<b>メニュー項目が無効かどうかは見ていない。</b>
     *
     * @param condition 通してはならない間 {@code true} になるもの
     */
    void setEditingBlockedWhen(ObservableValue<Boolean> condition) {
        this.editingBlocked = condition;
    }

    /** いま、文書を変える操作を通してはならないか。タイル（ドラッグの始まり）からも見る。 */
    boolean editingBlocked() {
        return Boolean.TRUE.equals(editingBlocked.getValue());
    }

    /**
     * 文書を表示する。
     *
     * @param session 表示する編集セッション
     */
    void show(DocumentSession session) {
        clear();

        this.document = session;
        PageOrder pageOrder = session.order();
        this.order = pageOrder;
        this.thumbnails = session.thumbnails();
        pageOrder.pages().addListener(pagesListener);

        applyColumns(rows.getWidth());
        rebuild();
        // 前の文書のスクロール位置が残らないよう、先頭まで戻す。
        selectAndReveal(pageOrder.size() > 0 ? 0 : -1);
    }

    /** 表示を空にする。 */
    void clear() {
        if (order != null) {
            order.pages().removeListener(pagesListener);
        }
        document = null;
        order = null;
        thumbnails = null;
        selectedIndex.set(-1);
        rows.getItems().clear();
    }

    /** サムネイルの供給元。タイルから使う。 */
    ThumbnailSource thumbnails() {
        return thumbnails;
    }

    /** 出どころを見分ける手がかりを出すか。複数のファイルを含むときだけ意味がある。 */
    boolean showsSources() {
        return document != null && document.sourceCount() > 1;
    }

    /**
     * ツールチップに出す 1 ページの説明。
     *
     * <p>複数のファイルを含むときは、どのファイルから来たページかを必ず出す。
     * 混ぜて並べ替えた後は、ページ番号だけでは出どころが分からなくなるため。
     * 1 ファイルだけのときは、言うまでもないので添えない。
     */
    String describe(PageSelection selection) {
        String origin = showsSources()
                ? document.sourceName(selection.sourceIndex()) + " の " + selection.pageNumber() + " ページ目"
                : "元の " + selection.pageNumber() + " ページ目";

        return selection.rotated()
                ? origin + "（" + selection.additionalRotation().degrees() + " 度回転）"
                : origin;
    }

    /** 1 行あたりの枚数。行セルから使う。 */
    int columns() {
        return columns;
    }

    /**
     * ページを選ぶ。スクロールはしない。
     *
     * <p>クリックとドラッグの開始から呼ぶ。どちらも利用者がタイルの位置を見て決めた操作であり、
     * そこで一覧を動かさない。
     */
    void select(int index) {
        selectedIndex.set(index);
    }

    /**
     * ページを選び、見えるところまでスクロールする。
     *
     * <p>選択が画面の外へ出うる操作から呼ぶ。既に見えているなら {@link #reveal} が何もしない。
     *
     * <p><b>★ {@code MainWindow} からも呼ぶ。</b>上書き保存の後にセッションを寄せ直すと
     * {@link #show} が先頭へ戻すので、控えておいた位置をここで返す（#118）。
     * <b>範囲の外なら {@link #reveal} が何もしない。</b>
     */
    void selectAndReveal(int index) {
        select(index);
        reveal(index);
    }

    /** ページを動かす。タイルのドロップから呼ぶ。 */
    void move(int fromIndex, int toIndex) {
        if (order == null || fromIndex == toIndex) {
            return;
        }
        order.move(fromIndex, toIndex);
        // 動かした先が別の行になることがある。掴んでいたページを見失わせない。
        selectAndReveal(toIndex);
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

        List<PageEntry> pages = order.pages();
        List<List<PageEntry>> next = new ArrayList<>();
        for (int from = 0; from < pages.size(); from += columns) {
            next.add(List.copyOf(pages.subList(from, Math.min(from + columns, pages.size()))));
        }
        rows.getItems().setAll(next);

        // 出どころの帯が出るようになった（あるいは消えた）ときは、中身の変わっていない
        // ページも描き直す必要がある。ListView は同じ内容のセルを更新しない。
        if (accentsShown != showsSources()) {
            accentsShown = showsSources();
            rows.refresh();
        }

        // ページが減ると選択が並びの外に出る。末尾へ寄せた選択は画面の外にありうる。
        if (selectedIndex.get() >= pages.size()) {
            selectAndReveal(pages.size() - 1);
        }
    }

    /**
     * そのページが見えるところまでスクロールする。既に見えているなら動かさない。
     *
     * <p><b>{@code ListView#scrollTo(int)} は使えない。</b>名前に反して「見えるようにする」
     * ではなく、その行を <b>viewport の最上段へ送る</b>。実体は
     * {@code ScrollToEvent.scrollToTopIndex()} を投げるだけで、受けた
     * {@code VirtualContainerBase} が {@code VirtualFlow#scrollToTop(int)} を呼ぶ。
     * 見えている行に対して呼んでも容赦なく飛ぶ。
     *
     * <p>{@code VirtualFlow#scrollTo(int)} のほうが目的に合う。行が見えていれば何もせず、
     * はみ出していれば足りない分だけ動かす。可視判定を自前で持つ必要はない。
     *
     * <p>{@code VirtualFlow} は {@code javafx.scene.control.skin} の公開クラスであり、
     * style class {@code virtual-flow} は {@code ListView} の skin が必ず付ける。
     * skin ができる前は取れないが、その時点では画面に何も出ていないので、
     * 最上段送りになっても見え方は変わらない。
     */
    private void reveal(int index) {
        if (index < 0 || columns <= 0) {
            return;
        }
        int row = index / columns;

        if (rows.lookup(".virtual-flow") instanceof VirtualFlow<?> flow) {
            flow.scrollTo(row);
        } else {
            rows.scrollTo(row);
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

        int next =
                switch (event.getCode()) {
                    case LEFT -> current - 1;
                    case RIGHT -> current + 1;
                    case UP -> current - columns;
                    case DOWN -> current + columns;
                    case HOME -> 0;
                    case END -> size - 1;
                    default -> current;
                };

        if (next != current && next >= 0 && next < size) {
            // キーで動かす選択は画面の外へ出る。行が変わったぶんだけ追いかける。
            selectAndReveal(next);
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
        for (Map.Entry<KeyCombination, Runnable> accelerator :
                scene.getAccelerators().entrySet()) {
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
    private static final class RowCell extends ListCell<List<PageEntry>> {

        private final ThumbnailGrid grid;

        private final List<ThumbnailTile> tiles = new ArrayList<>();

        private final HBox row = new HBox(GAP);

        RowCell(ThumbnailGrid grid) {
            this.grid = grid;
            row.setAlignment(Pos.TOP_LEFT);
            setGraphic(row);
        }

        @Override
        protected void updateItem(List<PageEntry> pages, boolean empty) {
            super.updateItem(pages, empty);

            if (empty || pages == null) {
                tiles.forEach(ThumbnailTile::clear);
                row.setVisible(false);
                return;
            }

            row.setVisible(true);
            ensureTiles(grid.columns());

            int base = getIndex() * grid.columns();

            for (int column = 0; column < tiles.size(); column++) {
                ThumbnailTile tile = tiles.get(column);
                if (column < pages.size()) {
                    tile.show(base + column, pages.get(column));
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
