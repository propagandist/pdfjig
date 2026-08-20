package io.github.propagandist.pdfjig.desktop;

import io.github.propagandist.pdfjig.core.PdfDocument;
import io.github.propagandist.pdfjig.core.PdfjigException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 1 回の編集の間の状態。
 *
 * <p>開いた文書、あとから足した文書、編集中のページ並び、サムネイルの供給元をひとまとめにする。
 * 別の文書を開くときは、これを閉じてから新しく作る。
 *
 * <p><b>1 つのセッションが複数のファイルのページを持てる</b>（SPEC.md §7.1）。
 * 「PDF を追加」で足したページは並びの末尾に付き、以後は元からあったページと区別なく
 * 並べ替え・回転・削除ができる。書き出しは {@link #paths()} を入力一覧として
 * 一度の {@code assemble} で行う。
 */
public final class DocumentSession implements AutoCloseable {

    /**
     * サムネイルを描く長辺の画素数。固定サイズの一覧にする（SPEC.md §7.1）。
     *
     * <p>画面に出す長辺（{@link ThumbnailTile#IMAGE_EDGE}）より大きく取ってある。
     * 表示倍率 150% の環境では論理 150px が物理 225px になり、等倍で描くと眠い絵になるため。
     */
    static final int THUMBNAIL_EDGE_PIXELS = 220;

    /** 出どころのファイル。並びの添字が {@code sourceIndex} になる。 */
    private final List<Path> paths = new ArrayList<>();

    private final List<PdfDocument> documents = new ArrayList<>();

    private final PageOrder order;

    private final ThumbnailSource thumbnails = new ThumbnailSource(THUMBNAIL_EDGE_PIXELS);

    private DocumentSession(Path path, PdfDocument document) {
        this.order = PageOrder.of(document.pageCount());
        register(path, document);
    }

    /**
     * パスワードなしで開く。
     *
     * @param path 対象ファイル
     * @return 開かれたセッション
     */
    public static DocumentSession open(Path path) {
        return wrap(path, PdfDocument.open(path));
    }

    /**
     * パスワード付きで開く。
     *
     * <p>{@code password} は {@link PdfDocument#open(Path, char[])} の規約どおり、
     * 成否によらずゼロ埋めされる。
     *
     * @param path     対象ファイル
     * @param password パスワード
     * @return 開かれたセッション
     */
    public static DocumentSession open(Path path, char[] password) {
        return wrap(path, PdfDocument.open(path, password));
    }

    /**
     * 文書を足す。ページは並びの末尾に付く。
     *
     * @param path 足すファイル
     */
    public void add(Path path) {
        adopt(path, PdfDocument.open(path));
    }

    /**
     * パスワード付きの文書を足す。
     *
     * @param path     足すファイル
     * @param password パスワード。{@link PdfDocument#open(Path, char[])} がゼロ埋めする
     */
    public void add(Path path, char[] password) {
        adopt(path, PdfDocument.open(path, password));
    }

    /** 最初に開いたファイル。表題と保存名の既定に使う。 */
    public Path path() {
        return paths.get(0);
    }

    /** 出どころのファイル。書き出しのとき入力一覧としてそのまま渡す。 */
    public List<Path> paths() {
        return List.copyOf(paths);
    }

    /** 含んでいるファイルの数。 */
    public int sourceCount() {
        return paths.size();
    }

    /**
     * 出どころのファイル名。
     *
     * @param sourceIndex 出どころ番号
     * @return 拡張子を含むファイル名
     */
    public String sourceName(int sourceIndex) {
        return paths.get(sourceIndex).getFileName().toString();
    }

    /** 編集中のページ並び。 */
    public PageOrder order() {
        return order;
    }

    /** サムネイルの供給元。 */
    public ThumbnailSource thumbnails() {
        return thumbnails;
    }

    /** 含んでいる全ファイルのページ数の合計。編集中の枚数とは異なりうる。 */
    public int sourcePageCount() {
        return documents.stream().mapToInt(PdfDocument::pageCount).sum();
    }

    /** 含んでいるファイルのいずれかが暗号化されているか。 */
    public boolean encrypted() {
        return documents.stream().anyMatch(PdfDocument::encrypted);
    }

    @Override
    public void close() {
        // 描画が文書を触っている間に閉じると壊れる。必ずこの順で閉じる。
        thumbnails.close();

        // 1 つ閉じ損ねても残りは閉じる。開いたままの文書を残すほうが害が大きい。
        PdfjigException failure = null;
        for (PdfDocument document : documents) {
            try {
                document.close();
            } catch (PdfjigException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    /** 開いた文書を受け持ち、そのページを並びの末尾に足す。 */
    private void adopt(Path path, PdfDocument document) {
        int sourceIndex;
        try {
            sourceIndex = register(path, document);
        } catch (RuntimeException e) {
            document.close();
            throw e;
        }
        order.append(sourceIndex, document.pageCount());
    }

    private int register(Path path, PdfDocument document) {
        int sourceIndex = thumbnails.addSource(document);
        paths.add(path);
        documents.add(document);
        return sourceIndex;
    }

    private static DocumentSession wrap(Path path, PdfDocument document) {
        try {
            return new DocumentSession(path, document);
        } catch (RuntimeException e) {
            document.close();
            throw e;
        }
    }
}
