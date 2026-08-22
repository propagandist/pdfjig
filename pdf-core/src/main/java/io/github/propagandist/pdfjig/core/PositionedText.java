package io.github.propagandist.pdfjig.core;

/**
 * 座標を伴う 1 グリフ分のテキスト。
 *
 * <p><b>座標系はページの左上を原点とし、右方向を +x、下方向を +y、単位は pt（1/72 インチ）である。</b>
 * PDF 本来の座標系は左下原点だが、画面表示と添字の向きが一致するこちらを採用する。
 * ページの回転が指定されている場合、その回転を適用した後の見た目の座標を返す。
 *
 * @param text     文字。合字は PDFBox が展開した後の Unicode 表現
 * @param x        左端の x 座標
 * @param y        上端の y 座標
 * @param width    見た目の幅
 * @param height   見た目の高さ
 * @param fontSize フォントサイズ（pt）
 */
public record PositionedText(String text, float x, float y, float width, float height, float fontSize) {

    public PositionedText {
        if (text == null) {
            throw new IllegalArgumentException("text は null にできません。");
        }
    }
}
