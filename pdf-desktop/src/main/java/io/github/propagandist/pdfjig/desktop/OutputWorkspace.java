package io.github.propagandist.pdfjig.desktop;

import io.github.propagandist.pdfjig.core.ErrorCode;
import io.github.propagandist.pdfjig.core.PdfjigException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 書き出しの間だけ使う、出力先の隣の作業場所。
 *
 * <p>画面からの書き出しは、ここに書いてから出力先を置き換える。先に出力先を消してしまうと、
 * 書き込みに失敗したときに元のファイルが失われる。置き換えなら、失敗しても元のファイルは残る。
 *
 * <p><b>出力先と同じボリュームに置く。</b> {@code %TEMP%} へ逃がせば出力先は汚れないが、
 * 別ボリュームだと {@link Files#move} が原子的でなくなり、置き換えの途中で失敗したときに
 * 元のファイルが壊れうる。一時物を出力先の隣に置くのは、そのための判断である。
 */
final class OutputWorkspace implements AutoCloseable {

    /** 作業場所の名前の頭。 */
    private static final String PREFIX = ".pdfjig-";

    /** 書き込み先の名前の尾。 */
    private static final String SUFFIX = ".tmp";

    private final Path file;

    private OutputWorkspace(Path file) {
        this.file = file;
    }

    /**
     * 出力先の隣に作業場所を用意する。
     *
     * @param output 最終的な出力先。その隣に作る
     */
    static OutputWorkspace nextTo(Path output) {
        try {
            Path file = Files.createTempFile(output.toAbsolutePath().getParent(), PREFIX, SUFFIX);
            // pdf-core は既存の出力を拒む。名前だけ押さえて実体は消しておく。
            Files.delete(file);
            return new OutputWorkspace(file);
        } catch (IOException e) {
            throw PdfjigException.wrapping(ErrorCode.IO_FAILURE, e);
        }
    }

    /** 書き込み先。 */
    Path file() {
        return file;
    }

    /**
     * 作業場所を片づける。消せなくても黙っている。
     *
     * <p>置き換えに成功していれば既に無い。残っていても保存の成否は変わらない。
     */
    @Override
    public void close() {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            // 消せなくても、保存の成否は変わらない。
        }
    }
}
