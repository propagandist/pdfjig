package io.github.propagandist.pdfjig.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxToolkit;
import org.testfx.util.WaitForAsyncUtils;

/**
 * 仕事が重なったときの {@link BackgroundTasks} の振る舞い（#114）。
 *
 * <p><b>★★ 進行中かどうかは真偽 1 つで持っている。</b>2 本が重なると
 * <b>先に終わったほうが下ろす</b>ので、まだ書いている最中に道具一式が戻る。
 * <b>数える形にはしない</b>——数えれば「2 本走っている」を表せるが、
 * <b>この道具に 2 本同時に走らせたい仕事は 1 つも無い。</b>断るほうが正しい。
 *
 * <p><b>これは入口を塞ぐことの代わりではない。</b>見えるのは入口の側だけであり
 * （押せない、と、押しても何も起きない、は違う）、<b>ここは入口が増えた日に
 * データを壊す手前で止めるためにある</b>——{@code MainWindow} の門が漏らしても、
 * <b>2 本目の書き出しはここで止まる。</b>
 *
 * <p><b>画面は出さない。</b>それでもここ（{@code uiTest}）に置いてあるのは
 * {@code Task} の状態遷移が {@code Platform.runLater} を通るためで、
 * {@code src/test} は ubuntu の {@code build} でも走るので Toolkit を起こせない
 * （{@link ThumbnailSourceUiTest} と同じ理由）。
 */
class BackgroundTasksUiTest {

    /** 何かが起きるのを待つ上限。CI のランナーは遅いので短くしない。 */
    private static final long TIMEOUT_MILLIS = 20_000L;

    /**
     * 断られたほうが走り出してしまわないことを見るための猶予。
     *
     * <p><b>「待てば直る」種類の待ちではない。</b>断れていれば絶対に走り出さないので、
     * 長く取っても偽の緑にならない。短すぎると、スレッドがまだ動き出していないだけの状態を
     * 「断れている」と読みうるので、そちら側に倒してある。
     */
    private static final long GRACE_MILLIS = 2_000L;

    @BeforeAll
    static void startToolkit() throws Exception {
        // 画面は出さない。JavaFX Toolkit を起こすためだけに呼ぶ。
        FxToolkit.registerPrimaryStage();
    }

    @Test
    void 走っている間に頼まれた仕事は始まらない() throws Exception {
        BackgroundTasks tasks = new BackgroundTasks();
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch second = new CountDownLatch(1);
        AtomicInteger finished = new AtomicInteger();

        onFx(() -> tasks.run(
                () -> held(running, release), value -> finished.incrementAndGet(), Throwable::printStackTrace));
        assertTrue(running.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS), "1 本目が走り出さない");

        onFx(() -> tasks.run(
                () -> {
                    second.countDown();
                    return "2 本目";
                },
                value -> finished.incrementAndGet(),
                Throwable::printStackTrace));

        assertFalse(second.await(GRACE_MILLIS, TimeUnit.MILLISECONDS), "走っている間に 2 本目が始まった");

        release.countDown();
        WaitForAsyncUtils.waitFor(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS, () -> finished.get() == 1);
        WaitForAsyncUtils.waitForFxEvents();

        assertEquals(1, finished.get(), "断ったはずの 2 本目が結果を返した");
        assertFalse(tasks.busy().get(), "走り終わったのに進行中のままである");
    }

    /**
     * 走り終われば次を受ける。
     *
     * <p><b>断ったまま二度と受けない形でも、上の 1 本は緑になる。</b>
     */
    @Test
    void 走り終われば次の仕事を受ける() throws Exception {
        BackgroundTasks tasks = new BackgroundTasks();
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger finished = new AtomicInteger();

        onFx(() -> tasks.run(
                () -> held(running, release), value -> finished.incrementAndGet(), Throwable::printStackTrace));
        assertTrue(running.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS), "1 本目が走り出さない");
        release.countDown();
        WaitForAsyncUtils.waitFor(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS, () -> finished.get() == 1);

        onFx(() -> tasks.run(() -> "2 本目", value -> finished.incrementAndGet(), Throwable::printStackTrace));
        WaitForAsyncUtils.waitFor(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS, () -> finished.get() == 2);
    }

    /** 走り出したことを報せ、放されるまで戻らない仕事。 */
    private static String held(CountDownLatch running, CountDownLatch release) {
        running.countDown();
        try {
            if (!release.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("仕事が放されないまま上限に達した。");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("仕事が割り込まれた。");
        }
        return "1 本目";
    }

    /**
     * JavaFX スレッドで走らせ、投げられたものを呼んだ側へ返す。
     *
     * <p>進行中かどうかは画面の有効・無効を縛る property であり、触るのは JavaFX スレッドである。
     */
    private static void onFx(Runnable work) throws Exception {
        WaitForAsyncUtils.waitForAsyncFx(TIMEOUT_MILLIS, work);
    }
}
