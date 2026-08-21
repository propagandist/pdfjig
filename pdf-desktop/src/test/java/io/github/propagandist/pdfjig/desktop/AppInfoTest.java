package io.github.propagandist.pdfjig.desktop;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AppInfoTest {

    @Test
    @DisplayName("診断情報に表示名と版数と実行環境が入る")
    void describesRuntime() {
        String diagnostics = AppInfo.diagnostics();

        assertTrue(diagnostics.contains(AppInfo.NAME), diagnostics);
        assertTrue(diagnostics.contains(AppInfo.version()), diagnostics);
        assertTrue(diagnostics.contains("Java "), diagnostics);
        assertTrue(diagnostics.contains("JavaFX "), diagnostics);
    }

    @Test
    @DisplayName("版数はビルドから来る")
    void takesVersionFromBuild() {
        // 画面に ${version} や unknown が出るのは、焼き込みが壊れているとき。
        assertFalse(AppInfo.version().contains("${"), AppInfo.version());
        assertFalse(AppInfo.version().isBlank());
    }
}
