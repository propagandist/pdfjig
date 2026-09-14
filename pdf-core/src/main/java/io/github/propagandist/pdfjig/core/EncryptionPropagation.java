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
     * <p>pdf-core はこの値を解決できないため、渡された場合は失敗する。
     *
     * <p><b>★★ 画面はこの値を渡さない。</b>画面が通るのは {@code PageOperations#assemble} と
     * {@code #assembleEach} であり、<b>あちらはこの列挙を受け取らない</b>——
     * <b>画面は問い、答えが「続行」なら普通に呼ぶ。</b>渡す先があるのは
     * {@code MergeOptions} と {@code SplitStrategy} だけで、<b>そちらは画面から呼ばれない。</b>
     *
     * <p><b>★★ 問うのは画面である</b>（#29）。<b>だから pdf-core には問い返す口が無い</b>
     * ——問うことは<b>呼ぶ側のコードを走らせること</b>であり、<b>包みの中では飲まれる</b>（#178）。
     * <b>理由の正本は {@code docs/SPEC.md} §4.3.1 にある。</b>
     *
     * <p><b>★ 解決先は実際には {@link #NONE} だけである</b>（{@link #INHERIT} を見ること）。
     */
    PROMPT
}
