package io.github.propagandist.pdfjig.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.propagandist.pdfjig.core.ErrorCode;
import io.github.propagandist.pdfjig.core.PageRange;
import io.github.propagandist.pdfjig.core.PageSelection;
import io.github.propagandist.pdfjig.core.PdfjigException;
import io.github.propagandist.pdfjig.core.Rotation;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PageOrderTest {

    @Test
    @DisplayName("初期状態は元の並びのまま")
    void startsUnmodified() {
        PageOrder order = PageOrder.of(3);

        assertEquals(List.of(1, 2, 3), pageNumbersOf(order));
        assertFalse(order.modified());
    }

    @Test
    @DisplayName("先頭のページを末尾へ動かせる")
    void movesPageToEnd() {
        PageOrder order = PageOrder.of(3);

        order.move(0, 2);

        assertEquals(List.of(2, 3, 1), pageNumbersOf(order));
        assertTrue(order.modified());
    }

    @Test
    @DisplayName("末尾のページを先頭へ動かせる")
    void movesPageToFront() {
        PageOrder order = PageOrder.of(3);

        order.move(2, 0);

        assertEquals(List.of(3, 1, 2), pageNumbersOf(order));
    }

    @Test
    @DisplayName("同じ位置への移動は何も変えない")
    void ignoresMoveToSameIndex() {
        PageOrder order = PageOrder.of(3);

        order.move(1, 1);

        assertEquals(List.of(1, 2, 3), pageNumbersOf(order));
        assertFalse(order.modified());
    }

    @Test
    @DisplayName("ページを取り除ける")
    void removesPage() {
        PageOrder order = PageOrder.of(3);

        order.removeAt(1);

        assertEquals(List.of(1, 3), pageNumbersOf(order));
        assertTrue(order.modified());
    }

    @Test
    @DisplayName("最後の 1 枚は取り除けない")
    void keepsAtLeastOnePage() {
        PageOrder order = PageOrder.of(2);
        order.removeAt(0);

        // 空の PDF は保存できない。保存の瞬間まで失敗が分からない状態を作らない。
        assertEquals(
                ErrorCode.EMPTY_RESULT,
                assertThrows(PdfjigException.class, () -> order.removeAt(0)).errorCode());
        assertEquals(List.of(2), pageNumbersOf(order));
    }

    @Test
    @DisplayName("回転は現在の向きに加算される")
    void addsRotation() {
        PageOrder order = PageOrder.of(2);

        order.rotateAt(0, Rotation.CLOCKWISE_90);
        order.rotateAt(0, Rotation.CLOCKWISE_90);

        assertEquals(Rotation.HALF_TURN, order.toPageSelections().get(0).additionalRotation());
        assertEquals(Rotation.NONE, order.toPageSelections().get(1).additionalRotation());
        assertTrue(order.modified());
    }

    @Test
    @DisplayName("回転は指定した位置のページだけに効く")
    void rotatesOnlyTheGivenPosition() {
        PageOrder order = PageOrder.of(3);
        order.move(2, 0);

        order.rotateAt(0, Rotation.CLOCKWISE_90);

        assertEquals(List.of(3, 1, 2), pageNumbersOf(order));
        assertEquals(
                List.of(Rotation.CLOCKWISE_90, Rotation.NONE, Rotation.NONE),
                order.toPageSelections().stream().map(PageSelection::additionalRotation).toList());
    }

    @Test
    @DisplayName("指定した範囲だけを残せる")
    void keepsOnlyTheGivenRange() {
        PageOrder order = PageOrder.of(5);

        order.keepOnly(PageRange.of(2, 4));

        assertEquals(List.of(2, 3, 4), pageNumbersOf(order));
    }

    @Test
    @DisplayName("範囲は一覧の位置で解釈される")
    void interpretsRangeAsPositions() {
        PageOrder order = PageOrder.of(4);
        order.move(3, 0);

        // 並びは 4, 1, 2, 3。その 1 枚目から 2 枚目を残す。
        order.keepOnly(PageRange.of(1, 2));

        assertEquals(List.of(4, 1), pageNumbersOf(order));
    }

    @Test
    @DisplayName("現在の枚数に収まらない範囲は PAGE_OUT_OF_RANGE")
    void rejectsRangeBeyondCurrentSize() {
        PageOrder order = PageOrder.of(3);
        order.removeAt(0);

        assertEquals(
                ErrorCode.PAGE_OUT_OF_RANGE,
                assertThrows(PdfjigException.class, () -> order.keepOnly(PageRange.of(1, 3)))
                        .errorCode());
    }

    @Test
    @DisplayName("範囲外の位置を指定すると PAGE_OUT_OF_RANGE")
    void rejectsIndexOutOfRange() {
        PageOrder order = PageOrder.of(2);

        assertEquals(
                ErrorCode.PAGE_OUT_OF_RANGE,
                assertThrows(PdfjigException.class, () -> order.move(0, 2)).errorCode());
        assertEquals(
                ErrorCode.PAGE_OUT_OF_RANGE,
                assertThrows(PdfjigException.class, () -> order.removeAt(-1)).errorCode());
        assertEquals(
                ErrorCode.PAGE_OUT_OF_RANGE,
                assertThrows(
                                PdfjigException.class,
                                () -> order.rotateAt(5, Rotation.CLOCKWISE_90))
                        .errorCode());
    }

    @Test
    @DisplayName("並びも向きも元に戻せる")
    void resetsToOriginalState() {
        PageOrder order = PageOrder.of(3);
        order.move(0, 2);
        order.removeAt(0);
        order.rotateAt(0, Rotation.CLOCKWISE_90);

        order.reset();

        assertEquals(List.of(1, 2, 3), pageNumbersOf(order));
        assertFalse(order.modified());
    }

    @Test
    @DisplayName("公開する一覧は直接変更できない")
    void exposesReadOnlyView() {
        PageOrder order = PageOrder.of(2);

        assertThrows(
                UnsupportedOperationException.class,
                () -> order.pages().add(PageSelection.of(3)));
    }

    private static List<Integer> pageNumbersOf(PageOrder order) {
        return order.toPageSelections().stream().map(PageSelection::pageNumber).toList();
    }
}
