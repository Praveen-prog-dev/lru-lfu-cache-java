package com.praveen.cache;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LruCacheTest {

    @Test
    void evictsLeastRecentlyUsed() {
        LruCache<String, Integer> cache = new LruCache<>(2);
        cache.put("a", 1);
        cache.put("b", 2);
        cache.put("c", 3);

        assertEquals(Optional.empty(), cache.get("a"));
        assertEquals(Optional.of(2), cache.get("b"));
        assertEquals(Optional.of(3), cache.get("c"));
    }

    @Test
    void readingAKeyProtectsItFromEviction() {
        LruCache<String, Integer> cache = new LruCache<>(2);
        cache.put("a", 1);
        cache.put("b", 2);
        cache.get("a");
        cache.put("c", 3);

        assertTrue(cache.get("a").isPresent());
        assertFalse(cache.get("b").isPresent());
    }

    @Test
    void updatingExistingKeyDoesNotEvict() {
        LruCache<String, Integer> cache = new LruCache<>(2);
        cache.put("a", 1);
        cache.put("b", 2);
        cache.put("a", 10);

        assertEquals(2, cache.size());
        assertEquals(Optional.of(10), cache.get("a"));
        assertEquals(List.of("a", "b"), cache.keysByRecency());
    }

    @Test
    void notifiesListenerOnEviction() {
        List<String> evicted = new ArrayList<>();
        LruCache<String, Integer> cache = new LruCache<>(1, (k, v) -> evicted.add(k + "=" + v));
        cache.put("a", 1);
        cache.put("b", 2);

        assertEquals(List.of("a=1"), evicted);
        assertEquals(1, cache.stats().evictions());
    }

    @Test
    void removeUnlinksNode() {
        LruCache<String, Integer> cache = new LruCache<>(3);
        cache.put("a", 1);
        cache.put("b", 2);
        cache.put("c", 3);

        assertTrue(cache.remove("b"));
        assertFalse(cache.remove("b"));
        assertEquals(List.of("c", "a"), cache.keysByRecency());
    }

    @Test
    void tracksHitsAndMisses() {
        LruCache<String, Integer> cache = new LruCache<>(2);
        cache.put("a", 1);
        cache.get("a");
        cache.get("a");
        cache.get("zzz");

        CacheStats stats = cache.stats();
        assertEquals(2, stats.hits());
        assertEquals(1, stats.misses());
        assertEquals(2.0 / 3, stats.hitRate(), 0.0001);
    }

    @Test
    void rejectsInvalidInput() {
        assertThrows(IllegalArgumentException.class, () -> new LruCache<String, String>(0));
        LruCache<String, String> cache = new LruCache<>(1);
        assertThrows(NullPointerException.class, () -> cache.put(null, "x"));
        assertThrows(NullPointerException.class, () -> cache.put("x", null));
    }

    @Test
    void staysWithinCapacityUnderConcurrentWrites() throws InterruptedException {
        LruCache<Integer, Integer> cache = new LruCache<>(50);
        Thread[] writers = new Thread[8];
        for (int t = 0; t < writers.length; t++) {
            int offset = t * 1000;
            writers[t] = new Thread(() -> {
                for (int i = 0; i < 1000; i++) {
                    cache.put(offset + i, i);
                    cache.get(offset + i / 2);
                }
            });
            writers[t].start();
        }
        for (Thread w : writers) {
            w.join();
        }

        assertEquals(50, cache.size());
        assertEquals(50, cache.keysByRecency().size());
    }
}
