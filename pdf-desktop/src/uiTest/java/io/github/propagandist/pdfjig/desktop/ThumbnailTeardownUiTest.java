package io.github.propagandist.pdfjig.desktop;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.propagandist.pdfjig.core.TestPdfs;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Platform;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.api.FxToolkit;

/**
 * 文書を手放すときの片づけの順（#129）。
 *
 * <p><b>★★ 「まだ絵の来ていないタイル」が居るときにだけ壊れる。</b>
 * {@code ThumbnailTile#show} の {@code samePage} は
 * <b>{@code imageView.getImage() != null} を条件に含む</b>ので、
 * <b>描画待ちのタイルは「同じページ」と判定されず、取り消しの後始末が本体へ入る。</b>
 *
 * <p><b>★★ 利用者が踏むのは {@code MainWindow#adopt} である。</b>
 * {@code closeSession()} の呼び出し元は<b>「窓を閉じる」だけではない</b>——
 * <b>別の文書を開く・「追加」・上書き保存の後の寄せ直し</b>（#118）が同じところを通る。
 * <b>飛ぶと {@code adopt} は {@code session = opened} に到達せず、
 * 前の文書も閉じられないまま画面が中途半端に残る</b>（しかも {@code Task} の外なので
 * 捕まらず、画面には何も出ない）。
 *
 * <p><b>画面は出さない。</b>{@link ThumbnailSourceUiTest} と同じ流儀で、Toolkit だけを起こす
 * ——<b>TestFX の robot を使わないので、開発機のマウスとキーボードを取り上げない。</b>
 * ここで見たいのは片づけの順だけであり、一覧のセルは要らない。
 *
 * <p><b>★★ ここが縛っているのは {@code ThumbnailTile} の側だけである</b>（2026-09-02 実測）。
 * <b>{@code ThumbnailGrid#clear} の順を戻してもここは緑になる</b>——このテストは
 * <b>タイルを一覧のセルとして持たせていない</b>ので、{@code grid.clear()} が
 * タイルを片づけず、<b>実際の経路（セル更新の発火）を通らない。</b>
 * <b>あちらを縛るには一覧のセルが要り、それには画面が要る</b>——
 * <b>実際の経路を見ているのは {@code MainWindowUiTest} の側である</b>
 * （#129 はそこで 2 回赤くなった）。
 *
 * <p><b>★ 待ち合わせに頼らない。</b>描画が終わる前に片づける、という状態を
 * <b>時間で作りにいくと機械の速さで結果が決まる</b>（{@code CLAUDE.md}「不安定なテストの扱い」）。
 * 代わりに<b>すべてを FX スレッドの 1 回の実行の中で済ませる</b>——
 * {@code Task} が成功へ移るのは {@code Platform.runLater} を通るので、
 * <b>こちらが FX スレッドを離さない限り、頼んだ描画は必ず「まだ終わっていない」ままである。</b>
 */
class ThumbnailTeardownUiTest {

    /** 何かが起きるのを待つ上限。CI のランナーは遅いので短くしない。 */
    private static final int TIMEOUT_SECONDS = 20;

    @BeforeAll
    static void startToolkit() throws Exception {
        // 画面は出さない。JavaFX Toolkit を起こすためだけに呼ぶ。
        FxToolkit.registerPrimaryStage();
    }

    /**
     * 描画待ちのタイルが居ても、文書を手放せる。
     *
     * <p><b>修正前はここで {@code NullPointerException} が飛ぶ。</b>片づけの順が
     * 2 か所で逆になっており、それが噛み合う——{@code ThumbnailGrid#clear} が
     * <b>まだ使う {@code thumbnails} を先に捨て</b>、{@code ThumbnailTile#clear} が
     * <b>「もう表示していない」と記す前に取り消す</b>。
     * {@code Task#cancel} は {@code onCancelled} を<b>同期で</b>発火するので、
     * <b>{@code stillShowing} が真のまま本体へ戻り、消えた {@code thumbnails} を引く。</b>
     */
    @Test
    @DisplayName("描画待ちのタイルが居ても、文書を手放せる")
    void letsGoOfTheDocumentWhileATileIsStillWaitingForItsImage(@TempDir Path directory) throws Exception {
        Path pdf = TestPdfs.plain(directory.resolve("doc.pdf"), 3);

        try (DocumentSession session = DocumentSession.open(pdf)) {
            AtomicReference<Throwable> failure = onFxThread(() -> {
                ThumbnailGrid grid = new ThumbnailGrid();
                grid.show(session);

                ThumbnailTile tile = new ThumbnailTile(grid);
                // 絵はまだ無い。ここで頼んだ描画は、この実行を抜けるまで終われない。
                tile.show(0, session.order().pages().get(0));

                // 利用者から見れば「別の文書を開く」か「窓を閉じる」である。
                grid.clear();
                tile.clear();
                return null;
            });

            assertDoesNotThrow(
                    () -> {
                        if (failure.get() != null) {
                            throw failure.get();
                        }
                    },
                    "描画待ちのタイルを抱えたまま文書を手放すと落ちる（#129）");
        }
    }

    /**
     * FX スレッドで走らせ、投げられたものを持ち帰る。
     *
     * <p><b>★ 例外を握り潰さない。</b>{@code Platform.runLater} の中で投げると
     * <b>既定の扱いでは呼んだ側に届かない</b>——それではこのテストが見たいものを見られない。
     */
    private static AtomicReference<Throwable> onFxThread(Callable<Void> work) throws Exception {
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                work.call();
            } catch (Throwable t) {
                thrown.set(t);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "FX スレッドが返ってこない");
        return thrown;
    }
}
