package io.github.propagandist.pdfjig.core;

/**
 * 暗号化された入力を扱ったとき、出力に保護を引き継ぐかどうかの指定。
 *
 * <p>PDFBox は結合・分割の出力を新しい文書として組み立てるため、入力の暗号化は
 * <b>黙って失われる</b>。この型を通して、失われることを利用者に必ず伝える
 * （SPEC.md §4.3）。
 */
public enum EncryptionPropagation {

    /**
     * 引き継がない。出力は平文になる。
     *
     * <p>暗号化された入力を扱った場合は {@link Warning#ENCRYPTION_NOT_PROPAGATED} を通知する。
     */
    NONE,

    /**
     * 入力の暗号化を出力に引き継ぐ。
     *
     * <p><b>M0 では未対応。</b> 引き継ぎには元のパスワードが必要だが、
     * pdfjig はパスワードを保持しない（CLAUDE.md INV-5）。暗号化機能一式を実装する
     * M1 で、パスワードを受け取る経路とあわせて対応する。
     */
    INHERIT,

    /**
     * 利用者に問い合わせる。
     *
     * <p>UI 層の既定であり、問い合わせの結果として {@link #NONE} または {@link #INHERIT} に
     * 解決してから pdf-core に渡す。pdf-core はこの値を解決できないため、
     * そのまま渡された場合は失敗する。
     */
    PROMPT
}
