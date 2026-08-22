package io.github.propagandist.pdfjig.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PageRangeTest {

    @Test
    @DisplayName("ページ番号は 1 始まりで、両端を含む")
    void rangeIsOneBasedAndInclusive() {
        PageRange range = PageRange.of(2, 4);

        assertEquals(3, range.pageCount());
        assertTrue(range.contains(2));
        assertTrue(range.contains(4));
        assertFalse(range.contains(1));
        assertFalse(range.contains(5));
    }

    @Test
    @DisplayName("0 始まりの添字への変換はこの型に閉じる")
    void convertsToZeroBasedIndices() {
        assertArrayEquals(new int[] {1, 2, 3}, PageRange.of(2, 4).toZeroBasedIndices());
        assertArrayEquals(new int[] {0}, PageRange.singlePage(1).toZeroBasedIndices());
    }

    @Test
    @DisplayName("0 ページ目や逆順の範囲は生成時点で弾く")
    void rejectsInvalidBounds() {
        assertEquals(
                ErrorCode.PAGE_OUT_OF_RANGE,
                assertThrows(PdfjigException.class, () -> PageRange.of(0, 3)).errorCode());
        assertEquals(
                ErrorCode.PAGE_OUT_OF_RANGE,
                assertThrows(PdfjigException.class, () -> PageRange.of(5, 3)).errorCode());
    }

    @Test
    @DisplayName("文書の総ページ数を超える範囲は検証時に弾く")
    void validatesAgainstDocumentLength() {
        PageRange range = PageRange.of(2, 10);

        range.validateAgainst(10);
        assertEquals(
                ErrorCode.PAGE_OUT_OF_RANGE,
                assertThrows(PdfjigException.class, () -> range.validateAgainst(9))
                        .errorCode());
    }
}
