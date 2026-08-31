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
 * 書き込みに失敗したときに元のファイルが失われる。
 *
 * <p><b>★★ 元の実体の退避先もここが持つ</b>（{@link #replaced}。#119）。出力先を
 * <b>消さずに</b>ここへ改名してから入れ替えるので、<b>どの瞬間にも「両方無い」が存在しない</b>——
 * 途中で落ちても、元は作業場所の中に実体として残る。<b>だからこの作業場所は、
 * 「書きかけの置き場」であると同時に「元の唯一の控えの置き場」でありうる。</b>
 * 片づけの側がそれを知っている必要がある（{@link #holdsTheOnlyCopy}）。
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

    /**
     * 退避した元の実体を入れるディレクトリの名前。
     *
     * <p><b>★ 中の名前は固定できない。</b>出力先のファイル名をそのまま使う——
     * {@link #holdsTheOnlyCopy} が「その名前が出力先のフォルダに戻っているか」で
     * <b>片づけてよいかを決める</b>ためであり、名前を捨てると判断の材料が消える。
     *
     * <p><b>だから 1 階層挟む。</b>{@link #NAME} と同じ高さに置くと、
     * <b>利用者が {@code output.pdf} という名前で保存したときに衝突する</b>——
     * 書きかけと控えが同じパスを指す。
     */
    private static final String REPLACED = "replaced";

    private final Path workspace;

    private final Path replaced;

    private OutputWorkspace(Path workspace, Path replaced) {
        this.workspace = workspace;
        this.replaced = replaced;
    }

    /**
     * 出力先の隣に作業場所を用意する。
     *
     * @param output 最終的な出力先。その隣に作る
     */
    static OutputWorkspace nextTo(Path output) {
        Path target = output.toAbsolutePath();
        Path directory = target.getParent();
        discardAbandoned(directory);
        try {
            Path workspace = Files.createTempDirectory(directory, PREFIX);
            Path aside = Files.createDirectory(workspace.resolve(REPLACED)).resolve(target.getFileName());
            return new OutputWorkspace(workspace, aside);
        } catch (IOException e) {
            throw PdfjigException.wrapping(ErrorCode.IO_FAILURE, e);
        }
    }

    /** 書き込み先。まだ存在しない。 */
    Path file() {
        return workspace.resolve(NAME);
    }

    /**
     * 出力先に元からあった実体の退避先。まだ存在しない。
     *
     * <p><b>入れ物のディレクトリは先に作ってある</b>——{@link Files#move} は親が無ければ落ちる。
     * 退避しないまま終わることのほうが多い（新規パスへの書き出し）ので、空のまま片づく。
     */
    Path replaced() {
        return replaced;
    }

    /**
     * 作業場所を中身ごと片づける。消せなくても保存は失敗させない。
     *
     * <p>置き換えに成功していれば、中に残るのは元の実体（控え）だけである。
     * <b>そこまで済んでいれば控えは要らない</b>——出力先には新しいものが載っている。
     *
     * <p><b>★★ 済んでいなければ消さない</b>（{@link #holdsTheOnlyCopy}）。
     * 退避したあと入れ替えにも巻き戻しにも失敗すると、<b>元の実体はここにしか無い。</b>
     * 片づけるほうを既定にすると、いちばん失って困る場面でだけ消すことになる
     * （{@code CLAUDE.md} 優先順位 1）。
     */
    @Override
    public void close() {
        if (holdsTheOnlyCopy(workspace)) {
            Logs.warn(LogEvent.REPLACED_FILE_KEPT);
            return;
        }
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
     * <b>★ ただし控えを抱えているものには触らない</b>（{@link #holdsTheOnlyCopy}）——
     * あちらが既に退避まで進んでいたなら、消すのは<b>あちらの元のファイルそのもの</b>である。
     *
     * <p><b>★★ 落ちた後に残った控えを、次の書き出しが消さない</b>（#119 の受け入れ基準）。
     * ここは<b>次に同じフォルダへ書き出すときに走る</b>ので、素通しにすると
     * 「保存が落ちた → 直して保存し直す」という<b>いちばんありそうな流れの中で、
     * 唯一残っていた元が消える。</b>
     */
    private static void discardAbandoned(Path directory) {
        try (Stream<Path> entries = Files.list(directory)) {
            // 名前で絞ってから種別を見る。isDirectory は 1 件ごとに stat を投げるので、
            // 逆にすると出力先フォルダの全エントリぶん走る。絞り込みの意味は変わらない。
            entries.filter(entry -> entry.getFileName().toString().startsWith(PREFIX))
                    .filter(Files::isDirectory)
                    .filter(entry -> !holdsTheOnlyCopy(entry))
                    .forEach(OutputWorkspace::discard);
        } catch (IOException | UncheckedIOException e) {
            // 片づけられなくても、これから書くものの成否は変わらない。
            Logs.warn(LogEvent.WORKSPACE_NOT_DISCARDED, e);
        }
    }

    /**
     * その作業場所が、出力先の元の実体を唯一の控えとして抱えているか。
     *
     * <p><b>見るのは名前だけである。</b>控えは出力先のファイル名のまま置いてあり
     * （{@link #REPLACED}）、作業場所は出力先と同じフォルダにある。だから
     * <b>同じ名前が隣に戻っていれば、入れ替えか巻き戻しのどちらかが済んでいる</b>——
     * 控えはもう唯一の実体ではない。戻っていなければ、<b>ここにしか無い。</b>
     *
     * <p><b>★ この 1 つの見方で {@link #close} と {@link #discardAbandoned} の両方が済む。</b>
     * 後者は<b>どの出力先のものだったかを知らない</b>ので、控えの名前がそれを持っている必要がある。
     * 判断を 2 つに分けると、片方だけ直したときに<b>消す側だけが緩くなる。</b>
     *
     * <p><b>読めなければ抱えている側に倒す。</b>消さずに残す損は、利用者から見える
     * {@code .pdfjig-*} が 1 つ残ることである。取り違えたときの損は、元のファイルが消えることである。
     */
    private static boolean holdsTheOnlyCopy(Path workspace) {
        Path kept = workspace.resolve(REPLACED);
        if (!Files.isDirectory(kept)) {
            // #119 より前の版が残したものと、退避まで進まなかったもの。控えは無い。
            return false;
        }
        Path directory = workspace.getParent();
        try (Stream<Path> entries = Files.list(kept)) {
            return entries.anyMatch(entry -> Files.notExists(directory.resolve(entry.getFileName())));
        } catch (IOException | UncheckedIOException e) {
            Logs.warn(LogEvent.REPLACED_FILE_KEPT, e);
            return true;
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
