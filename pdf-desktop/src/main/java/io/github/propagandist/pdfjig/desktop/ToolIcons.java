package io.github.propagandist.pdfjig.desktop;

import javafx.scene.Node;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.SVGPath;

/**
 * ツールバーのアイコン。
 *
 * <p>形は SVG のパスとして持ち、JavaFX 標準の {@link SVGPath} で描く。
 * 画像を同梱すると表示倍率ごとに用意が要り、アイコンフォントを使うと依存が増える。
 * 線だけで描けるものに限れば、この形が一番軽い。
 *
 * <p>色と太さは CSS の {@code .tool-icon} で決める。塗りは使わず線だけで描くため、
 * {@code -fx-fill} は透明にしてある。無効なボタンは JavaFX が全体を薄くするので、
 * アイコン側で無効時の色を持つ必要はない。
 *
 * <p>座標系は 24 × 24。この寸法をそのまま画面上の大きさとして使う。
 */
final class ToolIcons {

    /** アイコンを収める枠の一辺。パスの座標系と同じ 24。 */
    private static final double SIZE = 24;

    /** 開く。フォルダ。 */
    static final String OPEN =
            "M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z";

    /** 保存。フロッピー。 */
    static final String SAVE =
            "M19 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11l5 5v11a2 2 0 0 1-2 2z"
                    + "M17 21v-8H7v8M7 3v5h8";

    /** 削除。ごみ箱。 */
    static final String DELETE =
            "M3 6h18M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6"
                    + "m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2";

    /** 左に回転。反時計回りの矢印。 */
    static final String ROTATE_LEFT =
            "M1 4v6h6M3.51 15a9 9 0 1 0 2.13-9.36L1 10";

    /** 右に回転。時計回りの矢印。 */
    static final String ROTATE_RIGHT =
            "M23 4v6h-6M20.49 15a9 9 0 1 1-2.12-9.36L23 10";

    /** PDF を追加。紙にプラス。 */
    static final String ADD =
            "M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"
                    + "M14 2v6h6M12 18v-6M9 15h6";

    /** ファイル一覧から外す。× 印。 */
    static final String REMOVE = "M7 7l10 10M17 7l-10 10";

    /** ここで区切る。2 枚の紙の間に破線。 */
    static final String BREAK =
            "M4 4h5v16H4zM15 4h5v16h-5zM12 3v3M12 10.5v3M12 18v3";

    /** 分割。切り離された 2 枚。 */
    static final String SPLIT =
            "M4 4h6v16H4zM14 4h6v16h-6z";

    /** 範囲を指定して残す。左右の括弧。 */
    static final String RANGE =
            "M9 4H6a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h3M15 4h3a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2h-3";

    /** 並びと向きを元に戻す。折り返す矢印。 */
    static final String RESET =
            "M9 14L4 9l5-5M20 20v-7a4 4 0 0 0-4-4H4";

    private ToolIcons() {
    }

    /**
     * アイコンを 1 つ作る。
     *
     * <p>同じ大きさの枠に入れて中央に置く。パスごとに外接矩形が違うため、そのまま置くと
     * ボタンごとに図の高さが変わり、下に付く文字の位置が揃わない。
     *
     * @param content SVG のパス
     * @return 画面に置ける節点
     */
    static Node of(String content) {
        SVGPath path = new SVGPath();
        path.setContent(content);
        path.getStyleClass().add("tool-icon");

        StackPane box = new StackPane(path);
        box.setMinSize(SIZE, SIZE);
        box.setPrefSize(SIZE, SIZE);
        box.setMaxSize(SIZE, SIZE);
        return box;
    }
}
