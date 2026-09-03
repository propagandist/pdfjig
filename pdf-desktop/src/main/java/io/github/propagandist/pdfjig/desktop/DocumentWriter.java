package io.github.propagandist.pdfjig.desktop;

import io.github.propagandist.pdfjig.core.ErrorCode;
import io.github.propagandist.pdfjig.core.PageOperations;
import io.github.propagandist.pdfjig.core.PageSelection;
import io.github.propagandist.pdfjig.core.PdfBoxPageOperations;
import io.github.propagandist.pdfjig.core.PdfjigException;
import io.github.propagandist.pdfjig.core.Warning;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 編集した並びをファイルに書き出す。
 *
 * <p><b>書き出しは画面の仕事ではない</b>（#57）。画面に置いてあったせいで、
 * <b>同じ理由の修正が繰り返し画面へ来ていた</b>——一時ファイルの作り方（#53）も、
 * 分割の二重実装（#56）も、出どころはそこだった。
 *
 * <p><b>★ バックグラウンドスレッドから呼ぶ</b>（{@link BackgroundTasks}）。
 * ここに JavaFX の型は 1 つも無く、<b>画面を持たずに読める</b>。
 *
 * <p><b>警告は投げずに集めて返す。</b>書き出せたかどうかと、書き出した結果に
 * 気をつけるべき点があるかは別の話である（{@code CLAUDE.md} 優先順位 2）。
 */
final class DocumentWriter {

    private DocumentWriter() {}

    /**
     * ページ列を 1 つのファイルに書き出す。
     *
     * <p>{@link OutputWorkspace} に書いてから置き換える。保存先を選ぶダイアログは既存ファイルへの
     * 上書きを利用者に確認するが、pdf-core は既存の出力を拒む。先に消してしまうと
     * 書き込みに失敗したときに元のファイルが失われる。
     *
     * <p><b>★★ 置き換えは「元をどけてから入れる」2 本の改名である</b>（{@link #move}。#119）。
     * <b>出力先を開いたまま上書き保存する経路も、そこを通る</b>——{@code #113} で足した
     * 原子的な置き換えは<b>その経路だけを断られており</b>、いちばんありふれた使い方
     * （開いて、直して、同じ名前で保存する）が<b>「先に消してから動かす」に落ちていた。</b>
     *
     * <p><b>この経路だけが pdf-core の「既存の出力を拒む」約束の上に層を重ねている。</b>
     * ダイアログで確認が取れているので置き換えてよい、という判断であり、
     * <b>分割</b>は層を重ねずに拒むほうを保っている（{@link #splitInto}）。
     * 約束が 2 段になっていることの正本は {@code docs/SPEC.md} §4.2 にある。
     * <b>確認を出すのは OS のダイアログなので、出ていることを自動テストでは確かめられない</b>
     * （{@link FileDialogs} の向こう側。{@code docs/HANDOVER.md} 4-4 の 10 番）。
     *
     * <p><b>★★ 呼ぶ側は、書き出し先が利用者の確認を通っていることを保証すること。</b>
     * {@code MainWindow} の中に private で置いてあった間は呼べる相手が 1 つしか無かったので、
     * <b>ここへ出したぶんは ArchUnit で縛ってある</b>（{@code assembleIsCalledOnlyByMainWindow}）。
     *
     * <p><b>★ 確認を出しているのは {@link NativeFileDialogs} だけである。</b>
     * {@link FileDialogs} は差し替えられる口で、<b>「選ばれたファイル」としか約束していない</b>
     * ——{@code StubFileDialogs} は確認を 1 度も出さずに決め打ちのパスを返す。
     * <b>「{@link FileDialogs} を通った」は「利用者が上書きを承知した」ではない。</b>
     *
     * <p><b>確認を取れない経路から 1 つのファイルへ書くなら、ここではなく
     * {@link PageOperations#assemble} を直に呼ぶこと。</b>あちらは既存の出力を拒む。
     * <b>{@link #splitInto} は代わりにならない</b>——書き出し先はフォルダで、
     * 名前を決めるのは pdf-core である。
     *
     * <p><b>★★ 失敗して控えが残ったら、その在り処を載せて投げる</b>
     * （{@link ReplacedFileKeptException#reporting}。#124）。<b>そのとき出力先には何も無く、
     * 元は作業場所の中にしか無い</b>ので、汎用の失敗だけを返すと
     * <b>利用者から見えるのは「ファイルが消えた」だけになる</b>（{@code CLAUDE.md} 優先順位 2）。
     *
     * <p><b>★ 見るのは印だけで、失敗の仕方を数え上げない。</b>ここが<b>作業場所を閉じる
     * 唯一の場所</b>なので、<b>この先どんな失敗の仕方が増えても、控えを残したまま外へ出るものは
     * すべてここを通る。</b>
     *
     * @param sources 元のファイル
     * @param pages   書き出すページの指定
     * @param output  書き出し先
     * @return 途中で出た警告
     */
    static List<Warning> assemble(List<Path> sources, List<PageSelection> pages, Path output) {
        List<Warning> warnings = Collections.synchronizedList(new ArrayList<>());
        PageOperations operations = new PdfBoxPageOperations(warnings::add);

        // ★ try-with-resources の資源として使いつつ、catch からも触れる形にする。
        //   Java は catch より先に close を走らせるが、抱えたままなら片づけは何もしないので、
        //   印はそこで読める（OutputWorkspace#stillHoldsOriginal）。
        OutputWorkspace workspace = OutputWorkspace.nextTo(output);
        try (workspace) {
            operations.assemble(sources, pages, workspace.file());
            move(workspace.file(), output, workspace);
        } catch (RuntimeException failed) {
            throw ReplacedFileKeptException.reporting(failed, workspace);
        }
        return List.copyOf(warnings);
    }

    /**
     * この書き出しが、開いている出どころのどれかを置き換えるかどうか。
     *
     * <p><b>★★ 置き換えるなら、書き出した後にセッションを寄せ直さなければならない</b>（#118）。
     * {@link PageOperations#assemble} は<b>毎回ディスクから読み直す</b>ので、出どころの中身が
     * 書き出したものに入れ替わったまま同じ指定をもう一度当てると、<b>同じ変換が二重に掛かる</b>——
     * 回転は保存のたびに 90 度ずつ回り、削除は 2 回目に {@code PAGE_OUT_OF_RANGE} で止まる。
     *
     * <p><b>★ 名前で比べない。</b>Windows は大文字小文字を区別せず、リンクや {@code ..} を挟めば
     * 同じファイルが違う名前で書ける。<b>{@link Files#isSameFile} は実体で比べる。</b>
     * <b>★★ 比べられなければ「置き換えである」と答える</b>——外したときの損は
     * 要らない開き直しが 1 回走ることだけで、<b>取り違えたときの損は文書が壊れることである。</b>
     *
     * <p><b>★ 書き出す前に呼ぶこと。</b>後から呼んでも同じ答えになるが、
     * <b>「これから何を置き換えるのか」を見ているという意味が読めなくなる。</b>
     *
     * <p><b>出力先がまだ無ければ置き換えではない。</b>出どころは開かれている＝必ず存在するので、
     * 存在しないものと同じ実体にはなりえない。
     *
     * @param sources 開いている出どころ
     * @param output  書き出し先
     * @return 出どころのどれかを置き換えるなら {@code true}
     */
    static boolean replacesAnyOf(List<Path> sources, Path output) {
        if (!Files.exists(output)) {
            return false;
        }
        for (Path source : sources) {
            try {
                if (Files.isSameFile(source, output)) {
                    return true;
                }
            } catch (IOException e) {
                // ★★ 比べられなければ「置き換えである」と答える。名前で見る形に落とすと、
                //    ジャンクションやハードリンクを見抜けず——つまり isSameFile を選んだ理由の
                //    ちょうどその場合で——置き換えでないと答えてしまう。
                //    外したときの損は「要らない開き直しが 1 回走る」だけであり、
                //    取り違えたときの損は文書が壊れることである（CLAUDE.md 優先順位 1）。
                return true;
            }
        }
        return false;
    }

    /**
     * かたまりごとに書き出す。
     *
     * <p><b>連番の付け方も、書けないときの約束も pdf-core が持つ</b>
     * （{@link PageOperations#assembleEach}）。ここに写すと、同じ「分割」という操作の
     * 挙動が 2 か所に分かれ、しかも違いは失敗したときにしか出ない。
     *
     * @param sources   元のファイル
     * @param segments  かたまりごとのページ指定。先頭から順に連番で書き出す
     * @param outputDir 書き出し先のフォルダ
     * @return 書き出した数と、途中で出た警告
     */
    static SplitResult splitInto(List<Path> sources, List<List<PageSelection>> segments, Path outputDir) {
        List<Warning> warnings = Collections.synchronizedList(new ArrayList<>());
        PageOperations operations = new PdfBoxPageOperations(warnings::add);

        List<Path> outputs = operations.assembleEach(sources, segments, outputDir);
        return new SplitResult(outputs.size(), List.copyOf(warnings));
    }

    /**
     * 分割の結果。
     *
     * @param fileCount 書き出したファイルの数
     * @param warnings  途中で出た警告
     */
    record SplitResult(int fileCount, List<Warning> warnings) {}

    /**
     * 作業場所から出力先へ移す。
     *
     * <p><b>★★ 元をどけてから入れる。2 本の改名であり、置き換えは 1 度もしない</b>（#119）。
     * 出力先に何かあれば、まず作業場所へ<b>改名して退避し</b>（{@link #setAside}）、
     * 空いたところへ書けたものを入れる（{@link #replaceWith}）。
     *
     * <p><b>これで「両方無い」がどの瞬間にも存在しない。</b>2 本の間で落ちても、
     * 元は作業場所の中に実体として残る（{@link OutputWorkspace} は控えを抱えた作業場所を消さない）。
     * 2 本目に失敗したら 1 本目を巻き戻す（{@link #restore}）。
     *
     * <p><b>★ 済んだあとも控えそのものは残す。</b>片づけるのは {@link OutputWorkspace} の仕事で、
     * ここが下ろすのは<b>「抱えている」という印だけである</b>（{@link OutputWorkspace#releaseOriginal}）。
     * <b>控えを印にすると、いちど消せなかっただけで片づけが永久に閉じる</b>
     * （{@link OutputWorkspace#HELD}）。
     *
     * <p><b>★★ 素直に置き換えないのは、いちばんありふれた経路がそれを断るからである。</b>
     * {@code MoveFileEx(..., MOVEFILE_REPLACE_EXISTING)} は<b>置き換え先が開かれていると
     * {@code AccessDeniedException} で断り</b>、<b>開いているのは pdfjig 自身である</b>
     * ——{@code DocumentSession} が持つ {@code PdfDocument} は
     * <b>書き出しの元になっている文書を、保存の間に閉じない</b>（サムネイルの描画が
     * そのハンドルに依存している）。#113 はそこで普通の置き換えに落としており、
     * <b>「開いて、直して、同じ名前で保存する」だけが 2 段のまま残っていた。</b>
     *
     * <p><b>★ 断られるのは「掴まれている宛先を置き換える」ときだけである。</b>
     * <b>掴まれているものを改名して、空いた名前へ入れるのは通る</b>——2026-08-31 に
     * Windows 10 / JDK 21.0.8 で、{@code PdfDocument} で実際に開いたまま実測した。
     * 退避・入れ替え・巻き戻しの 3 本とも成功し、掴んだ実体は読めたままだった。
     *
     * <p><b>★★ 「置き換えを頼まない改名だから通る」ではない。</b>JDK は
     * {@code ATOMIC_MOVE} を頼まれた時点で<b>{@code MOVEFILE_REPLACE_EXISTING} を必ず渡す</b>
     * ——{@code REPLACE_EXISTING} を書かなくても、既存の宛先は黙って置き換わる
     * （2026-09-01 実測）。<b>効いているのは宛先が空いていることであって、旗の有無ではない。</b>
     * <b>{@link #setAside} が退避先を壊さないのは、作業場所が作りたてで中が空だからである</b>
     * （{@link OutputWorkspace}。#53）。
     * <b>PDFBox 3.0.5 が {@code FileChannel#open} で開いている</b>ことがその根拠である
     * （{@code RandomAccessReadBufferedFile}）——<b>{@code FILE_SHARE_DELETE} が要る。</b>
     * <b>★★ {@code java.io.RandomAccessFile} で開くと退避そのものが共有違反で落ちる</b>
     * （同日実測）。<b>PDFBox の開き方に依存しており、そこが変われば上書き保存が壊れる</b>
     * ——{@code DocumentWriterTest} は同じ開き方で縛っているが、
     * <b>あちらの版が変わったことは鳴らない</b>（{@code OverwriteSaveUiTest} が通る側である）。
     *
     * <p><b>★ 断る理由を数え上げない。</b>{@link AtomicMoveNotSupportedException} だけに
     * 絞ってはならない——次に増えた理由で落ちなくなる。{@link OutputWorkspace} は作業場所を
     * 出力先の<b>同じフォルダ</b>に作るので、Windows でこの例外になる唯一の条件
     * （{@code ERROR_NOT_SAME_DEVICE}）は起こりようがないが、それでも
     * {@code IOException} で受ける。
     *
     * <p><b>★ {@code Settings#replace} とは揃えない</b>（#113 が残した宿題への答え）。
     * <b>あちらが守るのは、失っても選び直せば済むフォルダの記憶であり、書けなければ諦める</b>
     * （{@code docs/SPEC.md} §10.2）。<b>ここが守るのは利用者の文書である。</b>
     * 形を 1 つにすると、<b>諦めてよい側の都合が、諦めてはならない側に効く。</b>
     *
     * <p><b>★ 落ちたことを記録しない。</b>フォールバックを通るのは失敗ではない。
     * ログに書くのは {@code WARNING} 以上、つまり失敗したことだけである
     * （{@code docs/SPEC.md} §10.4）——ここで書くと、正常に一巡しただけでログができる。
     *
     * <p><b>package-private なのはテストのためである</b>（{@code DocumentWriterTest}）。
     * <b>呼んでよいのはこのクラスの中だけである</b>——ArchUnit が縛っている
     * （{@code replaceIsCalledOnlyByDocumentWriter}）。
     *
     * @param from      書けたもの。作業場所の中にある
     * @param to        出力先
     * @param workspace 退避先と、抱えていることの印を持つ（{@link OutputWorkspace#replaced}）
     */
    static void move(Path from, Path to, OutputWorkspace workspace) {
        Path aside = workspace.replaced();
        boolean kept = setAside(to, aside, workspace);
        try {
            replaceWith(from, to);
        } catch (RuntimeException failed) {
            if (kept && restore(aside, to)) {
                // 元の場所へ返した。もう抱えていない。
                workspace.releaseOriginal();
            }
            throw failed;
        }
        if (kept) {
            // 置き換えが済んだ。控えはもう要らない（作業場所ごと片づく）。
            workspace.releaseOriginal();
        }
    }

    /**
     * 出力先に元からあったものを、消さずに作業場所へどける。
     *
     * <p><b>★★ 書けない出力先には手を出さない。</b>Windows の<b>改名は読み取り専用属性を
     * 無視して通る</b>——見ずに退避すると、<b>利用者が読み取り専用にした文書が警告なく
     * 置き換わり、属性まで落ちる</b>（2026-09-01 実測。以前は置き換えが
     * {@code AccessDenied} で断られ、保存そのものが失敗していた）。
     * <b>属性は利用者の意思表示であり、直しの巻き添えで無効にしてよいものではない</b>
     * （{@code CLAUDE.md} 優先順位 2）。
     *
     * <p><b>★ 退避できなければ書き出しを失敗させる。</b>普通の置き換えに落とす手もあるが、
     * それは<b>この修正が消したかった経路そのもの</b>である。失敗すれば元は手つかずで残り、
     * 利用者は失敗を見て選び直せる（{@code CLAUDE.md} 優先順位 1）。
     *
     * <p><b>★ 印を先に立てる。</b>退避してから立てると、その間に落ちたときに
     * <b>印の無い控えが残り、次の書き出しがそれを消す</b>（{@link OutputWorkspace#holdOriginal}）。
     *
     * @return 退避したなら {@code true}。出力先がまだ無ければ {@code false}
     */
    private static boolean setAside(Path to, Path aside, OutputWorkspace workspace) {
        if (Files.notExists(to)) {
            return false;
        }
        if (!Files.isWritable(to)) {
            throw new PdfjigException(ErrorCode.IO_FAILURE);
        }
        workspace.holdOriginal();
        try {
            Files.move(to, aside, StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (IOException e) {
            // 退避していないので、印を残すと空の作業場所が片づかなくなる。
            workspace.releaseOriginal();
            throw PdfjigException.wrapping(ErrorCode.IO_FAILURE, e);
        }
    }

    /**
     * 空いた出力先へ、書けたものを入れる。
     *
     * <p><b>{@code REPLACE_EXISTING} を外さない。</b>退避した後にそこへ何かが現れることは
     * ありうる（2 つ目の窓・別のアプリ）。<b>そのとき無いことを前提にすると落ちる</b>し、
     * 落ちれば巻き戻して元へ戻ることになる——<b>利用者が承知した上書きが、
     * 割り込んだ側の都合で失敗する。</b>
     */
    private static void replaceWith(Path from, Path to) {
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return;
        } catch (IOException refused) {
            // 断られた。落とした先で書き出せることがある（上の★）。
        }
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw PdfjigException.wrapping(ErrorCode.IO_FAILURE, e);
        }
    }

    /**
     * 退避したものを出力先へ戻す。
     *
     * <p><b>★ 入れるときと同じ形を通す</b>（{@link #replaceWith}）。書き分けると
     * <b>「出力先へ改名する」規則が 2 か所に分かれ、次に直すとき忘れられるのは
     * 巻き戻しのほうである</b>——ふつうは通らない経路だからである。
     *
     * <p><b>戻せなくても、ここで新しい例外を投げない。</b>呼ぶ側は入れ替えの失敗を投げ直す
     * ところであり、<b>後始末の失敗で元の理由を上書きすると、何が起きたのか読めなくなる。</b>
     *
     * <p><b>★★ 戻せなかったことは記録に残す。</b>そのとき出力先には何も無く、
     * <b>元は作業場所の中にしか無い</b>——{@link OutputWorkspace} はそれを消さないが、
     * <b>消さなかったことがどこにも残らなければ、利用者にも読む側にも辿れない。</b>
     * <b>この経路が {@link LogEvent#REPLACED_FILE_KEPT} の唯一の出どころである。</b>
     *
     * <p><b>★ {@code RuntimeException} で受ける。</b>{@link #replaceWith} が投げるのは
     * {@link PdfjigException} だけだが、<b>狭く書くと上の約束が型で保証されない</b>——
     * 別の非チェック例外が抜けた瞬間に、呼ぶ側の {@code throw failed} へ到達しなくなる。
     *
     * @return 戻せたなら {@code true}
     */
    private static boolean restore(Path kept, Path to) {
        try {
            replaceWith(kept, to);
            return true;
        } catch (RuntimeException e) {
            Logs.warn(LogEvent.REPLACED_FILE_KEPT, e);
            return false;
        }
    }
}
