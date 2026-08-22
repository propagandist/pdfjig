package io.github.propagandist.pdfjig.desktop;

import io.github.propagandist.pdfjig.ai.AiProvider;
import io.github.propagandist.pdfjig.ai.BoundaryCandidate;
import io.github.propagandist.pdfjig.ai.Proposal;
import io.github.propagandist.pdfjig.core.PageText;
import java.util.List;

/**
 * 可否だけを差し替えるプロバイダ。
 *
 * <p>提案は返さない。この版に AI の入口は無く、画面が見ているのは
 * {@link #isAvailable()} だけである（CLAUDE.md INV-3）。
 */
record StubAiProvider(boolean available) implements AiProvider {

    @Override
    public boolean isAvailable() {
        return available;
    }

    @Override
    public Proposal<List<BoundaryCandidate>> detectBoundaries(List<PageText> pages) {
        throw new UnsupportedOperationException("この版に AI の入口は無い");
    }
}
