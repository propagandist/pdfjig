package io.github.propagandist.pdfjig.desktop;

/**
 * 更新を確認した結果。
 *
 * <p><b>失敗の種類を分けない。</b>遮断・DNS 不達・応答の形違い・版数が読めない——
 * どれも {@link Unavailable} 1 つにする。利用者にできることは同じ（後でもう一度押す）であり、
 * <b>分けたところで判断が変わらない</b>（#72）。
 *
 * <p>分けるのは<b>次に何をするかが変わるとき</b>だけである。{@link Available} だけがリンクを伴う。
 */
sealed interface UpdateStatus {

    /**
     * 公開されている版のほうが新しい。
     *
     * @param latest 公開されている最新
     */
    record Available(ReleaseVersion latest) implements UpdateStatus {}

    /** 手元の版が、公開されている最新と同じである。 */
    record UpToDate() implements UpdateStatus {}

    /**
     * 手元の版が開発版で、公開されている最新より古くない。
     *
     * <p><b>{@link UpToDate} と分けるのは、開発版を「最新です」と答えさせないためである</b>
     * （{@code CLAUDE.md} 優先順位 2）。古い開発版は {@link Available} になる——
     * そちらは公開版へ移るべき状態であり、開発版であることは答えを変えない。
     *
     * @param latest 公開されている最新
     */
    record Development(ReleaseVersion latest) implements UpdateStatus {}

    /** 確認できなかった。 */
    record Unavailable() implements UpdateStatus {}
}
