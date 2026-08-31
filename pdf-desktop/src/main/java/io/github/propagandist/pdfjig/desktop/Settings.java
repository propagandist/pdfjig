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
 * {@code C:\scan} はファイルの中で {@code C\:\\scan} と見える。読み書きは対なので壊れないが、
 * 手で直すときに驚かないように書いておく。
 *
 * <p><b>読めなければ空として扱い、書けなければ諦める。例外を投げない。</b>
 * 覚えているのは次にダイアログが開くフォルダだけで、失われても選び直せば済む。
 * <b>設定が壊れているせいでアプリが起動しない、という形を作らない。</b>
 * ★ <b>ただし黙ってはいない。</b>捨てたこと・書けなかったことは {@link Logs} へ出す——
 * 覚えたはずのフォルダが戻らないとき、原因を追える先がどこかに要る。
 */
final class Settings {

    /** 最後に PDF を読んだフォルダ。 */
    static final String READING_FOLDER = "folder.reading";

    /** 最後に書き出したフォルダ。 */
    static final String WRITING_FOLDER = "folder.writing";

    /**
     * 書き出すときの見出し。日付の行は {@link Properties#store} が自分で付ける。
     *
     * <p><b>★ ASCII だけで書く。</b>{@code store} は見出しの中の U+00FF を超える文字を
     * <b>Writer の文字集合にかかわらず</b>ユニコード脱出へ変える。日本語で書くと
     * {@code #パス...} になり、<b>人が読める形式を選んだ理由がその見出しで潰れる。</b>
     * 値の側は素の UTF-8 で書かれるので影響を受けない。
     */
    private static final String HEADER = "pdfjig settings - no passwords are stored here (CLAUDE.md INV-5)";

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
            Logs.warn(LogEvent.SETTINGS_UNREADABLE, e);
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
        if (parent == null) {
            // 置き場を組み立てるのは UserDataDirectory なので実際には起きないが、
            // 起きたときに投げてはならない。createTempFile は null の親で NPE を投げ、
            // それは下の catch を素通りする。
            return;
        }
        Path temporary = null;
        try {
            Files.createDirectories(parent);
            temporary = Files.createTempFile(parent, "settings", ".tmp");
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                values.store(writer, HEADER);
            }
            replace(temporary, file);
            temporary = null;
        } catch (IOException e) {
            // 書けなくても諦める。次に選び直せば済むものであり、閉じる操作を失敗させない。
            Logs.warn(LogEvent.SETTINGS_UNWRITABLE, e);
        } finally {
            deleteQuietly(temporary);
        }
    }

    /**
     * 覚えているフォルダを書き出す。
     *
     * <p><b>★ 覚えていないものは書かないし、消しもしない。</b>覚えていない状態には
     * 2 つの意味がある——「一度も使っていない」と「まだ読み込めていない」である。
     * 後者は復元が背景で走るために起きる（{@link PdfjigApplication}）。
     * <b>これを「忘れろ」と読むと、すぐ閉じただけで前回のぶんが消える。</b>
     *
     * <p><b>どちらも覚えていなければ、ファイルを作らない。</b>中身の無いファイルは
     * アンインストールしても残る（{@code docs/SPEC.md} §10）。<b>覚えることが無いのに置かない。</b>
     *
     * @param file    設定ファイル
     * @param reading 読む用。覚えていなければ空
     * @param writing 書く用。覚えていなければ空
     */
    static void store(Path file, Optional<Path> reading, Optional<Path> writing) {
        if (reading.isEmpty() && writing.isEmpty()) {
            return;
        }
        // 読み直してから書く。将来ここへ別の項目が増えたときに、知らない鍵を消さないため。
        Settings settings = load(file);
        reading.ifPresent(folder -> settings.putFolder(READING_FOLDER, folder));
        writing.ifPresent(folder -> settings.putFolder(WRITING_FOLDER, folder));
        settings.save(file);
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
            Logs.warn(LogEvent.SETTINGS_VALUE_UNREADABLE, e);
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
     * <p>同じフォルダの中なので原子的な移動が使えるはずだが、断られることがある。
     * そのときは普通の置き換えに落とす——<b>書き終えた一時ファイルからの置き換えなので、
     * 落としても壊れたファイルにはならない。</b>
     *
     * <p><b>★★ 断られたら、理由を問わず落とす。</b>もとは
     * {@link AtomicMoveNotSupportedException} だけに絞っていたが、<b>それは誤りだった</b>
     * （#113 の実測）——<b>原子的な置き換えは、置き換え先が開かれていると
     * {@code AccessDeniedException} で断る。</b>設定ファイルは人が読める場所に置いてあり
     * （{@code docs/SPEC.md} §10）、ウイルス対策・インデクサ・2 つ目の窓が掴みうる。
     * <b>絞ったままだと、そのとき覚えたフォルダが黙って保存されない。</b>
     *
     * <p><b>★ 絞った条件は、ここでは起きようがなかった。</b>一時ファイルも同じフォルダに作るので、
     * Windows で {@link AtomicMoveNotSupportedException} になる唯一の条件
     * （{@code ERROR_NOT_SAME_DEVICE}）に当たりようがない。<b>死んだ catch だった。</b>
     *
     * <p><b>★★ {@code DocumentWriter#move} とは揃えない</b>（#119 で決めた。#113 の宿題）。
     * あちらは<b>退避してから入れ替える 2 本の改名</b>になり、ここは 2 段のままである。
     * <b>守っているものが違う</b>——ここが失うのは<b>次にダイアログが開くフォルダの記憶</b>だけで、
     * <b>失われても選び直せば済み、書けなければ諦める</b>（{@code docs/SPEC.md} §10.2）。
     * あちらが失うのは<b>利用者の文書</b>である。<b>形を 1 つにすると、諦めてよい側の都合が
     * 諦めてはならない側に効く</b>か、<b>その逆に、閉じる操作が設定の置き換えのために失敗する。</b>
     * <b>落とす条件だけは揃えてある</b>（上の★★）。
     */
    private static void replace(Path temporary, Path file) throws IOException {
        try {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return;
        } catch (IOException refused) {
            // 断られた。落とした先で書けることがある（上の★★）。
        }
        Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
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
