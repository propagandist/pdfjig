package io.github.propagandist.pdfjig.core;

/**
 * 呼ぶ側のコードが投げた失敗を、{@code pdf-core} の包みを素通しさせるための包み。
 *
 * <p><b>★★ 型では区別が付かないので、印を付ける。</b>{@code WarningListener} は
 * {@code pdf-core} の中から呼ばれる——<b>そこで走るのは呼ぶ側のコードである。</b>
 * 包みの中でそれが投げると、<b>呼ぶ側の失敗が「ファイルの読み書きに失敗しました」に化ける</b>
 * ——利用者は入力のせいだと読む（{@code CLAUDE.md} 優先順位 2）。
 *
 * <p><b>PDFBox が投げたのか、呼ぶ側が投げたのかは、捕まえた側からは分からない。</b>
 * <b>分からないなら、分かる側が印を付ける</b>——{@link PdfjigException#wrapping} を
 * 冪等にしたのと同じ発想である（#150 / #178）。
 *
 * <p><b>{@code pdf-core} の外へは出ない。</b>包みが剥がして、中身をそのまま投げ直す。
 */
final class CallerFailure extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient RuntimeException carried;

    CallerFailure(RuntimeException carried) {
        super(null, null, false, false);
        this.carried = carried;
    }

    /** 中身。<b>包みはこれをそのまま投げ直す。</b> */
    RuntimeException carried() {
        return carried;
    }
}
