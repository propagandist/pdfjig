package io.github.propagandist.pdfjig.desktop;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * ファイルとフォルダを選ばせる。
 *
 * <p>用途ごとに 1 メソッドを置いてある。汎用の「ファイルを選ばせる」1 つにまとめると、
 * 題や拡張子の絞り込みを呼び出し側が毎回渡すことになり、同じ用途なのに画面ごとに
 * 違う題が出る余地が生まれる。用途で分ければ、題は実装の中に 1 か所ずつ収まる。
 *
 * <p><b>この境界の外は Windows の共通ダイアログであり、自動テストの対象にできない。</b>
 * 逆に言えば、ここまでは差し替えられる。画面の操作を試すテストは
 * {@code pdf-desktop} の {@code uiTest} でこの実装を入れ替えて行う。
 *
 * <p>始めるフォルダを表す {@code initial} は {@code null} を許す。
 * 渡さないことは「どこから始めるかを Windows に任せる」という意味であり、
 * ホームなり既定なりを呼び出し側が決め打つのとは違う（{@link RecentFolders}）。
 */
interface FileDialogs {

    /**
     * 開く PDF を 1 つ選ばせる。
     *
     * @param initial 始めるフォルダ。{@code null} なら指定しない
     * @return 選ばれたファイル。取り消された場合は空
     */
    Optional<Path> openPdf(Path initial);

    /**
     * 追加する PDF を選ばせる。複数選べる。
     *
     * @param initial 始めるフォルダ。{@code null} なら指定しない
     * @return 選ばれたファイル。取り消された場合は空。空のリストは返らない
     */
    Optional<List<Path>> openPdfs(Path initial);

    /**
     * 書き出し先の PDF を選ばせる。
     *
     * @param initial       始めるフォルダ。{@code null} なら指定しない
     * @param suggestedName 既定のファイル名
     * @return 選ばれたファイル。取り消された場合は空
     */
    Optional<Path> savePdf(Path initial, String suggestedName);

    /**
     * 書き出し先のフォルダを選ばせる。
     *
     * @param initial 始めるフォルダ。{@code null} なら指定しない
     * @return 選ばれたフォルダ。取り消された場合は空
     */
    Optional<Path> chooseFolder(Path initial);
}
