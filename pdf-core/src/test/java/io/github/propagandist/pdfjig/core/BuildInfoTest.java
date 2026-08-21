package io.github.propagandist.pdfjig.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BuildInfoTest {

    @Test
    @DisplayName("版数を読める")
    void readsVersion() {
        assertNotEquals(BuildInfo.UNKNOWN, BuildInfo.version());
        assertFalse(BuildInfo.version().isBlank());
    }

    @Test
    @DisplayName("プレースホルダが展開されている")
    void expandsPlaceholder() {
        // ここが抜けると、画面と CLI に ${version} がそのまま出る。
        assertFalse(BuildInfo.version().contains("${"), BuildInfo.version());
        assertTrue(BuildInfo.version().matches("\\d+\\.\\d+\\.\\d+.*"), BuildInfo.version());
    }
}
