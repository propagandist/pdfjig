package io.github.propagandist.pdfjig.desktop;

import io.github.propagandist.pdfjig.core.PdfDocument;
import java.nio.file.Path;

/**
 * 1 つの文書を開いている間の状態。
 *
 * <p>開いた文書、編集中のページ並び、サムネイルの供給元をひとまとめにする。
 * 別の文書を開くときは、これを閉じてから新しく作る。
 */
public final class DocumentSession implements AutoCloseable {

    /** サムネイルの長辺。固定サイズの一覧にする（SPEC.md §7.1）。 */
    static final int THUMBNAIL_EDGE_PIXELS = 160;

    private final Path path;

    private final PdfDocument document;

    private final PageOrder order;

    private final ThumbnailSource thumbnails;

    private DocumentSession(Path path, PdfDocument document) {
        this.path = path;
        this.document = document;
        this.order = PageOrder.of(document.pageCount());
        this.thumbnails = new ThumbnailSource(document, THUMBNAIL_EDGE_PIXELS);
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

    /** 開いているファイル。 */
    public Path path() {
        return path;
    }

    /** 編集中のページ並び。 */
    public PageOrder order() {
        return order;
    }

    /** サムネイルの供給元。 */
    public ThumbnailSource thumbnails() {
        return thumbnails;
    }

    /** 元文書のページ数。編集中の枚数とは異なりうる。 */
    public int sourcePageCount() {
        return document.pageCount();
    }

    /** 元文書が暗号化されているか。 */
    public boolean encrypted() {
        return document.encrypted();
    }

    @Override
    public void close() {
        // 描画が文書を触っている間に閉じると壊れる。必ずこの順で閉じる。
        thumbnails.close();
        document.close();
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
