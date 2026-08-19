package io.github.propagandist.pdfjig.ai;

import io.github.propagandist.pdfjig.core.PageText;
import java.util.List;

/**
 * 既定のプロバイダ。何もしない。
 *
 * <p>これが既定であることが CLAUDE.md INV-3（AI 不在で全機能が動く）の構造的な裏付けになる。
 * API キー未設定・Ollama 未起動の環境ではこの実装が使われ、
 * AI 機能は UI 上で非表示になるが、それ以外のすべての機能は通常どおり動作する。
 */
public final class NoOpProvider implements AiProvider {

    @Override
    public boolean isAvailable() {
        return false;
    }

    @Override
    public Proposal<List<BoundaryCandidate>> detectBoundaries(List<PageText> pages) {
        return Proposal.none(List.of());
    }
}
