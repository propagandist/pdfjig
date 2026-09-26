package io.github.propagandist.pdfjig.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * 出どころを画面に出す名前（#128）。
 *
 * <p><b>★★ 名前が同じだと、取り消せない操作の対象を取り違えさせる</b>——外す確認・保護が落ちる窓・
 * 鍵を訊く窓が、どれもこの名前で相手を指す。
 */
class SourceNamesTest {

    /** 区切りは動いている環境のものを使う。配るのは Windows だけだが、CI は ubuntu でも走る。 */
    private static final String SEPARATOR = java.io.File.separator;

    /** ふだんの表示は変えない。同じ名前でなければ何も足さない。 */
    @Test
    void leavesDistinctNamesAlone(@TempDir Path dir) {
        assertEquals(
                List.of("a.pdf", "b.pdf"), SourceNames.of(List.of(dir.resolve("x/a.pdf"), dir.resolve("y/b.pdf"))));
    }

    /** 同じ名前なら、親フォルダを添える。 */
    @Test
    void addsTheParentWhenNamesCollide(@TempDir Path dir) {
        assertEquals(
                List.of("report.pdf（work）", "report.pdf（archive）", "other.pdf"),
                SourceNames.of(List.of(
                        dir.resolve("work/report.pdf"),
                        dir.resolve("archive/report.pdf"),
                        dir.resolve("work/other.pdf"))));
    }

    /** 親も同じ名前なら、違いが出るところまで遡る。 */
    @Test
    void climbsUntilTheyDiffer(@TempDir Path dir) {
        assertEquals(
                List.of("r.pdf（a" + SEPARATOR + "work）", "r.pdf（b" + SEPARATOR + "work）"),
                SourceNames.of(List.of(dir.resolve("a/work/r.pdf"), dir.resolve("b/work/r.pdf"))));
    }

    /**
     * 大文字と小文字だけが違う名前も、同じ名前として区別を付ける。
     *
     * <p><b>Windows ではどちらも同じ名前として扱われ、読み上げでは同じに聞こえる。</b>
     */
    @Test
    void treatsCaseOnlyDifferencesAsTheSameName(@TempDir Path dir) {
        assertEquals(
                List.of("Report.pdf（work）", "report.pdf（archive）"),
                SourceNames.of(List.of(dir.resolve("work/Report.pdf"), dir.resolve("archive/report.pdf"))));
    }

    /** 3 つ以上並んでも、どれもが他のどれとも違う名前になる。 */
    @Test
    void separatesEveryOneOfMany(@TempDir Path dir) {
        List<String> names = SourceNames.of(
                List.of(dir.resolve("a/work/r.pdf"), dir.resolve("b/work/r.pdf"), dir.resolve("b/home/r.pdf")));

        assertEquals(List.of("r.pdf（a" + SEPARATOR + "work）", "r.pdf（b" + SEPARATOR + "work）", "r.pdf（home）"), names);
    }

    /** 根でしか違わないなら、根を添える（Windows のドライブ）。 */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void addsTheDriveWhenOnlyTheDriveDiffers() {
        List<String> names = SourceNames.of(List.of(Path.of("C:\\report.pdf"), Path.of("D:\\report.pdf")));

        assertEquals(List.of("report.pdf（C:）", "report.pdf（D:）"), names);
    }

    /**
     * 同じファイルを 2 度足したときは、何番目かを添える。
     *
     * <p><b>★★ 遡っても違いが出ないが、取り違えて害が無いわけではない</b>——並べ替えや削除は
     * それぞれに掛かっているので、外す相手を取り違えれば失うものが違う。
     */
    @Test
    void numbersTheSameFileAddedTwice(@TempDir Path dir) {
        Path same = dir.resolve("work/r.pdf");
        List<String> names = SourceNames.of(List.of(same, same));

        assertNotEquals(names.get(0), names.get(1), "同じファイルを 2 度足すと区別が付かない");
        assertTrue(names.get(0).endsWith("、1 つ目）"), names.get(0));
        assertTrue(names.get(1).endsWith("、2 つ目）"), names.get(1));
    }

    /** 大文字と小文字しか違わず、フォルダも同じなら、何番目かを添える。読み上げでは同じに聞こえる。 */
    @Test
    void numbersNamesThatDifferOnlyInCaseWithinOneFolder(@TempDir Path dir) {
        List<String> names = SourceNames.of(List.of(dir.resolve("x/Report.pdf"), dir.resolve("x/report.pdf")));

        assertTrue(names.get(0).startsWith("Report.pdf（") && names.get(0).endsWith("、1 つ目）"), names.get(0));
        assertTrue(names.get(1).startsWith("report.pdf（") && names.get(1).endsWith("、2 つ目）"), names.get(1));
    }
}
