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
     * <p><b>★ 置き換えが「失敗しても元のファイルが残る」と言い切れるのは、原子的な移動が
     * 通った場合だけである</b>（{@link #move}）。<b>通らなかった経路は、いまも
     * 「先に消してから動かす」ままである。</b>
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
     * @param sources 元のファイル
     * @param pages   書き出すページの指定
     * @param output  書き出し先
     * @return 途中で出た警告
     */
    static List<Warning> assemble(List<Path> sources, List<PageSelection> pages, Path output) {
        List<Warning> warnings = Collections.synchronizedList(new ArrayList<>());
        PageOperations operations = new PdfBoxPageOperations(warnings::add);

        try (OutputWorkspace workspace = OutputWorkspace.nextTo(output)) {
            operations.assemble(sources, pages, workspace.file());
            move(workspace.file(), output);
        }
        return List.copyOf(warnings);
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
     * <p><b>まず原子的な移動を頼む。</b> 頼まないと Windows では {@code DeleteFile} →
     * {@code MoveFileEx} の 2 段になり、<b>先に消してから動かす</b>——その 2 つの間に
     * 割り込まれると、元のファイルも置き換えるはずのものも無い状態になる（#113）。
     * {@link OutputWorkspace} が作業場所を出力先の隣に作っているのは、
     * <b>同じボリュームなら 1 回の {@code MoveFileEx} で済むから</b>である。
     *
     * <p><b>★★ 断られたら、理由を問わず普通の置き換えに落とす。</b>
     * {@link AtomicMoveNotSupportedException} だけに絞ってはならない——<b>原子的な置き換えは、
     * 出力先が開かれていると {@code AccessDeniedException} で断る。</b>そして
     * <b>画面から上書き保存するとき、その出力先を開いているのは pdfjig 自身でありうる</b>
     * （{@code DocumentSession} は保存の間も入力を閉じない）。絞ると
     * <b>「開いている文書へ上書き保存する」が必ず失敗する</b>——2026-08-30 に
     * Windows 10 / JDK 21.0.8 で実測した（{@code DocumentWriterTest}）。
     *
     * <p><b>落ちた先は 2 段のままである。</b>それでも先に原子的を試す価値があるのは、
     * <b>出力先を誰も開いていない経路がそれで 1 回の {@code MoveFileEx} になる</b>からである。
     * {@code Settings#replace} も同じ形だが、あちらは落とす条件が狭い。
     * <b>2 つを 1 つにするかは #113 では決めていない。</b>
     *
     * <p><b>★ 落ちたことを記録しない。</b>落ちるのは<b>開いている文書へ上書き保存する</b>という
     * ふつうの経路であり、<b>失敗ではない</b>。ログに書くのは {@code WARNING} 以上、つまり
     * 失敗したことだけである（{@code docs/SPEC.md} §10.4）——ここで書くと、
     * <b>正常に一巡しただけでログができる。</b>
     * <b>どちらの経路を通ったかは、実行中も後からも分からない</b>ことになるが、
     * <b>それを知るために正常な動作を失敗として記録するほうが高くつく。</b>
     *
     * <p><b>package-private なのはテストのためである</b>（{@code DocumentWriterTest}）。
     * <b>呼んでよいのはこのクラスの中だけである</b>——ArchUnit が縛っている
     * （{@code replaceIsCalledOnlyByDocumentWriter}）。
     */
    static void move(Path from, Path to) {
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return;
        } catch (IOException refused) {
            // 断られた。理由は上の 2 通りある。どちらでも、落とした先で書き出せることがある。
        }
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw PdfjigException.wrapping(ErrorCode.IO_FAILURE, e);
        }
    }
}
