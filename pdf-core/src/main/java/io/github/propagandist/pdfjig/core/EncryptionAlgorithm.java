package io.github.propagandist.pdfjig.core;

import java.util.ArrayList;
import java.util.List;

/**
 * 暗号化の方式。
 *
 * <p><b>既定は {@link #AES_256} である</b>（{@code docs/SPEC.md} §6.2）。
 * <b>RC4 系と AES-128 は、古い閲覧環境との互換性が要るときだけの選択肢である</b>
 * ——<b>既定が弱いことのほうが説明できない。</b>
 */
public enum EncryptionAlgorithm {

    /** 暗号化されていない。 */
    NONE,

    /**
     * RC4 40 ビット。
     *
     * <p><b>読むためだけに残してある。</b>この方式で新しく暗号化する理由は無い。
     */
    RC4_40,

    /**
     * RC4 128 ビット。
     *
     * <p><b>読むためだけに残してある。</b>{@link #RC4_40} と同じ。
     */
    RC4_128,

    /** AES 128 ビット。互換性が要るときの選択肢。 */
    AES_128,

    /** AES 256 ビット（PDF 2.0 / Revision 6）。<b>既定である。</b> */
    AES_256,

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
    UNKNOWN;

    // ★ 鍵の長さも AES かどうかも、ここには持たせない。NONE と UNKNOWN が同じ値を返す形になり、
    //   「混ぜるな」と書いた隣で 2 つが同値になる。方式を方針へ当てるのは
    //   PdfBoxEncryption の網羅的な switch である——書ける方式が増えた日にコンパイラが知らせる。

    /**
     * 書き出しに指定できる方式。<b>並びは宣言の順（弱いほうから）である。</b>
     *
     * <p><b>★★ 先頭は既定ではない。</b>既定は AES-256（{@code docs/SPEC.md} §6.2）であり、
     * <b>ここでは最後に並ぶ</b>——<b>「先頭を選ぶ」と書くと 40 ビットの RC4 が既定になる</b>
     * （<b>2026-09-16 実測</b>。CI の uiTest が捕まえた）。<b>選ぶ側が名前で指定すること。</b>
     *
     * <p><b>★★ 選ばせる側が数え上げないためにある。</b>画面で並びを手で写すと、
     * <b>方式を 1 つ足した日に、方針の場所も見せ方もコンパイラが知らせるのに、
     * 選べる並びだけが黙って古いまま残る</b>（#30 の門の 1 段目）。
     *
     * <p><b>★ 網羅した {@code switch} で組む。</b>値が増えたらここがコンパイルで落ちる。
     *
     * @return 書ける方式。{@link #NONE} と {@link #UNKNOWN} は入らない
     */
    public static List<EncryptionAlgorithm> writable() {
        List<EncryptionAlgorithm> writable = new ArrayList<>(values().length);
        for (EncryptionAlgorithm algorithm : values()) {
            switch (algorithm) {
                case AES_256, AES_128, RC4_128, RC4_40 -> writable.add(algorithm);
                // 読んだ結果を表す値であり、掛ける側にはならない（Protection が拒む。#199）。
                case NONE, UNKNOWN -> {}
            }
        }
        return List.copyOf(writable);
    }
}
