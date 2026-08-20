package io.github.propagandist.pdfjig.core;

/**
 * 出力に含める 1 ページの指定。
 *
 * <p>並べ替え・削除・回転は、どれも「どの文書の、どのページを、どの順で、どの向きで出すか」に
 * 帰着する。UI 上でそれらを続けて行った結果を、一度の書き出しで確定させるための型である。
 *
 * <p>出どころを持つのは、1 回の書き出しに複数のファイルのページを混ぜられるようにするため
 * （SPEC.md §7.1）。1 ファイルだけを扱う場合は {@code sourceIndex} は 0 のままでよい。
 *
 * @param sourceIndex        出どころ。{@link PageOperations#assemble(java.util.List,
 *                           java.util.List, java.nio.file.Path)} に渡す入力一覧の添字（0 始まり）
 * @param pageNumber         その文書の中でのページ番号（1 始まり）
 * @param additionalRotation 元の回転角に <b>加える</b> 回転。絶対角ではない
 */
public record PageSelection(int sourceIndex, int pageNumber, Rotation additionalRotation) {

    public PageSelection {
        if (sourceIndex < 0) {
            throw new PdfjigException(ErrorCode.PAGE_OUT_OF_RANGE);
        }
        if (pageNumber < 1) {
            throw new PdfjigException(ErrorCode.PAGE_OUT_OF_RANGE);
        }
        if (additionalRotation == null) {
            throw new IllegalArgumentException("additionalRotation は null にできません。");
        }
    }

    /**
     * 最初の入力のページを、向きを変えずに指定する。
     *
     * @param pageNumber ページ番号（1 始まり）
     * @return 回転を加えない指定
     */
    public static PageSelection of(int pageNumber) {
        return of(0, pageNumber);
    }

    /**
     * 出どころを指定して、向きを変えずにページを指定する。
     *
     * @param sourceIndex 入力一覧の添字（0 始まり）
     * @param pageNumber  その文書の中でのページ番号（1 始まり）
     * @return 回転を加えない指定
     */
    public static PageSelection of(int sourceIndex, int pageNumber) {
        return new PageSelection(sourceIndex, pageNumber, Rotation.NONE);
    }

    /**
     * さらに回転を加えた指定を返す。出どころとページ番号は変わらない。
     *
     * @param additional 加える回転
     * @return 新しい指定
     */
    public PageSelection rotatedBy(Rotation additional) {
        return new PageSelection(sourceIndex, pageNumber, additionalRotation.plus(additional));
    }

    /** 元の向きから変わっているか。 */
    public boolean rotated() {
        return additionalRotation != Rotation.NONE;
    }
}
