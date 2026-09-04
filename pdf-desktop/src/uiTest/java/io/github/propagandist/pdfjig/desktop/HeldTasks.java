package io.github.propagandist.pdfjig.desktop;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * 放すまで仕事を持ったままにできる {@link Executor}。
 *
 * <p><b>「走っている間」をテストから作るための手である</b>（{@code BackgroundTasks} の
 * 差し替え口）。<b>実際の書き出しの速さで待ち合わせに行くと、落ちるかどうかが機械の速さで
 * 決まるテストになる</b>（{@code CLAUDE.md}「不安定なテストの扱い」）。
 *
 * <p><b>止めるのは頼まれた時点で決まる。</b>{@link #hold()} を呼んでから頼んだものだけを
 * 抱え、それ以外は既定どおりすぐ走らせる——<b>「開く」「追加」まで止めると、
 * そもそも文書を用意できない。</b>
 *
 * <p><b>★ 抱えたまま終わらせないこと。</b>{@code @Stop} で {@link #release()} を呼ぶ
 * ——assert が途中で倒れると放す行まで届かず、<b>捨てる文書を掴んだ仕事が残る。</b>
 */
final class HeldTasks implements Executor {

    private final List<Runnable> waiting = new ArrayList<>();

    private boolean holding;

    /** これ以降に頼まれた仕事を抱える。 */
    synchronized void hold() {
        holding = true;
    }

    /** 抱えている仕事を放し、以降は抱えない。何も抱えていなければ何もしない。 */
    synchronized void release() {
        holding = false;
        waiting.forEach(HeldTasks::start);
        waiting.clear();
    }

    @Override
    public synchronized void execute(Runnable work) {
        if (holding) {
            waiting.add(work);
        } else {
            start(work);
        }
    }

    private static void start(Runnable work) {
        Thread worker = new Thread(work, "held-operation");
        worker.setDaemon(true);
        worker.start();
    }
}
