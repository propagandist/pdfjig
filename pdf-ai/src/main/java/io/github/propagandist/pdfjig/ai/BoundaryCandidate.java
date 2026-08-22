package io.github.propagandist.pdfjig.ai;

/**
 * 文書境界の候補。「このページから新しい文書が始まる」という提案。
 *
 * @param startPage  新しい文書の開始ページ（1 始まり）
 * @param confidence 確信度 0.0〜1.0
 * @param reason     根拠
 */
public record BoundaryCandidate(int startPage, double confidence, String reason) {}
