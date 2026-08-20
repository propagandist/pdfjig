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

    /** 画像が届くまでの枠の形。A4 縦を仮に置く。 */
    private static final double PLACEHOLDER_RATIO = 1 / Math.sqrt(2);

    private static final PseudoClass SELECTED = PseudoClass.getPseudoClass("selected");

    private static final PseudoClass DROP_TARGET = PseudoClass.getPseudoClass("drop-target");

    private final ThumbnailGrid grid;

    private final ImageView imageView = new ImageView();

    private final StackPane frame = new StackPane(imageView);

    private final Label caption = new Label();

    private final VBox root = new VBox(6, frame, caption);

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

        caption.getStyleClass().add("thumbnail-caption");

        root.getStyleClass().add("thumbnail-tile");
        root.setAlignment(Pos.BOTTOM_CENTER);
        root.setMinSize(TILE_WIDTH, TILE_HEIGHT);
        root.setPrefSize(TILE_WIDTH, TILE_HEIGHT);
        root.setMaxSize(TILE_WIDTH, TILE_HEIGHT);

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
     * @param pageIndex 並びの中での位置（0 始まり）
     * @param selection 表示するページ
     * @param selected  選択中か
     */
    void show(int pageIndex, PageSelection selection, boolean selected) {
        boolean samePage = index == pageIndex
                && imageView.getImage() != null
                && Objects.equals(shown, selection);

        root.setVisible(true);
        root.setManaged(true);
        root.pseudoClassStateChanged(SELECTED, selected);

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
        imageView.setImage(null);
        root.setVisible(false);
        root.setManaged(true);
        root.pseudoClassStateChanged(SELECTED, false);
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
        if (index >= 0) {
            grid.select(index);
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
        if (index < 0) {
            return;
        }
        grid.select(index);

        Dragboard board = root.startDragAndDrop(TransferMode.MOVE);
        ClipboardContent content = new ClipboardContent();
        content.putString(String.valueOf(index));
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
        if (index < 0 || !isFromThisGrid(event)) {
            event.setDropCompleted(false);
            event.consume();
            return;
        }

        int from = Integer.parseInt(event.getDragboard().getString());
        grid.move(from, index);

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
