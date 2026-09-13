package io.github.propagandist.pdfjig.core;

import java.nio.file.Path;

/**
 * 入力と、それを開くための鍵。
 *
 * <p><b>★★ 鍵は読むだけである。</b>{@link Password} の持ち主は<b>作った場所</b>であり、
 * ここでは消さない（{@code CLAUDE.md} INV-5、{@link Password}）。
 *
 * <p><b>★★ 書き出しが終わるまで、枠が生きていなければならない。</b>
 * {@code PageOperations} は<b>書き出しの都合で同じ入力を何度も開き直す</b>
 * （{@code split} は出力ごと、複数の入力を混ぜる経路は複製ごと）。
 * <b>その間ずっとこの鍵を読む</b>ので、<b>先に閉じるとゼロ埋めされた配列で開こうとする。</b>
 * <b>枠より長く生きる仕事へ渡すときは、枠ごと渡すこと</b>（{@code BackgroundTasks#run}）。
 *
 * <p><b>★ 鍵を引く手（{@code Function<Path, Password>}）にしなかった。</b>
 * 開き直すたびに呼ぶ側のコードを走らせることになり、<b>それは包みの中で起きる</b>
 * ——#178 で塞いだ穴を、自分で開ける形である。<b>値なら包みの中で走るものが無い。</b>
 *
 * @param path     入力ファイル
 * @param password 開くための鍵。要らないなら {@code null}
 */
public record Source(Path path, Password password) {

    public Source {
        if (path == null) {
            throw new IllegalArgumentException("path は null にできません。");
        }
    }

    /**
     * 鍵の要らない入力。
     *
     * @param path 入力ファイル
     * @return 入力
     */
    public static Source of(Path path) {
        return new Source(path, null);
    }

    /**
     * 鍵の要る入力。
     *
     * @param path     入力ファイル
     * @param password 開くための鍵。<b>ここでは消さない</b>
     * @return 入力
     */
    public static Source of(Path path, Password password) {
        if (password == null) {
            throw new IllegalArgumentException("password は null にできません。鍵が無いなら of(Path) を使うこと。");
        }
        return new Source(path, password);
    }
}
