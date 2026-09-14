package io.github.propagandist.pdfjig.core;

/**
 * 書き出しに掛ける保護。
 *
 * <p><b>★★ 「引き継ぐ」ではない。</b>{@link EncryptionPropagation#INHERIT} は
 * <b>入力の保護を引き継ぐ</b>もので、<b>どのパスワードで暗号化し直すかが決まらない</b>ために
 * 実装しないと決めた（#29）。<b>こちらは呼ぶ側が指定した鍵で保護する</b>——
 * <b>鍵は呼ぶ側が持っている。</b>
 *
 * <p><b>★★ 鍵は読むだけである。</b>{@link Password} の持ち主は<b>作った場所</b>であり、
 * ここでは消さない（{@code CLAUDE.md} INV-5、{@link Source} と同じ規律）。
 * <b>書き出しが終わるまで、枠が生きていなければならない。</b>
 *
 * <p><b>★ 組み立てながら掛けるためにある</b>（#199）。
 * <b>既にある文書へ後から掛けるのは {@link Encryption#protect} である</b>——
 * あちらを通すと<b>平文が一度ディスクに現れる。</b>
 *
 * @param userPassword  文書を開くための鍵。<b>空でない鍵を渡すと、本文が暗号化される</b>
 * @param ownerPassword 権限を変更するための鍵
 * @param permissions   権限フラグ。<b>暗号学的には強制されない</b>（{@code docs/SPEC.md} §6.1）
 * @param algorithm     暗号方式。既定は {@link EncryptionAlgorithm#AES_256}（同 §6.2）
 */
public record Protection(
        Password userPassword, Password ownerPassword, AccessPermissions permissions, EncryptionAlgorithm algorithm) {

    public Protection {
        if (userPassword == null || ownerPassword == null) {
            throw new IllegalArgumentException("鍵は 2 本とも渡すこと。空の鍵は Password.copyOf(\"\") で表す。");
        }
        if (permissions == null) {
            throw new IllegalArgumentException("permissions は null にできません。");
        }
        if (algorithm == null) {
            throw new IllegalArgumentException("algorithm は null にできません。");
        }
    }
}
