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
    ENCRYPTION_NOT_PROPAGATED("入力の暗号化は出力に引き継がれません。出力されたファイルは保護されていません。"),

    /**
     * 出力に含まれないページを指すしおりやリンクがあったため、それらを取り除いた。
     *
     * <p>取り除かないと、出力に含めなかったページが参照から辿れてしまい、
     * ページ一覧には出ないのにファイルの中には在る、という状態になる。
     * 押しても何も起きないしおりを残さないためでもある。
     */
    DANGLING_REFERENCES_REMOVED("出力に含まれないページを指すしおりとリンクを取り除きました。"),

    /**
     * 複数の入力を組み合わせたため、文書情報は最初の入力のものだけが残る。
     *
     * <p>題名や作成者を 1 つに決める根拠がない。黙って先頭のものを採ると、
     * 出力の題名が中身と食い違ったまま気づかれない。
     */
    METADATA_FROM_FIRST_INPUT("複数の入力を組み合わせたため、文書情報は最初の入力のものになります。");

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
