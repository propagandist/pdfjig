package io.github.propagandist.pdfjig.core;

import java.util.stream.IntStream;

/**
 * ページ範囲。両端を含む。
 *
 * <p><b>ページ番号は 1 始まりである。</b> 利用者が UI・CLI で目にする番号と一致させるため、
 * 境界のずれを型の外に出さない。PDFBox が要求する 0 始まりの添字への変換は
 * {@link #toZeroBasedIndices()} に閉じ込める。
 *
 * @param firstPage 開始ページ（1 始まり、この値を含む）
 * @param lastPage  終了ページ（1 始まり、この値を含む）
 */
public record PageRange(int firstPage, int lastPage) {

    public PageRange {
        if (firstPage < 1 || lastPage < firstPage) {
            throw new PdfjigException(ErrorCode.PAGE_OUT_OF_RANGE);
        }
    }

    public static PageRange of(int firstPage, int lastPage) {
        return new PageRange(firstPage, lastPage);
    }

    public static PageRange singlePage(int page) {
        return new PageRange(page, page);
    }

    public int pageCount() {
        return lastPage - firstPage + 1;
    }

    public boolean contains(int page) {
        return page >= firstPage && page <= lastPage;
    }

    /**
     * この範囲が {@code totalPages} ページの文書に収まることを検証する。
     *
     * @param totalPages 対象文書の総ページ数
     * @throws PdfjigException 範囲外の場合
     */
    public void validateAgainst(int totalPages) {
        if (lastPage > totalPages) {
            throw new PdfjigException(ErrorCode.PAGE_OUT_OF_RANGE);
        }
    }

    /**
     * PDFBox 等が要求する 0 始まりの添字列に変換する。
     *
     * @return 昇順の添字配列
     */
    public int[] toZeroBasedIndices() {
        return IntStream.rangeClosed(firstPage, lastPage).map(p -> p - 1).toArray();
    }
}
