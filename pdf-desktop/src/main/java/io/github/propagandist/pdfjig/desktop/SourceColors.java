package io.github.propagandist.pdfjig.desktop;

/**
 * 出どころを見分けるための色。
 *
 * <p>複数のファイルを混ぜて並べ替えると、ページ番号だけではどれがどのファイルのものか
 * 分からなくなる。ツールチップは 1 枚ずつ確かめるしかないので、一目で分かる手がかりを別に置く。
 *
 * <p>選択の青（{@code #2f6fd0}）とドロップ先の琥珀（{@code #e8a33d}）は避けてある。
 * 状態を示す色と出どころを示す色が似ていると、どちらの意味か取り違える。
 */
final class SourceColors {

    /**
     * 出どころの色。彩度を抑えつつ色相を離してある。
     *
     * <p>ファイルが 8 を超えたら先頭から巡る。それだけ混ぜる場面では色で見分けるのは
     * どのみち無理があり、ツールチップとファイル一覧で確かめてもらう。
     */
    private static final String[] PALETTE = {
        "#2e7d6b",
        "#c2543d",
        "#4a5fbf",
        "#8a5fa8",
        "#b08a2e",
        "#3d7fa8",
        "#a8437a",
        "#5f7a3d",
    };

    private SourceColors() {
    }

    /**
     * その出どころに割り当てる色。
     *
     * @param sourceIndex 出どころ番号（0 始まり）
     * @return CSS で使える色
     */
    static String of(int sourceIndex) {
        return PALETTE[Math.floorMod(sourceIndex, PALETTE.length)];
    }
}
