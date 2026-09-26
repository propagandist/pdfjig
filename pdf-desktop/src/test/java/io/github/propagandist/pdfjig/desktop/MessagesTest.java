package io.github.propagandist.pdfjig.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.propagandist.pdfjig.core.ErrorCode;
import io.github.propagandist.pdfjig.core.PdfjigException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
        try (OutputWorkspace workspace = OutputWorkspace.nextTo(directory.resolve("out.pdf"), found -> {})) {
            workspace.holdOriginal();
            Files.writeString(workspace.replaced(), "元のファイル");
            RuntimeException failure = workspace
                    .failing(new PdfjigException(ErrorCode.IO_FAILURE), null)
                    .orElseThrow();

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
     * 前の書き出しが残した控えを、全部並べて出す（#138）。
     *
     * <p><b>丸ごと一致で見る。</b>{@code describe} の回と同じ理由である。
     * <b>「元の名前を付け直して」と言い切らない</b>のは、落ちたのが置き換えの前か後かが分からないからで、
     * <b>後なら元の名前には新しいほうが既にある。</b>
     * <b>「要らなければ消してよい」とも言わない</b>——共有フォルダなら、別の利用者の唯一の控えでありうる。
     */
    @Test
    @DisplayName("前の書き出しが残した控えを、全部並べて出す")
    void listsEveryCopyAPreviousWriteLeftBehind(@TempDir Path directory) {
        Path first = directory.resolve(".pdfjig-1").resolve("replaced.pdf");
        Path second = directory.resolve(".pdfjig-2").resolve("replaced.pdf");

        assertEquals(
                "前の保存が途中で終わったときの、保存する前のファイルが次の場所に残っています。\n\n"
                        + first + "\n" + second
                        + "\n\nどのファイルのものかは、開いて中身で確かめてください。"
                        + "元の名前のファイルが既にあるなら、それは保存が済んだ新しいほうかもしれません。"
                        + "上書きする前に中身を比べてください。"
                        + "\n\n自分のものだと確かめて、要るものを取り出したら、そのフォルダは消してかまいません。"
                        + "自分のものでなければ、消さずにおいてください。",
                Messages.describeAbandoned(List.of(first, second)).text());
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
        try (OutputWorkspace workspace = OutputWorkspace.nextTo(directory.resolve("out.pdf"), found -> {})) {
            workspace.holdOriginal();
            Files.writeString(workspace.replaced(), "元のファイル");
            RuntimeException failure = workspace
                    .failing(new IllegalStateException("password=ひみつ C:\\Users\\someone\\秘密.pdf"), null)
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

    /** 消し損ねた平文があれば、在ることと場所を言う（#184）。利用者は平文は書かれていないと信じている。 */
    @Test
    void saysWhereThePlaintextWasLeft() {
        Path kept = Path.of("C:", "work", ".pdfjig-1", "replaced.pdf");
        Path plaintext = OutputWorkspace.writtenBeside(kept);

        String message = Messages.describe(
                new ReplacedFileKeptException(kept, plaintext, new PdfjigException(ErrorCode.IO_FAILURE)));

        assertTrue(message.contains(plaintext.toString()), "消し損ねた平文の場所を言っていない: " + message);
        assertTrue(message.contains("保護されていない"), "何が残っているのかを言っていない: " + message);
    }

    /** 元を抱えていない失敗でも、消し損ねた平文の在り処を言う（#184 の門）。 */
    @Test
    void saysWhereThePlaintextWasLeftWithoutAKeptOriginal() {
        Path plaintext = Path.of("C:", "work", ".pdfjig-1", "output.pdf");

        String message =
                Messages.describe(new PlaintextLeftException(plaintext, new PdfjigException(ErrorCode.IO_FAILURE)));

        assertTrue(message.contains(plaintext.toString()), "消し損ねた平文の場所を言っていない: " + message);
        assertTrue(message.contains("保護されていない"), "何が残っているのかを言っていない: " + message);
    }

    /**
     * 写せる在り処は、窓に出した在り処と同じものを、同じ順に並べる（#137）。
     *
     * <p><b>★★ 片方だけ直すと、読んでいないパスを貼らせる</b>——あるいは、出したのに写せない。
     * <b>本文の中の位置で順を見る</b>ので、並べ替えても落ちる。
     */
    @Test
    void copiesTheSameLocationsItShowsInTheSameOrder() {
        Path kept = Path.of("C:", "work", ".pdfjig-1", "replaced.pdf");
        Path plaintext = OutputWorkspace.writtenBeside(kept);
        List<RuntimeException> failures = List.of(
                new ReplacedFileKeptException(kept, plaintext, new PdfjigException(ErrorCode.IO_FAILURE)),
                new ReplacedFileKeptException(kept, null, new PdfjigException(ErrorCode.IO_FAILURE)),
                new PlaintextLeftException(plaintext, new PdfjigException(ErrorCode.IO_FAILURE)));
        List<List<Path>> expected = List.of(List.of(kept, plaintext), List.of(kept), List.of(plaintext));

        for (int i = 0; i < failures.size(); i++) {
            Messages.Notice notice = Messages.notice(failures.get(i));
            List<Path> locations = notice.locations();
            String message = notice.text();

            assertEquals(expected.get(i), locations, "写せる在り処が違う: " + message);
            int previous = -1;
            for (Path location : locations) {
                int at = message.indexOf(location.toString(), previous + 1);
                assertTrue(at > previous, "窓に出ていない在り処、または出した順と違う: " + location + " / " + message);
                previous = at;
            }
        }
    }

    /**
     * 写すのは在り処の入ったフォルダで、出した順に、重ねずに並べる（#137）。
     *
     * <p><b>★★ ファイルそのものを写すと、アドレス欄へ貼ったときにファイルが開く</b>——
     * 消し損ねた平文なら、消せと言ったものを開かせる。<b>控えと平文は同じ作業場所にあるので、
     * その窓で写すものは 1 つになる。</b>
     */
    @Test
    void copiesTheFoldersNotTheFiles() {
        Path kept = Path.of("C:", "work", ".pdfjig-1", "replaced.pdf");
        Path plaintext = OutputWorkspace.writtenBeside(kept);
        Path first = Path.of("C:", "work", ".pdfjig-2", "replaced.pdf");
        Path second = Path.of("C:", "work", ".pdfjig-3", "replaced.pdf");

        assertEquals(
                List.of(kept.getParent()),
                Messages.notice(new ReplacedFileKeptException(
                                kept, plaintext, new PdfjigException(ErrorCode.IO_FAILURE)))
                        .folders());
        assertEquals(
                List.of(first.getParent(), second.getParent()),
                Messages.describeAbandoned(List.of(first, second)).folders());
    }

    /** ふつうの失敗には、写すものも無い。ボタンが出ないのはこのためである（#137）。 */
    @Test
    void copiesNothingFromAnOrdinaryFailure() {
        assertEquals(
                List.of(),
                Messages.notice(new PdfjigException(ErrorCode.IO_FAILURE)).locations());
        assertEquals(List.of(), Messages.notice(null).locations());
    }
}
