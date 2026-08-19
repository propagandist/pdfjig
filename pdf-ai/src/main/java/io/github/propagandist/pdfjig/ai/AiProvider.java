package io.github.propagandist.pdfjig.ai;

import io.github.propagandist.pdfjig.core.PageText;
import java.util.List;

/**
 * LLM プロバイダの抽象化。
 *
 * <p>実装上の制約:
 * <ul>
 *   <li>すべてのメソッドは {@link Proposal} を返す。{@code Path} を受け取ってファイルを
 *       書き出すメソッドをこのインタフェースに追加してはならない（CLAUDE.md INV-2）</li>
 *   <li>LLM に渡すのは抽出済みテキストのみ。PDF バイナリ・ページ画像は渡さない</li>
 *   <li>{@link #isAvailable()} が false のとき、各メソッドは
 *       {@link Proposal#none(Object)} を返す。例外を投げてはならない（INV-3）</li>
 * </ul>
 */
public interface AiProvider {

    /**
     * この実装が現在利用可能か。
     *
     * <p>API キー未設定・Ollama 未起動などの場合に false を返す。
     * 呼び出し側はこの値で機能の表示・非表示を切り替える。
     *
     * @return 利用可能なら true
     */
    boolean isAvailable();

    /**
     * ページ列から文書の境界を推定する。
     *
     * <p>全ページを一度に投げず、ページ単位の分類タスクに分解して実装すること。
     *
     * @param pages 抽出済みのページテキスト
     * @return 境界候補の提案
     */
    Proposal<List<BoundaryCandidate>> detectBoundaries(List<PageText> pages);
}
