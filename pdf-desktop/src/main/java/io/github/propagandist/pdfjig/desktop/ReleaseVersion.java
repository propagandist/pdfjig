package io.github.propagandist.pdfjig.desktop;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 版数。
 *
 * <p>読むのは 2 か所から来る文字列である。{@code BuildInfo.version()} が返す
 * {@code 0.1.1} / {@code 0.1.2-SNAPSHOT} / {@code unknown} の 3 種と、GitHub のタグ
 * {@code v0.1.1} である。<b>{@code unknown} は読めない</b>——空を返す。
 *
 * <p><b>★ 比べるのは数値 3 つだけである。</b>接尾辞に順序を与えない——{@code -SNAPSHOT} が
 * {@code -rc1} の前か後かを、この道具は決める立場にない。代わりに<b>接尾辞が付いていたことだけを
 * 覚える</b>（{@link #development()}）。開発版を「最新です」と答えさせないために要る
 * （{@code CLAUDE.md} 優先順位 2。{@code BuildInfo} が接尾辞を落とさないのと同じ理由）。
 *
 * @param major       主番号
 * @param minor       副番号
 * @param patch       修正番号
 * @param development 接尾辞が付いていたか。{@code 0.1.2-SNAPSHOT} なら {@code true}
 */
record ReleaseVersion(int major, int minor, int patch, boolean development) {

    /**
     * 読み取る形。
     *
     * <p><b>★ 桁を 9 までに縛る。</b>{@link Integer#parseInt} は 10 桁で溢れ、
     * {@code NumberFormatException} になる。ここへ来る文字列は<b>応答のヘッダ</b>——
     * こちらが形を決められないもの——なので、<b>読めないことは投げる理由にならない</b>。
     * 縛っておけば {@code parseInt} が失敗する経路がそもそも無くなる。
     */
    private static final Pattern FORM = Pattern.compile("v?(\\d{1,9})\\.(\\d{1,9})\\.(\\d{1,9})(-.*)?");

    /**
     * 文字列から読む。
     *
     * @param text 版数。{@code null} でも空でもよい
     * @return 読めた版数。読めなければ空
     */
    static Optional<ReleaseVersion> parse(String text) {
        if (text == null) {
            return Optional.empty();
        }
        Matcher form = FORM.matcher(text.trim());
        if (!form.matches()) {
            return Optional.empty();
        }
        return Optional.of(new ReleaseVersion(
                Integer.parseInt(form.group(1)),
                Integer.parseInt(form.group(2)),
                Integer.parseInt(form.group(3)),
                form.group(4) != null));
    }

    /**
     * この版が、渡された版より古いか。
     *
     * <p><b>接尾辞は見ない。</b>{@code 0.1.2-SNAPSHOT} は {@code 0.1.2} より古くない。
     * 開発版であることは {@link #development()} が別に持つ。
     *
     * @param other 比べる相手
     * @return 数値 3 つの比較で古ければ {@code true}
     */
    boolean isOlderThan(ReleaseVersion other) {
        if (major != other.major) {
            return major < other.major;
        }
        if (minor != other.minor) {
            return minor < other.minor;
        }
        return patch < other.patch;
    }

    /**
     * 画面に出す形。
     *
     * <p><b>接尾辞は復元しない。</b>この形で出すのは<b>公開されている版</b>だけであり、
     * そちらに接尾辞は付かない（{@link UpdateCheck#published}）。
     */
    String text() {
        return major + "." + minor + "." + patch;
    }
}
