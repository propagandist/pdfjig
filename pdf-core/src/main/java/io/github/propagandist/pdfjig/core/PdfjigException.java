package io.github.propagandist.pdfjig.core;

/**
 * pdfjig の唯一の非チェック例外。
 *
 * <p><b>原因例外を連結しない。</b> PDFBox をはじめとする依存ライブラリの例外メッセージには
 * 入力値が埋め込まれている可能性があり、{@code printStackTrace} やログ出力を経由して
 * パスワードが漏れる経路になりうる（CLAUDE.md INV-5）。
 * 診断に必要な情報は原因例外の <b>型名のみ</b> を保持する。
 */
public final class PdfjigException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ErrorCode errorCode;

    /** 原因例外の型名。原因を持たない場合は {@code null}。 */
    private final String causeType;

    public PdfjigException(ErrorCode errorCode) {
        super(errorCode.defaultMessage());
        this.errorCode = errorCode;
        this.causeType = null;
    }

    private PdfjigException(ErrorCode errorCode, String causeType) {
        super(errorCode.defaultMessage());
        this.errorCode = errorCode;
        this.causeType = causeType;
    }

    /**
     * 依存ライブラリの例外を包む。
     *
     * <p>{@code cause} は {@link Throwable#initCause} で連結せず、型名だけを取り出す。
     * メッセージもスタックトレースも引き継がない。
     *
     * <p><b>★★ 既に包んであるものは、そのまま返す。</b>未検査例外まで捕まえる
     * {@code catch} は<b>自分で分類した失敗もろとも拾う</b>ので、ここで通さないと
     * <b>{@code PAGE_OUT_OF_RANGE} が「抽出できませんでした」に化ける</b>——
     * <b>利用者は次に何をすればよいか分からなくなる</b>（CLAUDE.md 優先順位 2）。
     * <b>その判断をここに置くのは、境界を足すたびに写されるのを止めるためである</b>
     * ——#150 の前は、同じ 1 行が 7 か所に別々の綴りで在った。
     *
     * @param errorCode 失敗の分類。<b>{@code cause} が既に {@link PdfjigException} なら使われない</b>
     * @param cause     元の例外。メッセージは読まれない
     * @return 原因の型名だけを保持する例外
     */
    public static PdfjigException wrapping(ErrorCode errorCode, Throwable cause) {
        if (cause instanceof PdfjigException already) {
            return already;
        }
        return new PdfjigException(errorCode, cause.getClass().getName());
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    /**
     * 原因例外の型名を返す。
     *
     * @return 完全修飾クラス名。原因を持たない場合は {@code null}
     */
    public String causeType() {
        return causeType;
    }
}
