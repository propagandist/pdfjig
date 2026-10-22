package io.github.propagandist.pdfjig.desktop;

import com.sun.nio.file.ExtendedOpenOption;
import io.github.propagandist.pdfjig.core.ErrorCode;
import io.github.propagandist.pdfjig.core.PdfjigException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.regex.Pattern;
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

    /** pdfjig が作る作業場所の名前の形。{@link Files#createTempDirectory} は頭の後ろに数字だけを付ける。 */
    private static final Pattern OURS = Pattern.compile(Pattern.quote(PREFIX) + "\\d+");

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

    /**
     * 保存の間、作業場所を押さえておくために開いたままにするファイルの名前（#222。{@link #pin}）。
     * 中身は空でよい。
     */
    static final String PIN = "pin";

    /**
     * 作りたての作業場所を別の窓の片づけに先に押さえられたとき、作り直す回数の上限（#139。{@link #nextTo}）。
     * 先を越されるのは、あちらの片づけが作ってから押さえるまでの一瞬に重なったときだけなので、続けては起きない。
     * 続くなら誰かが押さえを置き続けている。
     */
    private static final int ATTEMPTS = 3;

    private final Path workspace;

    /** {@link #PIN} を開いた手。押さえていなければ {@code null}（{@link #pin}）。 */
    private final FileChannel pin;

    private OutputWorkspace(Path workspace, FileChannel pin) {
        this.workspace = workspace;
        this.pin = pin;
    }

    /**
     * 出力先の隣に作業場所を用意し、前の書き出しが残した控えを知らせる。
     *
     * <p><b>★★ 控えは、前の書き出しがアプリごと落ちて残した元の唯一の実体である</b>（#138）。
     * 電源断・強制終了・ログオフでは {@code close} も {@code catch} も走らないので、
     * <b>{@link #failing} の形では原理的に届かない。</b>見つけられるのは次に同じフォルダへ書き出すときで、
     * <b>見つけているのに黙ると、利用者から見えるのは出力先に増えた {@code .pdfjig-*} だけになる。</b>
     *
     * <p><b>知らせるのは控えのファイルそのものの場所である</b>（{@link #failing} が載せるものと同じ形）。
     * <b>控えが実在するものだけを知らせる</b>——印だけを見ると、無事に元へ戻ったものを
     * 「ここにしか無い」と伝えうる（{@link #failing} と同じ理由）。
     *
     * <p><b>★ 作業場所を作る前に知らせる。</b>作れずに投げる回こそ、利用者がやり直している回である
     * ——後に置くと、そこで見つけたものが黙って落ちる。
     *
     * <p><b>★ 別の窓がいま使っている作業場所は、Windows では拾わない</b>（#222。{@link #inUse}）。
     * それより前は拾いえた——元をどけてから入れ替えるまでの間（{@code DocumentWriter#move}）は、
     * 落ちた後と同じ形をしている。<b>Windows 以外では今も拾いうる</b>（押さえないため）。
     * 伝える文言が「開いて確かめる」よう促すのは
     * そのためでもある（{@code Messages#describeAbandoned}）。
     *
     * <p><b>★★ 知らせない版を置かない。</b>置くと、作業場所を開く口が 2 つ目に足された日に
     * 短いほうが選ばれ、<b>そこだけ黙る</b>——{@link #failing} を呼ぶ側の {@code catch} に置かない
     * のと同じ理由である。知らせなくてよい呼び出し（テスト）は、何もしない受け取り口を渡す。
     *
     * @param output    最終的な出力先。その隣に作る
     * @param abandoned 同じフォルダで見つけた控えを受け取る。見つからなければ呼ばれない
     */
    static OutputWorkspace nextTo(Path output, Consumer<List<Path>> abandoned) {
        Path directory = output.toAbsolutePath().getParent();
        List<Path> found = discardAbandoned(directory);
        if (!found.isEmpty()) {
            abandoned.accept(found);
        }
        for (int attempt = 1; ; attempt++) {
            try {
                Path created = Files.createTempDirectory(directory, PREFIX);
                return new OutputWorkspace(created, pin(created));
            } catch (FileAlreadyExistsException claimed) {
                // 作りたての作業場所を、別の窓の片づけが先に押さえた（#139。discard）。
                // 元にはまだ触っていないので、作り直せばよい。あちらがこの作業場所を消す。
                if (attempt == ATTEMPTS) {
                    throw PdfjigException.wrapping(ErrorCode.IO_FAILURE, claimed);
                }
            } catch (IOException e) {
                throw PdfjigException.wrapping(ErrorCode.IO_FAILURE, e);
            }
        }
    }

    /**
     * 保存の間、作業場所とその上のフォルダを改名できなくする（#222）。
     *
     * <p><b>★★ なぜ要るのか。</b>Windows では、作業場所は出力先フォルダの権限を引き継ぐ。
     * 出力先に書ける者は、保存の最中に作業場所をジャンクションへ差し替えられる。
     * {@link #file} も {@link #replaced} もパスで引き直すので、<b>新しい文書は指す先へ書かれ、
     * 上書き保存なら利用者の元のファイルが指す先へ退避される</b>——利用者の選んでいない場所に残る。
     *
     * <p><b>作業場所の中のファイル（{@link #PIN}）を、削除を共有しない形で開いたままにする。</b>
     * 開いている間は、<b>その {@code pin} を消すことも動かすこともできず、作業場所も、その実体の上の
     * フォルダも改名できない</b>——差し替えるには、まずどけなければならない。
     * 保存に要る改名（作業場所への退避・出力先への移動）と、作業場所の中のほかのファイルの削除は通る。
     * <b>閉じたら消える形で開く</b>（{@code DELETE_ON_CLOSE}）——片づけでパスを引いて消さずに済み、
     * プロセスが落ちても、OS が手を閉じれば消える（電源断では残る。残ったものは次の書き出しが消す。{@link #inUse}）。
     * （2026-10-19 実測。Windows 10 の NTFS の C: と D: で、{@code pin} の削除・移動と、作業場所・
     * 出力先フォルダの改名は断られ、退避・移動・削除は通り、閉じた後に {@code pin} は無かった）
     *
     * <p><b>★ 効く範囲。</b>確かめたのは NTFS（上の実測）と、Windows の共有（{@code \\localhost\C$}。#222 の門のレビューが実測）である。
     * <b>Samba の NAS では効かない見込みがある</b>（既定の {@code strict rename = no} では、
     * ほかのクライアントからの改名を断らない。確かめていない）。そのときも開けはするので、
     * 押さえられていないことは分からない。
     * <b>保存先のパスの途中にあるジャンクションは押さえられない</b>——押さえが掛かるのは実体の側で、
     * リンクそのものは向け直せる。それには、そのリンクを置いたフォルダに書けなければならない。
     *
     * <p><b>権限で絞る形は採らなかった。</b>作業場所を持ち主だけの DACL で作る形を作ったが、
     * JDK の ACL の口は汎用の権限（{@code GENERIC_*}）を読めず、親の ACE を写すと中身が空になる——
     * 2 台目のドライブの既定の ACL を受け継ぐフォルダ（{@code D:\work} など）では、
     * 保存が毎回 {@code AccessDenied} で失敗した（#222 の門の 2 段目で再現）。
     *
     * <p><b>★ 中に置かれることは止めない。</b>出力先に書ける者は、作業場所の中にも書ける。
     * 組み立てている間に {@code output.pdf} を先に置かれると、pdf-core はそれを切り詰めて書き、
     * <b>置いた者の権限を持ったまま出力先へ移る</b>。退避先を先に置かれても、退避が黙って置き換える。
     * <b>どちらも、出力先に書ける者が保存した後のファイルに対して元から持っている権限を越えない</b>。
     *
     * <p><b>★★ 開けなかったら、また開いた {@code pin} が作業場所の中に無かったら、保存を止める。</b>
     * <ul>
     *   <li>作りたての空のディレクトリで「既にある」と言われたなら、割り込まれている。
     *       <b>別の窓の片づけが先に押さえたのなら、それは正しい振る舞いである</b>（#139。{@link #discard}）。
     *       {@link FileAlreadyExistsException} のまま投げ、{@link #nextTo} が作り直す
     *   <li>ほかの理由で開けないなら、差し替えられて開けない先を指していたのかもしれない。
     *       その場で確かめても、差し替えて戻されていれば見分けられない
     *   <li>開けても、差し替えて開かせてから元に戻されうる——そのとき {@code pin} は指す先にあり、
     *       作業場所は押さえられていない。<b>だから、パスが指すファイルが自分の開いたものと同じかを見る</b>
     *       （{@link #sameFile}）。中身で比べる形は採らない——押さえは読み取りを共有するので、
     *       中身を写した偽物を置かれると見分けられない（#222 の門の 2 段目）
     * </ul>
     * 止めるときは開いた手を閉じる。{@code pin} は手から消えるので（{@code DELETE_ON_CLOSE}）、
     * パスを引いて別の場所のものを消すことはない。空の {@code .pdfjig-*} が残りうる。
     * 作りたての自分のディレクトリにファイルを 1 つ作れない場所では、保存そのものもできない。
     *
     * <p><b>Windows 以外では押さえない。</b>削除を共有しない開き方が無い。配布は Windows だけである。
     *
     * @return 開いた手。押さえなかったなら {@code null}
     * @throws IOException 開けなかった。呼ぶ側が扱いを決める（{@link #nextTo} と {@link #discard} で違う）
     * @throws PdfjigException {@link ErrorCode#IO_FAILURE}。開けたが押さえられなかった
     */
    private static FileChannel pin(Path workspace) throws IOException {
        if (!System.getProperty("os.name", "").startsWith("Windows")) {
            return null;
        }
        Path pin = workspace.resolve(PIN);
        FileChannel channel;
        try {
            channel = FileChannel.open(
                    pin,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.DELETE_ON_CLOSE,
                    ExtendedOpenOption.NOSHARE_DELETE);
        } catch (UnsupportedOperationException e) {
            throw PdfjigException.wrapping(ErrorCode.IO_FAILURE, e);
        }
        boolean pinned;
        try {
            pinned = isOwnDirectory(workspace) && sameFile(channel, pin);
        } catch (IOException e) {
            closeQuietly(channel);
            throw PdfjigException.wrapping(ErrorCode.IO_FAILURE, e);
        } catch (RuntimeException | Error e) {
            closeQuietly(channel);
            throw e;
        }
        if (pinned) {
            return channel;
        }
        // 開けたのに作業場所の中に無い。差し替えられている。投げる例外には理由を載せられないので、ここに残す。
        Logs.warn(LogEvent.WORKSPACE_NOT_PINNED);
        closeQuietly(channel);
        throw new PdfjigException(ErrorCode.IO_FAILURE);
    }

    /**
     * {@code path} が指すファイルが、{@code held} で開いているものと同じか。
     *
     * <p><b>ロックの重なりで見る。</b>JDK は同じプロセスの中のロックを、ファイルの識別子
     * （ボリュームとファイル番号）で突き合わせ、重なれば {@link OverlappingFileLockException} を投げる。
     * {@code held} に排他のロックを掛けてから、パスで開いた別の手でロックを試す——
     * <b>同じファイルならその場で投げ、別のファイルなら投げない</b>（ほかのプロセスが掛けたロックは
     * 投げずに {@code null} で返る）。2026-10-19 実測。Windows 10 の C: と D: で、作業場所の {@code pin} は
     * 同じ、中身を写したファイルは別のプロセスが開いていても別、と出た。
     */
    private static boolean sameFile(FileChannel held, Path path) throws IOException {
        FileLock mine = held.lock(0, 1, false);
        try (FileChannel check = FileChannel.open(path, StandardOpenOption.READ)) {
            FileLock other = check.tryLock(0, 1, true);
            if (other != null) {
                other.release();
            }
            return false;
        } catch (OverlappingFileLockException same) {
            return true;
        } finally {
            mine.release();
        }
    }

    private static void closeQuietly(FileChannel channel) {
        try {
            channel.close();
        } catch (IOException e) {
            Logs.warn(LogEvent.WORKSPACE_NOT_DISCARDED, e);
        }
    }

    /**
     * 控えの隣にある、書けた出力の場所（テストが名前を写さないため。#184）。
     *
     * @param kept {@link ReplacedFileKeptException#kept()} が返す控え
     */
    static Path writtenBeside(Path kept) {
        return kept.resolveSibling(NAME);
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
     * 控えを抱えたまま失敗したなら、その在り処を載せた例外を返す。
     *
     * <p><b>★★ 抱えたままなら、出力先には何も無く、元はこの中にしか無い</b>
     * （{@link #replaced}。#124）。伝えなければ、利用者から見えるのは
     * <b>「保存に失敗して、ファイルが消えた」だけである</b>——{@code .pdfjig-*} は
     * <b>よく分からないゴミにしか見えない</b>ので、消される。
     *
     * <p><b>★★ 関門は消す側と同じくここに置く</b>（{@link #discard} の説明）。
     * <b>呼ぶ側の {@code catch} に置くと、2 つ目の {@link #nextTo} が足された日に黙って素通りする</b>
     * ——そのとき戻るのは #124 そのものである。<b>{@code nextTo} と {@code failing} を呼ぶ場所は
     * ArchUnit が 1 つに縛っている</b>（{@code theWorkspaceIsOpenedAndReportedInOnePlace}）。
     *
     * <p><b>★★ 印だけでは足りない。控えが実在することまで見る。</b>{@link #HELD} を下ろすのは
     * <b>best-effort である</b>（{@link #releaseOriginal} は消せなくても続ける）ので、
     * <b>巻き戻して元へ返したのに印が残る</b>ことがありうる。そこで印だけを見ると、
     * <b>出力先に無事にあるものを「作業場所にしか無い」と伝える</b>——
     * <b>利用者は無事なファイルを探しに行って、見つけられない</b>（優先順位 2）。
     *
     * <p><b>★★ 「有ると確信できる」ときだけ言う。</b>{@link #holdsTheOnlyCopy} は
     * <b>「確かめられない」を「抱えている」に倒す</b>——<b>消さない判断には正しいが、
     * 伝える判断には逆である。</b>片づけは倒しすぎても<b>ゴミが残るだけ</b>だが、
     * こちらが倒しすぎると<b>嘘になる。</b>だから {@link Files#exists} で<b>正の証拠</b>を取る。
     *
     * <p><b>★ 読むのは {@link #close} より前である。</b>{@code close} の失敗まで
     * 「書き出しの失敗」にしてしまうと、<b>書けているのに失敗として出る</b>——
     * 呼ぶ側はそこで寄せ直しを飛ばし、次の保存で同じ変換が二重に掛かる（#118）。
     *
     * <p><b>★★ 消し損ねた平文も、ここで在り処を載せる</b>（#184。{@link #dropWrittenOutput}）。
     * <b>元を抱えていない失敗でも言う</b>（{@link PlaintextLeftException}）——元の控えがある回だけに
     * 載せると、巻き戻せた回や退避の前の失敗で、平文は黙って残る。
     * <b>平文を伝えない版は置かない</b>（{@link #nextTo} と同じ理由）。
     *
     * @param failed    起きた失敗
     * @param plaintext 消し損ねた、復号した中身のファイル。残っていなければ {@code null}
     * @return 控えを抱えたままか、平文を残したなら在り処を載せた例外。そうでなければ空
     */
    Optional<RuntimeException> failing(Throwable failed, Path plaintext) {
        Path copy = replaced();
        if (holdsTheOnlyCopy(workspace) && Files.exists(copy)) {
            return Optional.of(new ReplacedFileKeptException(copy, plaintext, failed));
        }
        return plaintext == null ? Optional.empty() : Optional.of(new PlaintextLeftException(plaintext, failed));
    }

    /**
     * 書けた出力を消す。<b>書き出しを終えた持ち主だけが呼ぶ</b>（#184）。
     *
     * <p><b>★★ 呼ぶのは、出力が「鍵の要る入力を復号した中身で、開くのに鍵が要らない」ときだけである</b>
     * （{@code DocumentWriter#assemble}）。置き換えにも巻き戻しにも失敗すると、ここには元と書けた出力の
     * 両方が残る。利用者が読むのは「保存に失敗した」であり、<b>平文は書かれていないと信じる</b>
     * （{@code SECURITY.md}「対象範囲」2 番目）。
     *
     * <p><b>★ ふつうの出力は消さない。</b>開いている文書へ上書き保存して失敗したとき、
     * <b>編集を反映した中身はここにしか無い</b>——元を戻さない限り、保存し直すこともできない。
     *
     * <p><b>★ 前の書き出しの残り物には呼ばない</b>（{@link #discardAbandoned} は触らない）。
     * 別の窓が退避と入れ替えの間にいると、<b>これから出力先へ移すものを消して、あちらの保存を失敗させる。</b>
     *
     * @return 消し損ねたなら、そのファイル。消せたか、もともと無ければ {@code null}
     */
    Path dropWrittenOutput() {
        Path written = file();
        try {
            Files.deleteIfExists(written);
            return null;
        } catch (IOException e) {
            Logs.warn(LogEvent.WORKSPACE_NOT_DISCARDED, e);
            return written;
        }
    }

    /**
     * 押さえを放すだけで、片づけない。<b>落ちた書き出しを作るテストだけが呼ぶ。</b>
     *
     * <p>プロセスが落ちれば、開いていた手は OS が閉じる。同じプロセスの中のテストは、
     * 放さないと次の書き出しがそれを片づけられない（{@link #pin}）。
     */
    void leaveBehind() {
        release();
    }

    /** 押さえを放す。押さえていなければ何もしない。 */
    private void release() {
        if (pin != null) {
            closeQuietly(pin);
        }
    }

    /**
     * 作業場所を片づける。消せなくても保存は失敗させない。
     *
     * <p><b>★ 押さえたまま中を消し、放してから作業場所を消す</b>（#222）。先に放すと、片づけの間に
     * 作業場所をジャンクションへ差し替えられ、中の名前が指す先で消えうる（#125 の形）。
     * 放せば {@code pin} は閉じたときに消える（{@link #pin}）。その後に消すのは作業場所そのものだけで、
     * 差し替えられていれば消えるのはリンクである。
     */
    @Override
    public void close() {
        try {
            if (isOwnDirectory(workspace) && !holdsTheOnlyCopy(workspace)) {
                deletePinned(workspace, pin);
            }
        } finally {
            release();
        }
    }

    /**
     * 押さえたまま中を消し、放してから作業場所を消す。順の理由は {@link #close} にある。
     * {@link #discard} も同じ順で消す（#139）。
     *
     * @param pin 押さえている手。押さえていなければ {@code null}
     */
    private static void deletePinned(Path directory, FileChannel pin) {
        deleteContents(directory);
        if (pin != null) {
            closeQuietly(pin);
        }
        deleteQuietly(directory);
    }

    /**
     * 前の書き出しが残した作業場所を片づける。
     *
     * <p>JVM ごと落ちると、{@link #close} が走らないまま残る。<b>この道具から終わらせる経路は
     * 塞いである</b>（保存中の「終了」も窓の × も待たせる。#134）が、<b>電源断・強制終了と、
     * Windows のログオフ・シャットダウンは残っている</b>（#141）。その場では消せないので、
     * 同じフォルダへ次に書き出すときにここで拾う。
     * <b>{@code deleteOnExit} は使わない</b>——書き出しは daemon スレッドで走り、
     * その経路では走らない。走らない後始末を足すと、片づいているつもりになるだけである。
     *
     * <p><b>消すのは、こちらが作る名前のディレクトリだけである。</b> 同じ名前の頭を持つ
     * ふつうのファイルには触らない——こちらはディレクトリしか作らないので、それは他人のものである。
     *
     * <p><b>2 つ目の窓が同じフォルダへ同時に書いている場合、Windows ではその作業場所に触らない</b>
     * （#222。{@link #inUse}）。作ってから押さえるまでの一瞬に重なれば、こちらが先に押さえて消し、
     * あちらは作り直す（#139。{@link #discard}）。<b>Windows 以外では消しうる。</b>
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
    private static List<Path> discardAbandoned(Path directory) {
        List<Path> kept = new ArrayList<>();
        try (Stream<Path> entries = Files.list(directory)) {
            // 名前で絞ってから discard に渡す。discard は 1 件ごとに属性を読むので、
            // 逆にすると出力先フォルダの全エントリぶん走る。
            // ★ リンクかどうかはここで見ない。discard の関門が見る（#125）。
            //   close() は押さえたまま中を消すので discard を通らないが、同じ関門を自分で通る。
            entries.filter(entry -> entry.getFileName().toString().startsWith(PREFIX))
                    .forEach(entry -> {
                        // ★ 残したものは拾う（#138）。関門は discard の側に置いたままにする。
                        // ★ 知らせるのは pdfjig が作る形の名前だけにする（createTempDirectory は
                        //   PREFIX の後ろに数字だけを付ける）。フォルダに書ける第三者が
                        //   .pdfjig-何でも を置けば、その名前がそのまま窓に出て、
                        //   pdfjig が「あなたの元のファイル」と請け合うことになる。
                        if (discard(entry)
                                && OURS.matcher(entry.getFileName().toString()).matches()
                                && Files.isRegularFile(entry.resolve(REPLACED))) {
                            kept.add(entry.resolve(REPLACED));
                        }
                    });
        } catch (IOException | UncheckedIOException e) {
            // 片づけられなくても、これから書くものの成否は変わらない。
            // ★ Files.list は、返した後の反復で起きた失敗を UncheckedIOException で包む。
            //   IOException を継承しないので、並記しないとここを素通りし、後始末の失敗が
            //   保存そのものの失敗として利用者に出る。
            Logs.warn(LogEvent.WORKSPACE_NOT_DISCARDED, e);
        }
        return List.copyOf(kept);
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
     * 作業場所を消す。消せなくても保存は失敗させない（{@link Logs} には残す）。
     *
     * <p><b>★★ 関門は 2 つあり、どちらもここに置く</b>——呼ぶ側それぞれに置くと、
     * <b>3 つ目の呼び出しが足されたときに黙って素通りする。</b>
     * <ul>
     *   <li><b>作業場所がリンクなら触らない</b>（{@link #isOwnDirectory}。#125）。
     *       自分で作ったものではなく、辿れば指す先を消す
     *   <li><b>控えを抱えているなら何もしない</b>（{@link #holdsTheOnlyCopy}）
     * </ul>
     *
     * <p><b>★★ 消す前に、こちらが押さえる</b>（#139。{@link #pin}）。使われていないと見ても、
     * 別の窓は作業場所を作ってから押さえるまでの一瞬、落ちた後と同じ形をしている——見てから消すまでに
     * あちらが押さえ・印を立て・元を退避すれば、<b>消えるのはあちらの元のファイルそのものである。</b>
     * こちらが押さえている間は、あちらは押さえを作れず、印を立てるところまで進めない
     * （{@link #nextTo} は作業場所を作り直す）。<b>だから印を見るのは押さえてからにする。</b>
     * 押さえられなければ消さない（控えは知らせる）。押さえられない場所では、保存そのものもできない。
     * Windows 以外では押さえないので、この隙は残る。
     *
     * <p><b>★★ 中は pdfjig が作る 4 つの名前だけを消す</b>（#125）——{@link #NAME} / {@link #REPLACED} /
     * {@link #HELD} / {@link #PIN}。<b>一覧を取らない。</b>
     * <ul>
     *   <li><b>{@link Files#walk} は使わない。</b>既定で {@code FOLLOW_LINKS} を付けていなくても、
     *       Windows のディレクトリジャンクションを降りる——出力先に書ける第三者が作業場所の中に
     *       ジャンクションを置けば、次の保存がその先を消していた
     *   <li><b>辿る形も、一覧を取って 1 階層だけ消す形も採らない。</b>作業場所そのものを、調べた後で
     *       ジャンクションへすり替えられると、<b>その後の消去はどれも指す先で起きる</b>——途中の階層の
     *       リンクは OS が必ず辿る。一覧を取って消すと、<b>第三者が中に置いた名前（{@code report.xlsx} など）が、
     *       指す先で消える</b>。Windows の NIO にはハンドルを起点に消す手（{@code SecureDirectoryStream}）が無い。
     *       辿る形は、再帰の深さを第三者に決めさせて {@code StackOverflowError} で保存ごと落ちた
     *       （7,000 階層前後。#125 の {@code /code-review max} で再現）
     *   <li><b>決まった名前だけなら、すり替えに勝たれても消えうるのはその 4 つの名前だけで、
     *       第三者は名前を選べない。</b>
     *   <li><b>ほかのものがあれば、作業場所は消せずに残り、記録される</b>——pdfjig が作らないもので
     *       あり、正体の分からないものを消さない側へ倒す（{@code CLAUDE.md} 優先順位 1）
     * </ul>
     *
     * <p><b>残ったことを記録しない。</b>残すのは正しい振る舞いであって失敗ではなく、
     * ログに書くのは失敗したことだけである（{@code docs/SPEC.md} §10.4）。
     * <b>そこへ至る失敗は既に記録されている</b>（{@code DocumentWriter#restore}）。
     * <b>★ 利用者へ伝えるのは呼ぶ側である</b>（{@link #nextTo(Path, Consumer)}。#138）。
     *
     * @return 控えを抱えていて、残したなら {@code true}
     */
    private static boolean discard(Path directory) {
        return discard(directory, () -> {});
    }

    /**
     * {@link #discard(Path)} の本体。
     *
     * <p><b>package-private なのはテストのためである</b>（{@code OutputWorkspaceTest}）。
     * 別の窓が割り込む順序を、手で組む（#139）。
     *
     * @param decided 消すと決めてから、消し始めるまでの間に走る。本番では何もしない
     */
    static boolean discard(Path directory, Runnable decided) {
        if (!isOwnDirectory(directory) || inUse(directory)) {
            return false;
        }
        FileChannel claim;
        try {
            claim = pin(directory);
        } catch (FileAlreadyExistsException e) {
            // 見てから押さえるまでの間に、持ち主が押さえた。片づけるのはあちらである。
            // ★ 押さえの名前でものを置かれただけなら、使われてはいない。控えは知らせる（#222）。
            return !inUse(directory) && holdsTheOnlyCopy(directory);
        } catch (IOException | PdfjigException e) {
            // 押さえられないまま消すと、#139 の隙が戻る。消さずに残し、控えは知らせる。
            Logs.warn(LogEvent.WORKSPACE_NOT_DISCARDED, e);
            return holdsTheOnlyCopy(directory);
        }
        try {
            if (holdsTheOnlyCopy(directory)) {
                return true;
            }
            decided.run();
            deletePinned(directory, claim);
            return false;
        } finally {
            if (claim != null) {
                closeQuietly(claim);
            }
        }
    }

    /** pdfjig が作業場所の中に作る名前を消す。{@link #PIN} は閉じたときに消えるので、ここでは触らない。 */
    private static void deleteContents(Path directory) {
        deleteQuietly(directory.resolve(NAME));
        deleteQuietly(directory.resolve(REPLACED));
        deleteQuietly(directory.resolve(HELD));
    }

    /**
     * 別の窓がいま使っている作業場所か（#222）。
     *
     * <p><b>使っている窓は {@link #PIN} を、削除を共有せずに開いている</b>（{@link #pin}）。
     * 消せなければ使われている——<b>触らず、知らせもしない</b>。片づけるのも、控えを伝えるのも、
     * あちらの仕事である。以前はここを見分けられず、あちらの書きかけを消しにいって
     * 「片づけられなかった」と記録し、退避の最中なら「控えが残っている」と伝えていた。
     *
     * <p><b>消せたなら使われていない。</b>落ちた書き出しの {@code pin} は、閉じられていれば消えている
     * （{@code DELETE_ON_CLOSE}）。<b>電源断では閉じられずに残る</b>——それも消してよい。
     *
     * <p><b>★ 「使われている」と読むのは、ふつうのファイルを消そうとして共有違反で断られたときだけである。</b>
     * 使っている窓が開いていれば、断りは {@code AccessDeniedException} ではない {@code FileSystemException} で来る
     * （2026-10-19 実測）。<b>ほかの断りを「使われている」と読むと、置くだけで落ちた書き出しの控えを
     * 知らせなくさせられる</b>——消せない {@code pin} を置かれる（権限で断られる）、
     * 中身のあるディレクトリを {@code pin} の名前で置かれる（{@code DirectoryNotEmptyException}。ふつうのファイルでないものは見ずに通す。
     * #222 の門の 2 段目で再現）。そのどちらでも、作業場所は消せずに残るが、控えは知らせる。
     * ★ <b>第三者が置いた {@code pin} を開いたまま持ち続ければ、知らせを止められる</b>——
     * 出力先でそのプロセスを動かし続けなければならない。
     */
    private static boolean inUse(Path directory) {
        Path pin = directory.resolve(PIN);
        if (!Files.isRegularFile(pin, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        try {
            Files.delete(pin);
            return false;
        } catch (AccessDeniedException | NoSuchFileException notInUse) {
            return false;
        } catch (IOException sharingViolation) {
            return true;
        }
    }

    /**
     * リンクではない、ここにある実体のディレクトリか。<b>確かめられなければ {@code false}</b>——触らない側へ倒す。
     *
     * <p><b>★ {@code isSymbolicLink} だけでは足りない。</b>ジャンクションは {@code NOFOLLOW_LINKS} で読んでも
     * {@code isDirectory()} が {@code true}、{@code isSymbolicLink()} が {@code false} を返す。
     * <b>リパースポイントであることは {@code isOther()} にしか出ない</b>（JDK の {@code WindowsFileAttributes}）。
     *
     * <p><b>★ {@code isOther()} だけでも決めない。</b>リンクでないリパースポイントもある——
     * クラウド同期のプレースホルダは、中身を持つ本物のディレクトリである。<b>弾くと作業場所が
     * 片づかなくなり、控えも伝えられなくなる。</b>だから<b>実体の場所が自分の場所と同じか</b>で見る
     * ——リンクなら指す先へ解決され、違う場所になる。
     */
    private static boolean isOwnDirectory(Path path) {
        try {
            BasicFileAttributes attributes =
                    Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isDirectory() || attributes.isSymbolicLink()) {
                return false;
            }
            if (!attributes.isOther()) {
                return true;
            }
            Path here = path.toAbsolutePath().getParent().toRealPath().resolve(path.getFileName());
            return path.toRealPath().equals(here);
        } catch (IOException e) {
            return false;
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
