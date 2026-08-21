package io.github.propagandist.pdfjig.desktop;

import io.github.propagandist.pdfjig.core.BuildInfo;

/**
 * 画面に出すアプリケーションの表示情報。
 *
 * <p>表示名・提供元・著作権はインストーラのメタデータにも同じ値が要る
 * （{@code pdf-desktop/build.gradle.kts} の {@code appName} / {@code appVendor} /
 * {@code --copyright}）。<b>年や社名を変えるときは両方を直すこと。</b>
 * ビルドスクリプトからここへ焼き込むこともできるが、版数と違って年に一度動くかどうかの値であり、
 * 仕組みを増やすほうが割に合わない。
 *
 * <p>版数だけは例外で、{@link BuildInfo} 経由でビルドから受け取る。ここに書くと必ず乖離する。
 *
 * <p>JavaFX の型を持ち込まないこと。Toolkit の初期化なしでテストできる状態を保つ。
 */
final class AppInfo {

    /** 画面上の表示名。コマンド名・パッケージ名の {@code pdfjig} とは別に、こちらで統一する。 */
    static final String NAME = "PDFjig";

    static final String VENDOR = "PROPAGANDIST CORPORATION";

    static final String COPYRIGHT = "Copyright 2026 " + VENDOR;

    static final String LICENSE = "Apache License 2.0";

    static final String REPOSITORY = "https://github.com/propagandist/pdfjig";

    /** 値を読めなかった項目に出す文言。 */
    private static final String UNKNOWN = "不明";

    private AppInfo() {
    }

    /** ビルドが付けた版数。 */
    static String version() {
        return BuildInfo.version();
    }

    /** 表題や本文に置く「PDFjig 0.1.0」の形。 */
    static String nameAndVersion() {
        return NAME + " " + version();
    }

    /**
     * AI 機能の状態。
     *
     * <p>AI が無いことは隠さない（CLAUDE.md INV-3）。ただし<b>「無効」とは書かない</b>。
     * 無効は「有効にできるが今は切ってある」と読め、利用者は在りもしない設定を探すことになる。
     * 実際、この版には AI 機能そのものが入っておらず、有効にする経路はどこにもない。
     *
     * <p>出す先はバージョン情報のダイアログである。文書の状態を出すステータスバーに置くと、
     * 版の性格と文書の状態が同じ行に混ざるうえ、常に同じ文字列が出続けることになる。
     *
     * @param available プロバイダが使える状態か
     */
    static String aiStatus(boolean available) {
        return "AI 機能: " + (available ? "利用可能" : "この版には含まれていません");
    }

    static String javaRuntime() {
        return "Java " + property("java.version");
    }

    /**
     * JavaFX の版数。
     *
     * <p>{@code javafx.runtime.version} は Toolkit の起動時に入る。この値を読むのは
     * 画面が出たあとに限られるため、実際には常に取れる。
     */
    static String javafxRuntime() {
        return "JavaFX " + property("javafx.runtime.version");
    }

    static String operatingSystem() {
        return property("os.name") + " " + property("os.version") + " (" + property("os.arch") + ")";
    }

    /**
     * 不具合報告にそのまま貼れる形で、版数と実行環境をまとめる。
     *
     * <p>報告のたびに環境を聞き直さずに済むようにするためのもので、
     * <b>ここに文書の内容やファイルパスを含めてはならない</b>。
     *
     * @return 改行で区切った診断情報
     */
    static String diagnostics() {
        return String.join(
                System.lineSeparator(),
                nameAndVersion(),
                javaRuntime(),
                javafxRuntime(),
                operatingSystem());
    }

    /** システムプロパティを読む。読めなくても表示を壊さない。 */
    private static String property(String key) {
        String value = System.getProperty(key);
        return value == null || value.isBlank() ? UNKNOWN : value;
    }
}
