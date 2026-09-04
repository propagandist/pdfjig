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

    @BeforeAll
    static void startToolkit() throws Exception {
        // 画面は出さない。JavaFX Toolkit を起こすためだけに呼ぶ。
        FxToolkit.registerPrimaryStage();
    }

    /**
     * 走っている間に頼まれた仕事は始まらない。
     *
     * <p><b>★★ 「まだ走り出していない」ではなく「実行係へ渡していない」で見る。</b>
     * 猶予を置いて走り出さないことを確かめる形にもできるが、<b>それは待つぶんだけ
     * どの実行でも遅くなり、しかも弱い</b>——渡したうえで出遅れているだけの状態と
     * 区別が付かない。<b>差し替えた実行係が数えれば、待たずに、より強く見える。</b>
     */
    @Test
    void 走っている間に頼まれた仕事は始まらない() throws Exception {
        AtomicInteger submitted = new AtomicInteger();
        BackgroundTasks tasks = new BackgroundTasks(work -> {
            submitted.incrementAndGet();
            start(work);
        });
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger finished = new AtomicInteger();

        onFx(() -> tasks.run(
                () -> held(running, release), value -> finished.incrementAndGet(), Throwable::printStackTrace));
        assertTrue(running.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS), "1 本目が走り出さない");

        boolean accepted = WaitForAsyncUtils.waitForAsyncFx(
                TIMEOUT_MILLIS,
                () -> tasks.run(() -> "2 本目", value -> finished.incrementAndGet(), Throwable::printStackTrace));

        assertFalse(accepted, "走っている間の 2 本目を受けたと答えた");
        assertEquals(1, submitted.get(), "断ったはずの 2 本目が実行係へ渡された");

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

    /**
     * 頼まれていたことは、受け手が戻ってから行う。
     *
     * <p><b>★★ 印が下りた瞬間ではない</b>（#134）。{@code busy} は受け手より先に下りるので、
     * そこで動くと<b>後始末がまだ走っていない。</b>ここでは受け手の中で見て、
     * <b>まだ行われていないこと</b>を確かめる。
     *
     * <p><b>★★ 「受け手が投げても行う」ほうは縛っていない。</b>受け手に投げさせると、
     * <b>その例外を TestFX が拾い、次に待つテストで投げ直す</b>——2026-09-04 に CI で
     * <b>無関係のテストが 2 度落ちた。</b>拾われるのは非同期なので、
     * <b>こちらから消しにいっても間に合わない。</b>
     * <b>守っているのは {@code BackgroundTasks#finish} の {@code finally} 1 行だけである。</b>
     */
    @Test
    void 頼まれていたことは受け手が戻ってから行う() throws Exception {
        BackgroundTasks tasks = new BackgroundTasks();
        AtomicInteger done = new AtomicInteger();
        AtomicInteger doneWhileInsideReceiver = new AtomicInteger();

        onFx(() -> {
            tasks.run(() -> "1 本目", value -> doneWhileInsideReceiver.set(done.get()), Throwable::printStackTrace);
            tasks.whenIdle(done::incrementAndGet);
        });

        WaitForAsyncUtils.waitFor(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS, () -> done.get() == 1);
        assertEquals(0, doneWhileInsideReceiver.get(), "受け手が戻る前に行っている。中で窓が出ていればその最中に動く（#134）");
    }

    /**
     * 後始末が次の仕事を始めていたら、まだ行わない。
     *
     * <p><b>★★ 上書き保存のあとの寄せ直しがこの形である</b>（#118）。
     * <b>そこで行うと、守りたかった区間の 2 本目に入る。</b>
     */
    @Test
    void 後始末が次の仕事を始めていたらまだ行わない() throws Exception {
        BackgroundTasks tasks = new BackgroundTasks();
        CountDownLatch secondRunning = new CountDownLatch(1);
        CountDownLatch releaseSecond = new CountDownLatch(1);
        AtomicInteger done = new AtomicInteger();

        onFx(() -> {
            tasks.run(
                    () -> "1 本目",
                    value -> tasks.run(
                            () -> held(secondRunning, releaseSecond), second -> {}, Throwable::printStackTrace),
                    Throwable::printStackTrace);
            tasks.whenIdle(done::incrementAndGet);
        });

        assertTrue(secondRunning.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS), "2 本目が走り出さない");
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals(0, done.get(), "2 本目が走っているのに行っている（#134）");

        releaseSecond.countDown();
        WaitForAsyncUtils.waitFor(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS, () -> done.get() == 1);
    }

    /** 走っていなければ、頼んだその場で行う。 */
    @Test
    void 走っていなければ頼んだその場で行う() throws Exception {
        BackgroundTasks tasks = new BackgroundTasks();
        AtomicInteger done = new AtomicInteger();

        WaitForAsyncUtils.waitForAsyncFx(TIMEOUT_MILLIS, () -> {
            tasks.whenIdle(done::incrementAndGet);
            return null;
        });

        assertEquals(1, done.get(), "走っていないのに待っている");
    }

    /** 仕事を走らせる。{@code BackgroundTasks} の既定と同じ形である。 */
    private static void start(Runnable work) {
        Thread worker = new Thread(work, "counted-operation");
        worker.setDaemon(true);
        worker.start();
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
