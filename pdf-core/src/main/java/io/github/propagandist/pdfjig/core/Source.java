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
 * <p><b>★ 枠は 1 本とは限らない。</b>{@link Sources} は入力ごとに違う鍵を運ぶので、
 * <b>{@code BackgroundTasks#run(Password, …)} のように 1 本を預ける形では足りない</b>
 * ——<b>N 本すべてが、書き出しが終わるまで開いていなければならない。</b>
 * <b>閉じた鍵で開こうとすると {@link Password#value()} が投げる</b>ので、
 * <b>誤りに名前が付く</b>（#193。符号は変わらないが、原因の型が変わる）。
 *
 * @param path     入力ファイル
 * @param password 開くための鍵。<b>{@code null} は「鍵が要らない」を意味する</b>
 *                 ——{@link #of(Path)} が作るのがその形である
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
        // ★ ここだけ null を拒む。正準コンストラクタは「鍵が要らない」を表すために通す
        //   ——2 引数で呼びながら null を渡すのは、鍵を取り違えている形である。
        if (password == null) {
            throw new IllegalArgumentException("password は null にできません。鍵が無いなら of(Path) を使うこと。");
        }
        return new Source(path, password);
    }
}
