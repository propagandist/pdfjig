package io.github.propagandist.pdfjig.core;

/**
 * ページの回転角。PDF が許す 90 度単位の 4 値のみを表現する。
 */
public enum Rotation {
    NONE(0),
    CLOCKWISE_90(90),
    HALF_TURN(180),
    COUNTERCLOCKWISE_90(270);

    private final int degrees;

    Rotation(int degrees) {
        this.degrees = degrees;
    }

    public int degrees() {
        return degrees;
    }

    /**
     * 角度から回転を得る。負値および 360 を超える値は正規化する。
     *
     * @param degrees 90 の倍数
     * @return 対応する回転
     * @throws IllegalArgumentException 90 の倍数でない場合
     */
    public static Rotation ofDegrees(int degrees) {
        if (degrees % 90 != 0) {
            throw new IllegalArgumentException("回転角は 90 度単位で指定してください。");
        }
        int normalized = Math.floorMod(degrees, 360);
        return switch (normalized) {
            case 0 -> NONE;
            case 90 -> CLOCKWISE_90;
            case 180 -> HALF_TURN;
            case 270 -> COUNTERCLOCKWISE_90;
            default -> throw new IllegalStateException("到達しない");
        };
    }

    /** この回転にさらに {@code other} を加えた結果を返す。 */
    public Rotation plus(Rotation other) {
        return ofDegrees(this.degrees + other.degrees);
    }
}
