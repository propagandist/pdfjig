package io.github.propagandist.pdfjig.core;

/**
 * 1 ページ分の抽出テキスト。
 *
 * @param pageNumber ページ番号（1 始まり）
 * @param text       抽出されたテキスト
 */
public record PageText(int pageNumber, String text) {

    public PageText {
        if (pageNumber < 1) {
            throw new PdfjigException(ErrorCode.PAGE_OUT_OF_RANGE);
        }
        if (text == null) {
            throw new IllegalArgumentException("text は null にできません。");
        }
    }
}
