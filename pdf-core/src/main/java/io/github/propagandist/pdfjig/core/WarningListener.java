package io.github.propagandist.pdfjig.core;

/**
 * 操作中に発せられた {@link Warning} の受け口。
 *
 * <p>pdf-core は UI も標準エラー出力も知らないため、伝達手段を呼び出し側から受け取る。
 *
 * <p>{@link PageOperations} の実装はこれをコンストラクタで <b>必ず</b> 要求する。
 * 既定で握り潰す実装を用意しないのは、警告を捨てるという判断を
 * {@link #ignoring()} という形で明示させるためである。
 */
@FunctionalInterface
public interface WarningListener {

    /**
     * 警告を受け取る。
     *
     * <p>実装は例外を投げてはならない。操作そのものは成功しており、
     * 通知の失敗で結果を巻き戻すことはできない。
     *
     * @param warning 発せられた警告
     */
    void onWarning(Warning warning);

    /**
     * 警告を捨てる実装を返す。
     *
     * <p>テストなど、警告の伝達が問題にならない文脈でのみ使う。
     *
     * @return 何もしないリスナー
     */
    static WarningListener ignoring() {
        return warning -> {};
    }
}
