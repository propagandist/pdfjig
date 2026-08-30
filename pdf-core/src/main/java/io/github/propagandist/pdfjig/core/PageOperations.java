package io.github.propagandist.pdfjig.core;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * ページ単位の操作。PDF 本文には触れない（CLAUDE.md INV-4）。
 *
 * <p><b>すべての実装が守る規約:</b>
 *
 * <ul>
 *   <li><b>ページ番号は 1 始まり。</b> 利用者が目にする番号と一致させる</li>
 *   <li><b>入力ファイルを変更しない。</b> 結果は必ず別のファイルに書く</li>
 *   <li><b>出力先が既に存在する場合は失敗する</b>（{@link ErrorCode#OUTPUT_ALREADY_EXISTS}）。
 *       上書きの判断は利用者のものであり、暗黙に行わない。この規約により、
 *       入力と同じパスを出力に指定して入力を壊す事故も同時に防がれる</li>
 *   <li><b>暗号化された入力を扱った場合、必ず警告を発する</b>
 *       （{@link Warning#ENCRYPTION_NOT_PROPAGATED}）。出力は平文になる</li>
 * </ul>
 *
 * <p><b>M0 の制約:</b> パスワードが必要な入力は開けない
 * （{@link ErrorCode#PASSWORD_REQUIRED} で失敗する）。パスワードを受け取る経路は
 * 暗号化機能一式とあわせて M1 で追加する。
 */
public interface PageOperations {

    /**
     * 複数の PDF を 1 つに結合する。
     *
     * @param inputs  入力ファイル。この順に連結される
     * @param output  出力ファイル。既存であってはならない
     * @param options 結合の指定
     * @return {@code output}
     * @throws PdfjigException 入力が空、出力が既存、読み書きに失敗した場合
     */
    Path merge(List<Path> inputs, Path output, MergeOptions options);

    /**
     * 1 つの PDF を複数に分割する。
     *
     * <p>出力ファイル名は {@code <入力名>_001.pdf} の形で、区切りの順に採番する。
     * {@code outputDir} は存在しなければ作成する。
     *
     * @param input     入力ファイル
     * @param strategy  切り方
     * @param outputDir 出力先ディレクトリ
     * @return 生成されたファイル。区切りの順
     * @throws PdfjigException 出力先に同名のファイルが既にある、読み書きに失敗した場合
     */
    List<Path> split(Path input, SplitStrategy strategy, Path outputDir);

    /**
     * ページを並べ替える。
     *
     * @param input    入力ファイル
     * @param newOrder 新しい順序。全ページの並べ替えでなければならない
     *                 （1 から総ページ数までが過不足なく 1 回ずつ現れること）
     * @param output   出力ファイル。既存であってはならない
     * @return {@code output}
     * @throws PdfjigException 並べ替えが不正な場合は {@link ErrorCode#INVALID_PAGE_ORDER}
     */
    Path reorder(Path input, List<Integer> newOrder, Path output);

    /**
     * 指定したページを、指定した順と向きで並べた文書を書き出す。
     *
     * <p>{@link #reorder} と {@link #extractPages} と {@link #deletePages} と
     * {@link #rotate} を一度に表現できる。UI 上でこれらを続けて行った結果は、
     * 一度の書き出しで確定させなければならない。個別の操作に分けると、並べ替えた
     * 状態で回転を実行した利用者に、並べ替えの消えた文書を渡すことになる。
     *
     * <p>同じページを複数回含めてよい。禁止する理由がなく、
     * {@link SplitStrategy.ByRanges} が範囲の重なりを許していることとも揃う。
     *
     * @param input  入力ファイル
     * @param pages  出力に含めるページ。この順に並ぶ
     * @param output 出力ファイル。既存であってはならない
     * @return {@code output}
     * @throws PdfjigException 指定が空の場合は {@link ErrorCode#EMPTY_RESULT}、
     *                         範囲外のページを含む場合は {@link ErrorCode#PAGE_OUT_OF_RANGE}
     */
    Path assemble(Path input, List<PageSelection> pages, Path output);

    /**
     * 複数の入力にまたがってページを集め、指定した順と向きで並べた文書を書き出す。
     *
     * <p>{@link #assemble(Path, List, Path)} の一般形である。どのページも
     * {@link PageSelection#sourceIndex()} で {@code inputs} のどれから取るかを示す。
     *
     * <p>これがあることで「複数のファイルを 1 つに綴じる」操作を、並べ替え・削除・回転と
     * 同じ 1 回の書き出しで表せる。{@link #merge} との違いは、ページ単位で順序と向きを
     * 指定できる点にある。UI は開いている文書に他のファイルを足したうえで、
     * すべてを混ぜて並べ替えてから一度だけ書き出す（SPEC.md §7.1）。
     *
     * @param inputs 入力ファイル。{@link PageSelection#sourceIndex()} がこの並びを指す
     * @param pages  出力に含めるページ。この順に並ぶ
     * @param output 出力ファイル。既存であってはならない
     * @return {@code output}
     * @throws PdfjigException 入力が空の場合は {@link ErrorCode#NO_INPUT}、
     *                         指定が空の場合は {@link ErrorCode#EMPTY_RESULT}、
     *                         範囲外の出どころやページを含む場合は
     *                         {@link ErrorCode#PAGE_OUT_OF_RANGE}
     */
    Path assemble(List<Path> inputs, List<PageSelection> pages, Path output);

    /**
     * 組み立てた並びを、かたまりごとに連番のファイルとして書き出す。
     *
     * <p>出力ファイル名は {@code <最初の入力名>_001.pdf} の形で、かたまりの順に採番する。
     * {@link #split} と同じ規則である。{@code outputDir} は存在しなければ作成する。
     *
     * <p><b>{@link #split} との違いは、何を切るかにある。</b> あちらは<b>元の並び</b>を
     * 戦略で切る。こちらは<b>呼び出し側が組み立てた並び</b>——並べ替え・回転・削除・
     * 複数ファイルの混在を反映したもの——をそのまま受け取って切る。編集の途中の並びを
     * 分けたい呼び出し側は、元の並びを切られると惑わされる（docs/HANDOVER.md
     * 「分割を『ここで区切る』に変えた」）。
     *
     * <p><b>1 つでも書けないなら、何も書かない。</b> 途中で失敗した場合も、それまでに
     * 書いたものを消してから投げる。何個できたのか分からないまま失敗だけを伝えると、
     * 呼び出し側は出力先を自分で見に行くしかない。
     *
     * @param inputs    入力ファイル。ページ指定の出どころ番号がこの並びの添字になる
     * @param segments  かたまりごとのページ指定。先頭から順に連番で書き出す
     * @param outputDir 出力先ディレクトリ
     * @return 生成されたファイル。かたまりの順
     * @throws PdfjigException          入力が空（{@link ErrorCode#NO_INPUT}）、かたまりが 1 つも無い
     *                                  （{@link ErrorCode#EMPTY_RESULT}）、指定したページが範囲外
     *                                  （{@link ErrorCode#PAGE_OUT_OF_RANGE}）、出力先に同名の
     *                                  ファイルが既にある（{@link ErrorCode#OUTPUT_ALREADY_EXISTS}）、
     *                                  読み書きに失敗した場合
     * @throws IllegalArgumentException {@code outputDir} が {@code null} の場合
     */
    List<Path> assembleEach(List<Path> inputs, List<List<PageSelection>> segments, Path outputDir);

    /**
     * ページを回転する。
     *
     * <p><b>指定は現在の回転角への追加である。</b> {@link Rotation#CLOCKWISE_90} を指定すると、
     * 既に 90 度回転しているページは 180 度になる。利用者の「右に回す」という操作と
     * 一致させるため、絶対角の指定にはしない。{@link Rotation#NONE} は何もしないことを意味する。
     *
     * <p>指定のないページはそのまま保たれる。
     *
     * @param input     入力ファイル
     * @param rotations ページ番号から追加回転への対応
     * @param output    出力ファイル。既存であってはならない
     * @return {@code output}
     * <p>PDF 仕様に反する回転角（90 度の倍数でない値）を持つ入力のページは、
     * PDFBox の解釈に従い 0 度として扱う。
     *
     * @throws PdfjigException ページが範囲外の場合
     */
    Path rotate(Path input, Map<Integer, Rotation> rotations, Path output);

    /**
     * 指定範囲のページだけを取り出す。
     *
     * @param input  入力ファイル
     * @param range  取り出す範囲
     * @param output 出力ファイル。既存であってはならない
     * @return {@code output}
     * @throws PdfjigException 範囲が文書に収まらない場合
     */
    Path extractPages(Path input, PageRange range, Path output);

    /**
     * 指定範囲のページを取り除く。
     *
     * @param input  入力ファイル
     * @param range  取り除く範囲
     * @param output 出力ファイル。既存であってはならない
     * @return {@code output}
     * @throws PdfjigException 全ページが対象になる場合は {@link ErrorCode#EMPTY_RESULT}
     */
    Path deletePages(Path input, PageRange range, Path output);
}
