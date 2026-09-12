package io.github.propagandist.pdfjig.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;

/**
 * 開かれた PDF 文書のハンドル。
 *
 * <p>必ず try-with-resources で扱うこと。
 *
 * <p>PDFBox の {@link PDDocument} はこのクラスの外に漏らさない。
 * 取得手段はパッケージプライベートの {@link #delegate()} のみであり、
 * 他モジュールから PDFBox の型に触れる経路は存在しない。
 */
public final class PdfDocument implements AutoCloseable {

    private final PDDocument delegate;

    private PdfDocument(PDDocument delegate) {
        this.delegate = delegate;
    }

    /**
     * パスワードなしで開く。
     *
     * @param path 入力ファイル
     * @return 開かれた文書
     * @throws PdfjigException 開けない場合。暗号化されている場合は
     *                         {@link ErrorCode#PASSWORD_REQUIRED}、
     *                         読めない場合は {@link ErrorCode#FILE_NOT_FOUND}。
     *                         それ以外はすべて {@link ErrorCode#NOT_A_PDF} になる——
     *                         <b>PDF でないこととは限らない。</b>原因を絞る材料が無い
     */
    public static PdfDocument open(Path path) {
        requireReadable(path);
        try {
            return new PdfDocument(Loader.loadPDF(path.toFile()));
        } catch (InvalidPasswordException e) {
            throw PdfjigException.wrapping(ErrorCode.PASSWORD_REQUIRED, e);
        } catch (IOException | RuntimeException e) {
            // ★ PDFBox は IOException ではない例外も投げる（#144）。ここでは秘密を持たないので
            //   INV-5 には当たらないが、包むのは「外へ出るのは PdfjigException だけ」という
            //   契約のためである——包まないと、呼ぶ側の分岐がどれも当たらない。
            //   ★ 分類を細かくする材料がここには無い。開こうとして駄目だった、しか分からない。
            throw PdfjigException.wrapping(ErrorCode.NOT_A_PDF, e);
        }
    }

    /**
     * パスワード付きで開く。
     *
     * <p><b>★★ 渡された {@code password} は読むだけである。</b>ゼロ埋めはしない——
     * <b>消すのは作った場所である</b>（{@link Password}）。ここで消すと、
     * <b>片づけが 2 か所になり、どちらが持ち主かを註でしか書けなくなる。</b>
     *
     * <p><b>既知の限界:</b> PDFBox 3 の {@code Loader.loadPDF} は {@code String} しか受け付けない。
     * そのため境界で一度だけ {@code String} が生成され、これは GC されるまでヒープに残り、
     * 明示的なゼロ埋めができない。pdfjig 側でこれを回避する手段はない。
     * 生成箇所をこの 1 か所に限定することで影響範囲を最小化している。
     *
     * @param path     入力ファイル
     * @param password パスワード。ここでは消さない
     * @return 開かれた文書
     * @throws PdfjigException 開けない場合。パスワード誤りは
     *                         {@link ErrorCode#INVALID_PASSWORD}、
     *                         読めない場合は {@link ErrorCode#FILE_NOT_FOUND}、
     *                         PDF として読めない場合は {@link ErrorCode#NOT_A_PDF}、
     *                         原因を絞れない場合は {@link ErrorCode#PASSWORD_OR_DOCUMENT_FAILURE}
     */
    public static PdfDocument open(Path path, Password password) {
        try {
            requireReadable(path);
            // INV-5 の境界。PDFBox の API 制約により String 化は避けられない。
            String boundaryPassword = new String(password.value());
            return new PdfDocument(Loader.loadPDF(path.toFile(), boundaryPassword));
        } catch (InvalidPasswordException e) {
            throw PdfjigException.wrapping(ErrorCode.INVALID_PASSWORD, e);
        } catch (IOException e) {
            throw PdfjigException.wrapping(ErrorCode.NOT_A_PDF, e);
        } catch (RuntimeException e) {
            // ★★ INV-5。PDFBox は AES-256 のとき、照合する前に SASLprep を通す。禁止文字に
            //   当たると、本物のパスワードの文字と位置をメッセージに載せた
            //   IllegalArgumentException を投げる——IOException ではないので、上の 2 つに
            //   当たらない。wrapping は型名しか残さないので、ここで包めば漏れない（#144）。
            //   ★ どちらが原因かは、呼んだ側からは区別が付かない。文書かもしれず、
            //   パスワードかもしれない。分からないことを分からないまま伝える。
            throw PdfjigException.wrapping(ErrorCode.PASSWORD_OR_DOCUMENT_FAILURE, e);
        }
    }

    /**
     * 総ページ数。
     *
     * <p><b>★ 符号は {@link ErrorCode#NOT_A_PDF} である</b>——<b>開けたのだから PDF では
     * あったが、読もうとしたところが壊れていた。</b>{@link #open(Path)} の
     * 「PDF でないこととは限らない」と同じ扱いであり、<b>原因を絞る材料がここにも無い。</b>
     * 下の {@link #encrypted()} と {@link #signed()} も同じである。
     *
     * @throws PdfjigException 数えられない場合は {@link ErrorCode#NOT_A_PDF}
     */
    public int pageCount() {
        try {
            return delegate.getNumberOfPages();
        } catch (RuntimeException e) {
            throw PdfjigException.wrapping(ErrorCode.NOT_A_PDF, e);
        }
    }

    /**
     * 暗号化されているか。
     *
     * @throws PdfjigException 読み取れない場合は {@link ErrorCode#NOT_A_PDF}
     */
    public boolean encrypted() {
        try {
            return delegate.isEncrypted();
        } catch (RuntimeException e) {
            throw PdfjigException.wrapping(ErrorCode.NOT_A_PDF, e);
        }
    }

    /**
     * 電子署名が付いているか。
     *
     * <p>署名が <b>在るか</b> だけを見る。正当性・証明書・失効は一切確かめない。
     * pdfjig は署名を作らず、検証もしない（SPEC.md 2.2 の Non-goals）。
     * それでも在ることを知る必要があるのは、ページを並べ替えて書き出せば
     * 署名が無効になるためである。黙って壊すと、利用者は署名済みのつもりで
     * 検証に落ちる文書を配ることになる。
     *
     * <p><b>★★ 壊れた {@code /AcroForm} で投げる。</b>{@code getSignatureDictionaries} は
     * フォームと欄の木を辿るので、そこは細工 PDF が決められるところである（#150）。
     *
     * @throws PdfjigException 読み取れない場合は {@link ErrorCode#NOT_A_PDF}
     */
    public boolean signed() {
        try {
            return !delegate.getSignatureDictionaries().isEmpty();
        } catch (RuntimeException e) {
            throw PdfjigException.wrapping(ErrorCode.NOT_A_PDF, e);
        }
    }

    /**
     * PDFBox の文書オブジェクト。
     *
     * <p>パッケージプライベート。pdf-core の内部実装のみが使う。
     */
    PDDocument delegate() {
        return delegate;
    }

    /**
     * 閉じる。
     *
     * <p><b>★★ 閉じるときに初めて投げることがある。</b>{@code COSDocument#close} は
     * オブジェクトの溜まりを辿って参照を解決するので、<b>そこで初めて壊れた参照に当たる</b>
     * 細工 PDF がある。{@code PDDocument#close} が通す {@code IOUtils} は
     * {@code IOException} しか握らないため、<b>未検査例外はそのまま抜ける</b>（#150）。
     *
     * @throws PdfjigException 閉じられない場合は {@link ErrorCode#IO_FAILURE}
     */
    @Override
    public void close() {
        try {
            delegate.close();
        } catch (IOException | RuntimeException e) {
            throw PdfjigException.wrapping(ErrorCode.IO_FAILURE, e);
        }
    }

    private static void requireReadable(Path path) {
        if (!Files.isReadable(path)) {
            throw new PdfjigException(ErrorCode.FILE_NOT_FOUND);
        }
    }
}
