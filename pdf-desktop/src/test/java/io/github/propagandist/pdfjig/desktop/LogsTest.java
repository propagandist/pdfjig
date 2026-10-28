package io.github.propagandist.pdfjig.desktop;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.propagandist.pdfjig.core.ErrorCode;
import io.github.propagandist.pdfjig.core.PdfjigException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link Logs} の振る舞い。
 *
 * <p><b>★ 中心は「書かないと決めたものが書かれていないこと」である。</b>
 * ログは利用者の PC に残り、アンインストールでも消えない（{@code docs/SPEC.md} §10.3）。
 * 何を書かないかは §10.4 が決めており、<b>それを機械で縛るのがここ</b>——
 * 決めただけの線は、次に書く者には見えない。
 */
class LogsTest {

    /** 業務の文書らしいパス。これがログに出たら負けである。 */
    private static final String SECRET_PATH = "C:\\業務\\2026\\機密_契約書.pdf";

    /** 例外のメッセージに紛れうるパスワード（CLAUDE.md INV-5）。 */
    private static final String PASSWORD = "hunter2-very-secret";

    /**
     * {@code Logs} が使うファイル名の形。
     *
     * <p><b>ここだけは写しである。</b>{@code Logs} の側は private で、外へ出すと
     * 「置き場の形を知っているのは Logs だけ」という線が崩れる。
     * <b>ずれたら下の 2 つ目のテストが赤になる</b>——2 つのハンドラが同じ錠を争わなくなるためである。
     */
    private static final String LOG_FILE_NAME = "pdfjig.%u.%g.log";

    @AfterEach
    void closeLogs() {
        // 静的な状態を持つので、テストの間に持ち越さない。開いたままだと Windows で
        // @TempDir の後始末が失敗する（.lck が掴まれたままになる）。
        Logs.stop();
    }

    @Test
    @DisplayName("置き場を決めていなければ、書こうとしても何も作らない")
    void writesNothingWithoutDirectory(@TempDir Path directory) {
        Logs.stop();

        Logs.warn(LogEvent.OPERATION_FAILED, new IOException("boom"));

        assertTrue(isEmptyDirectory(directory));
    }

    @Test
    @DisplayName("最初の 1 件が出るまでファイルもフォルダも作らない")
    void staysOutOfTheWayUntilSomethingHappens(@TempDir Path directory) {
        Path logs = directory.resolve("logs");
        Logs.startIn(logs, 1024, 2);

        assertFalse(Files.exists(logs), "正常に動いている限り、置き場そのものを作らない");

        Logs.warn(LogEvent.OPERATION_FAILED, new IOException("boom"));

        assertFalse(readAll(logs).isBlank(), "1 件目でファイルが開かれる");
    }

    @Test
    @DisplayName("★ 例外のメッセージを書かない。パスもファイル名もそこから入る")
    void neverWritesExceptionMessages(@TempDir Path logs) {
        Logs.startIn(logs, 64 * 1024, 2);

        // NoSuchFileException のメッセージはパスそのものである。SimpleFormatter を使うと
        // ここが素通りする——だから独自の Formatter が要る。
        Logs.warn(LogEvent.SETTINGS_UNWRITABLE, new NoSuchFileException(SECRET_PATH));

        String written = readAll(logs);
        assertFalse(written.contains(SECRET_PATH), "パスが出てはならない");
        assertFalse(written.contains("機密_契約書"), "ファイル名が出てはならない");
        assertTrue(written.contains(NoSuchFileException.class.getName()), "型名は出る");
    }

    @Test
    @DisplayName("★ 原因の連鎖に紛れたパスワードも書かない（INV-5）")
    void neverWritesPasswordsHiddenInCauses(@TempDir Path logs) {
        Logs.startIn(logs, 64 * 1024, 2);
        IOException root = new IOException("password=" + PASSWORD);
        IllegalStateException middle = new IllegalStateException("opening " + SECRET_PATH, root);

        Logs.severe(LogEvent.UNCAUGHT, new RuntimeException("wrapping " + SECRET_PATH, middle));

        String written = readAll(logs);
        assertFalse(written.contains(PASSWORD), "パスワードが出てはならない");
        assertFalse(written.contains(SECRET_PATH), "連鎖の途中のパスも出てはならない");
        assertTrue(written.contains(IOException.class.getName()), "連鎖はたどる。型名だけを書く");
    }

    @Test
    @DisplayName("PdfjigException は分類と原因の型名まで書く")
    void keepsTheDiagnosticsThatAreSafe(@TempDir Path logs) {
        // PdfjigException は入力値を持たないことが保証されている（あちらの Javadoc）。
        // 診断の役に立つので、そこだけは読む。
        Logs.startIn(logs, 64 * 1024, 2);

        Logs.warn(
                LogEvent.OPERATION_FAILED,
                PdfjigException.wrapping(ErrorCode.IO_FAILURE, new NoSuchFileException(SECRET_PATH)));

        String written = readAll(logs);
        assertTrue(written.contains(ErrorCode.IO_FAILURE.name()), "失敗の分類は出る");
        assertTrue(written.contains("cause=" + NoSuchFileException.class.getName()), "原因の型名は出る");
        assertFalse(written.contains(SECRET_PATH), "包んだ元のパスは出ない");
    }

    /**
     * 包んだ失敗でも、原因が投げられた位置を書く（#152）。
     *
     * <p><b>包む前は書けていた。</b>包んでから、診断ファイルに残るのは「{@code open} の中で
     * 何かの {@code IllegalArgumentException} が起きた」までになった。報せを受けても、どこで落ちたのかが読めない。
     */
    @Test
    @DisplayName("★ 包んだ失敗でも、原因が投げられた位置を書く。原因のメッセージは書かない")
    void writesWhereTheWrappedCauseWasThrown(@TempDir Path logs) {
        Logs.startIn(logs, 64 * 1024, 2);
        IllegalArgumentException cause = new IllegalArgumentException("password=" + PASSWORD);
        cause.setStackTrace(frames(3));

        Logs.warn(LogEvent.OPERATION_FAILED, PdfjigException.wrapping(ErrorCode.NOT_A_PDF, cause));

        String written = readAll(logs);
        assertTrue(written.contains("\tCaused by " + IllegalArgumentException.class.getName()), "原因の型を見出しにする");
        assertTrue(written.contains("at Frame0.run(Frame.java:0)"), "原因が投げられた位置を落としている（#152）");
        assertFalse(written.contains(PASSWORD), "原因のメッセージが出てはならない");
    }

    /**
     * 抑えられた例外も、型名とフレームだけを書く（#152）。
     *
     * <p>try-with-resources は {@code close()} の失敗を抑えて付ける。{@code getCause} だけを辿ると、
     * その失敗は診断ファイルに載りようがない。
     */
    @Test
    @DisplayName("★ 抑えられた例外も、型名とフレームを書く。メッセージは書かない")
    void writesSuppressedFailures(@TempDir Path logs) {
        Logs.startIn(logs, 64 * 1024, 2);
        IOException closing = new IOException("closing " + SECRET_PATH);
        closing.setStackTrace(frames(2));
        IllegalStateException failed = new IllegalStateException("failed " + SECRET_PATH);
        failed.addSuppressed(closing);

        Logs.warn(LogEvent.OPERATION_FAILED, failed);

        String written = readAll(logs);
        assertTrue(written.contains("\tSuppressed: " + IOException.class.getName()), "抑えられた例外を辿っていない");
        assertTrue(written.contains("at Frame1.run(Frame.java:1)"), "抑えられた例外の位置を落としている");
        assertFalse(written.contains(SECRET_PATH), "抑えられた例外のメッセージが出てはならない");
    }

    /**
     * 抑えられた包んだ失敗も、原因が投げられた位置を書く（#152 の門の 2 段目）。
     *
     * <p>開いた後に投げた失敗へ、閉じ損ねた失敗（包んだもの）を抑えて付ける経路がある。
     * そこで位置を落とすと、PDFBox のどこで閉じ損ねたのかが読めない。
     */
    @Test
    @DisplayName("★ 抑えられた包んだ失敗も、原因が投げられた位置を書く")
    void writesWhereASuppressedWrappedCauseWasThrown(@TempDir Path logs) {
        Logs.startIn(logs, 64 * 1024, 2);
        IOException closing = new IOException("closing " + SECRET_PATH);
        closing.setStackTrace(frames(2));
        IllegalStateException failed = new IllegalStateException("failed");
        failed.addSuppressed(PdfjigException.wrapping(ErrorCode.IO_FAILURE, closing));

        Logs.warn(LogEvent.OPERATION_FAILED, failed);

        String written = readAll(logs);
        assertTrue(written.contains("at Frame1.run(Frame.java:1)"), "抑えられた包んだ失敗の位置を落としている");
        assertFalse(written.contains(SECRET_PATH));
    }

    /**
     * 抑えられた失敗は、包んだ原因より先に書く（#152 の門の 2 段目）。
     *
     * <p>原因の型の下に続けて書くと、抑えられた失敗が原因のほうに付いていたと読まれる。
     * {@code printStackTrace} と同じく、その例外のフレームの直後に置く。
     */
    @Test
    @DisplayName("抑えられた失敗は、包んだ原因より先に書く")
    void writesSuppressedBeforeTheWrappedCause(@TempDir Path logs) {
        Logs.startIn(logs, 64 * 1024, 2);
        PdfjigException failure = PdfjigException.wrapping(ErrorCode.NOT_A_PDF, new IllegalArgumentException());
        failure.addSuppressed(new IOException());

        Logs.warn(LogEvent.OPERATION_FAILED, failure);

        String written = readAll(logs);
        int suppressed = written.indexOf("\tSuppressed: " + IOException.class.getName());
        int cause = written.indexOf("\tCaused by " + IllegalArgumentException.class.getName());
        assertTrue(suppressed >= 0 && cause >= 0, "どちらかを書いていない");
        assertTrue(suppressed < cause, "抑えられた失敗が、包んだ原因に付いていたように読める");
    }

    /**
     * 抑えられた失敗の数も途中で切り、切ったことを書く（#152 の門の 2 段目）。
     *
     * <p>まとめて閉じると、閉じ損ねた数だけ付く。1 件が世代を丸ごと押し出すと、
     * 押し出されるのは直前に起きた本当の原因のほうである（フレームの上限と同じ理由）。
     */
    @Test
    @DisplayName("★ 抑えられた失敗の数も途中で切り、切ったことを書く")
    void trimsManySuppressedFailures(@TempDir Path logs) {
        Logs.startIn(logs, 512 * 1024, 2);
        IllegalStateException failed = new IllegalStateException("failed");
        for (int i = 0; i < 50; i++) {
            failed.addSuppressed(new IOException());
        }

        Logs.warn(LogEvent.OPERATION_FAILED, failed);

        String written = readAll(logs);
        assertTrue(written.contains("件は書いていない"), "切ったことを書く");
        assertTrue(written.split("\tSuppressed: ", -1).length - 1 < 50, "抑えられた失敗を全部書いている");
    }

    @Test
    @DisplayName("事象の名前と説明が、そのまま行に出る")
    void namesTheEvent(@TempDir Path logs) {
        Logs.startIn(logs, 64 * 1024, 2);

        Logs.warn(LogEvent.SETTINGS_UNREADABLE, new IOException("boom"));

        String written = readAll(logs);
        assertTrue(written.contains(LogEvent.SETTINGS_UNREADABLE.name()));
        assertTrue(written.contains(LogEvent.SETTINGS_UNREADABLE.description()));
        assertTrue(written.contains("WARNING"));
    }

    @Test
    @DisplayName("原因の例外が無くても 1 行残る（偽のスタックトレースは足さない）")
    void writesTheEventAloneWhenThereIsNoCause(@TempDir Path logs) {
        Logs.startIn(logs, 64 * 1024, 2);

        // 例外にならない失敗がある。応答は返ったが中身が想定と違う、といった経路である
        // （UpdateCheck）。記録のために例外をこしらえると、書かれるフレームは
        // 「作った場所」でしかなく、読む者を偽の原因へ案内する。
        Logs.warn(LogEvent.UPDATE_NOT_CHECKED);

        String written = readAll(logs);
        assertTrue(written.contains(LogEvent.UPDATE_NOT_CHECKED.name()), written);
        assertFalse(written.contains("\tat "), written);
    }

    @Test
    @DisplayName("見出しに、何を書いていないかが載る")
    void saysWhatItDoesNotWrite(@TempDir Path logs) {
        Logs.startIn(logs, 64 * 1024, 2);

        Logs.warn(LogEvent.OPERATION_FAILED, new IOException("boom"));

        assertTrue(readAll(logs).contains("パスワードは書かない"));
    }

    @Test
    @DisplayName("上限を超えると世代が回り、決めた数までしか残らない")
    void rotates(@TempDir Path logs) {
        // 上限を小さく取る。実寸（512 KB × 3）で試すと、確かめたい判断は同じまま遅くなるだけである。
        int limit = 16 * 1024;
        Logs.startIn(logs, limit, 2);

        for (int i = 0; i < 100; i++) {
            Logs.warn(LogEvent.OPERATION_FAILED, new IOException("boom"));
        }

        assertEquals(2, logFiles(logs).size(), "世代は 2 つまで");
        // 1 件を書き終えてから上限を見るので、最後の 1 件ぶんは超える。超え方が青天井でないことを見る。
        assertTrue(logFiles(logs).stream().allMatch(file -> size(file) < limit * 2L), "1 世代が上限から大きく外れない");
    }

    @Test
    @DisplayName("★ 深いスタックは途中で切り、切ったことを書く")
    void trimsDeepStacks(@TempDir Path logs) {
        // StackOverflowError は 1000 を超えるフレームを持つ。1 件が世代を丸ごと押し出すと、
        // 押し出されるのは直前に起きた本当の原因のほうである。
        Logs.startIn(logs, 512 * 1024, 2);
        Throwable deep = new IllegalStateException("boom");
        deep.setStackTrace(frames(500));

        Logs.severe(LogEvent.UNCAUGHT, deep);

        String written = readAll(logs);
        assertTrue(written.contains("以下 460 フレームは書いていない"), "切ったことを書く");
        assertFalse(written.contains("Frame460"), "上限を超えたぶんは出ない");
        assertTrue(written.contains("Frame0"), "手前は残る。原因に近いのはそちらである");
    }

    private static StackTraceElement[] frames(int count) {
        StackTraceElement[] frames = new StackTraceElement[count];
        for (int i = 0; i < count; i++) {
            frames[i] = new StackTraceElement("Frame" + i, "run", "Frame.java", i);
        }
        return frames;
    }

    @Test
    @DisplayName("★ 原因の連鎖も途中で切り、切ったことを書く")
    void marksTheCutOfTheCauseChain(@TempDir Path logs) {
        Logs.startIn(logs, 64 * 1024, 2);
        Throwable deepest = new IOException("boom");
        Throwable chained = deepest;
        for (int i = 0; i < 8; i++) {
            chained = new IllegalStateException("layer " + i, chained);
        }

        Logs.severe(LogEvent.UNCAUGHT, chained);

        assertTrue(readAll(logs).contains("これより奥は書いていない"), "5 つ目が根だと読まれないようにする");
    }

    @Test
    @DisplayName("★ 2 つ目のプロセスが同時に書いても、決めた形の外へ出さない")
    void keepsTheFileSetEvenWithASecondProcess(@TempDir Path logs) throws IOException {
        // この道具は 2 つ目の窓を止めていない（OutputWorkspace も 2 つ目を前提に書いてある）。
        // FileHandler は錠を取れないと番号を繰り上げるが、%u が無いと名前の末尾へ .1 を継ぎ足し、
        // pdfjig.0.log.1 という決めた形の外のファイルを作る。
        Logs.startIn(logs, 64 * 1024, 2);
        Logs.warn(LogEvent.OPERATION_FAILED, new IOException("boom"));

        FileHandler second = new FileHandler(logs + "/" + LOG_FILE_NAME, 64 * 1024, 2, true);
        try {
            second.publish(new LogRecord(Level.WARNING, "from the second process"));
        } finally {
            second.close();
        }

        assertTrue(
                names(logs).stream().allMatch(name -> name.endsWith(".log") || name.endsWith(".log.lck")),
                "置き場に出るのは決めた形のファイルだけである: " + names(logs));
        assertEquals(
                2, names(logs).stream().filter(name -> name.endsWith(".log")).count(), "2 つ目は別のファイルへ書く");
    }

    @Test
    @DisplayName("★ 置き場のパスに % が入っていても、その中に書く")
    void survivesPercentInThePath(@TempDir Path directory) {
        // FileHandler のパターンは %g / %u / %t / %h を置き換える。逃がさないと違う場所へ書きに行く。
        Path logs = directory.resolve("100% 完了");
        Logs.startIn(logs, 64 * 1024, 2);

        Logs.warn(LogEvent.OPERATION_FAILED, new IOException("boom"));

        assertFalse(logFiles(logs).isEmpty(), "決めたフォルダの中に書かれる");
    }

    @Test
    @DisplayName("書けない置き場でも投げない")
    void givesUpQuietlyWhenTheDirectoryCannotBeCreated(@TempDir Path directory) throws IOException {
        // ファイルの下にはフォルダを作れない。createDirectories がここで失敗する。
        Path blocker = Files.createFile(directory.resolve("blocker"));
        Logs.startIn(blocker.resolve("logs"), 64 * 1024, 2);

        assertDoesNotThrow(() -> Logs.warn(LogEvent.OPERATION_FAILED, new IOException("boom")));
    }

    private static List<String> names(Path logs) {
        if (!Files.isDirectory(logs)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(logs)) {
            return entries.map(entry -> entry.getFileName().toString()).sorted().toList();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private static List<Path> logFiles(Path logs) {
        if (!Files.isDirectory(logs)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(logs)) {
            return entries.filter(entry -> entry.getFileName().toString().endsWith(".log"))
                    .toList();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private static String readAll(Path logs) {
        StringBuilder text = new StringBuilder();
        for (Path file : logFiles(logs)) {
            try {
                text.append(Files.readString(file, StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new AssertionError(e);
            }
        }
        return text.toString();
    }

    private static long size(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private static boolean isEmptyDirectory(Path directory) {
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.findAny().isEmpty();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }
}
