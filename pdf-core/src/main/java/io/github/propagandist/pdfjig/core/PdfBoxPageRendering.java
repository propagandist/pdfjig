package io.github.propagandist.pdfjig.core;

import static io.github.propagandist.pdfjig.core.PdfBoxGuard.guarded;

import java.awt.image.BufferedImage;
import java.io.IOException;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

/**
 * PDFBox による {@link PageRendering} の実装。
 *
 * <p>状態を持たないが、{@link PdfDocument} 側がスレッド安全でないため、
 * 同じ文書に対する描画は呼び出し側で直列化すること。
 */
public final class PdfBoxPageRendering implements PageRendering {

    /** 1 インチあたりのポイント数。 */
    private static final float POINTS_PER_INCH = 72f;

    /** 表示用。アルファを持たない分だけサムネイル 1 枚あたりの占有量が小さい。 */
    private static final ImageType IMAGE_TYPE = ImageType.RGB;

    @Override
    public BufferedImage renderThumbnail(PdfDocument document, int pageNumber, int maxEdgePixels) {
        if (maxEdgePixels < 1) {
            throw new IllegalArgumentException("maxEdgePixels は 1 以上でなければなりません。");
        }
        PageRange.singlePage(pageNumber).validateAgainst(document.pageCount());

        // ★★ 寸法を読むところも包みの中である。ページツリーも CropBox も細工 PDF が
        //   決められるところであり、PDFBox はそこで IOException ではない例外を投げる（#144 / #150）。
        return guarded(ErrorCode.RENDERING_FAILED, () -> {
            PDRectangle box = document.delegate().getPage(pageNumber - 1).getCropBox();
            // ページが回転していても長辺の長さは変わらないため、回転角は考慮しなくてよい。
            float longEdge = Math.max(box.getWidth(), box.getHeight());
            if (longEdge <= 0f) {
                // ★ 自分で分類した失敗は塗り替えられない。wrapping が通す（PdfjigException）。
                throw new PdfjigException(ErrorCode.RENDERING_FAILED);
            }
            return renderScaled(document, pageNumber, maxEdgePixels / longEdge);
        });
    }

    @Override
    public BufferedImage render(PdfDocument document, int pageNumber, float dpi) {
        if (dpi <= 0f) {
            throw new IllegalArgumentException("dpi は正の値でなければなりません。");
        }
        PageRange.singlePage(pageNumber).validateAgainst(document.pageCount());

        return guarded(ErrorCode.RENDERING_FAILED, () -> renderScaled(document, pageNumber, dpi / POINTS_PER_INCH));
    }

    /**
     * 縮尺を指定して 1 ページを描く。
     *
     * <p><b>★★ ここは細工 PDF の全ページを通る。</b>サムネイル一覧は開いた文書のすべての
     * ページについてこれを呼ぶので、{@code docs/SECURITY.md}「対象範囲」が名指しする脅威が
     * <b>いちばん多く通る経路である</b>（#150）。
     *
     * <p><b>★★ それなのに、ここは長く検査の外に在った</b>（#178）。private は
     * 「公開の入口が PDFBox を直に呼ぶなら包みの中に置く」という規則の対象外であり、
     * <b>{@code catch} を {@code IOException} だけに戻しても規則は 0 件のまま緑だった</b>
     * （<b>2026-09-12 実測</b>）。<b>いまは自前の {@code catch} を持たない</b>
     * ——包むのは呼ぶ側であり、<b>{@code render} から {@code guarded} を外せば規則が赤くなる。</b>
     */
    private static BufferedImage renderScaled(PdfDocument document, int pageNumber, float scale) throws IOException {
        return new PDFRenderer(document.delegate()).renderImage(pageNumber - 1, scale, IMAGE_TYPE);
    }
}
