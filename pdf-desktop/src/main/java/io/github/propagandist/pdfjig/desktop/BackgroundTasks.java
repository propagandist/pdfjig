package io.github.propagandist.pdfjig.desktop;

import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.concurrent.Task;

/**
 * ファイル I/O を伴う仕事を、画面を止めずに走らせる。
 *
 * <p><b>JavaFX スレッドで待たない</b>（{@code CLAUDE.md}「JavaFX」）。100 ページの文書でも
 * 開いた瞬間に固まらないためであり、<b>これは画面の都合ではなく約束である</b>。
 *
 * <p><b>走っている間 {@link #busy()} が立つ。</b>どの操作を止めるかを決めるのは画面である
 * （{@code MainWindow} の {@code editingBlocked}）——ここはその条件を出すだけで、
 * <b>「終了」と「バージョン情報」のように、走っていても通してよいものがある。</b>
 *
 * <p><b>★★ 重なったら断る</b>（#114）。<b>主は入口の側で、ここは控えである</b>——
 * 押せないことが見えるのは入口だけだが（<b>押しても何も起きない、と、押せない、は違う</b>）、
 * <b>入口が増えた日に漏れても、2 本目の書き出しはここで止まる。</b>
 * <b>断ったことは画面に出さず、記録にだけ残す</b>（{@link LogEvent#OPERATION_REFUSED}）——
 * <b>ここへ届くこと自体が実装の誤り</b>であり、利用者に打つ手は無い。
 *
 * <p><b>★ 書き込みできる口を外へ出さない。</b>{@link #busy()} が返すのは読み取り専用であり、
 * <b>立てるのも下ろすのもここだけ</b>である。誰かが外から下ろせると、
 * 走っている最中に操作が通る形ができる。
 *
 * <p><b>★ 仕事の始め方だけを差し替えられる</b>（{@link #BackgroundTasks(Executor)}）。
 * <b>「走っている間」をテストから作るためである</b>——実際の書き出しの速さで待ち合わせに
 * いくと、<b>落ちるかどうかが機械の速さで決まるテストになる</b>
 * （{@code CLAUDE.md}「不安定なテストの扱い」。{@code ThumbnailSource} に
 * {@code PageRendering} を差し込んであるのと同じ形である）。
 */
final class BackgroundTasks {

    /**
     * 仕事を始める手。
     *
     * <p><b>既定は仕事ごとの daemon スレッドである。</b>差し替えるのはテストだけで、
     * 配布物はこの既定しか通らない。
     */
    private final Executor starter;

    /**
     * 進行中の仕事があるか。
     *
     * <p><b>数えていない。真偽 1 つでは 2 つ以上を表せない</b>——重なれば<b>先に終わったほうが
     * 下ろす</b>ので、まだ書いている最中に操作が通る。<b>数える形にはしない。</b>
     * この道具に 2 本同時に走らせたい仕事は 1 つも無く、{@link #run} が 2 本目を断るので、
     * <b>表す必要のある状態が 2 つしかない</b>（#114）。
     */
    private final ReadOnlyBooleanWrapper busy = new ReadOnlyBooleanWrapper(false);

    BackgroundTasks() {
        this(BackgroundTasks::startWorker);
    }

    /**
     * 仕事の始め方を指定して作る。
     *
     * @param starter 仕事を始める手。渡されたものを走らせれば、どこで走らせてもよい
     */
    BackgroundTasks(Executor starter) {
        this.starter = starter;
    }

    /** 進行中の仕事があるか。操作の有効・無効を縛るのに使う。 */
    ReadOnlyBooleanProperty busy() {
        return busy.getReadOnlyProperty();
    }

    /**
     * 走らせる。失敗は既定の受け手へ渡す。
     *
     * <p><b>★ 既に走っていれば、何もせずに戻る</b>（#114）。受けたふりをして待たせる形は採らない
     * ——<b>待たせると、頼んだときの前提（並び・出どころ）が変わった後に走り出す。</b>
     *
     * @param work        バックグラウンドで行う仕事
     * @param onSucceeded 成功したときに JavaFX スレッドで呼ばれる
     * @param onFailed    失敗したときに JavaFX スレッドで呼ばれる
     * @param <T>         仕事の結果
     */
    <T> void run(Supplier<T> work, Consumer<T> onSucceeded, Consumer<Throwable> onFailed) {
        if (busy.get()) {
            Logs.warn(LogEvent.OPERATION_REFUSED);
            return;
        }

        Task<T> task = new Task<>() {
            @Override
            protected T call() {
                return work.get();
            }
        };
        task.setOnSucceeded(event -> {
            busy.set(false);
            onSucceeded.accept(task.getValue());
        });
        task.setOnFailed(event -> {
            busy.set(false);
            onFailed.accept(task.getException());
        });

        busy.set(true);
        starter.execute(task);
    }

    /** 既定の始め方。常駐させない——仕事は利用者の操作ごとに 1 つで、使い回す相手がいない。 */
    private static void startWorker(Runnable work) {
        Thread worker = new Thread(work, "pdfjig-operation");
        worker.setDaemon(true);
        worker.start();
    }
}
