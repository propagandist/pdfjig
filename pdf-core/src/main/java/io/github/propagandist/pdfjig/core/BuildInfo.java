package io.github.propagandist.pdfjig.core;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * ビルド時に焼き込まれた版数。
 *
 * <p>値の出どころはルートの {@code build.gradle.kts} の {@code version} ただ一つで、
 * CI はタグから {@code -Pversion=} で渡す。画面の「バージョン情報」も CLI の
 * {@code --version} もここを読む。<b>版数を Java 側に手で書いてはならない</b>。
 * 書いた瞬間に、リリースされた成果物と表示が食い違う経路ができる。
 */
public final class BuildInfo {

    /** 版数を読めなかったときに返す値。 */
    static final String UNKNOWN = "unknown";

    private static final String RESOURCE = "build.properties";

    private static final String VERSION = readVersion();

    private BuildInfo() {
    }

    /**
     * 版数を返す。
     *
     * <p>{@code 0.1.0-SNAPSHOT} のような開発版の接尾辞は落とさない。
     * 開発中のものを正式版に見せないため（CLAUDE.md 判断の優先順位 2）。
     *
     * @return 版数。読めなかった場合は {@code "unknown"}。{@code null} にも空にもならない
     */
    public static String version() {
        return VERSION;
    }

    /**
     * リソースから版数を読む。
     *
     * <p>読めなくても例外を投げない。版数が分からないことでアプリが起動しないのは本末転倒であり、
     * 版数は表示のためだけに使う値である。
     */
    private static String readVersion() {
        Properties properties = new Properties();
        try (InputStream stream = BuildInfo.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                return UNKNOWN;
            }
            properties.load(stream);
        } catch (IOException e) {
            return UNKNOWN;
        }
        String version = properties.getProperty("version", "").trim();
        return version.isEmpty() ? UNKNOWN : version;
    }
}
