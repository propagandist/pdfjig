package io.github.propagandist.pdfjig.core;

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

        PDRectangle box = document.delegate().getPage(pageNumber - 1).getCropBox();
        // ページが回転していても長辺の長さは変わらないため、回転角は考慮しなくてよい。
        float longEdge = Math.max(box.getWidth(), box.getHeight());
        if (longEdge <= 0f) {
            throw new PdfjigException(ErrorCode.RENDERING_FAILED);
        }
        return renderScaled(document, pageNumber, maxEdgePixels / longEdge);
    }

    @Override
    public BufferedImage render(PdfDocument document, int pageNumber, float dpi) {
        if (dpi <= 0f) {
            throw new IllegalArgumentException("dpi は正の値でなければなりません。");
        }
        PageRange.singlePage(pageNumber).validateAgainst(document.pageCount());

        return renderScaled(document, pageNumber, dpi / POINTS_PER_INCH);
    }

    private static BufferedImage renderScaled(PdfDocument document, int pageNumber, float scale) {
        try {
            return new PDFRenderer(document.delegate()).renderImage(pageNumber - 1, scale, IMAGE_TYPE);
        } catch (IOException e) {
            throw PdfjigException.wrapping(ErrorCode.RENDERING_FAILED, e);
        }
    }
}
