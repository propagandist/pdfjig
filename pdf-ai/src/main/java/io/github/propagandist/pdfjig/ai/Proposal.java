package io.github.propagandist.pdfjig.ai;

import java.util.List;

/**
 * AI からの提案。
 *
 * <p><b>これはファイルを変更しない。</b> pdf-ai のすべての公開メソッドはこの型を返す
 * （CLAUDE.md INV-2）。適用は「提案 → 差分表示 → ユーザー承認 → pdf-core による適用」
 * の順を必ず踏む。
 *
 * @param <T>        提案される値の型
 * @param value      提案値
 * @param confidence 確信度 0.0〜1.0
 * @param rationale  根拠。利用者に提示する
 * @param changes    元データとの差分
 */
public record Proposal<T>(T value, double confidence, String rationale, List<Change> changes) {

    public Proposal {
        if (confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence は 0.0〜1.0 の範囲で指定してください。");
        }
        changes = List.copyOf(changes);
    }

    /**
     * 提案なしを表す。
     *
     * <p>AI が利用不可の場合、および LLM 出力の JSON パースに失敗した場合に返す。
     * 例外を投げないことで、呼び出し側は AI の有無を意識せず同じ経路を通れる（INV-3）。
     *
     * @param <T>      提案値の型
     * @param fallback 確定的処理の結果。そのまま使ってよい値
     * @return 確信度 0、差分なしの提案
     */
    public static <T> Proposal<T> none(T fallback) {
        return new Proposal<>(fallback, 0.0, "AI による提案はありません。", List.of());
    }

    /** 承認を求める価値のある提案か。 */
    public boolean hasSuggestion() {
        return !changes.isEmpty();
    }
}
