package io.github.propagandist.pdfjig.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.propagandist.pdfjig.core.Warning;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 書き出す前に同意を取ったぶんを、後からもう一度出さないところ（#29 / #192）。
 *
 * <p><b>★★ 全部落としてはならない。</b>{@code pdf-core} は<b>寄与する入力のうち
 * 暗号化されているものの数だけ</b>警告を発するが、<b>窓で名前を出して問うたのは
 * そのうち鍵を渡して開いたものだけ</b>である——<b>オーナーパスワードだけの文書は
 * 窓に出ていない。</b>あのぶんを落とすと、<b>保護が落ちたことを伝える口が 1 つも無くなる。</b>
 */
class MainWindowWarningsTest {

    @Test
    @DisplayName("問わなかったなら、1 件も落とさない")
    void keepsEverythingWhenNothingWasAsked() {
        List<Warning> warnings = List.of(Warning.ENCRYPTION_NOT_PROPAGATED, Warning.METADATA_FROM_FIRST_INPUT);

        assertEquals(warnings, MainWindow.exceptWhatWasAsked(warnings, Warning.ENCRYPTION_NOT_PROPAGATED, 0));
    }

    @Test
    @DisplayName("問うた数だけ落とす")
    void dropsAsManyAsWereAsked() {
        List<Warning> warnings = List.of(
                Warning.ENCRYPTION_NOT_PROPAGATED, Warning.ENCRYPTION_NOT_PROPAGATED, Warning.SIGNATURE_INVALIDATED);

        assertEquals(
                List.of(Warning.ENCRYPTION_NOT_PROPAGATED, Warning.SIGNATURE_INVALIDATED),
                MainWindow.exceptWhatWasAsked(warnings, Warning.ENCRYPTION_NOT_PROPAGATED, 1));
    }

    @Test
    @DisplayName("★★ 窓に出ていない出どころのぶんは残る")
    void keepsNoticesForSourcesTheDialogNeverNamed() {
        // 鍵の要る文書と、オーナーパスワードだけの文書を 1 つずつ使うと、この形になる。
        // pdf-core は 2 件発し、窓で名前を出したのは 1 件である。
        List<Warning> warnings = List.of(Warning.ENCRYPTION_NOT_PROPAGATED, Warning.ENCRYPTION_NOT_PROPAGATED);

        assertEquals(
                List.of(Warning.ENCRYPTION_NOT_PROPAGATED),
                MainWindow.exceptWhatWasAsked(warnings, Warning.ENCRYPTION_NOT_PROPAGATED, 1));
    }

    @Test
    @DisplayName("★★ 分割は、かたまりの数だけ発せられる")
    void dropsOncePerSegmentWhenSplitting() {
        // ★★ pdf-core は assembleEach でかたまりの数だけ warnAboutContributing を通すので、
        //   同じ出どころについて N 回発する（2026-09-14 実測）。問うたのは 1 回でも、
        //   落とす数はそこに合わせる——合わせないと、同意したことをもう一度伝える窓が続く。
        List<Warning> warnings = List.of(
                Warning.ENCRYPTION_NOT_PROPAGATED,
                Warning.ENCRYPTION_NOT_PROPAGATED,
                Warning.ENCRYPTION_NOT_PROPAGATED);

        assertEquals(List.of(), MainWindow.exceptWhatWasAsked(warnings, Warning.ENCRYPTION_NOT_PROPAGATED, 3));
    }

    @Test
    @DisplayName("★★ 問う窓が何を言ったかで、落とす値が変わる")
    void dropsWhicheverWarningTheDialogAlreadySaid() {
        // ★★ 落とす値をフィルタの側へ書き込むと、問う窓を 1 つ足した日に
        //   その分が黙って素通りする——同じ文を窓と警告で 2 度読ませるのは、
        //   読まずに閉じる習慣を作る側である（#30 の門の 1 段目）。
        List<Warning> warnings = List.of(Warning.CONTENT_OPENS_WITHOUT_A_KEY, Warning.ENCRYPTION_NOT_PROPAGATED);

        assertEquals(
                List.of(Warning.ENCRYPTION_NOT_PROPAGATED),
                MainWindow.exceptWhatWasAsked(warnings, Warning.CONTENT_OPENS_WITHOUT_A_KEY, 1),
                "窓で言ったのに、同じことをもう一度伝えている");
        assertEquals(
                List.of(Warning.CONTENT_OPENS_WITHOUT_A_KEY),
                MainWindow.exceptWhatWasAsked(
                        List.of(Warning.CONTENT_OPENS_WITHOUT_A_KEY), Warning.ENCRYPTION_NOT_PROPAGATED, 1),
                "窓で言っていない値まで落としている");
    }

    @Test
    @DisplayName("他の警告は落とさない")
    void keepsUnrelatedWarnings() {
        List<Warning> warnings = List.of(
                Warning.METADATA_FROM_FIRST_INPUT,
                Warning.ENCRYPTION_NOT_PROPAGATED,
                Warning.DANGLING_REFERENCES_REMOVED);

        assertEquals(
                List.of(Warning.METADATA_FROM_FIRST_INPUT, Warning.DANGLING_REFERENCES_REMOVED),
                MainWindow.exceptWhatWasAsked(warnings, Warning.ENCRYPTION_NOT_PROPAGATED, 1));
    }
}
