package io.github.propagandist.pdfjig.desktop;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Optional;

/**
 * 利用者ごとのデータを置くフォルダ。
 *
 * <p><b>{@code %LOCALAPPDATA%\pdfjig\} を使う。{@code %APPDATA%}（ローミング）ではない。</b>
 * ここに置くのは<b>パス</b>であり、ローミングプロファイルは別のマシンへ運ばれる。
 * {@code D:\scan\} は運んだ先に無いので、<b>運べば必ず外れる値を運ぶことになる</b>。
 * ローミングは容量に上限を掛けられる運用も多く、ログ（別の項目）を同じ木に置くとそこにも効く。
 *
 * <p>EXE の入れ先も {@code %LOCALAPPDATA%} である（{@code --win-per-user-install}）ため、
 * 入れ物と持ち物が同じ木に揃う。
 *
 * <p><b>環境変数が無ければ空を返す。</b>Windows 以外では {@code LOCALAPPDATA} が無く、
 * CI の ubuntu もそこに当たる。<b>例外にしない</b>——置き場が無いことは、
 * 覚えられないというだけであって、動かない理由ではない（CLAUDE.md 優先順位）。
 */
final class UserDataDirectory {

    /** 環境変数の名前。 */
    private static final String VARIABLE = "LOCALAPPDATA";

    /** この木の名前。表示名の {@code PDFjig} ではなくコマンド名に揃える。 */
    private static final String FOLDER = "pdfjig";

    private UserDataDirectory() {}

    /**
     * 利用者ごとのデータを置くフォルダ。
     *
     * @return 置き場。{@code LOCALAPPDATA} が無い環境では空
     */
    static Optional<Path> locate() {
        return resolve(System.getenv(VARIABLE));
    }

    /**
     * 設定ファイル。
     *
     * <p>この木に何を置くかは {@code docs/SPEC.md}「利用者の PC に置くもの」が持つ。
     * <b>置き場の組み立てはこのクラスに集める</b>——ログ（#13）も提案（#103）も同じ木に来る。
     *
     * @return 設定ファイル。置き場が無い環境では空
     */
    static Optional<Path> settingsFile() {
        return locate().map(directory -> directory.resolve("settings.properties"));
    }

    /**
     * 置き場を組み立てる。
     *
     * <p>環境変数の値を引数で渡せるようにしてある。{@link System#getenv} はテストから
     * 差し替えられないため、組み立ての判断だけをここで固める。
     *
     * @param localAppData {@code %LOCALAPPDATA%} の値。null や空白でもよい
     * @return 置き場。値が無い・空白・パスとして読めない場合は空
     */
    static Optional<Path> resolve(String localAppData) {
        if (localAppData == null || localAppData.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Path.of(localAppData).resolve(FOLDER));
        } catch (InvalidPathException e) {
            // 環境変数に何が入っているかは制御できない。読めないなら覚えないだけである。
            return Optional.empty();
        }
    }
}
