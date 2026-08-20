package io.github.propagandist.pdfjig.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.propagandist.pdfjig.core.ErrorCode;
import io.github.propagandist.pdfjig.core.PdfjigException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PageOrderTest {

    @Test
    @DisplayName("初期状態は元の並びのまま")
    void startsUnmodified() {
        PageOrder order = PageOrder.of(3);

        assertEquals(List.of(1, 2, 3), order.toPageNumbers());
        assertFalse(order.modified());
    }

    @Test
    @DisplayName("先頭のページを末尾へ動かせる")
    void movesPageToEnd() {
        PageOrder order = PageOrder.of(3);

        order.move(0, 2);

        assertEquals(List.of(2, 3, 1), order.toPageNumbers());
        assertTrue(order.modified());
    }

    @Test
    @DisplayName("末尾のページを先頭へ動かせる")
    void movesPageToFront() {
        PageOrder order = PageOrder.of(3);

        order.move(2, 0);

        assertEquals(List.of(3, 1, 2), order.toPageNumbers());
    }

    @Test
    @DisplayName("同じ位置への移動は何も変えない")
    void ignoresMoveToSameIndex() {
        PageOrder order = PageOrder.of(3);

        order.move(1, 1);

        assertEquals(List.of(1, 2, 3), order.toPageNumbers());
        assertFalse(order.modified());
    }

    @Test
    @DisplayName("ページを取り除ける")
    void removesPage() {
        PageOrder order = PageOrder.of(3);

        order.removeAt(1);

        assertEquals(List.of(1, 3), order.toPageNumbers());
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
        assertEquals(List.of(2), order.toPageNumbers());
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
    }

    @Test
    @DisplayName("元の並びに戻せる")
    void resetsToOriginalOrder() {
        PageOrder order = PageOrder.of(3);
        order.move(0, 2);
        order.removeAt(0);

        order.reset();

        assertEquals(List.of(1, 2, 3), order.toPageNumbers());
        assertFalse(order.modified());
    }

    @Test
    @DisplayName("公開する一覧は直接変更できない")
    void exposesReadOnlyView() {
        PageOrder order = PageOrder.of(2);

        assertThrows(UnsupportedOperationException.class, () -> order.pages().add(3));
    }
}
