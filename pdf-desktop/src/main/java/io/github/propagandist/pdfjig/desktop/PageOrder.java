package io.github.propagandist.pdfjig.desktop;

import io.github.propagandist.pdfjig.core.ErrorCode;
import io.github.propagandist.pdfjig.core.PageRange;
import io.github.propagandist.pdfjig.core.PageSelection;
import io.github.propagandist.pdfjig.core.PdfjigException;
import io.github.propagandist.pdfjig.core.Rotation;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

/**
 * 編集中のページ並び。
 *
 * <p>並べ替え・削除・回転はこの一覧の上でだけ起きる。<b>ファイルには一切触れない。</b>
 * 「保存」で初めてページ列として pdf-core に渡る（HANDOVER.md 3-2）。
 *
 * <p>三つの操作をひとつの状態にまとめているのは、別々に適用すると
 * 「並べ替えた状態で回転したら並べ替えが消えた文書が出てくる」ことになるためである。
 *
 * <p>JavaFX Application Thread からのみ操作すること。
 */
public final class PageOrder {

    /**
     * 並びを戻すときの基準。
     *
     * <p>文書を追加するたびに伸びる。ページ数ひとつでは表せないのは、
     * どの出どころの何ページ目が並んでいたかまで戻す必要があるため。
     */
    private final List<PageSelection> baseline = new ArrayList<>();

    /** 表示順に並んだページ指定。 */
    private final ObservableList<PageSelection> pages;

    /**
     * {@link #pages()} が返す読み取り専用ビュー。
     *
     * <p><b>使い回すこと。呼ばれるたびに作ってはならない。</b>
     * {@code unmodifiableObservableList} が返すラッパーは元の一覧を弱参照で監視する。
     * 呼び出しごとに作ると、呼び出し側がラッパーへの参照を持たない限り GC で回収され、
     * そこに登録した変更リスナが黙って呼ばれなくなる。
     * 画面は開いた直後だけ正しく、しばらくすると更新が止まる、という追いにくい壊れ方をする。
     */
    private final ObservableList<PageSelection> view;

    private PageOrder(int sourcePageCount) {
        this.pages = FXCollections.observableArrayList();
        this.view = FXCollections.unmodifiableObservableList(pages);
        append(0, sourcePageCount);
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
     * 表示順に並んだページ指定。
     *
     * <p>UI にそのまま束ねられる読み取り専用ビューであり、変更は
     * {@link #move} / {@link #removeAt} / {@link #rotateAt} を通してのみ行う。
     *
     * <p>常に同じインスタンスを返す。呼び出しごとに作らない理由は {@code view} の説明にある。
     *
     * @return 変更できないビュー
     */
    public ObservableList<PageSelection> pages() {
        return view;
    }

    /**
     * 追加した文書のページを、並びの末尾に足す。
     *
     * <p>差し込む位置を選ばせないのは、足した直後にサムネイル上でドラッグして
     * 動かせるためである。位置を尋ねるダイアログを挟むより、置いてから動かすほうが早い。
     *
     * <p>戻すときの基準も一緒に伸びる。{@link #reset()} は追加した文書を含んだまま、
     * それぞれの元の順に並べ直す。
     *
     * @param sourceIndex 追加した文書の出どころ番号
     * @param pageCount   その文書のページ数
     */
    public void append(int sourceIndex, int pageCount) {
        if (pageCount < 1) {
            throw new PdfjigException(ErrorCode.EMPTY_RESULT);
        }
        List<PageSelection> added = identityOrder(sourceIndex, pageCount);
        baseline.addAll(added);
        pages.addAll(added);
    }

    /** 現在の枚数。 */
    public int size() {
        return pages.size();
    }

    /**
     * 保存時に pdf-core へ渡すページ列。
     *
     * @return 表示順のページ指定
     */
    public List<PageSelection> toPageSelections() {
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
        requireIndex(fromIndex);
        requireIndex(toIndex);
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
        requireIndex(index);
        if (pages.size() == 1) {
            throw new PdfjigException(ErrorCode.EMPTY_RESULT);
        }
        pages.remove(index);
    }

    /**
     * ページに回転を加える。
     *
     * <p>指定は現在の向きへの加算である。同じページが複数回並んでいても、
     * 回すのは指定した位置のものだけになる。
     *
     * @param index      回すページの位置（0 始まり）
     * @param additional 加える回転
     */
    public void rotateAt(int index, Rotation additional) {
        requireIndex(index);
        pages.set(index, pages.get(index).rotatedBy(additional));
    }

    /**
     * 指定した範囲だけを残す。
     *
     * <p>範囲は <b>一覧の中での位置</b> であり、元文書のページ番号ではない。
     * 並べ替えた後は「今見えている 3 枚目から 5 枚目」と言えるほうが素直なため。
     *
     * @param range 残す範囲（1 始まり、両端を含む）
     * @throws PdfjigException 範囲が現在の枚数に収まらない場合
     */
    public void keepOnly(PageRange range) {
        range.validateAgainst(pages.size());
        pages.setAll(List.copyOf(pages.subList(range.firstPage() - 1, range.lastPage())));
    }

    /** 元の並びと向きから変わっているか。追加した文書があっても、それ自体は変更にあたらない。 */
    public boolean modified() {
        return !pages.equals(baseline);
    }

    /**
     * 元の並びと向きに戻す。
     *
     * <p>追加した文書は含んだまま、それぞれの元の順に並べ直す。追加そのものを取り消したい
     * ときは文書を閉じて開き直す。メニューの文言どおり、ここで戻すのは並びと向きだけである。
     */
    public void reset() {
        pages.setAll(baseline);
    }

    private static List<PageSelection> identityOrder(int sourceIndex, int pageCount) {
        return IntStream.rangeClosed(1, pageCount)
                .mapToObj(pageNumber -> PageSelection.of(sourceIndex, pageNumber))
                .toList();
    }

    private void requireIndex(int index) {
        if (index < 0 || index >= pages.size()) {
            throw new PdfjigException(ErrorCode.PAGE_OUT_OF_RANGE);
        }
    }
}
