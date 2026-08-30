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
 * <p><b>★★ 止まらない入口が実際にある。</b>{@link #busy()} を見ているのは {@link Action} から
 * 作られた節点だけであり、<b>一覧の「×」（{@code SourceLegend}）・サムネイルの DELETE キー
 * （{@code ThumbnailGrid}）・タイルのドラッグ・「終了」・「バージョン情報」は素通りする。</b>
 * <b>「走っている間は何も起きない」と読まないこと。</b>塞ぎ方は #114 が持つ。
 *
 * <p><b>★ 書き込みできる口を外へ出さない。</b>{@link #busy()} が返すのは読み取り専用であり、
 * <b>立てるのも下ろすのもここだけ</b>である。誰かが外から下ろせると、
 * 走っている最中に操作が通る形ができる。
 */
final class BackgroundTasks {

    /**
     * 進行中の仕事があるか。
     *
     * <p><b>数えていない。真偽 1 つでは 2 つ以上を表せない</b>——重なれば<b>先に終わったほうが
     * 下ろす</b>ので、まだ書いている最中に操作が通る。<b>いまは重ならない</b>が、それは
     * {@code run} が縛っているからではなく、呼ぶ側がたまたま重ねていないだけである（#114）。
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
