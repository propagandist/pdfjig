package io.github.propagandist.pdfjig.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.propagandist.pdfjig.core.ErrorCode;
import io.github.propagandist.pdfjig.core.PageRange;
import io.github.propagandist.pdfjig.core.PageSelection;
import io.github.propagandist.pdfjig.core.PdfjigException;
import io.github.propagandist.pdfjig.core.Rotation;
import java.util.ArrayList;
import java.util.List;
import javafx.collections.ListChangeListener;
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
                () -> order.pages().add(PageEntry.of(PageSelection.of(3))));
    }

    @Test
    @DisplayName("追加した文書のページは末尾に付く")
    void appendsPagesOfAddedDocument() {
        PageOrder order = PageOrder.of(2);

        order.append(1, 3);

        assertEquals(
                List.of("0:1", "0:2", "1:1", "1:2", "1:3"),
                order.toPageSelections().stream()
                        .map(page -> page.sourceIndex() + ":" + page.pageNumber())
                        .toList());
    }

    @Test
    @DisplayName("追加しただけでは変更にあたらない")
    void appendingIsNotAModification() {
        PageOrder order = PageOrder.of(2);

        order.append(1, 2);

        assertFalse(order.modified());
    }

    @Test
    @DisplayName("元に戻すと、追加した文書を含んだまま各文書の元の順に並ぶ")
    void resetKeepsAddedDocuments() {
        PageOrder order = PageOrder.of(2);
        order.append(1, 2);
        order.move(3, 0);
        order.rotateAt(0, Rotation.CLOCKWISE_90);
        order.removeAt(1);

        order.reset();

        assertEquals(
                List.of("0:1", "0:2", "1:1", "1:2"),
                order.toPageSelections().stream()
                        .map(page -> page.sourceIndex() + ":" + page.pageNumber())
                        .toList());
        assertFalse(order.modified());
    }

    @Test
    @DisplayName("公開する一覧は毎回同じインスタンスを返す")
    void exposesStableView() {
        PageOrder order = PageOrder.of(2);

        assertSame(order.pages(), order.pages());
    }

    @Test
    @DisplayName("公開する一覧に付けた変更リスナは、参照を手放しても呼ばれ続ける")
    void keepsNotifyingAfterGarbageCollection() {
        PageOrder order = PageOrder.of(3);

        List<String> seen = new ArrayList<>();
        ListChangeListener<PageEntry> listener = change -> seen.add("changed");
        order.pages().addListener(listener);

        // unmodifiableObservableList のラッパーを呼び出しごとに作っていると、
        // 誰も参照しないラッパーが回収された時点で通知が黙って止まる。
        // 画面が開いた直後だけ正しく、しばらくすると更新されなくなる壊れ方をした。
        System.gc();
        System.gc();

        order.rotateAt(0, Rotation.CLOCKWISE_90);

        assertEquals(List.of("changed"), seen);
    }

    @Test
    @DisplayName("区切りはページに付いて一緒に動く")
    void breakFollowsThePage() {
        PageOrder order = PageOrder.of(4);
        order.toggleBreakAt(2);

        // 区切りのあるページ（3 枚目）を 2 枚目へ動かす。
        order.move(2, 1);

        assertTrue(order.hasBreakAt(1));
        assertFalse(order.hasBreakAt(2));
        assertEquals(List.of(1, 3, 2, 4), pageNumbersOf(order));
    }

    @Test
    @DisplayName("先頭には区切りを付けられない")
    void refusesBreakAtTheFront() {
        PageOrder order = PageOrder.of(3);

        order.toggleBreakAt(0);

        assertFalse(order.hasBreakAt(0));
        assertEquals(0, order.breakCount());
    }

    @Test
    @DisplayName("区切りのあるページが先頭へ来ると、その区切りは効かない")
    void ignoresBreakThatMovedToTheFront() {
        PageOrder order = PageOrder.of(3);
        order.toggleBreakAt(2);

        order.move(2, 0);

        assertEquals(0, order.breakCount());
        assertEquals(1, order.segmentCount());
    }

    @Test
    @DisplayName("区切りどおりに切り分ける")
    void splitsAtBreaks() {
        PageOrder order = PageOrder.of(5);
        order.toggleBreakAt(1);
        order.toggleBreakAt(4);

        assertEquals(
                List.of(List.of(1), List.of(2, 3, 4), List.of(5)),
                order.toSegments().stream()
                        .map(segment -> segment.stream().map(PageSelection::pageNumber).toList())
                        .toList());
        assertEquals(3, order.segmentCount());
    }

    @Test
    @DisplayName("区切りが無ければ全体でひとかたまり")
    void keepsOneSegmentWithoutBreaks() {
        PageOrder order = PageOrder.of(3);

        assertEquals(1, order.toSegments().size());
        assertEquals(List.of(1, 2, 3),
                order.toSegments().get(0).stream().map(PageSelection::pageNumber).toList());
    }

    @Test
    @DisplayName("枚数ごとに区切り直すと、前の区切りは残らない")
    void replacesBreaksWhenApplyingEveryNPages() {
        PageOrder order = PageOrder.of(6);
        order.toggleBreakAt(1);

        order.applyEveryNPages(3);

        assertFalse(order.hasBreakAt(1));
        assertTrue(order.hasBreakAt(3));
        assertEquals(2, order.segmentCount());
    }

    @Test
    @DisplayName("区切りをすべて外せる")
    void clearsBreaks() {
        PageOrder order = PageOrder.of(4);
        order.applyEveryNPages(2);

        order.clearBreaks();

        assertEquals(0, order.breakCount());
    }

    @Test
    @DisplayName("区切りは変更にあたらないが、元に戻すと外れる")
    void breaksAreNotAModificationButResetClearsThem() {
        PageOrder order = PageOrder.of(3);
        order.toggleBreakAt(1);

        // 書き出す内容は変わらない。付けただけで「未保存の変更」と出るのは筋が違う。
        assertFalse(order.modified());

        order.reset();

        assertEquals(0, order.breakCount());
    }

    @Test
    @DisplayName("出どころごと取り除くと、後ろの出どころ番号が繰り下がる")
    void removesSourceAndRenumbers() {
        PageOrder order = PageOrder.of(2);
        order.append(1, 2);
        order.append(2, 1);

        order.removeSource(1);

        assertEquals(
                List.of("0:1", "0:2", "1:1"),
                order.toPageSelections().stream()
                        .map(page -> page.sourceIndex() + ":" + page.pageNumber())
                        .toList());
    }

    @Test
    @DisplayName("取り除いた出どころは元に戻しても復活しない")
    void keepsRemovedSourceOutOfTheBaseline() {
        PageOrder order = PageOrder.of(2);
        order.append(1, 2);
        order.removeSource(1);

        order.reset();

        assertEquals(List.of("0:1", "0:2"),
                order.toPageSelections().stream()
                        .map(page -> page.sourceIndex() + ":" + page.pageNumber())
                        .toList());
        assertFalse(order.modified());
    }

    @Test
    @DisplayName("空になる出どころの取り除きは EMPTY_RESULT。並びは変わらない")
    void refusesRemovingTheLastSource() {
        PageOrder order = PageOrder.of(2);

        assertEquals(
                ErrorCode.EMPTY_RESULT,
                assertThrows(PdfjigException.class, () -> order.removeSource(0)).errorCode());
        assertEquals(List.of(1, 2), pageNumbersOf(order));
    }

    private static List<Integer> pageNumbersOf(PageOrder order) {
        return order.toPageSelections().stream().map(PageSelection::pageNumber).toList();
    }
}
