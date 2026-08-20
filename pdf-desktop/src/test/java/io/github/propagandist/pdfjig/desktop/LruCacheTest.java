package io.github.propagandist.pdfjig.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LruCacheTest {

    @Test
    @DisplayName("入れた値を取り出せる")
    void returnsStoredValue() {
        LruCache<Integer, String> cache = new LruCache<>(2);

        cache.put(1, "one");

        assertEquals(Optional.of("one"), cache.get(1));
        assertEquals(Optional.empty(), cache.get(2));
    }

    @Test
    @DisplayName("上限を超えると最も長く使われていないものが落ちる")
    void evictsLeastRecentlyUsed() {
        LruCache<Integer, String> cache = new LruCache<>(2);
        cache.put(1, "one");
        cache.put(2, "two");

        cache.put(3, "three");

        assertEquals(2, cache.size());
        assertEquals(Optional.empty(), cache.get(1));
        assertTrue(cache.get(2).isPresent());
        assertTrue(cache.get(3).isPresent());
    }

    @Test
    @DisplayName("取り出した鍵は新しく使われたものとして扱われる")
    void refreshesOnAccess() {
        LruCache<Integer, String> cache = new LruCache<>(2);
        cache.put(1, "one");
        cache.put(2, "two");

        cache.get(1);
        cache.put(3, "three");

        // 直前に触った 1 ではなく、触っていない 2 が落ちる。
        assertTrue(cache.get(1).isPresent());
        assertEquals(Optional.empty(), cache.get(2));
    }

    @Test
    @DisplayName("すべて捨てられる")
    void clearsEverything() {
        LruCache<Integer, String> cache = new LruCache<>(2);
        cache.put(1, "one");

        cache.clear();

        assertEquals(0, cache.size());
        assertEquals(Optional.empty(), cache.get(1));
    }

    @Test
    @DisplayName("容量 0 以下は作れない")
    void rejectsNonPositiveCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new LruCache<Integer, String>(0));
    }
}
