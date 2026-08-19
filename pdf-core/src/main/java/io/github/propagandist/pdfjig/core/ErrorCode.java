package io.github.propagandist.pdfjig.core;

/**
 * pdfjig が投げる失敗の分類。
 *
 * <p>ここに定義するメッセージは <b>定数であり、実行時の値を一切埋め込まない</b>。
 * パスワード・ファイルパス・文書の中身が例外メッセージ経由で
 * ログに流出することを構造的に防ぐため（CLAUDE.md INV-5）。
 */
public enum ErrorCode {

    /** 入力ファイルが存在しない、または読み取れない。 */
    FILE_NOT_FOUND("入力ファイルを開けません。"),

    /** PDF として解釈できない。 */
    NOT_A_PDF("PDF として読み取れません。"),

    /** 暗号化されており、パスワードが必要。 */
    PASSWORD_REQUIRED("この文書はパスワードで保護されています。"),

    /** パスワードが誤っている。 */
    INVALID_PASSWORD("パスワードが正しくありません。"),

    /** 指定されたページが文書の範囲外。 */
    PAGE_OUT_OF_RANGE("指定されたページが文書の範囲外です。"),

    /** 対応していない暗号化方式。 */
    UNSUPPORTED_ENCRYPTION("この暗号化方式には対応していません。"),

    /** 読み書きの失敗。 */
    IO_FAILURE("ファイルの読み書きに失敗しました。");

    private final String defaultMessage;

    ErrorCode(String defaultMessage) {
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
