package io.github.propagandist.pdfjig.desktop;

import io.github.propagandist.pdfjig.core.PageSelection;
import io.github.propagandist.pdfjig.core.Password;
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
     * <p>{@code password} は読むだけである（{@link PdfDocument#open(Path, Password)}）。
     * <b>消すのは作った場所である。</b>
     *
     * @param path     対象ファイル
     * @param password パスワード。ここでは消さない
     * @return 開かれたセッション
     */
    public static DocumentSession open(Path path, Password password) {
        return wrap(path, PdfDocument.open(path, password));
    }

    /**
     * 文書を足す。ページは並びの末尾に付く。
     *
     * <p><b>失敗するときは、何も変わっていない</b>（#148）。
     *
     * @param path 足すファイル
     * @throws PdfjigException ページが 1 枚も無いファイルは {@link io.github.propagandist.pdfjig.core.ErrorCode#EMPTY_DOCUMENT}
     */
    public void add(Path path) {
        adopt(path, PdfDocument.open(path));
    }

    /**
     * パスワード付きの文書を足す。失敗するときの約束は {@link #add(Path)} と同じである。
     *
     * @param path     足すファイル
     * @param password パスワード。ここでは消さない（{@link PdfDocument#open(Path, Password)}）
     */
    public void add(Path path, Password password) {
        adopt(path, PdfDocument.open(path, password));
    }

    /**
     * 文書を 1 つ外す。そのファイルのページは並びから消える。
     *
     * <p><b>失敗するときは、何も変わっていない。</b> 走っている描画を待つのも、取り除くと
     * ページが 1 枚も残らないかを見るのも、状態を触る前に済ませる。ここが崩れると、
     * 並びと {@link ThumbnailSource} の出どころ番号が食い違ったまま残る。
     *
     * <p>最初のファイルも外せる。外すと {@link #path()} は次のファイルになる。
     *
     * @param sourceIndex 外す出どころ番号
     * @throws PdfjigException 残りが空になる場合は {@link io.github.propagandist.pdfjig.core.ErrorCode#EMPTY_RESULT}。
     *                         サムネイルの描画が終わるのを待てなかった場合は
     *                         {@link io.github.propagandist.pdfjig.core.ErrorCode#THUMBNAIL_RENDERING_BUSY}
     */
    public void remove(int sourceIndex) {
        // ★ 何かを変える前に待つ。ここで投げたときは、並びも文書一覧もまだ元のままである。
        //   変えた後に待つと、待てなかったときに出どころ番号の食い違いが残る。
        thumbnails.awaitRendering();
        order.removeSource(sourceIndex);

        paths.remove(sourceIndex);
        PdfDocument removed = documents.remove(sourceIndex);
        // 描画が文書を触っている間に閉じると壊れる。removeSource も走っている描画を待つので、
        // この順で閉じてよい（ThumbnailSource の契約）。上で待っているため、ここでは待たされない。
        thumbnails.removeSource(sourceIndex);
        // ★★ 閉じる失敗は飲んで記録する（#148）。ここまでで外すことは済んでいる——投げると、
        //   一覧からは消えているのに「ファイルの読み書きに失敗しました」が出る（優先順位 2）。
        //   どのみち捨てる文書であり、利用者にできることは無い。
        try {
            removed.close();
        } catch (PdfjigException e) {
            Logs.warn(LogEvent.DOCUMENT_NOT_CLOSED, e);
        }
    }

    /** 最初に開いたファイル。表題と保存名の既定に使う。 */
    public Path path() {
        return paths.get(0);
    }

    /**
     * 最初に開いたファイルの、拡張子を除いた名前。
     *
     * <p>保存名の既定と、画面に出す名前に使う。<b>書き出すファイルの連番の付け方は
     * ここに無い</b>——それは {@code pdf-core} の
     * {@link io.github.propagandist.pdfjig.core.PageOperations#assembleEach} が持つ。
     */
    public String baseName() {
        String fileName = path().getFileName().toString();
        int extension = fileName.lastIndexOf('.');
        return extension > 0 ? fileName.substring(0, extension) : fileName;
    }

    /**
     * 出どころのファイル。
     *
     * <p><b>★ これだけでは書き出せない。</b>鍵の要る出どころがあれば
     * {@code Sources} へ組み直す必要がある（{@link #keyed}。#193）。
     */
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

    /**
     * その出どころは、鍵を渡して開いたか。
     *
     * <p><b>書き出すときも同じ鍵が要る</b>——{@code pdf-core} は書き出しの都合で
     * <b>同じ入力を開き直す</b>ので、鍵なしでは {@code PASSWORD_REQUIRED} で落ちる（#193）。
     *
     * <p><b>★★ ここは覚えない。開いた文書に訊く</b>（{@link PdfDocument#openedWithPassword()}）。
     * <b>並びをもう 1 本増やすと、出どころを外すたびに 3 本を同じだけずらすことになる</b>
     * ——1 本でも書き忘れると、<b>別のファイルの鍵を訊く形で静かに壊れる。</b>
     *
     * <p><b>★ 鍵そのものは誰も持たない</b>（#193）。抱えると<b>文書を開いている間ずっと
     * 平文の鍵がヒープに残る</b>——{@code docs/RELEASE_NOTES.md}
     * 「パスワードが手元のメモリに平文で残っていた」は<b>まさにその形</b>であり、
     * <b>今度は意図して作ることになる。</b>
     *
     * @param sourceIndex 出どころ番号
     * @return 鍵を渡して開いたなら {@code true}
     */
    public boolean keyed(int sourceIndex) {
        return documents.get(sourceIndex).openedWithPassword();
    }

    /**
     * 出力に寄与する出どころのうち、鍵を渡して開いたもののファイル名。
     *
     * <p><b>★★ 「出力に寄与する入力が保護されているか」を答える</b>
     * （{@code docs/SPEC.md} §4.3.1。#29）。<b>開いているものが保護されているか、ではない</b>
     * ——暗号化された文書を足してから<b>そのページを全部消して保存すると、
     * 出力に 1 バイトも入らないのに問われる。</b>中身と食い違う窓は、
     * <b>次に本物を扱ったときに読まずに押される</b>（優先順位 2）。
     * {@code PdfBoxPageOperations#warnAboutContributing} が既に同じ粗さを却下している。
     *
     * <p><b>★★ 見るのは「鍵を渡して開いたか」であって「暗号化されているか」ではない</b>（同）。
     * <b>オーナーパスワードだけが掛かった文書は、鍵を打たずに開けている</b>——
     * <b>そこで書き出しを止める窓を出すと、本物の機密文書に当たる前に
     * 「読まずに続行を押す」習慣ができる。</b>
     *
     * <p><b>★ 画面がここで答えを出せるので、{@code pdf-core} には問い返す口が無い</b>（#29）。
     * <b>寄与する出どころは {@link PageSelection#sourceIndex()} そのもの</b>であり、
     * <b>開き直さずに済む。</b>
     *
     * @param pages 出力に含めるページ
     * @return 保護が落ちる出どころのファイル名。出どころ番号の順
     */
    public List<String> keyedContributors(List<PageSelection> pages) {
        return pages.stream()
                .map(PageSelection::sourceIndex)
                .distinct()
                .sorted()
                .filter(this::keyed)
                .map(this::sourceName)
                .toList();
    }

    /** 含んでいるファイルのいずれかが暗号化されているか。 */
    public boolean encrypted() {
        return documents.stream().anyMatch(PdfDocument::encrypted);
    }

    /**
     * 含んでいるファイルのいずれかに電子署名があるか。
     *
     * <p>署名の正当性は見ない。在ることだけが分かればよい。書き出せば必ず無効になる。
     */
    public boolean signed() {
        return documents.stream().anyMatch(PdfDocument::signed);
    }

    @Override
    public void close() {
        // 描画が文書を触っている間に閉じると壊れる。必ずこの順で閉じる。
        // ★★ ただし描画の後始末が投げても、文書は閉じる（#148）。閉じ損ねると、Windows では
        //   開いた PDF の手が握られたまま残り、利用者は同じファイルを消せない・別のアプリで開けない。
        //   ★ そのとき描画がまだ文書を触っていれば、その 1 枚の描画が失敗する。手を握り続けるより害が小さい。
        //   ★ 両方が投げたら、両方を残す（先に起きたほうを投げ、後を添える）。
        RuntimeException failure = null;
        try {
            thumbnails.close();
        } catch (RuntimeException e) {
            failure = e;
        }
        try {
            closeDocuments();
        } catch (RuntimeException e) {
            if (failure == null) {
                failure = e;
            } else {
                failure.addSuppressed(e);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    /** 1 つ閉じ損ねても残りは閉じる。開いたままの文書を残すほうが害が大きい。 */
    private void closeDocuments() {
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

    /**
     * 開いた文書を受け持ち、そのページを並びの末尾に足す。
     *
     * <p><b>★★ 入れる前に検める</b>（#148）。0 ページの文書を登録してから並びへ足すと、
     * {@link PageOrder#append} が投げたときに<b>一覧・文書・サムネイルには入っているのに
     * 並びには 1 件も無い</b>まま残り、文書も閉じられない。検めてから入れれば、戻すものが無い
     * ——{@link #remove} が「何かを変える前に待つ」のと同じ手である。
     * <b>最初に開く経路（{@link #wrap}）は同じ入力を弾いて閉じている。</b>足す側だけが非対称だった。
     */
    private void adopt(Path path, PdfDocument document) {
        int sourceIndex;
        try {
            PageOrder.requirePages(document.pageCount());
            sourceIndex = register(path, document);
        } catch (RuntimeException e) {
            closeAfter(document, e);
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
            closeAfter(document, e);
            throw e;
        }
    }

    /**
     * 受け入れ損ねた文書を閉じる。<b>閉じる失敗は元の失敗に添えて、上書きしない</b>（#148 の門）
     * ——上書きすると、空の PDF を足した利用者が「ファイルの読み書きに失敗しました」を見る。
     */
    private static void closeAfter(PdfDocument document, RuntimeException failed) {
        try {
            document.close();
        } catch (PdfjigException e) {
            failed.addSuppressed(e);
        }
    }
}
