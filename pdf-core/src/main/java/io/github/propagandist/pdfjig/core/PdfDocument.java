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
     *                         {@link ErrorCode#PASSWORD_REQUIRED}
     */
    public static PdfDocument open(Path path) {
        requireReadable(path);
        try {
            return new PdfDocument(Loader.loadPDF(path.toFile()));
        } catch (InvalidPasswordException e) {
            throw PdfjigException.wrapping(ErrorCode.PASSWORD_REQUIRED, e);
        } catch (IOException e) {
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
     *                         {@link ErrorCode#INVALID_PASSWORD}
     */
    public static PdfDocument open(Path path, char[] password) {
        requireReadable(path);
        // INV-5 の境界。PDFBox の API 制約により String 化は避けられない。
        String boundaryPassword = new String(password);
        try {
            return new PdfDocument(Loader.loadPDF(path.toFile(), boundaryPassword));
        } catch (InvalidPasswordException e) {
            throw PdfjigException.wrapping(ErrorCode.INVALID_PASSWORD, e);
        } catch (IOException e) {
            throw PdfjigException.wrapping(ErrorCode.NOT_A_PDF, e);
        } finally {
            Arrays.fill(password, '\0');
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
