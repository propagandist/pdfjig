package io.github.propagandist.pdfjig.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
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
     *                         読めない場合は {@link ErrorCode#FILE_NOT_FOUND}、
     *                         PDF として読めない場合は {@link ErrorCode#NOT_A_PDF}
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
     * <p>渡された {@code password} は、成否によらず <b>このメソッドの中でゼロ埋めされる</b>。
     * 呼び出し側は戻った後の配列の内容に依存してはならない。
     *
     * <p><b>既知の限界:</b> PDFBox 3 の {@code Loader.loadPDF} は {@code String} しか受け付けない。
     * そのため境界で一度だけ {@code String} が生成され、これは GC されるまでヒープに残り、
     * 明示的なゼロ埋めができない。pdfjig 側でこれを回避する手段はない。
     * 生成箇所をこの 1 か所に限定することで影響範囲を最小化している。
     *
     * @param path     入力ファイル
     * @param password パスワード。呼び出し後にゼロ埋めされる
     * @return 開かれた文書
     * @throws PdfjigException 開けない場合。パスワード誤りは
     *                         {@link ErrorCode#INVALID_PASSWORD}、
     *                         読めない場合は {@link ErrorCode#FILE_NOT_FOUND}、
     *                         PDF として読めない場合は {@link ErrorCode#NOT_A_PDF}、
     *                         原因を絞れない場合は {@link ErrorCode#PASSWORD_OR_DOCUMENT_FAILURE}
     */
    public static PdfDocument open(Path path, char[] password) {
        // ★ char[] を持つ側は、読めるかを見る関門も try の中に置く。ここより外で投げると
        //   finally を通らず、平文が残る（INV-5。#135）。
        //   パスワードを取らない open(Path) には掛からない——消すものが無い。
        try {
            requireReadable(path);
            // INV-5 の境界。PDFBox の API 制約により String 化は避けられない。
            String boundaryPassword = new String(password);
            return new PdfDocument(Loader.loadPDF(path.toFile(), boundaryPassword));
        } catch (InvalidPasswordException e) {
            throw PdfjigException.wrapping(ErrorCode.INVALID_PASSWORD, e);
        } catch (IOException e) {
            throw PdfjigException.wrapping(ErrorCode.NOT_A_PDF, e);
        } catch (PdfjigException e) {
            // ★★ 無いと requireReadable の FILE_NOT_FOUND が「原因を絞れない失敗」に化ける
            //   ——自分で分類したものを、自分で捨てることになる。外すと #135 の 2 本が赤くなる。
            throw e;
        } catch (RuntimeException e) {
            // ★★ INV-5。PDFBox は AES-256 のとき、照合する前に SASLprep を通す。禁止文字に
            //   当たると、本物のパスワードの文字と位置をメッセージに載せた
            //   IllegalArgumentException を投げる——IOException ではないので、上の 2 つに
            //   当たらない。wrapping は型名しか残さないので、ここで包めば漏れない（#144）。
            //   ★ どちらが原因かは、呼んだ側からは区別が付かない。文書かもしれず、
            //   パスワードかもしれない。分からないことを分からないまま伝える。
            throw PdfjigException.wrapping(ErrorCode.PASSWORD_OR_DOCUMENT_FAILURE, e);
        } finally {
            // ★★ 片づけからは投げない。null を渡されると Arrays.fill は NPE を投げ、
            //   finally から投げた例外は本当の失敗を「置き換える」——ErrorCode ごと消える。
            //   消すものが無いときは、何も言わずに何もしないのが正しい。
            if (password != null) {
                Arrays.fill(password, '\0');
            }
        }
    }

    /** 総ページ数。 */
    public int pageCount() {
        return delegate.getNumberOfPages();
    }

    /** 暗号化されているか。 */
    public boolean encrypted() {
        return delegate.isEncrypted();
    }

    /**
     * 電子署名が付いているか。
     *
     * <p>署名が <b>在るか</b> だけを見る。正当性・証明書・失効は一切確かめない。
     * pdfjig は署名を作らず、検証もしない（SPEC.md 2.2 の Non-goals）。
     * それでも在ることを知る必要があるのは、ページを並べ替えて書き出せば
     * 署名が無効になるためである。黙って壊すと、利用者は署名済みのつもりで
     * 検証に落ちる文書を配ることになる。
     */
    public boolean signed() {
        return !delegate.getSignatureDictionaries().isEmpty();
    }

    /**
     * PDFBox の文書オブジェクト。
     *
     * <p>パッケージプライベート。pdf-core の内部実装のみが使う。
     */
    PDDocument delegate() {
        return delegate;
    }

    @Override
    public void close() {
        try {
            delegate.close();
        } catch (IOException e) {
            throw PdfjigException.wrapping(ErrorCode.IO_FAILURE, e);
        }
    }

    private static void requireReadable(Path path) {
        if (!Files.isReadable(path)) {
            throw new PdfjigException(ErrorCode.FILE_NOT_FOUND);
        }
    }
}
