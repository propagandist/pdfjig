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

    /** 入力が 1 つも指定されていない。 */
    NO_INPUT("入力が指定されていません。"),

    /** PDF として解釈できない。 */
    NOT_A_PDF("PDF として読み取れません。"),

    /** 暗号化されており、パスワードが必要。 */
    PASSWORD_REQUIRED("この文書はパスワードで保護されています。"),

    /** パスワードが誤っている。 */
    INVALID_PASSWORD("パスワードが正しくありません。"),

    /** 指定されたページが文書の範囲外。 */
    PAGE_OUT_OF_RANGE("指定されたページが文書の範囲外です。"),

    /** ページ順の指定が全ページの並べ替えになっていない。 */
    INVALID_PAGE_ORDER("ページ順の指定が全ページの並べ替えになっていません。"),

    /** 操作の結果、ページが 1 枚も残らない。 */
    EMPTY_RESULT("この操作を行うとページが 1 枚も残りません。"),

    /** ページの回転角が 90 度の倍数でない。 */
    MALFORMED_PAGE_ROTATION("ページの回転角が PDF 仕様に反しています。"),

    /** 対応していない暗号化方式。 */
    UNSUPPORTED_ENCRYPTION("この暗号化方式には対応していません。"),

    /** 指定された暗号化の引き継ぎ方法に対応していない。 */
    ENCRYPTION_PROPAGATION_UNSUPPORTED("この暗号化の引き継ぎ方法にはまだ対応していません。"),

    /** テキストを抽出できない。 */
    TEXT_EXTRACTION_FAILED("テキストを抽出できませんでした。"),

    /** 出力先に同名のファイルが既に存在する。 */
    OUTPUT_ALREADY_EXISTS("出力先に同名のファイルが既に存在します。"),

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
