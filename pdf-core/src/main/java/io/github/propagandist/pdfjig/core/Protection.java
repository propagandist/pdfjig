package io.github.propagandist.pdfjig.core;

import java.util.List;

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
 * @param algorithm     暗号方式。既定は {@link EncryptionAlgorithm#AES_256}（同 §6.2）。
 *                      <b>{@link EncryptionAlgorithm#NONE} と {@link EncryptionAlgorithm#UNKNOWN} は
 *                      書けないので、ここで拒む</b>
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
        // ★★ 書けない方式は値の段で拒む。奥で拒むと、そこへ届くまでに
        //   秘密が String 化され（StandardProtection）、入力も全部開かれている
        //   ——断ると分かっている要求のために、消せない文字列が 2 本残る（#199 の門）。
        if (algorithm == EncryptionAlgorithm.NONE || algorithm == EncryptionAlgorithm.UNKNOWN) {
            throw new PdfjigException(ErrorCode.UNSUPPORTED_ENCRYPTION);
        }
    }

    /**
     * この保護を掛けた出力を開くのに、パスワードが要るか。
     *
     * <p><b>★★ 「暗号化されるか」ではない。</b>{@link EncryptionInfo#userPasswordRequired()} と
     * <b>同じことを、掛ける前に訊いている</b>——<b>ユーザーパスワードが空でも、中身は
     * 暗号化される。</b>鍵が空文字列から導かれるので、<b>誰でも開ける</b>だけである
     * （<b>2026-09-16 実測</b>。空の鍵で書いた出力は {@code encrypted() == true} ／
     * {@code AES_256} ／ 鍵なしで開けた）。
     *
     * <p><b>★★ 「保護を掛けた」と「守られる」を同じものとして扱わないこと。</b>
     * <b>ここが false なら、残るのは権限フラグだけ</b>で、あれは<b>閲覧ソフトが自主的に
     * 従っているだけの申告制である</b>（{@code docs/SPEC.md} §6.1）。
     * <b>そのことを告げる口が {@link Warning#CONTENT_OPENS_WITHOUT_A_KEY} である</b>
     * ——<b>ここが false のとき黙ってはならない。</b>
     *
     * @return 開くのにパスワードが要るなら {@code true}
     */
    public boolean userPasswordRequired() {
        return !userPassword.isEmpty();
    }

    /**
     * この保護が抱えている鍵。
     *
     * <p><b>★★ 呼ぶ側が数え上げて別に持たない。</b>持つと<b>「鍵を足したが、片づける一覧へは
     * 足さなかった」形が書ける</b>——そこを通った平文の配列は<b>二度と消されない</b>
     * （INV-5。#135 / #144 / #145 で 3 度破れたのと同じ類型である）。
     * <b>1 つの正本から引けば、書き忘れる場所が無い。</b>
     * ★ <b>入力の側は {@code MainWindow#keysOf(List)} が同じことをしている</b>——
     * <b>あちらを {@code Sources} へ下ろすのは #207 が持つ。</b>
     *
     * <p><b>★ 閉じるのは持ち主である。</b>ここが返すのは読むための並びであって、
     * <b>持ち主が移るわけではない</b>（{@code BackgroundTasks#run(List, …)} へ渡すと、あそこが持つ）。
     *
     * @return 抱えている鍵。<b>空の鍵も並ぶ</b>——閉じる対象であることに変わりはない
     */
    public List<Password> keys() {
        return List.of(userPassword, ownerPassword);
    }
}
