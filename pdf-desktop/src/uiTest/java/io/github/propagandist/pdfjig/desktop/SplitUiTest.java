package io.github.propagandist.pdfjig.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.propagandist.pdfjig.core.PdfDocument;
import io.github.propagandist.pdfjig.core.TestPdfs;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.Start;
import org.testfx.framework.junit5.Stop;
import org.testfx.util.WaitForAsyncUtils;

/**
 * 区切りを付けて分割する経路。
 *
 * <p>切り出すのは<b>編集中の並び</b>である。並べ替えや削除をした後で分割したとき、
 * それが反映されない結果を渡すほうが利用者を惑わせる。
 */
class SplitUiTest extends DesktopUiTest {

    @Start
    void start(Stage stage) {
        setUp(stage);
    }

    @Stop
    void stop() {
        tearDown();
    }

    @Test
    void 区切りを付けると分かれる数を出す(@TempDir Path dir, FxRobot robot) throws Exception {
        openFixture(robot, TestPdfs.plain(dir.resolve("doc.pdf"), 4));

        robot.clickOn("#thumbnail-tile-2");
        robot.clickOn("#tool-toggle-break");
        WaitForAsyncUtils.waitForFxEvents();

        assertEquals("4 / 4 ページ　区切り 1 か所 → 2 ファイルに分かれます", statusText(robot));
    }

    @Test
    void 先頭のページには区切りを付けられない(@TempDir Path dir, FxRobot robot) throws Exception {
        openFixture(robot, TestPdfs.plain(dir.resolve("doc.pdf"), 3));

        robot.clickOn("#thumbnail-tile-0");
        WaitForAsyncUtils.waitForFxEvents();

        // 先頭は区切らなくてもファイルの始まりである。押せてしまうと意味のない操作を許すことになる。
        assertTrue(button(robot, "#tool-toggle-break").isDisabled());
    }

    @Test
    void 区切りが無いまま分割を押すと断って何も書かない(@TempDir Path dir, FxRobot robot)
            throws Exception {
        openFixture(robot, TestPdfs.plain(dir.resolve("doc.pdf"), 3));

        Path outputDir = Files.createDirectory(dir.resolve("out"));
        dialogs.willChooseFolder(outputDir);
        robot.clickOn("#tool-split");

        // 全ページを 1 ファイルに書き出しても分割にならない。黙ってそうするより断る。
        clickWhenReady(robot, "#message-ok");
        WaitForAsyncUtils.waitForFxEvents();

        assertEquals(List.of(), listPdfs(outputDir));
    }

    @Test
    void 区切ったところで切り分けて書き出す(@TempDir Path dir, FxRobot robot) throws Exception {
        openFixture(robot, TestPdfs.plain(dir.resolve("doc.pdf"), 4));

        robot.clickOn("#thumbnail-tile-2");
        robot.clickOn("#tool-toggle-break");

        Path outputDir = Files.createDirectory(dir.resolve("out"));
        dialogs.willChooseFolder(outputDir);
        robot.clickOn("#tool-split");

        clickWhenReady(robot, "#message-ok");
        WaitForAsyncUtils.waitForFxEvents();

        // 名前は pdf-core の分割と同じ形にそろえてある。
        assertEquals(List.of("doc_001.pdf", "doc_002.pdf"), listPdfs(outputDir));

        try (PdfDocument first = PdfDocument.open(outputDir.resolve("doc_001.pdf"));
                PdfDocument second = PdfDocument.open(outputDir.resolve("doc_002.pdf"))) {
            assertEquals(2, first.pageCount());
            assertEquals(2, second.pageCount());
        }
    }

    @Test
    void 削除してから分割すると編集後の並びが切り分けられる(@TempDir Path dir, FxRobot robot)
            throws Exception {
        openFixture(robot, TestPdfs.plain(dir.resolve("doc.pdf"), 5));

        // 1 枚消してから区切る。pdf-core の split は元の並びを切るため、ここでは使っていない。
        robot.clickOn("#thumbnail-tile-0");
        robot.clickOn("#tool-delete");
        WaitForAsyncUtils.waitForFxEvents();

        robot.clickOn("#thumbnail-tile-2");
        robot.clickOn("#tool-toggle-break");

        Path outputDir = Files.createDirectory(dir.resolve("out"));
        dialogs.willChooseFolder(outputDir);
        robot.clickOn("#tool-split");

        clickWhenReady(robot, "#message-ok");
        WaitForAsyncUtils.waitForFxEvents();

        try (PdfDocument first = PdfDocument.open(outputDir.resolve("doc_001.pdf"));
                PdfDocument second = PdfDocument.open(outputDir.resolve("doc_002.pdf"))) {
            // 消した 1 枚は出てこない。4 枚が 2 + 2 に分かれる。
            assertEquals(2, first.pageCount());
            assertEquals(2, second.pageCount());
        }
    }

    @Test
    void すべてを1枚ずつに分けて書き出す(@TempDir Path dir, FxRobot robot) throws Exception {
        openFixture(robot, TestPdfs.plain(dir.resolve("doc.pdf"), 4));

        Path outputDir = Files.createDirectory(dir.resolve("out"));
        dialogs.willChooseFolder(outputDir);
        robot.clickOn("#tool-split-pages");

        clickWhenReady(robot, "#message-ok");
        WaitForAsyncUtils.waitForFxEvents();

        assertEquals(
                List.of("doc_001.pdf", "doc_002.pdf", "doc_003.pdf", "doc_004.pdf"),
                listPdfs(outputDir));

        // 画面の上で数が合っていても、中身まで見なければファイルが正しい保証にはならない。
        for (String name : listPdfs(outputDir)) {
            try (PdfDocument page = PdfDocument.open(outputDir.resolve(name))) {
                assertEquals(1, page.pageCount());
            }
        }
    }

    @Test
    void 区切りがあっても1枚ずつに分ける(@TempDir Path dir, FxRobot robot) throws Exception {
        openFixture(robot, TestPdfs.plain(dir.resolve("doc.pdf"), 4));

        robot.clickOn("#thumbnail-tile-2");
        robot.clickOn("#tool-toggle-break");
        WaitForAsyncUtils.waitForFxEvents();

        Path outputDir = Files.createDirectory(dir.resolve("out"));
        dialogs.willChooseFolder(outputDir);
        robot.clickOn("#tool-split-pages");

        clickWhenReady(robot, "#message-ok");
        WaitForAsyncUtils.waitForFxEvents();

        // 区切りが 1 つあっても 2 ファイルにはならない。この操作は区切りを見ない。
        assertEquals(
                List.of("doc_001.pdf", "doc_002.pdf", "doc_003.pdf", "doc_004.pdf"),
                listPdfs(outputDir));

        // 付けてある区切りも消えない。書き出しは画面の状態を変えない。
        assertEquals("4 / 4 ページ　区切り 1 か所 → 2 ファイルに分かれます", statusText(robot));
    }

    @Test
    void ページが1枚だけなら1枚ずつには分けられない(@TempDir Path dir, FxRobot robot)
            throws Exception {
        openFixture(robot, TestPdfs.plain(dir.resolve("doc.pdf"), 1));

        // できるのは元と同じ 1 ファイルだけで分割にならない。区切りが無いときと違って
        // 利用者に打つ手も無いため、断るのではなく初めから押させない。
        assertTrue(button(robot, "#tool-split-pages").isDisabled());
    }

    @Test
    void 出力先に同名があれば1つも書かない(@TempDir Path dir, FxRobot robot) throws Exception {
        openFixture(robot, TestPdfs.plain(dir.resolve("doc.pdf"), 3));

        Path outputDir = Files.createDirectory(dir.resolve("out"));
        // 3 枚のうち 2 枚目の行き先だけを塞いでおく。
        Path blocker = outputDir.resolve("doc_002.pdf");
        Files.writeString(blocker, "書き換えられては困るもの");

        dialogs.willChooseFolder(outputDir);
        robot.clickOn("#tool-split-pages");

        clickWhenReady(robot, "#message-ok");
        WaitForAsyncUtils.waitForFxEvents();

        // 1 つでも書けないなら何も書かない。1 枚目と 3 枚目も作られていない。
        assertEquals(List.of("doc_002.pdf"), listPdfs(outputDir));
        // 上書きするかどうかは利用者の判断であり、黙って潰さない。
        assertEquals("書き換えられては困るもの", Files.readString(blocker));
    }

    private static List<String> listPdfs(Path directory) throws Exception {
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".pdf"))
                    .sorted()
                    .toList();
        }
    }
}
