package io.github.propagandist.pdfjig.core;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * INV-5 の検査に使う道具。
 *
 * <p><b>★★ 「全部を連結して見る」という定義を 1 か所にする。</b>特定の語が出ていないことで
 * 見ると 2 通りに漏れる——<b>向こうが言い換えたときと、{@code getCause} 以外
 * （{@code addSuppressed}）で繋がれたときである。</b>
 * <b>写しが増えると、強化が片方にしか入らず、もう片方は緑のまま素通りする。</b>
 */
public final class Secrets {

    /**
     * SASLprep が禁じる文字を含むパスワード。U+200E は LEFT-TO-RIGHT MARK である。
     *
     * <p>右書き文脈やパスワード管理ソフトからの貼り付けで実際に混ざる。
     * JavaFX の入力欄が落とすのは ASCII の制御文字だけであり、これは残る。
     *
     * <p><b>読む側（{@code PdfDocument#open}）でも書く側（{@code Encryption#protect}）でも
     * 同じ漏えいが開く</b>——PDFBox は<b>本物の文字と位置をメッセージに載せて投げる。</b>
     */
    public static final String PROHIBITED = "pa\u200Ess";

    private Secrets() {}

    /**
     * メッセージ・{@code toString}・スタックトレースをすべて連結した文字列。
     *
     * @param throwable 見る例外
     * @return 連結した文字列
     */
    public static String renderFully(Throwable throwable) {
        StringWriter buffer = new StringWriter();
        try (PrintWriter writer = new PrintWriter(buffer)) {
            throwable.printStackTrace(writer);
        }
        return throwable.getMessage() + '\n' + throwable + '\n' + buffer;
    }
}
