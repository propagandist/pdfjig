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
 * <b>利用者に打つ手は無い。</b>
 *
 * <p><b>★★ 断ったことは呼ぶ側へ返す</b>（{@link #run} の戻り値）。
 * <b>void にすると、頼んだ側は「始まった」と「捨てられた」を区別できない</b>——
 * <b>始まる前に済ませた後始末</b>（パスワードのゼロ埋め、覚えたフォルダ）<b>が宙に浮き、
 * 走った前提で進む経路</b>（{@code MainWindow#reopenAt}）<b>が黙って壊れる。</b>
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

    /**
     * 走っている仕事が無くなったら 1 度だけ呼ぶもの。無ければ {@code null}。
     *
     * <p><b>★★ 印が下りた瞬間ではなく、後始末まで済んでから呼ぶ</b>（{@link #drainIdle}）。
     */
    private Runnable idle;

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
     * 走っている仕事が無くなったら 1 度だけ行う。既に無ければその場で行う。
     *
     * <p><b>★★ {@link #busy()} を見張る形にしてはならない。</b>あの印は
     * <b>成功したときの後始末より先に下りる</b>ので、そこで動くと<b>後始末がまだ走っていない。</b>
     * <b>そして {@code Platform#runLater} で 1 拍ずらしても足りない</b>——
     * 後始末の中で {@code Alert#showAndWait} が入れ子のイベントループを回すため、
     * <b>ずらしたものは窓が出ている最中に動く</b>（{@code Messages}）。
     * <b>「保存に失敗しました。元のファイルはここにあります」を読んでいる最中に
     * 主画面が閉じる</b>のがその形である（#124 / #134）。
     *
     * <p><b>だからここが呼ぶ。</b>受け手が戻ってから見るので、<b>窓を閉じるところまで済んでいる。</b>
     *
     * <p><b>★ 後始末が次の仕事を始めていたら、まだ行わない</b>（上書き保存のあとの寄せ直し。#118）。
     * <b>その仕事が終わるときに、またここへ来る。</b>
     *
     * @param action 行うこと。<b>取り消す手は無い</b>——頼めるのは「終了」だけであり、
     *               <b>押した意思を打ち消す入口が画面に無い</b>
     */
    void whenIdle(Runnable action) {
        idle = action;
        drainIdle();
    }

    /**
     * 頼まれていたことを、走っている仕事が無ければ行う。
     *
     * <p><b>先に取り出してから呼ぶ。</b>行った先で {@link #whenIdle} が呼ばれても、
     * <b>取り出したぶんが上書きされて消えない。</b>
     */
    private void drainIdle() {
        if (idle == null || busy.get()) {
            return;
        }
        Runnable action = idle;
        idle = null;
        action.run();
    }

    /**
     * 走らせる。失敗は既定の受け手へ渡す。
     *
     * <p><b>★ 既に走っていれば、何もせずに戻る</b>（#114）。受けたふりをして待たせる形は採らない
     * ——<b>待たせると、頼んだときの前提（並び・出どころ）が変わった後に走り出す。</b>
     * <b>断ったときは {@code work} を 1 度も呼ばない</b>ので、
     * <b>仕事の中で片づける約束のもの（パスワードのゼロ埋め）は呼ぶ側が片づけること。</b>
     *
     * @param work        バックグラウンドで行う仕事
     * @param onSucceeded 成功したときに JavaFX スレッドで呼ばれる
     * @param onFailed    失敗したときに JavaFX スレッドで呼ばれる
     * @param <T>         仕事の結果
     * @return 走り出したなら {@code true}。断ったなら {@code false} で、どちらの受け手も呼ばれない
     */
    <T> boolean run(Supplier<T> work, Consumer<T> onSucceeded, Consumer<Throwable> onFailed) {
        if (busy.get()) {
            Logs.warn(LogEvent.OPERATION_REFUSED);
            return false;
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
            // ★★ 受け手が戻ってから見る。中で窓が出ていれば、閉じるところまで済んでいる（whenIdle）。
            drainIdle();
        });
        task.setOnFailed(event -> {
            busy.set(false);
            onFailed.accept(task.getException());
            drainIdle();
        });
        // ★ 取り消しでも下ろす。いまは誰も取り消さないが、下ろす経路が 2 つしか無い形にしておくと、
        //   取り消しを足した日に「進行中のまま二度と戻らない」を作る（#114）。
        task.setOnCancelled(event -> {
            busy.set(false);
            drainIdle();
        });

        busy.set(true);
        try {
            starter.execute(task);
        } catch (RuntimeException | Error e) {
            // ★★ 始められなかった。ここで下ろさないと、この門は二度と開かない——
            //   走っている印が立ったまま、以降の頼みはすべて上で断られる（#114）。
            busy.set(false);
            // ★ 始められなかったぶんも見る。ここで見ないと、頼まれていたことが
            //   「次に何かを走らせるまで」宙に浮く——次が無ければ永久に浮く。
            drainIdle();
            throw e;
        }
        return true;
    }

    /** 既定の始め方。常駐させない——仕事は利用者の操作ごとに 1 つで、使い回す相手がいない。 */
    private static void startWorker(Runnable work) {
        Thread worker = new Thread(work, "pdfjig-operation");
        worker.setDaemon(true);
        worker.start();
    }
}
