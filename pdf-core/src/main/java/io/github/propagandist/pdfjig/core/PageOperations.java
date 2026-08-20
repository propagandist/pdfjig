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
     * @throws PdfjigException ページが範囲外、または入力のページが 90 度の倍数でない
     *                         回転角を持つ場合（{@link ErrorCode#MALFORMED_PAGE_ROTATION}）
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
