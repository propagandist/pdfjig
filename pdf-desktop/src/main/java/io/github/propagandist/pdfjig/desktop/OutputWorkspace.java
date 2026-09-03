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
 * <b>片づけはそれを見て止まる</b>（{@link #discard}）。
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
     * 退避した元の実体を置く名前。中は自分のものなので、こちらも固定でよい。
     *
     * <p><b>★ 出力先の名前は持ち込まない。</b>持ち込んで「同じ名前が隣に戻っているか」で
     * 片づけを決める形も採れるが、それは<b>弱い手がかりである</b>——別の窓や他のアプリが
     * その名前で何かを置けば「済んだ」と読み、<b>唯一の控えを抱えた作業場所を次の書き出しが消す。</b>
     */
    private static final String REPLACED = "replaced.pdf";

    /**
     * 退避したまま済んでいないことの印。中身は空でよい。
     *
     * <p><b>★★ 控えそのものを印にしてはならない。</b>いちど<b>控えを消せなかった</b>だけで
     * <b>片づけの関門が永久に閉じる</b>——{@link #discard} は {@link #close} からも
     * {@link #discardAbandoned} からも同じ関門を通るので、<b>どの経路からも二度と消えなくなる。</b>
     * <b>成功した保存のたびに、利用者の文書の 1 世代前を抱えた {@code .pdfjig-*} が
     * 1 つずつ積み上がることになる</b>（2026-09-01 実測。出力先が読み取り専用だと、
     * 改名は通るのに削除だけが {@code AccessDenied} で落ちる）。
     *
     * <p><b>だから印は自分で作る。</b>こちらが作った空のファイルなら<b>必ず消せる</b>ので、
     * 印を外し損ねて閉じ込められることがない。<b>控えを消せなかったときは作業場所ごと残るが、
     * 印はもう無いので、次に同じフォルダへ書き出すときに片づけ直せる。</b>
     */
    private static final String HELD = "held";

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
     * 出力先に元からあった実体の退避先。まだ存在しない。
     *
     * <p><b>ここへ退避したら {@link #holdOriginal} を、済んだら {@link #releaseOriginal} を呼ぶこと。</b>
     * <b>済んだかどうかを知っているのは呼ぶ側だけである</b>（{@code DocumentWriter#move}）。
     */
    Path replaced() {
        return workspace.resolve(REPLACED);
    }

    /**
     * 元の実体をここに抱えていることを記す。
     *
     * <p><b>★ 退避する前に呼ぶこと。</b>先に退避すると、その間に落ちたときに
     * <b>印の無い控えが残り、次の書き出しがそれを消す</b>——記すのは、
     * 抱えうる状態に入ることそのものである。
     *
     * <p><b>記せなければ退避を始めさせない</b>（例外を投げる）。印を立てられないまま退避すると、
     * <b>唯一の控えを守れないまま置き換えに進むことになる</b>（{@code CLAUDE.md} 優先順位 1）。
     */
    void holdOriginal() {
        try {
            Files.createFile(workspace.resolve(HELD));
        } catch (IOException e) {
            throw PdfjigException.wrapping(ErrorCode.IO_FAILURE, e);
        }
    }

    /**
     * もう抱えていないことを記す。
     *
     * <p>置き換えが済んだか、巻き戻して元の場所へ返したときに呼ぶ。
     *
     * <p><b>これで片づけの関門が開く。</b>控えそのものを消せるかどうかは
     * {@link #discard} の仕事であり、<b>消せなくても閉じ込められない</b>——
     * 印はこちらが作った空のファイルなので、必ず消せる（{@link #HELD}）。
     */
    void releaseOriginal() {
        try {
            Files.deleteIfExists(workspace.resolve(HELD));
        } catch (IOException e) {
            // 消せないことは想定していない。残ると作業場所が片づかないので、理由を追える先を残す。
            Logs.warn(LogEvent.WORKSPACE_NOT_DISCARDED, e);
        }
    }

    /**
     * 元の実体を、まだ唯一の控えとして抱えたままか。
     *
     * <p><b>★★ 失敗が外へ出るときに、それを利用者へ伝えてよいかの判断がこれである</b>
     * （{@link ReplacedFileKeptException#reporting}。#124）。真なら<b>出力先には何も無く、
     * 元はこの中にしか無い</b>——{@link #replaced} がその場所である。
     *
     * <p><b>★ 片づけと同じ印を見る。</b>別の見方を足すと、<b>「消さない」と「伝える」が
     * 食い違う日が来る</b>——消さずに残したのに何も言わない、あるいは
     * 巻き戻して元へ返したのに「残っている」と言う。どちらも優先順位 2 に触れる。
     *
     * <p><b>{@link #close} の後にも答えられる。</b>抱えているなら片づけは何もしないので、
     * 印はそのまま残っている（{@link #discard}）。
     */
    boolean stillHoldsOriginal() {
        return holdsTheOnlyCopy(workspace);
    }

    /** 作業場所を片づける。消せなくても保存は失敗させない。 */
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
     * <b>★ ただし控えを抱えているものには触らない</b>（{@link #discard}）——
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
                    .forEach(OutputWorkspace::discard);
        } catch (IOException | UncheckedIOException e) {
            // 片づけられなくても、これから書くものの成否は変わらない。
            Logs.warn(LogEvent.WORKSPACE_NOT_DISCARDED, e);
        }
    }

    /**
     * その作業場所が、元の実体を唯一の控えとして抱えているか。
     *
     * <p><b>印だけを見る</b>（{@link #HELD}）。立てるのも外すのも、置き換えが済んだかを
     * 知っている側である（{@code DocumentWriter#move}）。
     *
     * <p><b>★★ 「無いと確信できる」ときだけ開ける。</b>{@link Files#exists} は
     * <b>「無い」と「確かめられない」を同じ {@code false} に潰す</b>ので、
     * 権限や一時的な失敗で属性を読めないだけの作業場所を「印は無い」と読み、
     * <b>唯一の控えごと消しにいく。</b>関門は消さない側へ倒す（{@code CLAUDE.md} 優先順位 1）。
     *
     * <p><b>★ 出力先の側を見に行かない。</b>「同じ名前が隣に戻っているか」で判断する形も
     * 採れるが、それは<b>置き換えが済んだことの証拠にならない</b>——別の窓や他のアプリが
     * その名前で何かを置いただけでも「済んだ」と読む。作業場所が出力先の隣にあることも、
     * <b>ボリュームを揃えるための判断であって</b>（このクラスの説明）、
     * <b>片づけの根拠に使ってよいものではない。</b>
     */
    private static boolean holdsTheOnlyCopy(Path workspace) {
        return !Files.notExists(workspace.resolve(HELD));
    }

    /**
     * ディレクトリを中身ごと消す。消せなくても保存は失敗させない（{@link Logs} には残す）。
     *
     * <p><b>★★ 控えを抱えているなら何もしない</b>（{@link #holdsTheOnlyCopy}）。
     * <b>この関門を消す側の 1 か所に置く</b>——呼ぶ側それぞれに置くと、
     * <b>3 つ目の呼び出しが足されたときに黙って素通りする。</b>
     *
     * <p><b>残ったことを記録しない。</b>残すのは正しい振る舞いであって失敗ではなく、
     * ログに書くのは失敗したことだけである（{@code docs/SPEC.md} §10.4）。
     * <b>そこへ至る失敗は既に記録されている</b>（{@code DocumentWriter#restore}）。
     */
    private static void discard(Path directory) {
        if (holdsTheOnlyCopy(directory)) {
            return;
        }
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
