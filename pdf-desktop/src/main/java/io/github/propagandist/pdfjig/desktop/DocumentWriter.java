package io.github.propagandist.pdfjig.desktop;

import io.github.propagandist.pdfjig.core.ErrorCode;
import io.github.propagandist.pdfjig.core.PageOperations;
import io.github.propagandist.pdfjig.core.PageSelection;
import io.github.propagandist.pdfjig.core.PdfBoxPageOperations;
import io.github.propagandist.pdfjig.core.PdfjigException;
import io.github.propagandist.pdfjig.core.Warning;
import java.io.IOException;
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
     * 書き込みに失敗したときに元のファイルが失われる。置き換えなら、失敗しても
     * 元のファイルはそのまま残る。
     *
     * <p><b>この経路だけが pdf-core の「既存の出力を拒む」約束の上に層を重ねている。</b>
     * ダイアログで確認が取れているので置き換えてよい、という判断であり、
     * <b>分割</b>は層を重ねずに拒むほうを保っている（{@link #splitInto}）。
     * 約束が 2 段になっていることの正本は {@code docs/SPEC.md} §4.2 にある。
     * <b>確認を出すのは OS のダイアログなので、出ていることを自動テストでは確かめられない</b>
     * （{@link FileDialogs} の向こう側。{@code docs/HANDOVER.md} 4-4 の 10 番）。
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

    private static void move(Path from, Path to) {
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw PdfjigException.wrapping(ErrorCode.IO_FAILURE, e);
        }
    }
}
