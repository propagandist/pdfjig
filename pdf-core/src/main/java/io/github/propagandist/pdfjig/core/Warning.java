package io.github.propagandist.pdfjig.core;

/**
 * 操作は成功したが、利用者が知らなければ誤解する事柄。
 *
 * <p>{@link ErrorCode} と同じく <b>メッセージは定数であり、実行時の値を埋め込まない</b>
 * （CLAUDE.md INV-5）。
 */
public enum Warning {

    /**
     * 暗号化された入力を扱ったが、出力には保護が引き継がれない。
     *
     * <p>これを黙って行うと、利用者は保護されているつもりで平文の機密文書を配布することになる。
     */
    ENCRYPTION_NOT_PROPAGATED(
            "入力の暗号化は出力に引き継がれません。出力されたファイルは保護されていません。");

    private final String defaultMessage;

    Warning(String defaultMessage) {
        this.defaultMessage = defaultMessage;
    }

    /**
     * 利用者に提示してよい定型メッセージを返す。
     *
     * @return 実行時の値を含まない定数メッセージ
     */
    public String defaultMessage() {
        return defaultMessage;
    }
}
