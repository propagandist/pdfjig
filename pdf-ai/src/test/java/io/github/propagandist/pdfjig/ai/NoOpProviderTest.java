package io.github.propagandist.pdfjig.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.propagandist.pdfjig.core.PageText;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * CLAUDE.md INV-3 の検証。AI 不在でも例外にならず、空の提案が返る。
 */
class NoOpProviderTest {

    private final AiProvider provider = new NoOpProvider();

    @Test
    @DisplayName("既定のプロバイダは利用不可を報告する")
    void reportsUnavailable() {
        assertFalse(provider.isAvailable());
    }

    @Test
    @DisplayName("利用不可でも例外を投げず、空の提案を返す")
    void returnsEmptyProposalInsteadOfThrowing() {
        Proposal<List<BoundaryCandidate>> proposal = provider.detectBoundaries(List.of(new PageText(1, "請求書")));

        assertEquals(List.of(), proposal.value());
        assertEquals(0.0, proposal.confidence());
        assertFalse(proposal.hasSuggestion());
        assertTrue(proposal.changes().isEmpty());
    }
}
