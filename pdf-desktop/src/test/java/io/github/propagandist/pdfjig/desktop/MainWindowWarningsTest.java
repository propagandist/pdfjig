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

        assertEquals(warnings, MainWindow.exceptWhatWasAsked(warnings, 0));
    }

    @Test
    @DisplayName("問うた数だけ落とす")
    void dropsAsManyAsWereAsked() {
        List<Warning> warnings = List.of(
                Warning.ENCRYPTION_NOT_PROPAGATED, Warning.ENCRYPTION_NOT_PROPAGATED, Warning.SIGNATURE_INVALIDATED);

        assertEquals(
                List.of(Warning.ENCRYPTION_NOT_PROPAGATED, Warning.SIGNATURE_INVALIDATED),
                MainWindow.exceptWhatWasAsked(warnings, 1));
    }

    @Test
    @DisplayName("★★ 窓に出ていない出どころのぶんは残る")
    void keepsNoticesForSourcesTheDialogNeverNamed() {
        // 鍵の要る文書と、オーナーパスワードだけの文書を 1 つずつ使うと、この形になる。
        // pdf-core は 2 件発し、窓で名前を出したのは 1 件である。
        List<Warning> warnings = List.of(Warning.ENCRYPTION_NOT_PROPAGATED, Warning.ENCRYPTION_NOT_PROPAGATED);

        assertEquals(List.of(Warning.ENCRYPTION_NOT_PROPAGATED), MainWindow.exceptWhatWasAsked(warnings, 1));
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

        assertEquals(List.of(), MainWindow.exceptWhatWasAsked(warnings, 3));
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
                MainWindow.exceptWhatWasAsked(warnings, 1));
    }
}
