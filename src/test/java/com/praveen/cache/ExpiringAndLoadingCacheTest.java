package com.praveen.cache;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExpiringAndLoadingCacheTest {

    @Test
    void entriesExpireAfterTtl() {
        AtomicLong now = new AtomicLong(0);
        ExpiringCache<String, String> cache =
                new ExpiringCache<>(new LruCache<>(10), Duration.ofSeconds(5), now::get);

        cache.put("session", "abc");
        now.set(4_999);
        assertEquals(Optional.of("abc"), cache.get("session"));

        now.set(5_000);
        assertEquals(Optional.empty(), cache.get("session"));
        assertEquals(0, cache.size(), "expired entry should be removed on read");
    }

    @Test
    void reputRefreshesTtl() {
        AtomicLong now = new AtomicLong(0);
        ExpiringCache<String, String> cache =
                new ExpiringCache<>(new LfuCache<>(10), Duration.ofSeconds(5), now::get);

        cache.put("k", "v1");
        now.set(4_000);
        cache.put("k", "v2");
        now.set(8_000);

        assertEquals(Optional.of("v2"), cache.get("k"));
    }

    @Test
    void loaderRunsOnceForConcurrentMissesOnSameKey() throws InterruptedException {
        AtomicInteger loads = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);
        LoadingCache<String, String> cache = new LoadingCache<>(new LruCache<>(10), key -> {
            loads.incrementAndGet();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return key.toUpperCase();
        });

        ExecutorService pool = Executors.newFixedThreadPool(10);
        AtomicInteger correct = new AtomicInteger();
        for (int i = 0; i < 10; i++) {
            pool.submit(() -> {
                if ("abc".equalsIgnoreCase(cache.get("abc"))) {
                    correct.incrementAndGet();
                }
            });
        }
        Thread.sleep(100); // let all threads pile up behind the first load
        release.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));

        assertEquals(1, loads.get());
        assertEquals(10, correct.get());
    }

    @Test
    void failedLoadIsNotCachedAndCanBeRetried() {
        AtomicInteger attempts = new AtomicInteger();
        LoadingCache<String, String> cache = new LoadingCache<>(new LruCache<>(10), key -> {
            if (attempts.incrementAndGet() == 1) {
                throw new IllegalStateException("db timeout");
            }
            return "ok";
        });

        assertThrows(IllegalStateException.class, () -> cache.get("k"));
        assertEquals("ok", cache.get("k"));
        assertEquals("ok", cache.get("k"));
        assertEquals(2, attempts.get());
    }
}
