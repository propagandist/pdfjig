package io.github.propagandist.pdfjig.desktop;

import io.github.propagandist.pdfjig.core.PageSelection;
import java.util.Objects;
import java.util.Optional;
import javafx.concurrent.Task;
import javafx.css.PseudoClass;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.DragEvent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * グリッドに並ぶサムネイル 1 枚分。
 *
 * <p>描画は <b>タイルが実際に使われた時点で</b> 依頼する。可視範囲の外は描かない
 * （HANDOVER.md 3-1）。行セルに使い回されるため、別のページを表示するときは
 * 進行中の描画を取り消す。
 *
 * <p>枠はページの形に貼り付く。縦長と横長が枠の形で見分けられないと、
 * 何を並べ替えているのか分からなくなるため。回転を加えたページは枠ごと回る。
 *
 * <p>{@link javafx.scene.layout.Pane} を継承せず包み持つのは、コンストラクタから
 * {@code getChildren()} を呼ぶと {@code this-escape} 警告になり、
 * このビルドは {@code -Werror} だからである。
 */
final class ThumbnailTile {

    /** サムネイルの長辺（論理画素）。実際の描画はこれより大きい解像度で行う。 */
    static final double IMAGE_EDGE = 150;

    /** タイルの外形。全ページで同じにして、グリッドの桁を揃える。 */
    static final double TILE_WIDTH = 176;

    static final double TILE_HEIGHT = 206;

    /** 出どころを示す帯の高さ。 */
    private static final double ACCENT_HEIGHT = 3;

    /** 区切りの縦線の幅。 */
    private static final double BREAK_WIDTH = 3;

    /** 画像が届くまでの枠の形。A4 縦を仮に置く。 */
    private static final double PLACEHOLDER_RATIO = 1 / Math.sqrt(2);

    private static final PseudoClass SELECTED = PseudoClass.getPseudoClass("selected");

    private static final PseudoClass DROP_TARGET = PseudoClass.getPseudoClass("drop-target");

    private static final PseudoClass BREAK = PseudoClass.getPseudoClass("break");

    private final ThumbnailGrid grid;

    private final ImageView imageView = new ImageView();

    private final StackPane frame = new StackPane(imageView);

    /**
     * 出どころを示す帯。ページの真下に、ページと同じ幅で敷く。
     *
     * <p>複数のファイルを混ぜているときだけ出す。1 つしか開いていないなら意味がない。
     */
    private final Region accent = new Region();

    /** ページと帯。間を空けず、帯がページに付いているように見せる。 */
    private final VBox page = new VBox(0, frame, accent);

    private final Label caption = new Label();

    /**
     * 区切りの縦線。
     *
     * <p>タイルに重ねて描く。枠線として持たせるとタイルの内側の余白が変わり、
     * 区切りのあるページだけ中身がずれる。
     */
    private final Region breakMark = new Region();

    private final VBox body = new VBox(5, page, caption);

    private final StackPane root = new StackPane(body, breakMark);

    /** 表示中のページの、並びの中での位置。空きタイルのときは -1。 */
    private int index = -1;

    /** 表示中のページ。空きタイルのときは null。 */
    private PageSelection shown;

    /** 進行中の描画。タイルが別のページに回されたら取り消す。 */
    private Task<Image> pending;

    ThumbnailTile(ThumbnailGrid grid) {
        this.grid = grid;

        imageView.setPreserveRatio(true);
        imageView.setSmooth(true);

        frame.getStyleClass().add("thumbnail-frame");
        frame.setMinSize(StackPane.USE_PREF_SIZE, StackPane.USE_PREF_SIZE);
        frame.setMaxSize(StackPane.USE_PREF_SIZE, StackPane.USE_PREF_SIZE);

        accent.getStyleClass().add("thumbnail-accent");
        accent.setMinHeight(ACCENT_HEIGHT);
        accent.setPrefHeight(ACCENT_HEIGHT);
        accent.setMaxHeight(ACCENT_HEIGHT);
        // 帯はページの形に追従させる。回転して横長になれば帯も伸びる。
        accent.prefWidthProperty().bind(frame.prefWidthProperty());
        accent.setMaxWidth(Region.USE_PREF_SIZE);

        page.setAlignment(Pos.BOTTOM_CENTER);

        breakMark.getStyleClass().add("thumbnail-break");
        breakMark.setMaxWidth(BREAK_WIDTH);
        breakMark.setMouseTransparent(true);
        StackPane.setAlignment(breakMark, Pos.CENTER_LEFT);

        body.getStyleClass().add("thumbnail-body");
        body.setAlignment(Pos.BOTTOM_CENTER);

        caption.getStyleClass().add("thumbnail-caption");

        root.getStyleClass().add("thumbnail-tile");
        root.setMinSize(TILE_WIDTH, TILE_HEIGHT);
        root.setPrefSize(TILE_WIDTH, TILE_HEIGHT);
        root.setMaxSize(TILE_WIDTH, TILE_HEIGHT);

        // 選択枠は自分で当てる。一覧側が ListView#refresh で当て直すこともできるが、
        // あれは全セルを作り直す（VirtualFlow が sheet を空にし、各セルを updateIndex(-1) する）。
        // 選択が動いて更新が要るのは旧選択と新選択の 2 枚だけであり、全部を作り直す理由がない。
        // 作り直すと、ドラッグのジェスチャの最中にタイルが入れ替わって掴んだページを見失う。
        grid.selectedIndexProperty().addListener((observable, previous, current) ->
                applySelected(current.intValue()));

        root.setOnMousePressed(this::select);
        root.setOnDragDetected(this::startDrag);
        root.setOnDragOver(this::acceptDrag);
        root.setOnDragEntered(event -> markDropTarget(isFromThisGrid(event)));
        root.setOnDragExited(event -> markDropTarget(false));
        root.setOnDragDropped(this::completeDrag);
        root.setOnDragDone(event -> markDropTarget(false));
    }

    /** 画面に置くための節点。 */
    Node node() {
        return root;
    }

    /**
     * このタイルに 1 ページを表示させる。
     *
     * <p>選択中かどうかは受け取らない。一覧が持つ選択位置ただ一つを見る
     * （{@link #applySelected}）。両方から当てられるようにすると、
     * どちらかを呼び忘れた経路で枠が残る。
     *
     * @param pageIndex 並びの中での位置（0 始まり）
     * @param entry     表示するページ
     */
    void show(int pageIndex, PageEntry entry) {
        PageSelection selection = entry.selection();
        boolean samePage = index == pageIndex
                && imageView.getImage() != null
                && Objects.equals(shown, selection);

        root.setVisible(true);
        root.setManaged(true);
        // タイルは行ごと使い回される。受け持つページが変われば id も変わる。
        root.setId("thumbnail-tile-" + pageIndex);
        // このタイルが受け持つページが変わったので、選択枠を当て直す。
        applySelected(pageIndex, grid.selectedIndex());
        // 帯の有無は並びの中身ではなく、いくつのファイルを含んでいるかで決まる。
        // 同じページを出し続けている間にファイルが増えることがあるので、毎回当て直す。
        applyAccent(selection.sourceIndex());
        // 区切りも並びの中身とは別に動く。先頭に来たページの区切りは効かないので出さない。
        applyBreak(pageIndex > 0 && entry.startsNewFile());

        if (samePage) {
            return;
        }

        cancelPending();
        index = pageIndex;
        shown = selection;

        caption.setText(String.valueOf(pageIndex + 1));
        Tooltip.install(root, new Tooltip(grid.describe(selection)));

        // 描くのは元の向きのページなので、加えた回転は表示側で当てる。
        imageView.setRotate(selection.additionalRotation().degrees());

        int sourceIndex = selection.sourceIndex();
        int pageNumber = selection.pageNumber();

        Optional<Image> cached = grid.thumbnails().cached(sourceIndex, pageNumber);
        if (cached.isPresent()) {
            apply(cached.get(), selection);
            return;
        }

        placeholder(selection);

        Task<Image> task = grid.thumbnails().request(sourceIndex, pageNumber);
        task.setOnSucceeded(event -> {
            // 描画が終わる前にタイルが別のページへ回されていることがある。
            if (stillShowing(sourceIndex, pageNumber)) {
                apply(task.getValue(), shown);
            }
        });
        task.setOnFailed(event -> {
            // 絵が出ないだけで操作は続けられる。黙って枠のままにせず、理由を添える。
            if (stillShowing(sourceIndex, pageNumber)) {
                Tooltip.install(
                        root, new Tooltip(grid.describe(shown) + "（表示できません）"));
            }
        });
        pending = task;
    }

    /**
     * 選択枠を当てる。一覧の選択が動いたときに呼ばれる。
     *
     * @param selected 一覧が選んでいるページの位置
     */
    private void applySelected(int selected) {
        applySelected(index, selected);
    }

    /**
     * 選択枠を当てる。
     *
     * <p>受け持つページと選ばれているページを引数で受けるのは、{@link #show} が
     * {@link #index} を書き換える前に呼ぶためである。空きタイル（{@code -1}）に
     * 枠が付かないよう、位置が負なら必ず外す。
     *
     * @param pageIndex このタイルが受け持つページの位置。空きタイルなら {@code -1}
     * @param selected  一覧が選んでいるページの位置
     */
    private void applySelected(int pageIndex, int selected) {
        root.pseudoClassStateChanged(SELECTED, pageIndex >= 0 && pageIndex == selected);
    }

    /** 区切りの縦線を当てる。「このページから新しいファイルが始まる」ことを示す。 */
    private void applyBreak(boolean present) {
        breakMark.setVisible(present);
    }

    /** 出どころの帯を当てる。1 ファイルだけのときは出さない。 */
    private void applyAccent(int sourceIndex) {
        boolean show = grid.showsSources();
        accent.setVisible(show);
        accent.setManaged(show);
        if (show) {
            accent.setStyle("-fx-background-color: " + SourceColors.of(sourceIndex) + ";");
        }
    }

    /** そのページをまだ表示しているか。同じページ番号でも文書が違えば別物である。 */
    private boolean stillShowing(int sourceIndex, int pageNumber) {
        return shown != null
                && shown.sourceIndex() == sourceIndex
                && shown.pageNumber() == pageNumber;
    }

    /** 余った桁を空にする。行の幅は保ったままにして、桁が詰まらないようにする。 */
    void clear() {
        cancelPending();
        index = -1;
        shown = null;
        // 空きタイルに古い id を残さない。残すと、同じ id の節点が一覧に 2 つ並びうる。
        root.setId(null);
        imageView.setImage(null);
        root.setVisible(false);
        root.setManaged(true);
        applySelected(-1, grid.selectedIndex());
        applyBreak(false);
        markDropTarget(false);
    }

    private void apply(Image image, PageSelection selection) {
        imageView.setImage(image);

        double scale = IMAGE_EDGE / Math.max(image.getWidth(), image.getHeight());
        double width = image.getWidth() * scale;
        double height = image.getHeight() * scale;

        imageView.setFitWidth(width);
        imageView.setFitHeight(height);

        // 90 / 270 度では見た目の縦横が入れ替わる。枠はその形に合わせる。
        boolean quarterTurn = selection.additionalRotation().degrees() % 180 != 0;
        frame.setPrefSize(quarterTurn ? height : width, quarterTurn ? width : height);
    }

    private void placeholder(PageSelection selection) {
        imageView.setImage(null);

        boolean quarterTurn = selection.additionalRotation().degrees() % 180 != 0;
        double width = IMAGE_EDGE * PLACEHOLDER_RATIO;
        frame.setPrefSize(quarterTurn ? IMAGE_EDGE : width, quarterTurn ? width : IMAGE_EDGE);
    }

    private void markDropTarget(boolean active) {
        root.pseudoClassStateChanged(DROP_TARGET, active);
    }

    private void select(MouseEvent event) {
        int pressed = index;
        if (pressed >= 0) {
            grid.select(pressed);
            root.requestFocus();
        }
        event.consume();
    }

    private void cancelPending() {
        if (pending != null) {
            pending.cancel(false);
            pending = null;
        }
    }

    private void startDrag(MouseEvent event) {
        // 掴んだ位置はここで確定させ、以降フィールドを読まない。
        //
        // タイルは行セルに使い回される。ドラッグは MOUSE_PRESSED → DRAG_DETECTED →
        // DRAG_DROPPED と続く一連のジェスチャで、その間にレイアウトパスが挟まる。
        // 一覧が作り直されればこのタイルは別のページを受け持ち、フィールドを読むと
        // 掴んだのと違うページを動かすことになる。
        int dragged = index;
        if (dragged < 0) {
            return;
        }
        grid.select(dragged);

        Dragboard board = root.startDragAndDrop(TransferMode.MOVE);
        ClipboardContent content = new ClipboardContent();
        content.putString(String.valueOf(dragged));
        board.setContent(content);
        if (imageView.getImage() != null) {
            board.setDragView(imageView.getImage());
        }
        event.consume();
    }

    private void acceptDrag(DragEvent event) {
        if (index >= 0 && isFromThisGrid(event) && event.getGestureSource() != root) {
            event.acceptTransferModes(TransferMode.MOVE);
        }
        event.consume();
    }

    private void completeDrag(DragEvent event) {
        markDropTarget(false);
        // 落とし先も掴んだ位置と同じ理由でローカルに取る（startDrag のコメント）。
        int target = index;
        if (target < 0 || !isFromThisGrid(event)) {
            event.setDropCompleted(false);
            event.consume();
            return;
        }

        int from = Integer.parseInt(event.getDragboard().getString());
        grid.move(from, target);

        event.setDropCompleted(true);
        event.consume();
    }

    /**
     * この一覧の中から始まったドラッグか。
     *
     * <p>文書間のページ移動は実装しないため、外から来たドロップは受け付けない
     * （SPEC.md §7.1）。
     */
    private boolean isFromThisGrid(DragEvent event) {
        return event.getDragboard().hasString() && grid.owns(event.getGestureSource());
    }
}
