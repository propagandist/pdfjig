package io.github.propagandist.pdfjig.core;

/**
 * 出力に含める 1 ページの指定。
 *
 * <p>並べ替え・削除・回転は、どれも「どのページを、どの順で、どの向きで出すか」に
 * 帰着する。UI 上でそれらを続けて行った結果を、一度の書き出しで確定させるための型である。
 *
 * @param pageNumber         元文書のページ番号（1 始まり）
 * @param additionalRotation 元の回転角に <b>加える</b> 回転。絶対角ではない
 */
public record PageSelection(int pageNumber, Rotation additionalRotation) {

    public PageSelection {
        if (pageNumber < 1) {
            throw new PdfjigException(ErrorCode.PAGE_OUT_OF_RANGE);
        }
        if (additionalRotation == null) {
            throw new IllegalArgumentException("additionalRotation は null にできません。");
        }
    }

    /**
     * 向きを変えずにページを指定する。
     *
     * @param pageNumber 元文書のページ番号（1 始まり）
     * @return 回転を加えない指定
     */
    public static PageSelection of(int pageNumber) {
        return new PageSelection(pageNumber, Rotation.NONE);
    }

    /**
     * さらに回転を加えた指定を返す。
     *
     * @param additional 加える回転
     * @return 新しい指定
     */
    public PageSelection rotatedBy(Rotation additional) {
        return new PageSelection(pageNumber, additionalRotation.plus(additional));
    }

    /** 元の向きから変わっているか。 */
    public boolean rotated() {
        return additionalRotation != Rotation.NONE;
    }
}
