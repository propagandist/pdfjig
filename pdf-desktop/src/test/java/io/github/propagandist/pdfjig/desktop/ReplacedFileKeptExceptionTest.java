package io.github.propagandist.pdfjig.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.github.propagandist.pdfjig.core.ErrorCode;
import io.github.propagandist.pdfjig.core.PdfjigException;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 控えが残ったことを、失敗に載せるかどうかの関門。
 *
 * <p><b>作業場所は本物を使う。</b>印の名前も控えの名前も {@link OutputWorkspace} の
 * private な決めごとであり、<b>ここで組み直すと、あちらが形を変えても気づかないまま緑になる</b>
 * （{@code DocumentWriterTest} と同じ判断）。
 */
class ReplacedFileKeptExceptionTest {

    /**
     * 抱えたままなら、控えの在り処を載せる。
     *
     * <p><b>★ 原因はそのまま連ねる。</b>何が起きたのかを持っているのは原因の側であり、
     * <b>落とすと「場所は分かるが理由が分からない」失敗になる。</b>
     */
    @Test
    @DisplayName("抱えたままなら、控えの在り処を載せる")
    void carriesThePlaceWhileTheOriginalIsStillHeld(@TempDir Path directory) {
        try (OutputWorkspace workspace = OutputWorkspace.nextTo(directory.resolve("out.pdf"))) {
            workspace.holdOriginal();
            PdfjigException failed = new PdfjigException(ErrorCode.IO_FAILURE);

            RuntimeException reported = ReplacedFileKeptException.reporting(failed, workspace);

            ReplacedFileKeptException kept = assertInstanceOf(ReplacedFileKeptException.class, reported);
            assertEquals(workspace.replaced(), kept.kept(), "控えの在り処が退避先と違う");
            assertSame(failed, kept.getCause(), "何が起きたのかを落としている");
        }
    }

    /**
     * 抱えていなければ、包まずにそのまま返す。
     *
     * <p><b>★★ 見るのは印だけである</b>（{@code OutputWorkspace#stillHoldsOriginal}）。
     * 失敗の種類で分けると、<b>次に増えた失敗の仕方が黙って「場所を言わない」側へ落ちる</b>
     * ——ここは同じ {@code IO_FAILURE} で、印だけが違う。
     */
    @Test
    @DisplayName("抱えていなければ、包まずにそのまま返す")
    void passesTheFailureThroughWhenNothingIsHeld(@TempDir Path directory) {
        try (OutputWorkspace workspace = OutputWorkspace.nextTo(directory.resolve("out.pdf"))) {
            PdfjigException failed = new PdfjigException(ErrorCode.IO_FAILURE);

            assertSame(failed, ReplacedFileKeptException.reporting(failed, workspace), "残っていない控えの在り処を載せている");
        }
    }

    /**
     * メッセージには場所を入れない。
     *
     * <p><b>★★ {@link Logs} は例外のメッセージを読まないが、捕まらなかった例外は
     * {@code printStackTrace} も通る</b>（{@link Logs#start}）。<b>文書のパスを
     * 残り続ける記録へ入れないという線は、そこでも守られていなければならない</b>
     * （{@code docs/SPEC.md} §10.4）。<b>取り出す口は {@code kept()} だけである。</b>
     */
    @Test
    @DisplayName("メッセージには場所を入れない")
    void keepsThePlaceOutOfTheMessage(@TempDir Path directory) {
        try (OutputWorkspace workspace = OutputWorkspace.nextTo(directory.resolve("out.pdf"))) {
            workspace.holdOriginal();

            RuntimeException reported =
                    ReplacedFileKeptException.reporting(new PdfjigException(ErrorCode.IO_FAILURE), workspace);

            assertFalse(
                    reported.getMessage()
                            .contains(workspace
                                    .replaced()
                                    .getParent()
                                    .getFileName()
                                    .toString()),
                    "例外のメッセージに作業場所の名前が入っている。printStackTrace を通れば、そのまま残り続ける記録になる（SPEC §10.4）");
        }
    }
}
