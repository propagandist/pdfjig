package io.github.propagandist.pdfjig.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

/**
 * PDFBox による {@link TextExtraction} の実装。
 *
 * <p>状態を持たない。複数スレッドから同時に呼び出してよい
 * （{@link PDFTextStripper} は呼び出しごとに生成する）。
 *
 * <p><b>抽出の既定:</b>
 * <ul>
 *   <li>座標順に並べ替える（{@code setSortByPosition(true)}）。多段組の文書で
 *       コンテンツストリーム順のまま出すと読み順が崩れ、下流の処理に影響するため</li>
 *   <li>改行は {@code \n} に固定する。プラットフォーム既定に従うと同じ入力から
 *       異なる出力が出る</li>
 * </ul>
 */
public final class PdfBoxTextExtraction implements TextExtraction {

    private static final String LINE_SEPARATOR = "\n";

    @Override
    public String extractAll(PdfDocument document) {
        PDFTextStripper stripper = newStripper();
        try {
            return stripper.getText(document.delegate());
        } catch (IOException e) {
            throw PdfjigException.wrapping(ErrorCode.TEXT_EXTRACTION_FAILED, e);
        }
    }

    @Override
    public List<PageText> extractByPage(PdfDocument document) {
        int pageCount = document.pageCount();
        PDFTextStripper stripper = newStripper();
        List<PageText> pages = new ArrayList<>(pageCount);
        try {
            for (int pageNumber = 1; pageNumber <= pageCount; pageNumber++) {
                stripper.setStartPage(pageNumber);
                stripper.setEndPage(pageNumber);
                pages.add(new PageText(pageNumber, stripper.getText(document.delegate())));
            }
        } catch (IOException e) {
            throw PdfjigException.wrapping(ErrorCode.TEXT_EXTRACTION_FAILED, e);
        }
        return List.copyOf(pages);
    }

    @Override
    public List<PositionedText> extractWithPositions(PdfDocument document, int pageNumber) {
        PageRange.singlePage(pageNumber).validateAgainst(document.pageCount());

        PositionCollector collector = configure(new PositionCollector());
        collector.setStartPage(pageNumber);
        collector.setEndPage(pageNumber);
        try {
            collector.getText(document.delegate());
        } catch (IOException e) {
            throw PdfjigException.wrapping(ErrorCode.TEXT_EXTRACTION_FAILED, e);
        }
        return List.copyOf(collector.collected);
    }

    private static PDFTextStripper newStripper() {
        return configure(new PDFTextStripper());
    }

    private static <T extends PDFTextStripper> T configure(T stripper) {
        stripper.setSortByPosition(true);
        stripper.setLineSeparator(LINE_SEPARATOR);
        stripper.setPageEnd(LINE_SEPARATOR);
        return stripper;
    }

    /**
     * グリフごとの座標を集める。
     *
     * <p>{@link PDFTextStripper#writeString(String, List)} の第 1 引数は正規化済みの文字列であり、
     * 第 2 引数の要素と 1 対 1 に対応するとは限らない。座標を得るには
     * {@link TextPosition} 側の Unicode を使う必要がある。
     */
    private static final class PositionCollector extends PDFTextStripper {

        private final List<PositionedText> collected = new ArrayList<>();

        @Override
        protected void writeString(String text, List<TextPosition> textPositions) {
            for (TextPosition position : textPositions) {
                String unicode = position.getUnicode();
                if (unicode == null || unicode.isEmpty()) {
                    // 対応する Unicode を持たないグリフ。座標だけあっても意味を持たない。
                    continue;
                }
                float height = position.getHeightDir();
                collected.add(new PositionedText(
                        unicode,
                        position.getXDirAdj(),
                        // getYDirAdj はグリフの下端を返す。矩形の原点を左上に揃えるため高さを引く。
                        position.getYDirAdj() - height,
                        position.getWidthDirAdj(),
                        height,
                        position.getFontSizeInPt()));
            }
        }
    }
}
