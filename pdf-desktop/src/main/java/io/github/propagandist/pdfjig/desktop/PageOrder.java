package io.github.propagandist.pdfjig.desktop;

import io.github.propagandist.pdfjig.core.ErrorCode;
import io.github.propagandist.pdfjig.core.PdfjigException;
import java.util.List;
import java.util.stream.IntStream;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

/**
 * 編集中のページ並び。
 *
 * <p>並べ替えと削除はこの一覧の上でだけ起きる。<b>ファイルには一切触れない。</b>
 * 「保存」で初めて元ページ番号の列として pdf-core に渡る（HANDOVER.md 3-2）。
 *
 * <p>JavaFX Application Thread からのみ操作すること。
 */
public final class PageOrder {

    /** 元文書のページ数。並びを戻すときの基準になる。 */
    private final int sourcePageCount;

    /** 表示順に並んだ元ページ番号（1 始まり）。 */
    private final ObservableList<Integer> pages;

    private PageOrder(int sourcePageCount) {
        this.sourcePageCount = sourcePageCount;
        this.pages = FXCollections.observableArrayList(identityOrder(sourcePageCount));
    }

    /**
     * 元の並びのまま作る。
     *
     * @param sourcePageCount 元文書のページ数
     * @return 手つかずの並び
     */
    public static PageOrder of(int sourcePageCount) {
        if (sourcePageCount < 1) {
            throw new PdfjigException(ErrorCode.EMPTY_RESULT);
        }
        return new PageOrder(sourcePageCount);
    }

    /**
     * 表示順に並んだ元ページ番号。
     *
     * <p>UI にそのまま束ねられる読み取り専用ビューであり、この一覧への変更は
     * {@link #move} と {@link #removeAt} を通してのみ行う。
     *
     * @return 変更できないビュー
     */
    public ObservableList<Integer> pages() {
        return FXCollections.unmodifiableObservableList(pages);
    }

    /** 現在の枚数。 */
    public int size() {
        return pages.size();
    }

    /**
     * 保存時に pdf-core へ渡すページ列。
     *
     * @return 表示順の元ページ番号
     */
    public List<Integer> toPageNumbers() {
        return List.copyOf(pages);
    }

    /**
     * ページを別の位置へ動かす。
     *
     * <p>{@code fromIndex} の要素を取り出したうえで、残った並びの {@code toIndex} の
     * 位置へ差し込む。取り出す前の添字ではないことに注意。
     *
     * @param fromIndex 動かすページの現在位置（0 始まり）
     * @param toIndex   差し込む位置（0 始まり）
     */
    public void move(int fromIndex, int toIndex) {
        requireIndex(fromIndex, pages.size());
        requireIndex(toIndex, pages.size());
        if (fromIndex == toIndex) {
            return;
        }
        pages.add(toIndex, pages.remove(fromIndex));
    }

    /**
     * ページを一覧から取り除く。
     *
     * <p>最後の 1 枚は取り除けない。ページのない PDF は保存できないため、
     * 空にできてしまうと保存の瞬間まで失敗が分からない。
     *
     * @param index 取り除くページの位置（0 始まり）
     * @throws PdfjigException 残り 1 枚の場合は {@link ErrorCode#EMPTY_RESULT}
     */
    public void removeAt(int index) {
        requireIndex(index, pages.size());
        if (pages.size() == 1) {
            throw new PdfjigException(ErrorCode.EMPTY_RESULT);
        }
        pages.remove(index);
    }

    /** 元の並びから変わっているか。 */
    public boolean modified() {
        return !pages.equals(identityOrder(sourcePageCount));
    }

    /** 元の並びに戻す。 */
    public void reset() {
        pages.setAll(identityOrder(sourcePageCount));
    }

    private static List<Integer> identityOrder(int pageCount) {
        return IntStream.rangeClosed(1, pageCount).boxed().toList();
    }

    private static void requireIndex(int index, int size) {
        if (index < 0 || index >= size) {
            throw new PdfjigException(ErrorCode.PAGE_OUT_OF_RANGE);
        }
    }
}
