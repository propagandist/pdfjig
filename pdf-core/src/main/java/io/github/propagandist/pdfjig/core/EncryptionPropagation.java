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
     * <p><b>★★ 実装しない。</b>渡されたら {@link ErrorCode#ENCRYPTION_PROPAGATION_UNSUPPORTED}
     * で失敗する（2026-09-14 に決めた。#29）。理由は 2 つある。
     *
     * <ul>
     *   <li><b>どのパスワードで暗号化し直すのかが決まらない。</b>元のものを使い回す形は
     *       <b>INV-5 に真っ向から当たる</b>——<b>書き出すまで鍵を抱えることになる</b></li>
     *   <li><b>結合では「引き継ぐ」が 1 つに定まらない。</b>入力ごとにパスワードが違いうる</li>
     * </ul>
     *
     * <p><b>引き継ぎたい呼ぶ側は、平文で書き出してから {@code Encryption#protect} を掛けられる。</b>
     * <b>少し不便だが正直である</b>（{@code CLAUDE.md} の優先順位）。
     *
     * <p><b>★ それでも列挙から消さない。</b>消すと<b>「そういう考え方が無い」ことになり、
     * 次に検討するときに一から始まる。</b>
     */
    INHERIT,

    /**
     * 利用者に問い合わせる。
     *
     * <p>UI 層の既定であり、問い合わせの結果として {@link #NONE} または {@link #INHERIT} に
     * 解決してから pdf-core に渡す。pdf-core はこの値を解決できないため、
     * そのまま渡された場合は失敗する。
     *
     * <p><b>★★ 問うのは画面である</b>（{@code docs/SPEC.md} §4.3.1。#29）。
     * <b>寄与する出どころは {@link PageSelection#sourceIndex()} そのもの</b>であり、
     * <b>鍵が要ったかどうかは {@link PdfDocument#openedWithPassword()} で読める</b>
     * ——<b>画面は開き直さずに答えを出せる。</b>
     *
     * <p><b>★ だから pdf-core には問い返す口が無い。</b>問うことは<b>呼ぶ側のコードを
     * 走らせること</b>であり、<b>包みの中では飲まれる</b>（#178）——<b>包みの外で問うには
     * 入力を 2 回開くことになる。</b>
     *
     * <p><b>★★ 引き継ぐ道は出さない</b>ので、解決先は実際には {@link #NONE} だけである
     * （{@link #INHERIT} を見ること）。<b>訊くのは「中止か、続行か」である。</b>
     */
    PROMPT
}
