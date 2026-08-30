package io.github.propagandist.pdfjig.desktop;

import io.github.propagandist.pdfjig.core.PdfjigException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.logging.FileHandler;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * 診断のためのログ。
 *
 * <p><b>配布物には標準エラー出力が無い。</b>{@code jpackage} は {@code --win-console} を付けずに
 * GUI アプリとして固めるため、異常終了しても<b>手がかりが 1 つも残らない</b>。ここはそのための
 * 置き場であり、<b>操作の履歴ではない</b>。
 *
 * <p><b>書くのは {@link Level#WARNING} 以上だけである。</b>正常に動いている限りファイルは
 * 作られない——<b>最初の 1 件が出るまで開かない</b>。中身の無いファイルを置かないのは設定と
 * 同じ判断である（{@code docs/SPEC.md} §10）。
 *
 * <p><b>★ 文書のパスもファイル名も書かない。例外のメッセージも書かない。</b>
 * 何を書き何を書かないかは {@code docs/SPEC.md} §10.4 が持つ。<b>ここへ写さない。</b>
 * 守り方は 2 つある。
 *
 * <ul>
 *   <li><b>口が {@link LogEvent} しか受け取らない。</b>自由な文字列を渡せないので、
 *       呼ぶ側がうっかりパスを載せることができない
 *   <li><b>例外からは型名とスタックフレームだけを取る。</b>{@code getMessage} を読まない——
 *       {@code NoSuchFileException} のメッセージはパスそのものである。
 *       {@link PdfjigException} が原因例外を連結しない理由と同じものが、出口の側にも要る
 * </ul>
 *
 * <p><b>読めなくても書けなくても諦める。例外を投げない。</b>
 * ログが取れないことは、動かない理由ではない。
 */
final class Logs {

    /** 1 世代あたりの上限。文字数にして数千行で、原因を追うには足りる。 */
    private static final int LIMIT = 512 * 1024;

    /** 残す世代の数。現行を含む。合わせて 1.5 MB を超えない。 */
    private static final int COUNT = 3;

    /**
     * ファイル名。{@code %g} が世代に置き換わる。
     *
     * <p>現行が {@code pdfjig.0.log} で、古いものが {@code pdfjig.1.log} → {@code pdfjig.2.log} と
     * 下がっていく。<b>同じ場所に {@code .lck} が並ぶ</b>のは {@link FileHandler} の仕組みで、
     * 消せないものではない。
     */
    private static final String FILE_NAME = "pdfjig.%g.log";

    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

    /** 原因の連鎖をたどる深さの上限。循環していても止まる。 */
    private static final int CAUSE_DEPTH = 5;

    /**
     * 1 つの例外について書くフレームの数の上限。
     *
     * <p><b>★ 上限を置かないと、1 件が世代を丸ごと押し出す。</b>{@code StackOverflowError} は
     * 1000 を超えるフレームを持ち、<b>それはまさに {@link LogEvent#UNCAUGHT} に来る種類である</b>。
     * 押し出されるのは、その直前に起きた本当の原因のほうである。
     *
     * <p>手前から取る。原因に近いのはそちらで、奥は框（JavaFX のイベントループ）である。
     */
    private static final int FRAME_LIMIT = 40;

    /**
     * 記録の口。
     *
     * <p><b>親のハンドラへ渡さない。</b>渡すと JUL の既定でコンソールへ出る——
     * 画面のテストが実際のログ設定に触れない状態を保つ（設定と同じ判断。{@link PdfjigApplication}）。
     *
     * <p>強い参照で持つ。{@link Logger#getLogger} の戻り値を保持しないと、設定ごと回収されうる。
     */
    private static final Logger LOGGER = configure(Logger.getLogger("io.github.propagandist.pdfjig"));

    private static final Object LOCK = new Object();

    /** 書き出す先。{@link #startIn} を呼ぶまでは {@code null}。 */
    private static Path directory;

    /** 開こうとしたかどうか。失敗しても二度目を試さない。 */
    private static boolean attempted;

    /** 開けたハンドラ。開いていなければ {@code null}。 */
    private static Handler handler;

    /** 1 世代あたりの上限。{@link #startIn} が上書きする。 */
    private static int limit = LIMIT;

    /** 残す世代の数。{@link #startIn} が上書きする。 */
    private static int count = COUNT;

    private Logs() {}

    private static Logger configure(Logger logger) {
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.WARNING);
        return logger;
    }

    /**
     * ログを取り始める。
     *
     * <p><b>置き場が無い環境では何もしない。</b>Windows 以外がそれに当たる
     * （{@link UserDataDirectory}）。
     *
     * <p>あわせて、どこも捕まえなかった例外をここへ流す。<b>従来の標準エラーへの出力は残す</b>
     * ——配布物ではどこへも出ないが、手元で {@code ./gradlew run} したときの手がかりが減る。
     */
    static void start() {
        UserDataDirectory.logDirectory().ifPresent(logs -> startIn(logs, LIMIT, COUNT));

        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            severe(LogEvent.UNCAUGHT, throwable);
            if (previous != null) {
                previous.uncaughtException(thread, throwable);
            } else {
                throwable.printStackTrace();
            }
        });
    }

    /**
     * 書き出す先を決める。
     *
     * <p>実際に開くのは最初の 1 件が出たときである。<b>ここではフォルダも作らない。</b>
     *
     * @param logs  書き出す先のフォルダ
     * @param limit 1 世代あたりの上限（バイト）
     * @param count 残す世代の数
     */
    static void startIn(Path logs, int limit, int count) {
        synchronized (LOCK) {
            close();
            directory = logs;
            Logs.limit = limit;
            Logs.count = count;
            attempted = false;
        }
    }

    /**
     * 開いているファイルを閉じる。
     *
     * <p><b>★ アプリの終了時には呼ばない。</b>閉じる途中で落ちたものを取り逃がす。
     * {@link java.util.logging.LogManager} が停止フックで閉じるので、放っておいてよい
     * （{@link java.util.logging.StreamHandler} は 1 件ごとに flush する）。
     * 置き場を決め直すときのための口である。
     */
    static void stop() {
        synchronized (LOCK) {
            close();
            directory = null;
            attempted = false;
        }
    }

    /**
     * 想定できる失敗を記録する。
     *
     * @param event 何が起きたか
     * @param cause 原因。型名とスタックフレームだけが読まれる
     */
    static void warn(LogEvent event, Throwable cause) {
        write(Level.WARNING, event, cause);
    }

    /**
     * 想定していない失敗を記録する。
     *
     * @param event 何が起きたか
     * @param cause 原因。型名とスタックフレームだけが読まれる
     */
    static void severe(LogEvent event, Throwable cause) {
        write(Level.SEVERE, event, cause);
    }

    private static void write(Level level, LogEvent event, Throwable cause) {
        synchronized (LOCK) {
            if (!open()) {
                return;
            }
        }
        LogRecord record = new LogRecord(level, event.name() + " " + event.description());
        record.setThrown(cause);
        LOGGER.log(record);
    }

    /**
     * 最初の 1 件が出たときにファイルを開く。
     *
     * <p><b>一度失敗したら二度は試さない。</b>書けない理由（権限・ディスク）は同じセッションの
     * 中で変わらないことが多く、失敗のたびに開き直すと、記録を残そうとして遅くなる。
     */
    private static boolean open() {
        if (attempted) {
            return handler != null;
        }
        attempted = true;
        if (directory == null) {
            return false;
        }
        try {
            Files.createDirectories(directory);
            FileHandler opened = new FileHandler(pattern(directory), limit, count, true);
            opened.setEncoding(StandardCharsets.UTF_8.name());
            opened.setFormatter(new Diagnostics());
            opened.setLevel(Level.WARNING);
            LOGGER.addHandler(opened);
            handler = opened;
            return true;
        } catch (IOException | RuntimeException e) {
            // 書けなくても諦める。ログが取れないことは、動かない理由ではない（SPEC §10.2 と同じ線）。
            return false;
        }
    }

    /**
     * {@link FileHandler} に渡すパターンを組み立てる。
     *
     * <p><b>★ 置き場のパスに入った {@code %} を逃がす。</b>{@code FileHandler} は
     * {@code %g} / {@code %u} / {@code %t} / {@code %h} を置き換えるため、
     * {@code C:\Users\...\100% 完了\} のようなフォルダの下では違う場所へ書きに行く。
     * {@code %%} だけがその場の {@code %} を意味する。
     */
    private static String pattern(Path logs) {
        return logs.toString().replace("%", "%%") + "/" + FILE_NAME;
    }

    private static void close() {
        if (handler != null) {
            LOGGER.removeHandler(handler);
            handler.close();
            handler = null;
        }
    }

    /**
     * 書き出しの形。
     *
     * <p><b>例外のメッセージを読まない。</b>{@link java.util.logging.SimpleFormatter} は
     * {@code printStackTrace} を通すので使えない——{@code NoSuchFileException} のメッセージは
     * パスそのものであり、<b>書かないと決めたものがそこから入る</b>。
     *
     * <p>スタックフレームは書く。{@link StackTraceElement} が持つのはクラス名・メソッド名・
     * ソースファイル名・行番号だけで、<b>利用者のファイルの名前は入らない</b>。
     */
    private static final class Diagnostics extends Formatter {

        private static final String NEW_LINE = System.lineSeparator();

        @Override
        public String format(LogRecord record) {
            StringBuilder text = new StringBuilder(512);
            text.append(TIMESTAMP.format(Instant.ofEpochMilli(record.getMillis())))
                    .append(' ')
                    .append(record.getLevel().getName())
                    .append(' ')
                    .append(record.getMessage())
                    .append(NEW_LINE);
            appendCauses(text, record.getThrown());
            return text.toString();
        }

        /**
         * 見出し。<b>何を書いていないかを、ログ自身に書いておく。</b>
         *
         * <p>報告のために貼る人が、伏せ字にする手間をかけずに済む——
         * <b>載っていないことがその場で読める</b>（CLAUDE.md 優先順位 2）。
         */
        @Override
        public String getHead(Handler target) {
            return "# " + AppInfo.diagnostics().replace(NEW_LINE, NEW_LINE + "# ") + NEW_LINE
                    + "# 文書のパスもファイル名も、例外のメッセージも書いていない。" + NEW_LINE
                    + "# パスワードは書かない（CLAUDE.md INV-5）。" + NEW_LINE
                    + "# 消してよい。次に必要になったとき作り直す。" + NEW_LINE;
        }

        private static void appendCauses(StringBuilder text, Throwable thrown) {
            Throwable current = thrown;
            for (int depth = 0; current != null && depth < CAUSE_DEPTH; depth++) {
                text.append(depth == 0 ? "\t" : "\tCaused by ")
                        .append(describe(current))
                        .append(NEW_LINE);
                StackTraceElement[] frames = current.getStackTrace();
                int shown = Math.min(frames.length, FRAME_LIMIT);
                for (int i = 0; i < shown; i++) {
                    text.append("\t\tat ").append(frames[i]).append(NEW_LINE);
                }
                if (frames.length > shown) {
                    // 切ったことを書く。黙って切ると、そこが底だと読まれる。
                    text.append("\t\t... 以下 ")
                            .append(frames.length - shown)
                            .append(" フレームは書いていない")
                            .append(NEW_LINE);
                }
                Throwable cause = current.getCause();
                current = cause == current ? null : cause;
            }
        }

        /**
         * 例外を 1 行で表す。
         *
         * <p>{@link PdfjigException} は失敗の分類と原因の型名を持っており、
         * <b>どちらも利用者の入力を含まないことが保証されている</b>（あちらの Javadoc）。
         * 診断の役に立つので、そこだけは読む。
         */
        private static String describe(Throwable thrown) {
            String type = thrown.getClass().getName();
            if (thrown instanceof PdfjigException failure) {
                String causeType = failure.causeType();
                return type + " [" + failure.errorCode() + "]" + (causeType == null ? "" : " cause=" + causeType);
            }
            return type;
        }
    }
}
