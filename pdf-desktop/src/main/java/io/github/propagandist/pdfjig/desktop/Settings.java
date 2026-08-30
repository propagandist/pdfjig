package io.github.propagandist.pdfjig.desktop;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.Properties;

/**
 * 再起動をまたいで覚えておく設定。
 *
 * <p><b>覚えるのはダイアログを始めるフォルダ 2 つだけである。</b>
 * 増やすときは {@code docs/SPEC.md}「利用者の PC に置くもの」にも足すこと——
 * <b>何を覚えているかは、利用者が確かめられる状態を保つ</b>（CLAUDE.md 優先順位 2）。
 *
 * <p><b>★ パスワードをここへ書いてはならない</b>（CLAUDE.md INV-5）。
 * 設定ファイルは平文であり、消えずに残る。{@link SettingsTest} が明示的に見ている。
 *
 * <p><b>形式は {@link Properties} の text 形式（UTF-8）。</b>JDK だけで足り、
 * 依存も jlink のモジュールも増えない。人が開いて読めることを優先した。
 * <b>★ {@code store} は {@code \} と {@code :} をエスケープする</b>ので、
 * {@code C:\scan} はファイルの中で {@code C\:\scan} と見える。読み書きは対なので壊れないが、
 * 手で直すときに驚かないように書いておく。
 *
 * <p><b>読めなければ空として扱い、書けなければ諦める。例外を投げない。</b>
 * 覚えているのは次にダイアログが開くフォルダだけで、失われても選び直せば済む。
 * <b>設定が壊れているせいでアプリが起動しない、という形を作らない。</b>
 * ★ ログの仕組みが入ったら、捨てたこと・書けなかったことをそこへ出す（#13）。
 */
final class Settings {

    /** 最後に PDF を読んだフォルダ。 */
    static final String READING_FOLDER = "folder.reading";

    /** 最後に書き出したフォルダ。 */
    static final String WRITING_FOLDER = "folder.writing";

    /** 書き出すときの見出し。日付の行は {@link Properties#store} が自分で付ける。 */
    private static final String HEADER = "pdfjig settings - パスワードは保存しない (CLAUDE.md INV-5)";

    private final Properties values = new Properties();

    private Settings() {}

    /**
     * ファイルから読む。
     *
     * @param file 設定ファイル。無くてよい
     * @return 読めた設定。ファイルが無い・読めない・壊れている場合は空の設定
     */
    static Settings load(Path file) {
        Settings settings = new Settings();
        if (file == null || !Files.isRegularFile(file)) {
            return settings;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            settings.values.load(reader);
        } catch (IOException | IllegalArgumentException e) {
            // 壊れた設定は捨てて既定に戻る。中身が読めないことは、動かない理由にはならない。
            settings.values.clear();
        }
        return settings;
    }

    /**
     * ファイルへ書く。
     *
     * <p><b>同じフォルダに一時ファイルを作ってから置き換える。</b>名前だけ先に決めて直接書くと、
     * 書いている途中で終了したときに壊れたファイルが残る（#53 と同じ判断）。
     *
     * @param file 設定ファイル。親フォルダが無ければ作る
     */
    void save(Path file) {
        if (file == null) {
            return;
        }
        Path parent = file.getParent();
        Path temporary = null;
        try {
            if (parent != null) {
                Files.createDirectories(parent);
            }
            temporary = Files.createTempFile(parent, "settings", ".tmp");
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                values.store(writer, HEADER);
            }
            replace(temporary, file);
            temporary = null;
        } catch (IOException e) {
            // 書けなくても諦める。次に選び直せば済むものであり、閉じる操作を失敗させない。
        } finally {
            deleteQuietly(temporary);
        }
    }

    /**
     * 覚えているフォルダ。
     *
     * <p><b>存在するかどうかは見ない。</b>取り出した側が決めること
     * （{@link RecentFolders} が消えたフォルダを落とす）。
     *
     * @param key 鍵
     * @return フォルダ。覚えていない・パスとして読めない場合は空
     */
    Optional<Path> folder(String key) {
        String value = values.getProperty(key);
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Path.of(value));
        } catch (InvalidPathException e) {
            // 手で書き換えられていることがある。読めないなら覚えていないのと同じ扱いにする。
            return Optional.empty();
        }
    }

    /**
     * フォルダを覚える。
     *
     * @param key    鍵
     * @param folder フォルダ。{@code null} なら覚えていたものを忘れる
     */
    void putFolder(String key, Path folder) {
        if (folder == null) {
            values.remove(key);
        } else {
            values.setProperty(key, folder.toString());
        }
    }

    /**
     * 置き換える。
     *
     * <p>同じフォルダの中なので原子的な移動が使えるはずだが、ファイルシステムによっては
     * 断られる。そのときは普通の置き換えに落とす——<b>書き終えた一時ファイルからの置き換えなので、
     * 落としても壊れたファイルにはならない。</b>
     */
    private static void replace(Path temporary, Path file) throws IOException {
        try {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** 置き換えに失敗したときの後始末。ここの失敗は元の失敗より重要ではない。 */
    private static void deleteQuietly(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // 消せなくても、次の保存が新しい一時ファイルを作る。
        }
    }
}
