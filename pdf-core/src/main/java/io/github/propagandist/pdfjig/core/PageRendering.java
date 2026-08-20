package io.github.propagandist.pdfjig.core;

import java.awt.image.BufferedImage;

/**
 * ページを画像に描画する。
 *
 * <p>描画は読み取りのみであり、入力文書を変更しない（CLAUDE.md INV-4）。
 *
 * <p><b>呼び出しは必ずバックグラウンドスレッドで行うこと</b>（SPEC.md §7.2）。
 * 100 ページの文書を開いた瞬間に UI が固まるのを避けるための規約である。
 *
 * <p><b>同一の {@link PdfDocument} に対する描画を並行して行ってはならない。</b>
 * PDFBox の文書オブジェクトはスレッド安全ではない。呼び出し側は 1 文書あたり
 * 1 スレッドに直列化する必要がある。
 */
public interface PageRendering {

    /**
     * 一覧表示用の縮小画像を描画する。
     *
     * <p>縦横比は保たれる。長辺が {@code maxEdgePixels} になる倍率で描画するため、
     * 縦長のページと横長のページが同じ枠に収まる。
     *
     * @param document      対象文書
     * @param pageNumber    ページ番号（1 始まり）
     * @param maxEdgePixels 長辺のピクセル数
     * @return 描画された画像
     * @throws PdfjigException ページが範囲外、または描画に失敗した場合
     */
    BufferedImage renderThumbnail(PdfDocument document, int pageNumber, int maxEdgePixels);

    /**
     * 指定した解像度でページを描画する。
     *
     * @param document   対象文書
     * @param pageNumber ページ番号（1 始まり）
     * @param dpi        解像度
     * @return 描画された画像
     * @throws PdfjigException ページが範囲外、または描画に失敗した場合
     */
    BufferedImage render(PdfDocument document, int pageNumber, float dpi);
}
