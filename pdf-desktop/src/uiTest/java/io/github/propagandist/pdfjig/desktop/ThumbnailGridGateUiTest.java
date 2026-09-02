package io.github.propagandist.pdfjig.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.propagandist.pdfjig.core.TestPdfs;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.event.Event;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.api.FxToolkit;
import org.testfx.util.WaitForAsyncUtils;

/**
 * サムネイル一覧の側にある 2 つの入口（#114）。
 *
 * <p><b>どちらも {@link Action} を通らない。</b>DELETE キーは {@code handleKey} が
 * 処理を直に呼び、ドラッグの落とし先は {@code ThumbnailGrid#move} を直に呼ぶ——
 * <b>無効になっているはずのメニュー項目を、どちらも見ていなかった。</b>
 *
 * <p><b>★★ ドラッグをここで見るのは、robot で駆動できないからである。</b>
 * TestFX でドラッグすると windows ランナーで 1 本 19 分かかり、CI から外すことになる
 * （{@link ReorderUiTest}）。<b>CI で守られない場所へ、新しく入れた門を置かない。</b>
 * 落とし先が呼ぶ道は {@code move} 1 本なので、そこを縛れば同じことを見たことになる。
 *
 * <p><b>画面は出さない。</b>{@link ThumbnailSourceUiTest} と同じ流儀で Toolkit だけを起こす
 * ——<b>TestFX の robot を使わないので、開発機のマウスとキーボードを取り上げない。</b>
 * 一覧のセルも要らない：見たいのは並びが動くかどうかだけである。
 *
 * <p><b>★ 放すところまで見る。</b>止めたままにする形でも「効かない」側は緑になるので、
 * <b>それだけでは「二度と効かない」を通してしまう。</b>
 */
class ThumbnailGridGateUiTest {

    /** 何かが起きるのを待つ上限。CI のランナーは遅いので短くしない。 */
    private static final long TIMEOUT_MILLIS = 20_000L;

    private final BooleanProperty blocked = new SimpleBooleanProperty(false);

    @BeforeAll
    static void startToolkit() throws Exception {
        // 画面は出さない。JavaFX Toolkit を起こすためだけに呼ぶ。
        FxToolkit.registerPrimaryStage();
    }

    /** 止めている間は、ドラッグの落とし先が並びを変えない。 */
    @Test
    void 止めている間はドラッグで並べ替わらない(@TempDir Path directory) throws Exception {
        Path pdf = TestPdfs.plain(directory.resolve("doc.pdf"), 3);

        try (DocumentSession session = DocumentSession.open(pdf)) {
            ThumbnailGrid grid = attach(session);

            onFx(() -> {
                blocked.set(true);
                grid.move(0, 2);
            });
            assertEquals(List.of(1, 2, 3), pageNumbers(session), "止めている間にドラッグで並べ替わった");

            onFx(() -> {
                blocked.set(false);
                grid.move(0, 2);
            });
            assertEquals(List.of(2, 3, 1), pageNumbers(session), "放しても並べ替わらない");

            onFx(grid::clear);
        }
    }

    /** 止めている間は、DELETE キーが削除の処理へ届かない。 */
    @Test
    void 止めている間はDELETEキーが届かない(@TempDir Path directory) throws Exception {
        Path pdf = TestPdfs.plain(directory.resolve("doc.pdf"), 3);

        try (DocumentSession session = DocumentSession.open(pdf)) {
            ThumbnailGrid grid = attach(session);
            AtomicBoolean deleted = new AtomicBoolean(false);
            onFx(() -> grid.setOnDelete(() -> deleted.set(true)));

            onFx(() -> {
                blocked.set(true);
                Event.fireEvent(grid.node(), pressDelete());
            });
            assertFalse(deleted.get(), "止めている間に DELETE キーが削除へ届いた");

            onFx(() -> {
                blocked.set(false);
                Event.fireEvent(grid.node(), pressDelete());
            });
            assertTrue(deleted.get(), "放しても DELETE キーが届かない");

            onFx(grid::clear);
        }
    }

    /** 一覧を組み立てて文書を差す。門は {@link #blocked} が握る。 */
    private ThumbnailGrid attach(DocumentSession session) throws Exception {
        return WaitForAsyncUtils.waitForAsyncFx(TIMEOUT_MILLIS, () -> {
            ThumbnailGrid grid = new ThumbnailGrid();
            grid.setEditingBlockedWhen(blocked);
            grid.show(session);
            return grid;
        });
    }

    private static KeyEvent pressDelete() {
        return new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.DELETE, false, false, false, false);
    }

    private static List<Integer> pageNumbers(DocumentSession session) {
        return session.order().pages().stream()
                .map(entry -> entry.selection().pageNumber())
                .toList();
    }

    /**
     * JavaFX スレッドで走らせ、投げられたものを呼んだ側へ返す。
     *
     * <p>{@code Platform.runLater} だけでは、中で投げたものが呼んだ側に届かない。
     */
    private static void onFx(Runnable work) throws Exception {
        WaitForAsyncUtils.waitForAsyncFx(TIMEOUT_MILLIS, work);
    }
}
