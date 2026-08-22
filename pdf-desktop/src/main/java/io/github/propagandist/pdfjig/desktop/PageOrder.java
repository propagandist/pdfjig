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
 * <p>並べ替え・削除・回転・区切りはこの一覧の上でだけ起きる。<b>ファイルには一切触れない。</b>
 * 「保存」で初めてページ列として pdf-core に渡る（HANDOVER.md 3-2）。
 *
 * <p>三つの操作をひとつの状態にまとめているのは、別々に適用すると
 * 「並べ替えた状態で回転したら並べ替えが消えた文書が出てくる」ことになるためである。
 *
 * <p>区切りは分割のための印であり、保存では無視される。詳細は {@link PageEntry}。
 *
 * <p>JavaFX Application Thread からのみ操作すること。
 */
public final class PageOrder {

    /**
     * 並びを戻すときの基準。
     *
     * <p>文書を追加するたびに伸びる。ページ数ひとつでは表せないのは、
     * どの出どころの何ページ目が並んでいたかまで戻す必要があるため。
     * 区切りは含まない（開いた直後は区切りが無い）。
     */
    private final List<PageSelection> baseline = new ArrayList<>();

    /** 表示順に並んだページ。 */
    private final ObservableList<PageEntry> pages;

    /**
     * {@link #pages()} が返す読み取り専用ビュー。
     *
     * <p><b>使い回すこと。呼ばれるたびに作ってはならない。</b>
     * {@code unmodifiableObservableList} が返すラッパーは元の一覧を弱参照で監視する。
     * 呼び出しごとに作ると、呼び出し側がラッパーへの参照を持たない限り GC で回収され、
     * そこに登録した変更リスナが黙って呼ばれなくなる。
     * 画面は開いた直後だけ正しく、しばらくすると更新が止まる、という追いにくい壊れ方をする。
     */
    private final ObservableList<PageEntry> view;

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
     * 表示順に並んだページ。
     *
     * <p>UI にそのまま束ねられる読み取り専用ビューであり、変更は
     * {@link #move} / {@link #removeAt} / {@link #rotateAt} を通してのみ行う。
     *
     * <p>常に同じインスタンスを返す。呼び出しごとに作らない理由は {@code view} の説明にある。
     *
     * @return 変更できないビュー
     */
    public ObservableList<PageEntry> pages() {
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
        pages.addAll(added.stream().map(PageEntry::of).toList());
    }

    /**
     * 出どころごと取り除く。
     *
     * <p>その文書のページを並びから消し、<b>それより後ろの出どころ番号を 1 つ繰り下げる。</b>
     * 番号は書き出しに渡す入力一覧の添字であり、詰めないと別のファイルを指すことになる。
     *
     * <p>取り除くとページが 1 枚も残らない場合は失敗させ、並びは変えない。
     *
     * @param sourceIndex 取り除く出どころ番号
     * @throws PdfjigException 残りが空になる場合は {@link ErrorCode#EMPTY_RESULT}
     */
    public void removeSource(int sourceIndex) {
        List<PageEntry> remaining = pages.stream()
                .filter(entry -> entry.selection().sourceIndex() != sourceIndex)
                .map(entry -> entry.withSelection(shifted(entry.selection(), sourceIndex)))
                .toList();
        if (remaining.isEmpty()) {
            throw new PdfjigException(ErrorCode.EMPTY_RESULT);
        }

        List<PageSelection> remainingBaseline = baseline.stream()
                .filter(selection -> selection.sourceIndex() != sourceIndex)
                .map(selection -> shifted(selection, sourceIndex))
                .toList();

        baseline.clear();
        baseline.addAll(remainingBaseline);
        pages.setAll(remaining);
    }

    /** 現在の枚数。 */
    public int size() {
        return pages.size();
    }

    /**
     * 保存時に pdf-core へ渡すページ列。区切りは含まない。
     *
     * @return 表示順のページ指定
     */
    public List<PageSelection> toPageSelections() {
        return pages.stream().map(PageEntry::selection).toList();
    }

    /**
     * 区切りで切り分けたページ列。分割で使う。
     *
     * <p>区切りが 1 つも無ければ、全体がひとかたまりになる。
     *
     * @return かたまりごとのページ指定
     */
    public List<List<PageSelection>> toSegments() {
        List<List<PageSelection>> segments = new ArrayList<>();
        List<PageSelection> current = new ArrayList<>();

        for (int index = 0; index < pages.size(); index++) {
            PageEntry entry = pages.get(index);
            // 先頭の区切りは無視する。先頭は区切らなくてもファイルの始まりである。
            if (index > 0 && entry.startsNewFile()) {
                segments.add(List.copyOf(current));
                current = new ArrayList<>();
            }
            current.add(entry.selection());
        }
        segments.add(List.copyOf(current));
        return List.copyOf(segments);
    }

    /**
     * 1 ページずつに切り分けたページ列。分割で使う。
     *
     * <p><b>区切りは見ない。</b>「1 ページずつ」に切れ目の判断は無く、区切りを介す意味が
     * ないためである。付けてある区切りもそのまま残る（{@link #toSegments()} と違い、
     * この操作は区切りを書き換えない）。
     *
     * <p>対象は編集中の並びであり、並べ替え・削除・回転は反映される。
     *
     * @return 1 ページだけを持つかたまりの列
     */
    public List<List<PageSelection>> toSinglePageSegments() {
        return pages.stream().map(entry -> List.of(entry.selection())).toList();
    }

    /** 区切りで分かれるファイルの数。 */
    public int segmentCount() {
        return breakCount() + 1;
    }

    /** 効いている区切りの数。先頭に付いた区切りは数えない。 */
    public int breakCount() {
        return (int) IntStream.range(1, pages.size())
                .filter(index -> pages.get(index).startsNewFile())
                .count();
    }

    /**
     * 指定した位置に区切りがあるか。先頭は常に {@code false}。
     *
     * @param index 位置（0 始まり）
     */
    public boolean hasBreakAt(int index) {
        return index > 0 && index < pages.size() && pages.get(index).startsNewFile();
    }

    /**
     * 区切りを付け外しする。
     *
     * <p>先頭には付けられない。先頭は区切らなくてもファイルの始まりであり、
     * そこに印を置けるようにすると意味のない状態が作れてしまう。
     *
     * @param index 位置（0 始まり）
     */
    public void toggleBreakAt(int index) {
        requireIndex(index);
        if (index == 0) {
            return;
        }
        PageEntry entry = pages.get(index);
        pages.set(index, entry.withBreak(!entry.startsNewFile()));
    }

    /**
     * 指定した枚数ごとに区切り直す。
     *
     * <p>既にある区切りは一度すべて外す。足すのではなく引き直すのは、
     * 前の指定が残っていると何枚ごとになっているのか読めなくなるため。
     *
     * @param pagesPerFile 1 ファイルあたりのページ数
     */
    public void applyEveryNPages(int pagesPerFile) {
        if (pagesPerFile < 1) {
            throw new PdfjigException(ErrorCode.PAGE_OUT_OF_RANGE);
        }
        pages.setAll(IntStream.range(0, pages.size())
                .mapToObj(index -> pages.get(index).withBreak(index > 0 && index % pagesPerFile == 0))
                .toList());
    }

    /** 区切りをすべて外す。 */
    public void clearBreaks() {
        pages.setAll(pages.stream().map(entry -> entry.withBreak(false)).toList());
    }

    /**
     * ページを別の位置へ動かす。
     *
     * <p>{@code fromIndex} の要素を取り出したうえで、残った並びの {@code toIndex} の
     * 位置へ差し込む。取り出す前の添字ではないことに注意。
     *
     * <p>区切りはページに付いているので一緒に動く。
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
        PageEntry entry = pages.get(index);
        pages.set(index, entry.withSelection(entry.selection().rotatedBy(additional)));
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

    /**
     * 元の並びと向きから変わっているか。
     *
     * <p>区切りは見ない。区切りは書き出す内容を変えず、付けただけで
     * 「未保存の変更があります」と出るのは筋が違う。
     */
    public boolean modified() {
        return !toPageSelections().equals(baseline);
    }

    /**
     * 開いた直後の状態に戻す。
     *
     * <p>並び・向きに加えて<b>区切りも外す</b>。追加した文書は含んだままで、
     * それぞれの元の順に並べ直す。追加そのものを取り消すときはファイル一覧から外す。
     */
    public void reset() {
        pages.setAll(baseline.stream().map(PageEntry::of).toList());
    }

    /** その出どころより後ろの番号を 1 つ繰り下げる。 */
    private static PageSelection shifted(PageSelection selection, int removedSourceIndex) {
        return selection.sourceIndex() < removedSourceIndex
                ? selection
                : new PageSelection(
                        selection.sourceIndex() - 1, selection.pageNumber(), selection.additionalRotation());
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
