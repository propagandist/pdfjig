package io.github.propagandist.pdfjig.ai;

/**
 * 提案に含まれる 1 件の変更。UI の差分表示に使う。
 *
 * @param location 変更箇所の説明（「3 ページ目」「2 行 4 列」など）
 * @param before   元の値
 * @param after    提案後の値
 */
public record Change(String location, String before, String after) {}
