package io.github.propagandist.pdfjig.desktop;

import io.github.propagandist.pdfjig.core.PageSelection;
import java.util.Optional;
import javafx.concurrent.Task;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
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
 * サムネイル 1 枚分のセル。
 *
 * <p>描画は <b>セルが表示された時点で</b> 依頼する。可視範囲の外は描かない
 * （HANDOVER.md 3-1）。描画が終わるまではプレースホルダを出し、UI を止めない。
 *
 * <p>並べ替えは同じ一覧の中でだけ受け付ける。文書間のページ移動は実装しない
 * （SPEC.md §7.1）。
 */
final class ThumbnailCell extends ListCell<PageSelection> {

    /** プレースホルダの一辺。実際のサムネイルと同じ枠を保ち、表示が跳ねないようにする。 */
    private static final double PLACEHOLDER_SIZE = DocumentSession.THUMBNAIL_EDGE_PIXELS;

    private final ListView<PageSelection> owner;

    private final PageOrder order;

    private final ThumbnailSource thumbnails;

    private final ImageView imageView = new ImageView();

    private final StackPane frame = new StackPane(imageView);

    private final Label caption = new Label();

    private final VBox content = new VBox(4, frame, caption);

    /** 進行中の描画。セルが別のページに使い回されたら取り消す。 */
    private Task<Image> pending;

    ThumbnailCell(ListView<PageSelection> owner, PageOrder order, ThumbnailSource thumbnails) {
        this.owner = owner;
        this.order = order;
        this.thumbnails = thumbnails;

        imageView.setPreserveRatio(true);
        imageView.setFitWidth(PLACEHOLDER_SIZE);
        imageView.setFitHeight(PLACEHOLDER_SIZE);
        frame.setMinSize(PLACEHOLDER_SIZE, PLACEHOLDER_SIZE);
        frame.setPrefSize(PLACEHOLDER_SIZE, PLACEHOLDER_SIZE);
        frame.getStyleClass().add("thumbnail-frame");
        content.setAlignment(Pos.CENTER);

        setAlignment(Pos.CENTER);
        setOnDragDetected(this::startDrag);
        setOnDragOver(this::acceptDrag);
        setOnDragDropped(this::completeDrag);
    }

    @Override
    protected void updateItem(PageSelection selection, boolean empty) {
        super.updateItem(selection, empty);
        cancelPending();

        if (empty || selection == null) {
            setGraphic(null);
            setTooltip(null);
            return;
        }

        // 表示するのは並びの中での位置。元のページ番号は補助情報にとどめる。
        caption.setText(String.valueOf(getIndex() + 1));
        setTooltip(new Tooltip(describe(selection)));
        setGraphic(content);

        // 描くのは元の向きのページなので、加えた回転は表示側で当てる。
        imageView.setRotate(selection.additionalRotation().degrees());

        int pageNumber = selection.pageNumber();
        Optional<Image> cached = thumbnails.cached(pageNumber);
        if (cached.isPresent()) {
            imageView.setImage(cached.get());
            return;
        }

        imageView.setImage(null);
        Task<Image> task = thumbnails.request(pageNumber);
        task.setOnSucceeded(event -> {
            // 描画が終わる前にセルが別のページへ回されていることがある。
            if (isStillShowing(pageNumber)) {
                imageView.setImage(task.getValue());
            }
        });
        task.setOnFailed(event -> {
            // 絵が出ないだけで操作は続けられる。黙って枠のままにせず、理由を添える。
            if (isStillShowing(pageNumber)) {
                setTooltip(new Tooltip("元の " + pageNumber + " ページ目（表示できません）"));
            }
        });
        pending = task;
    }

    private boolean isStillShowing(int pageNumber) {
        return getItem() != null && getItem().pageNumber() == pageNumber;
    }

    private static String describe(PageSelection selection) {
        String origin = "元の " + selection.pageNumber() + " ページ目";
        return selection.rotated()
                ? origin + "（" + selection.additionalRotation().degrees() + " 度回転）"
                : origin;
    }

    private void cancelPending() {
        if (pending != null) {
            pending.cancel(false);
            pending = null;
        }
    }

    private void startDrag(MouseEvent event) {
        if (getItem() == null) {
            return;
        }
        Dragboard board = startDragAndDrop(TransferMode.MOVE);
        ClipboardContent content = new ClipboardContent();
        content.putString(String.valueOf(getIndex()));
        board.setContent(content);
        if (imageView.getImage() != null) {
            board.setDragView(imageView.getImage());
        }
        event.consume();
    }

    private void acceptDrag(DragEvent event) {
        if (isFromThisList(event) && event.getGestureSource() != this) {
            event.acceptTransferModes(TransferMode.MOVE);
        }
        event.consume();
    }

    private void completeDrag(DragEvent event) {
        if (!isFromThisList(event)) {
            event.setDropCompleted(false);
            event.consume();
            return;
        }
        int from = Integer.parseInt(event.getDragboard().getString());
        // 空セルに落とされた場合は末尾へ送る。
        int to = isEmpty() ? order.size() - 1 : getIndex();
        order.move(from, to);
        owner.getSelectionModel().select(to);

        event.setDropCompleted(true);
        event.consume();
    }

    /**
     * この一覧の中から始まったドラッグか。
     *
     * <p>文書間のページ移動は実装しないため、外から来たドロップは受け付けない。
     */
    private boolean isFromThisList(DragEvent event) {
        return event.getDragboard().hasString()
                && event.getGestureSource() instanceof ThumbnailCell source
                && source.owner == owner;
    }
}
