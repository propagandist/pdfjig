package io.github.propagandist.pdfjig.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.propagandist.pdfjig.core.ErrorCode;
import io.github.propagandist.pdfjig.core.PdfjigException;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 画面に出す文言。
 *
 * <p><b>窓は立てない。</b>見たいのは「出してよいものの線」であり、
 * {@link Messages#describe} はそのために分けてある。
 *
 * <p><b>★ 控えが残った経路そのものは、ここからも {@code DocumentWriterTest} からも作れない。</b>
 * 退避まで進んで入れ替えに失敗し、<b>巻き戻しにも失敗する</b>という重なりが要り、
 * <b>後ろの 2 つは同じ 2 つのパスの間の逆向きの改名である</b>——
 * 片方を塞ぐ条件はもう片方も塞ぐ。だから見るのは<b>「印が立ったまま失敗が外へ出たとき、
 * 何を言うか」</b>までで、そこへ至る条件は {@code OutputWorkspaceTest} が別に持つ。
 * <b>実際に起きたときの見え方は人が見る</b>（{@code docs/HANDOVER.md} 4-4 の 10 番）。
 */
class MessagesTest {

    /**
     * 控えが残ったなら、その場所を出す。
     *
     * <p><b>★★ これが #124 そのものである。</b>出力先には何も無く、元は作業場所の中にしか
     * 無いのに、<b>画面に出るのは汎用の「読み書きに失敗しました」だけだった</b>——
     * 利用者から見えるのは「ファイルが消えた」であり、
     * <b>{@code .pdfjig-*} は消してよいゴミにしか見えない。</b>
     */
    @Test
    @DisplayName("控えが残ったなら、その場所を出す")
    void tellsWhereTheOriginalIsKept(@TempDir Path directory) {
        try (OutputWorkspace workspace = OutputWorkspace.nextTo(directory.resolve("out.pdf"))) {
            workspace.holdOriginal();
            RuntimeException failure =
                    ReplacedFileKeptException.reporting(new PdfjigException(ErrorCode.IO_FAILURE), workspace);

            String message = Messages.describe(failure);

            assertTrue(
                    message.contains(workspace.replaced().toString()),
                    "元がどこに残っているのかを言っていない。利用者には「ファイルが消えた」としか見えない（#124）");
            assertTrue(message.contains(ErrorCode.IO_FAILURE.defaultMessage()), "場所を足すために、何が起きたのかを落としている");
        }
    }

    /**
     * 控えが残っていなければ、場所は出さない。
     *
     * <p><b>★ 巻き戻せていれば元は出力先にある。</b>そこで作業場所の名前を出すと、
     * <b>「消えていない」を「どこかへ行った」と読ませる</b>（{@code CLAUDE.md} 優先順位 2）。
     */
    @Test
    @DisplayName("控えが残っていなければ、場所は出さない")
    void saysNothingAboutAPlaceWhenNothingWasKept(@TempDir Path directory) {
        try (OutputWorkspace workspace = OutputWorkspace.nextTo(directory.resolve("out.pdf"))) {
            RuntimeException failure =
                    ReplacedFileKeptException.reporting(new PdfjigException(ErrorCode.IO_FAILURE), workspace);

            String message = Messages.describe(failure);

            assertEquals(ErrorCode.IO_FAILURE.defaultMessage(), message, "残っていない控えの在り処を伝えている");
        }
    }

    /**
     * pdfjig の失敗でなければ、定型文だけを出す。
     *
     * <p><b>★★ 例外のメッセージを画面へ出さない</b>（{@code CLAUDE.md} INV-5）。
     * 依存ライブラリの例外には入力値が埋め込まれていることがあり、
     * <b>そこにパスワードが混ざりうる。</b>
     */
    @Test
    @DisplayName("pdfjig の失敗でなければ、定型文だけを出す")
    void showsOnlyTheStockPhraseForAnUnknownFailure() {
        String message = Messages.describe(new IllegalStateException("password=ひみつ C:\\Users\\someone\\秘密.pdf"));

        assertEquals("操作に失敗しました。", message);
        assertFalse(message.contains("ひみつ"), "例外のメッセージを画面へ出している（INV-5）");
    }
}
