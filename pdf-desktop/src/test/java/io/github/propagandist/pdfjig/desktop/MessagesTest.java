package io.github.propagandist.pdfjig.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.propagandist.pdfjig.core.ErrorCode;
import io.github.propagandist.pdfjig.core.PdfjigException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 画面に出す文言。
 *
 * <p><b>窓は立てない。</b>見たいのは「出してよいものの線」であり、
 * {@link Messages#describe} はそのために切り出してある。
 *
 * <p>ここが見るのは<b>文言の組み立てだけ</b>である。控えが残った状態を実際に作って端から端まで
 * 通すのは {@code KeptCopyReportTest}（windows でだけ走る）が持つ。
 */
class MessagesTest {

    /**
     * 控えが残ったなら、その場所を出す。
     *
     * <p><b>★★ これが #124 そのものである。</b>出力先には何も無く、元は作業場所の中にしか
     * 無いのに、<b>画面に出るのは汎用の「読み書きに失敗しました」だけだった</b>——
     * 利用者から見えるのは「ファイルが消えた」である。
     */
    @Test
    @DisplayName("控えが残ったなら、その場所を出す")
    void tellsWhereTheOriginalIsKept(@TempDir Path directory) throws IOException {
        try (OutputWorkspace workspace = OutputWorkspace.nextTo(directory.resolve("out.pdf"))) {
            workspace.holdOriginal();
            Files.writeString(workspace.replaced(), "元のファイル");
            RuntimeException failure =
                    workspace.failing(new PdfjigException(ErrorCode.IO_FAILURE)).orElseThrow();

            String message = Messages.describe(failure);

            // ★ 丸ごと一致で見る。contains だけにすると、原因の getMessage を継ぎ足す変更が
            //   素通りする——依存ライブラリの例外にはパスもパスワードも混ざりうる（INV-5）。
            assertEquals(
                    ErrorCode.IO_FAILURE.defaultMessage() + "\n\n元のファイルは次の場所に残っています。\n"
                            + workspace.replaced()
                            + "\n\n取り出して、元の名前を付け直してください。そのあと、このフォルダは消してかまいません。",
                    message,
                    "元がどこに残っているのかを言っていない。利用者には「ファイルが消えた」としか見えない（#124）");
        }
    }

    /**
     * pdfjig の失敗でない原因を包んでも、その中身は出さない。
     *
     * <p><b>★★ 包む相手は {@code PdfjigException} とは限らない。</b>
     * {@code assemble} は {@code RuntimeException} と {@code Error} を丸ごと受けるので、
     * <b>依存ライブラリの例外がそのまま原因になりうる</b>——そこに入力値が混ざる（INV-5）。
     */
    @Test
    @DisplayName("pdfjig の失敗でない原因を包んでも、その中身は出さない")
    void keepsAForeignCauseOutOfTheKeptMessage(@TempDir Path directory) throws IOException {
        try (OutputWorkspace workspace = OutputWorkspace.nextTo(directory.resolve("out.pdf"))) {
            workspace.holdOriginal();
            Files.writeString(workspace.replaced(), "元のファイル");
            RuntimeException failure = workspace
                    .failing(new IllegalStateException("password=ひみつ C:\\Users\\someone\\秘密.pdf"))
                    .orElseThrow();

            String message = Messages.describe(failure);

            assertTrue(message.startsWith("操作に失敗しました。"), "定型文に落ちていない");
            assertFalse(message.contains("ひみつ"), "例外のメッセージを画面へ出している（INV-5）");
        }
    }

    /**
     * ふつうの失敗には、場所を足さない。
     *
     * <p><b>★ 巻き戻せていれば元は出力先にある。</b>そこで作業場所の名前を出すと、
     * <b>「消えていない」を「どこかへ行った」と読ませる</b>（{@code CLAUDE.md} 優先順位 2）。
     * <b>包まれていない失敗がここへ来るのがその場合である</b>
     * （{@code OutputWorkspaceTest} が包まない条件を持つ）。
     */
    @Test
    @DisplayName("ふつうの失敗には、場所を足さない")
    void addsNoPlaceToAnOrdinaryFailure() {
        assertEquals(
                ErrorCode.IO_FAILURE.defaultMessage(),
                Messages.describe(new PdfjigException(ErrorCode.IO_FAILURE)),
                "残っていない控えの在り処を伝えている");
    }

    /**
     * pdfjig の失敗でなければ、定型文だけを出す。
     *
     * <p><b>★★ 例外のメッセージを画面へ出さない</b>（{@code CLAUDE.md} INV-5）。
     * 依存ライブラリの例外には入力値が埋め込まれていることがあり、
     * <b>そこにパスワードが混ざりうる。</b><b>丸ごと一致で見る</b>ので、
     * 混ざれば何であれ落ちる。
     */
    @Test
    @DisplayName("pdfjig の失敗でなければ、定型文だけを出す")
    void showsOnlyTheStockPhraseForAnUnknownFailure() {
        String message = Messages.describe(new IllegalStateException("password=ひみつ C:\\Users\\someone\\秘密.pdf"));

        assertEquals("操作に失敗しました。", message, "例外のメッセージを画面へ出している（INV-5）");
    }
}
