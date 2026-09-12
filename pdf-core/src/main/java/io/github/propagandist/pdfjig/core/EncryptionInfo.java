package io.github.propagandist.pdfjig.core;

/**
 * 暗号化の状態。
 *
 * @param encrypted            暗号化されているか
 * @param algorithm            方式。暗号化されていなければ {@link EncryptionAlgorithm#NONE}
 * @param userPasswordRequired 開くのにパスワードが要るか。
 *                             <b>★★ 暗号化されていることと同じではない</b>——
 *                             <b>オーナーパスワードだけが掛かった文書は、暗号化されているが
 *                             パスワードなしで開ける</b>（{@code docs/SPEC.md} §6.1 の表）。
 *                             <b>「保護されているか」を訊きたいなら、こちらを見る</b>（§4.3.1）
 * @param permissions          権限フラグ。暗号化されていなければ {@link AccessPermissions#all()}
 */
public record EncryptionInfo(
        boolean encrypted, EncryptionAlgorithm algorithm, boolean userPasswordRequired, AccessPermissions permissions) {

    /**
     * 暗号化されていない文書の状態。
     *
     * @return 暗号化されていないことを表す状態
     */
    public static EncryptionInfo none() {
        return new EncryptionInfo(false, EncryptionAlgorithm.NONE, false, AccessPermissions.all());
    }
}
