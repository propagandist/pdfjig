package io.github.propagandist.pdfjig.desktop;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 枚数で上限を切る LRU キャッシュ。
 *
 * <p>サムネイルの保持に使う。上限をメモリ量ではなく枚数で決めているのは、
 * 挙動が読みやすいためである（HANDOVER.md 3-1）。
 *
 * <p>スレッド安全ではない。JavaFX Application Thread からのみ触ること。
 *
 * @param <K> 鍵の型
 * @param <V> 値の型
 */
public final class LruCache<K, V> {

    private final Entries<K, V> entries;

    /**
     * @param capacity 保持する最大件数
     */
    public LruCache(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity は 1 以上でなければなりません。");
        }
        this.entries = new Entries<>(capacity);
    }

    /**
     * 値を取り出し、その鍵を最も新しく使われたものとして扱う。
     *
     * @param key 鍵
     * @return 保持していればその値
     */
    public Optional<V> get(K key) {
        return Optional.ofNullable(entries.get(key));
    }

    /**
     * 値を入れる。上限を超えた場合、最も長く使われていないものが落ちる。
     *
     * @param key   鍵
     * @param value 値
     */
    public void put(K key, V value) {
        entries.put(key, value);
    }

    /** すべて捨てる。別の文書を開いたときに呼ぶ。 */
    public void clear() {
        entries.clear();
    }

    /** 現在の保持件数。 */
    public int size() {
        return entries.size();
    }

    /**
     * アクセス順で並ぶ {@link LinkedHashMap}。
     *
     * <p>匿名クラスにすると直列化まわりの警告が出るため、名前を付けて
     * {@code serialVersionUID} を明示する。
     */
    private static final class Entries<K, V> extends LinkedHashMap<K, V> {

        private static final long serialVersionUID = 1L;

        private final int capacity;

        Entries(int capacity) {
            super(16, 0.75f, true);
            this.capacity = capacity;
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
            return size() > capacity;
        }
    }
}
