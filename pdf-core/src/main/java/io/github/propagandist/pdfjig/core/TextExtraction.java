package io.github.propagandist.pdfjig.core;

import java.util.List;

/**
 * PDF からのテキスト抽出。
 *
 * <p>抽出は読み取りのみであり、入力文書を変更しない（CLAUDE.md INV-4）。
 *
 * <p><b>ページ番号は 1 始まりである。</b>
 */
public interface TextExtraction {

    /**
     * 文書全体のテキストを 1 つの文字列として返す。
     *
     * @param document 対象文書
     * @return 抽出されたテキスト。改行は {@code \n} に正規化される
     * @throws PdfjigException 抽出に失敗した場合
     */
    String extractAll(PdfDocument document);

    /**
     * ページごとに分割してテキストを返す。
     *
     * @param document 対象文書
     * @return ページ番号昇順のリスト。要素数は {@link PdfDocument#pageCount()} に等しい
     * @throws PdfjigException 抽出に失敗した場合
     */
    List<PageText> extractByPage(PdfDocument document);

    /**
     * 1 ページ分のテキストを座標付きで返す。
     *
     * @param document   対象文書
     * @param pageNumber ページ番号（1 始まり）
     * @return 出現順のグリフ列。テキストを持たないページでは空リスト
     * @throws PdfjigException ページが範囲外、または抽出に失敗した場合
     */
    List<PositionedText> extractWithPositions(PdfDocument document, int pageNumber);
}
