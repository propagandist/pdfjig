package io.github.propagandist.pdfjig.core;

import java.nio.file.Path;
import java.util.List;

/**
 * 複数の入力。
 *
 * <p><b>★★ なぜ {@code List<Source>} を直に受け取らないか。</b>
 * {@code assemble(List<Path>, …)} と {@code assemble(List<Source>, …)} は
 * <b>消去後に同じ綴りになり、多重定義として置けない</b>（{@code List<Path>} も
 * {@code List<Source>} も {@code List} になる）。<b>値型で包めば衝突しない</b>ので、
 * <b>既存の 9 本を 1 つも変えずに鍵の要る経路を足せる</b>——
 * <b>平文しか扱わない呼ぶ側は 1 行も変わらない</b>（#193）。
 *
 * <p>鍵の持ち主と生存期間は {@link Source} が持つ。
 *
 * @param all 入力。この順に扱われ、{@link PageSelection#sourceIndex()} がこの並びを指す
 */
public record Sources(List<Source> all) {

    public Sources {
        if (all == null || all.isEmpty()) {
            throw new PdfjigException(ErrorCode.NO_INPUT);
        }
        // ★ null の要素は List.copyOf が弾く。重ねて contains(null) を書かないこと
        //   ——不変リストはあれ自体が NullPointerException を投げる（2026-09-13 実測）。
        all = List.copyOf(all);
    }

    /**
     * 並べて作る。
     *
     * @param sources 入力
     * @return 入力の並び
     */
    public static Sources of(Source... sources) {
        return new Sources(List.of(sources));
    }

    /**
     * 鍵の要らない入力だけの並び。
     *
     * @param paths 入力ファイル
     * @return 入力の並び
     */
    public static Sources ofPaths(List<Path> paths) {
        // ★ 空は下の正準コンストラクタが同じ符号で弾く。重ねない
        //   ——ここで検めるのは null だけである（stream() が NullPointerException を投げる）。
        if (paths == null) {
            throw new PdfjigException(ErrorCode.NO_INPUT);
        }
        return new Sources(paths.stream().map(Source::of).toList());
    }

    /**
     * 入力の数。
     *
     * @return 数
     */
    public int size() {
        return all.size();
    }

    /**
     * その位置の入力。
     *
     * @param index 位置
     * @return 入力
     */
    public Source get(int index) {
        return all.get(index);
    }
}
