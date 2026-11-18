package com.praveen.cache;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LfuCacheTest {

    @Test
    void evictsLeastFrequentlyUsed() {
        LfuCache<String, Integer> cache = new LfuCache<>(2);
        cache.put("a", 1);
        cache.put("b", 2);
        cache.get("a");
        cache.get("a");
        cache.get("b");
        cache.put("c", 3);

        assertTrue(cache.get("a").isPresent());
        assertFalse(cache.get("b").isPresent());
        assertTrue(cache.get("c").isPresent());
    }

    @Test
    void tiesAreBrokenByRecency() {
        LfuCache<String, Integer> cache = new LfuCache<>(2);
        cache.put("a", 1);
        cache.put("b", 2);
        // both at frequency 1, "a" is older
        cache.put("c", 3);

        assertFalse(cache.get("a").isPresent());
        assertTrue(cache.get("b").isPresent());
    }

    @Test
    void newEntryIsEvictedBeforePopularOldOnes() {
        LfuCache<String, Integer> cache = new LfuCache<>(2);
        cache.put("hot", 1);
        for (int i = 0; i < 5; i++) {
            cache.get("hot");
        }
        cache.put("x", 2);
        cache.put("y", 3); // evicts x (freq 1), not hot (freq 6)

        assertTrue(cache.get("hot").isPresent());
        assertFalse(cache.get("x").isPresent());
    }

    @Test
    void updateCountsAsAUse() {
        LfuCache<String, Integer> cache = new LfuCache<>(3);
        cache.put("a", 1);
        cache.put("a", 2);
        cache.put("a", 3);

        assertEquals(3, cache.frequencyOf("a"));
    }

    @Test
    void evictionStillWorksAfterRemovingMinFrequencyKey() {
        List<String> evicted = new ArrayList<>();
        LfuCache<String, Integer> cache = new LfuCache<>(2, (k, v) -> evicted.add(k));
        cache.put("a", 1);
        cache.put("b", 2);
        cache.get("b");
        cache.remove("a"); // min frequency group (1) is now empty
        cache.put("c", 3);
        cache.get("c");
        cache.get("c");
        cache.put("d", 4);

        assertEquals(List.of("b"), evicted);
        assertEquals(2, cache.size());
    }
}
