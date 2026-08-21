package io.github.propagandist.pdfjig.desktop;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * ファイルダイアログを始めるフォルダを覚えておく。
 *
 * <p>これが無いと、初期フォルダを決めるのは Windows の共通ダイアログ側になる。
 * JavaFX は {@code FileChooser#initialDirectory} が未設定なら {@code null} をそのまま
 * ネイティブへ渡すだけで、その先はアプリの実行ファイルに紐づく MRU が使われる。
 * 前回のフォルダが出ることもあるが規則は保証されず、起動のしかたが変われば別の履歴になる。
 *
 * <p><b>読む用と書く用を分けて持つ。</b>PDF を取ってくる場所と、整理した結果を置く場所は
 * 違うことが多い。片方に引きずられると、そのつどたどり直すことになる。
 *
 * <p>覚えるのは各 1 つだけで、履歴は持たない。<b>アプリを閉じると忘れる。</b>
 * 再起動をまたいで維持するには設定ファイルの置き場を決める必要があり、
 * それは {@code HANDOVER.md} の未決事項として M1 に残してある。
 *
 * <p>JavaFX の型を持ち込まないこと。Toolkit の初期化なしでテストできる状態を保つ。
 */
final class RecentFolders {

    /** 最後に PDF を読んだフォルダ。まだ無ければ null。 */
    private Path reading;

    /** 最後に書き出したフォルダ。まだ無ければ null。 */
    private Path writing;

    /** PDF を選ぶダイアログを始めるフォルダ。 */
    Optional<Path> reading() {
        return existing(reading);
    }

    /** 書き出し先を選ぶダイアログを始めるフォルダ。 */
    Optional<Path> writing() {
        return existing(writing);
    }

    /**
     * 読んだファイルの置かれていたフォルダを覚える。
     *
     * @param file 読んだファイル。フォルダではない
     */
    void rememberReadFile(Path file) {
        Path folder = parentOf(file);
        if (folder != null) {
            reading = folder;
        }
    }

    /**
     * 書き出したファイルの置き先フォルダを覚える。
     *
     * @param file 書き出し先のファイル。フォルダではない
     */
    void rememberWrittenFile(Path file) {
        Path folder = parentOf(file);
        if (folder != null) {
            writing = folder;
        }
    }

    /**
     * 書き出し先に選ばれたフォルダを覚える。
     *
     * @param folder 書き出し先のフォルダ
     */
    void rememberWrittenFolder(Path folder) {
        if (folder != null) {
            writing = folder;
        }
    }

    /**
     * 覚えたフォルダのうち、いまも存在するものだけを返す。
     *
     * <p>覚えたあとに消えることがある。USB を抜く、ネットワークの割り当てが切れる、
     * 利用者自身が片づける。無くなったフォルダを渡すとダイアログの出方が読めなくなるので、
     * ここで落として Windows の既定に任せる。
     */
    private static Optional<Path> existing(Path folder) {
        return Optional.ofNullable(folder).filter(Files::isDirectory);
    }

    /**
     * ファイルの置かれているフォルダ。
     *
     * <p>{@code Path#getParent} は要素が 1 つの相対パス（{@code a.pdf} など）に対して
     * {@code null} を返す。ダイアログは絶対パスを返すので実際には起きないが、
     * その値を覚えると次から必ず外れるため弾いておく。
     */
    private static Path parentOf(Path file) {
        return file == null ? null : file.getParent();
    }
}
