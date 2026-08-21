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

    @Test
    @DisplayName("AI が使えないことは伝えるが、無効とは言わない")
    void tellsAiIsAbsentWithoutSayingDisabled() {
        String absent = AppInfo.aiStatus(false);

        // 「無効」は「有効にできるが今は切ってある」と読める。この版に設定は存在しない。
        assertFalse(absent.contains("無効"), absent);
        assertTrue(absent.contains("この版には含まれていません"), absent);
    }

    @Test
    @DisplayName("AI が使えるときはそう出す")
    void tellsAiIsAvailable() {
        assertTrue(AppInfo.aiStatus(true).contains("利用可能"), AppInfo.aiStatus(true));
    }
}
