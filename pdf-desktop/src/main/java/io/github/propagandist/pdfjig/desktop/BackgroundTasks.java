package io.github.propagandist.pdfjig.desktop;

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
 * <p><b>走っている間 {@link #busy()} が立つ。</b>操作の重ね掛けを防ぐのは呼ぶ側の仕事で、
 * ここはその条件を出すだけである——<b>どの操作を止めるかは画面が決める</b>
 * （{@code MainWindow} の {@code buildActions}）。
 *
 * <p><b>★ 書き込みできる口を外へ出さない。</b>{@link #busy()} が返すのは読み取り専用であり、
 * <b>立てるのも下ろすのもここだけ</b>である。誰かが外から下ろせると、
 * 走っている最中に操作が通る形ができる。
 */
final class BackgroundTasks {

    /**
     * 進行中の仕事があるか。
     *
     * <p>数えていない。<b>この画面は 1 つずつしか走らせない</b>——操作は走っている間
     * 無効になるので、2 つ目が始まる経路が無い。数え始めるのは、その前提が崩れたときでよい。
     */
    private final ReadOnlyBooleanWrapper busy = new ReadOnlyBooleanWrapper(false);

    /** 進行中の仕事があるか。操作の有効・無効を縛るのに使う。 */
    ReadOnlyBooleanProperty busy() {
        return busy.getReadOnlyProperty();
    }

    /**
     * 走らせる。失敗は既定の受け手へ渡す。
     *
     * @param work        バックグラウンドで行う仕事
     * @param onSucceeded 成功したときに JavaFX スレッドで呼ばれる
     * @param onFailed    失敗したときに JavaFX スレッドで呼ばれる
     * @param <T>         仕事の結果
     */
    <T> void run(Supplier<T> work, Consumer<T> onSucceeded, Consumer<Throwable> onFailed) {
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
        // 常駐させない。仕事は利用者の操作ごとに 1 つで、使い回す相手がいない。
        Thread worker = new Thread(task, "pdfjig-operation");
        worker.setDaemon(true);
        worker.start();
    }
}
