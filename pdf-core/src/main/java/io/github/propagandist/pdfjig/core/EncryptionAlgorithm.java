package io.github.propagandist.pdfjig.core;

/**
 * 暗号化の方式。
 *
 * <p><b>既定は {@link #AES_256} である</b>（{@code docs/SPEC.md} §6.2）。
 * <b>RC4 系と AES-128 は、古い閲覧環境との互換性が要るときだけの選択肢である</b>
 * ——<b>既定が弱いことのほうが説明できない。</b>
 */
public enum EncryptionAlgorithm {

    /** 暗号化されていない。 */
    NONE(0),

    /**
     * RC4 40 ビット。
     *
     * <p><b>読むためだけに残してある。</b>この方式で新しく暗号化する理由は無い。
     */
    RC4_40(40),

    /**
     * RC4 128 ビット。
     *
     * <p><b>読むためだけに残してある。</b>{@link #RC4_40} と同じ。
     */
    RC4_128(128),

    /** AES 128 ビット。互換性が要るときの選択肢。 */
    AES_128(128),

    /** AES 256 ビット（PDF 2.0 / Revision 6）。<b>既定である。</b> */
    AES_256(256),

    /**
     * 暗号化されているが、方式を読めなかった。
     *
     * <p><b>★★ {@link #NONE} と混ぜない。</b>あちらは「暗号化されていない」であり、
     * <b>これは「暗号化されているが、こちらから見えない」である</b>——
     * 混ぜると<b>保護されていない文書と区別が付かなくなる</b>（優先順位 2）。
     *
     * <p><b>ユーザーパスワードが要る文書を、パスワードなしで調べたときに返る</b>
     * （2026-09-12 実測。PDFBox はそのとき {@code PDDocument} を返さないので、
     * <b>暗号化辞書そのものが手に入らない</b>）。<b>パスワードを渡せば読める。</b>
     */
    UNKNOWN(0);

    private final int keyLengthBits;

    EncryptionAlgorithm(int keyLengthBits) {
        this.keyLengthBits = keyLengthBits;
    }

    /**
     * 鍵の長さ（ビット）。
     *
     * <p>{@link #NONE} は 0 を返す。
     *
     * @return 鍵の長さ
     */
    public int keyLengthBits() {
        return keyLengthBits;
    }

    /**
     * AES を使う方式か。
     *
     * <p><b>RC4 と AES は鍵の長さだけでは区別が付かない</b>——128 ビットが 2 つある。
     *
     * @return AES なら {@code true}
     */
    public boolean aes() {
        return this == AES_128 || this == AES_256;
    }
}
