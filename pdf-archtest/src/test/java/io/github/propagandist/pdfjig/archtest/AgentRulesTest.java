package io.github.propagandist.pdfjig.archtest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * {@code .claude/rules/} の frontmatter と、ルールの節を指す参照を検証する（#168）。
 *
 * <p><b>壊れても誰も赤くしなかった。</b>#75 で 5 本を入れたとき、閉じの {@code ---} を 5 本すべてから
 * 消していた。{@code paths:} が frontmatter として読まれないと、<b>全部が常時載るか、
 * まったく読まれないかのどちらかになる</b>。ルールは {@code .md} なので、どちらでも緑のままだった。
 *
 * <p><b>★ ここに置いたのは、コードを含む変更のたびに走るからである。</b>{@code build.yml} は
 * {@code .md} を起動条件から外しているので、<b>ルールだけを直す PR では CI が起きない</b>。
 * そのときは次にコードを含む PR が赤くなる。手元では {@code ./gradlew :pdf-archtest:agentRulesTest} で走る。
 * 起動条件を広げる案は、ルールを 1 行直すたびに windows が 6 分走るので採らなかった（#168）。
 *
 * <p><b>★ 見るのは git が追跡しているファイルだけである。</b>作業ツリーを歩くと、
 * {@code .claude/worktrees/} の古い写しや追跡外のファイルを拾い、<b>手元でだけ赤く、
 * あるいは手元でだけ緑になる</b>。
 *
 * <p><b>★ 参照の検証は #170 から来た。</b>節を移したとき、移した節を指す参照が 30 か所ほど残った。
 * {@code <ルール名>.md「<節名>」} の形だけを見る。{@code CLAUDE.md「…」} は見ない。
 * <b>{@code docs/HANDOVER.md}「決まったことの記録」も見ない</b>——当時の節名を指すのが正しく、
 * 検査に掛けると、節を改名するたびに過去の記録を書き換えさせることになる。
 */
class AgentRulesTest {

    private static final Pattern HEADING = Pattern.compile("^#+\\s+(.+?)\\s*$");

    private static final Pattern GLOB_ITEM = Pattern.compile("^  - \"([^\"]+)\"$");

    /**
     * {@code desktop-ui.md「JavaFX」} や、Javadoc の code タグ・バッククォートで包んだ形。
     * <b>折り返しを挟んでもよい</b>（文書は 80〜100 桁で折り返す）。節名の中の改行は許さない。
     */
    private static final Pattern SECTION_REFERENCE = Pattern.compile("([a-z][a-z-]*)\\.md[`}]?\\s*「([^」\\n]+)」");

    /** ここから先は過去の記録であり、当時の節名を指す。 */
    private static final String RECORD_HEADING = "### 決まったことの記録";

    private static Path root;
    private static List<String> tracked;
    private static Map<String, List<String>> frontmatters;
    private static Map<String, Set<String>> headings;

    @BeforeAll
    static void load() throws IOException, InterruptedException {
        Path here = Path.of("").toAbsolutePath();
        while (here != null && !Files.exists(here.resolve("settings.gradle.kts"))) {
            here = here.getParent();
        }
        assertTrue(here != null, "リポジトリの根（settings.gradle.kts）が見つからない");
        root = here;

        Process git = new ProcessBuilder("git", "ls-files", "-z")
                .directory(root.toFile())
                .redirectErrorStream(true)
                .start();
        String listing = new String(git.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, git.waitFor(), "git ls-files が失敗した: " + listing);
        tracked = Arrays.stream(listing.split("\0"))
                .filter(f -> !f.isEmpty())
                .filter(f -> Files.exists(root.resolve(f)))
                .toList();

        frontmatters = new HashMap<>();
        headings = new HashMap<>();
        for (String file : tracked) {
            if (file.startsWith(".claude/rules/") && file.endsWith(".md")) {
                String name = file.substring(".claude/rules/".length());
                String text = read(file);
                frontmatters.put(name, frontmatter(name, text));
                headings.put(name, headingsOf(text));
            }
        }
    }

    /** 対象が 0 本なら、下の検証はすべて黙って成功する（{@link ArchitectureTest} の冒頭と同じ理由）。 */
    @Test
    void rulesExist() {
        assertFalse(frontmatters.isEmpty(), ".claude/rules/*.md が 1 本も見つからない");
    }

    /** #75 で壊れたのはここである。閉じが無いと、本文の最初の {@code ---} までが frontmatter になる。 */
    @Test
    void everyRuleHasAClosedFrontmatterWithPaths() {
        frontmatters.forEach((rule, front) -> {
            assertFalse(front.isEmpty(), rule + ": frontmatter が空である");
            assertEquals("paths:", front.get(0), rule + ": frontmatter が paths: で始まらない");
            assertTrue(front.size() > 1, rule + ": paths: に glob が 1 本も無い");
            for (String line : front.subList(1, front.size())) {
                assertTrue(GLOB_ITEM.matcher(line).matches(), rule + ": paths: の項目として読めない行がある: " + line);
            }
        });
    }

    /**
     * 当たらない glob は永久に発火しない。<b>★ {@code [} と brace 展開は、版によっては 1 つで
     * ファイル読み取りを全滅させる</b>（CLAUDE.md「維持ルールの索引」）。
     *
     * <p><b>★ 照合は自前でする。</b>{@code java.nio} の {@code PathMatcher} は Windows で大小文字を
     * 区別せず、{@code **}{@code /x} が根の {@code x} に当たらない。どちらも Claude Code の照合と食い違い、
     * <b>手元でだけ緑になる</b>。
     */
    @Test
    void everyGlobMatchesATrackedFile() {
        frontmatters.forEach((rule, front) -> {
            for (String glob : globs(front)) {
                assertFalse(glob.contains("[") || glob.contains("{"), rule + ": [ と brace 展開は使わない: " + glob);
                Pattern pattern = globToRegex(glob);
                assertTrue(
                        tracked.stream().anyMatch(f -> pattern.matcher(f).matches()),
                        rule + ": 追跡しているファイルに 1 つも当たらない: " + glob);
            }
        });
    }

    /** 自前の照合そのものが、Claude Code の読み方と揃っていること。 */
    @Test
    void globMatchingIsCaseSensitiveAndDoubleStarMatchesTheRoot() {
        assertTrue(globToRegex("**/CLAUDE.md").matcher("CLAUDE.md").matches());
        assertTrue(globToRegex("pdf-*/src/main/java/**")
                .matcher("pdf-core/src/main/java/a/B.java")
                .matches());
        assertFalse(globToRegex("pdf-*/src/main/java/**")
                .matcher("pdf-core/src/Main/java/a/B.java")
                .matches());
        assertFalse(globToRegex("pdf-*/build.gradle.kts")
                .matcher("pdf-core/sub/build.gradle.kts")
                .matches());
    }

    /** 索引に載らないルールを作らない（CLAUDE.md「維持ルールの索引」）。 */
    @Test
    void theIndexListsExactlyTheRules() {
        String claude = read("CLAUDE.md");
        int start = claude.indexOf("## 維持ルールの索引");
        assertTrue(start >= 0, "CLAUDE.md に「維持ルールの索引」が無い");
        Set<String> indexed = new TreeSet<>();
        Matcher row = Pattern.compile("(?m)^\\| `([a-z-]+\\.md)` \\|").matcher(claude.substring(start));
        while (row.find()) {
            indexed.add(row.group(1));
        }
        assertEquals(new TreeSet<>(frontmatters.keySet()), indexed, "索引の行と .claude/rules/*.md が一致しない");
    }

    /** 節を移す・改名する日に赤くなる。指した先が無いと、読んだ人は探して見つからない（#170）。 */
    @Test
    void everySectionReferenceNamesAnExistingHeading() {
        List<String> broken = new ArrayList<>();
        int checked = 0;
        for (String file : tracked) {
            String text = read(file);
            if (text.indexOf('\0') >= 0) {
                continue;
            }
            if (file.equals("docs/HANDOVER.md")) {
                int record = text.indexOf(RECORD_HEADING);
                assertTrue(record >= 0, "docs/HANDOVER.md に「決まったことの記録」が無い");
                text = text.substring(0, record);
            }
            Matcher reference = SECTION_REFERENCE.matcher(text);
            while (reference.find()) {
                Set<String> known = headings.get(reference.group(1) + ".md");
                if (known == null) {
                    continue;
                }
                checked++;
                if (!known.contains(reference.group(2))) {
                    broken.add(file + " → " + reference.group(1) + ".md「" + reference.group(2) + "」");
                }
            }
        }
        assertTrue(checked > 0, "ルールの節を指す参照が 1 つも見つからない（検証が何も見ていない）");
        assertEquals(List.of(), broken, "指した節がルールに無い");
    }

    private static List<String> frontmatter(String rule, String text) {
        List<String> lines = text.lines().toList();
        assertTrue(!lines.isEmpty() && lines.get(0).equals("---"), rule + ": 開きの --- が無い");
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.equals("---")) {
                return lines.subList(1, i);
            }
            // 閉じより先に本文が来たら壊れている。#75 では最初の見出しまでが frontmatter になった。
            assertFalse(
                    line.isBlank() || line.startsWith("#") || line.startsWith("<!--"),
                    rule + ": 閉じの --- が無い（" + (i + 1) + " 行目で本文が始まる）");
        }
        throw new AssertionError(rule + ": 閉じの --- が無い");
    }

    private static List<String> globs(List<String> front) {
        return front.stream()
                .map(GLOB_ITEM::matcher)
                .filter(Matcher::matches)
                .map(m -> m.group(1))
                .toList();
    }

    /** コードブロックと HTML コメントの中の {@code #} は見出しではない。 */
    private static Set<String> headingsOf(String text) {
        Set<String> found = new HashSet<>();
        boolean fence = false;
        boolean comment = false;
        for (String line : text.lines().toList()) {
            if (line.stripLeading().startsWith("```")) {
                fence = !fence;
                continue;
            }
            if (!fence && line.contains("<!--")) {
                comment = true;
            }
            if (!fence && !comment) {
                Matcher heading = HEADING.matcher(line);
                if (heading.matches()) {
                    found.add(heading.group(1));
                }
            }
            if (comment && line.contains("-->")) {
                comment = false;
            }
        }
        return found;
    }

    /** {@code **}{@code /} は 0 個以上のディレクトリ、{@code **} は何でも、{@code *} と {@code ?} は区切りを越えない。 */
    static Pattern globToRegex(String glob) {
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (glob.startsWith("**/", i)) {
                regex.append("(?:.*/)?");
                i += 2;
            } else if (glob.startsWith("**", i)) {
                regex.append(".*");
                i += 1;
            } else if (c == '*') {
                regex.append("[^/]*");
            } else if (c == '?') {
                regex.append("[^/]");
            } else {
                regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(regex.toString());
    }

    private static String read(String file) {
        try {
            // 読めない並びは置き換える。1 本の壊れたファイルで検証ごと止めない。
            return new String(Files.readAllBytes(root.resolve(file)), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
