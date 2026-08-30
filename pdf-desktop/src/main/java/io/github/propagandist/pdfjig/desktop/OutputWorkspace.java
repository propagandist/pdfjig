package io.github.propagandist.pdfjig.desktop;

import io.github.propagandist.pdfjig.core.ErrorCode;
import io.github.propagandist.pdfjig.core.PdfjigException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * 書き出しの間だけ使う、出力先の隣の作業場所。
 *
 * <p>画面からの書き出しは、ここに書いてから出力先を置き換える。先に出力先を消してしまうと、
 * 書き込みに失敗したときに元のファイルが失われる。<b>★ ただし「置き換えなら失敗しても元が残る」と
 * 言い切れるのは、原子的な移動が通った場合だけである</b>（{@link DocumentWriter}。#113）。
 *
 * <p><b>ファイルではなくディレクトリを作る。</b> 名前だけ押さえて実体を消し、同じ名前で
 * 作り直すのは CWE-377 の形であり、消えてから書かれるまでに隙がある。出力先に書ける第三者が
 * その隙に同じ名前を用意でき、存在しない先へのリンクを置かれれば書き込みがそちらへ落ちる。
 * ディレクトリの作成は排他的なので、<b>作れた時点でその中は空である</b>ことが保証される——
 * 隙を短くするのではなく、隙そのものが無くなる。{@code pdf-core} の「既存の出力を拒む」約束
 * （{@link ErrorCode#OUTPUT_ALREADY_EXISTS}）も、中が必ず空なのでそのまま守れる。経緯は #53。
 *
 * <p><b>出力先と同じボリュームに置く。</b> {@code %TEMP%} へ逃がせば出力先は汚れないが、
 * 別ボリュームだと {@link Files#move} が原子的になりえず、置き換えの途中で失敗したときに
 * 元のファイルが壊れうる。一時物を出力先の隣に置くのは、そのための判断である。
 * <b>★ 原子的な移動を実際に頼むようになったのは #113 からである</b>——それまでは
 * 隣に置いても 2 段のままだった。<b>この判断が効くようにしたのはあちらである。</b>
 */
final class OutputWorkspace implements AutoCloseable {

    /** 作業場所の名前の頭。残ったものを次の書き出しで見つけるための目印でもある。 */
    private static final String PREFIX = ".pdfjig-";

    /** 作業場所の中に置くファイルの名前。中は自分のものなので固定でよい。 */
    private static final String NAME = "output.pdf";

    private final Path workspace;

    private OutputWorkspace(Path workspace) {
        this.workspace = workspace;
    }

    /**
     * 出力先の隣に作業場所を用意する。
     *
     * @param output 最終的な出力先。その隣に作る
     */
    static OutputWorkspace nextTo(Path output) {
        Path directory = output.toAbsolutePath().getParent();
        discardAbandoned(directory);
        try {
            return new OutputWorkspace(Files.createTempDirectory(directory, PREFIX));
        } catch (IOException e) {
            throw PdfjigException.wrapping(ErrorCode.IO_FAILURE, e);
        }
    }

    /** 書き込み先。まだ存在しない。 */
    Path file() {
        return workspace.resolve(NAME);
    }

    /**
     * 作業場所を中身ごと片づける。消せなくても保存は失敗させない。
     *
     * <p>置き換えに成功していれば中は空である。残っていても保存の成否は変わらない。
     */
    @Override
    public void close() {
        discard(workspace);
    }

    /**
     * 前の書き出しが残した作業場所を片づける。
     *
     * <p>保存中にウィンドウを閉じると JVM ごと落ち、{@link #close} が走らないまま残る。
     * その場では消せないので、同じフォルダへ次に書き出すときにここで拾う。
     * <b>{@code deleteOnExit} は使わない</b>——書き出しは daemon スレッドで走り、
     * その経路では走らない。走らない後始末を足すと、片づいているつもりになるだけである。
     *
     * <p><b>消すのは、こちらが作る名前のディレクトリだけである。</b> 同じ名前の頭を持つ
     * ふつうのファイルには触らない——こちらはディレクトリしか作らないので、それは他人のものである。
     *
     * <p><b>2 つ目の窓が同じフォルダへ同時に書いている場合、その作業場所を消しうる。</b>
     * 書きかけのファイルは開かれているため実際には消せないことが多いが、消せたとしても
     * 起きるのは<b>あちらの保存が失敗すること</b>だけである。置き換えはまだ済んでいないので、
     * あちらの元のファイルは失われない（{@code CLAUDE.md} 優先順位 1）。
     */
    private static void discardAbandoned(Path directory) {
        try (Stream<Path> entries = Files.list(directory)) {
            // 名前で絞ってから種別を見る。isDirectory は 1 件ごとに stat を投げるので、
            // 逆にすると出力先フォルダの全エントリぶん走る。絞り込みの意味は変わらない。
            entries.filter(entry -> entry.getFileName().toString().startsWith(PREFIX))
                    .filter(Files::isDirectory)
                    .forEach(OutputWorkspace::discard);
        } catch (IOException | UncheckedIOException e) {
            // 片づけられなくても、これから書くものの成否は変わらない。
            Logs.warn(LogEvent.WORKSPACE_NOT_DISCARDED, e);
        }
    }

    /** ディレクトリを中身ごと消す。消せなくても保存は失敗させない（{@link Logs} には残す）。 */
    private static void discard(Path directory) {
        try (Stream<Path> entries = Files.walk(directory)) {
            entries.sorted(Comparator.reverseOrder()).forEach(OutputWorkspace::deleteQuietly);
        } catch (IOException | UncheckedIOException e) {
            // 消せなくても、保存の成否は変わらない。
            // ★ Files.walk と Files.list は、返した後の反復で起きた失敗を UncheckedIOException で
            //   包む。IOException を継承しないので、並記しないとここを素通りする——置き換えが
            //   済んだ後の後始末の失敗が、保存そのものの失敗として利用者に出る。
            Logs.warn(LogEvent.WORKSPACE_NOT_DISCARDED, e);
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            // 同上。出力先に .pdfjig-* が残るのは利用者から見えるので、理由を追える先を残す。
            Logs.warn(LogEvent.WORKSPACE_NOT_DISCARDED, e);
        }
    }
}
