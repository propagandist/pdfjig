package io.github.propagandist.pdfjig.desktop;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import io.github.propagandist.pdfjig.core.TestPdfs;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.api.FxToolkit;
import org.testfx.util.WaitForAsyncUtils;

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
 * ここで見たいのは取り消しの後始末だけであり、一覧のセルは要らない。
 *
 * <p><b>★★ 「描画が終わる前」は構造では作れていない。余裕で作っている。</b>
 * 当初はここに「FX スレッドを離さない限り {@code Task} は成功へ移れない」と書いたが、
 * <b>それは誤りである</b>（門の 1 段目が突いた）——{@code Task} は
 * {@code Platform.runLater} で成功を<b>通知する</b>が、{@code FutureTask} の状態そのものは
 * <b>描画スレッドの上で先に決まる。</b>そこまで進んでいれば {@code cancel(false)} は偽を返し、
 * <b>取り消しの後始末は走らない——つまり偽の緑になりうる。</b>
 * <b>実際には 3 行の代入より PDF 1 枚の描画のほうが桁で遅いので当たらない</b>が、
 * <b>「構造で保証している」と書くのは嘘である。</b>
 * ── ★ <b>構造にするには {@code GatedRendering}</b>（{@link ThumbnailSourceUiTest}）
 * <b>のような差し替えが要る。</b>いまは {@code DocumentSession} が {@code ThumbnailSource} を
 * 直に組んでおり、差し込む口が無い。
 *
 * <p><b>★ 縛れているのは {@code cancelPending} がハンドラを外すことだけである</b>
 * （2026-09-02 実測。外すと赤、それ以外の変異では緑）。
 * <b>{@code show} の側の再入</b>——タイルが別のページへ回されたとき、
 * <b>離れたばかりのページを頼み直し、その依頼を取り消せないまま捨てる</b>——
 * <b>は同じ 1 行が直すが、ここでは見ていない。</b>外から見える違いが
 * 「要らない描画が 1 つ走る」だけだからである。
 */
class ThumbnailTeardownUiTest {

    /** 何かが起きるのを待つ上限。CI のランナーは遅いので短くしない。 */
    private static final long TIMEOUT_MILLIS = 20_000L;

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
    void 描画待ちのタイルが居ても文書を手放せる(@TempDir Path directory) throws Exception {
        Path pdf = TestPdfs.plain(directory.resolve("doc.pdf"), 3);

        try (DocumentSession session = DocumentSession.open(pdf)) {
            // ★ waitForAsyncFx は FX スレッドで走らせ、投げられたものを呼んだ側へ返す。
            //   Platform.runLater だけでは、中で投げたものが呼んだ側に届かない。
            assertDoesNotThrow(
                    () -> WaitForAsyncUtils.waitForAsyncFx(TIMEOUT_MILLIS, () -> {
                        ThumbnailGrid grid = new ThumbnailGrid();
                        grid.show(session);

                        ThumbnailTile tile = new ThumbnailTile(grid);
                        // 絵はまだ無い。ここで頼んだ描画は、まだ終わっていない（上の★★）。
                        tile.show(0, session.order().pages().get(0));

                        // 利用者から見れば「別の文書を開く」か「窓を閉じる」である。
                        grid.clear();
                        tile.clear();
                    }),
                    "描画待ちのタイルを抱えたまま文書を手放すと落ちる（#129）");
        }
    }
}
