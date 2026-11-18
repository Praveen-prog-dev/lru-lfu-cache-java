package com.praveen.cache;

import java.util.concurrent.atomic.LongAdder;

// LongAdder instead of AtomicLong: lots of threads bump these, and nobody reads them often
final class StatsCounter {

    private final LongAdder hits = new LongAdder();
    private final LongAdder misses = new LongAdder();
    private final LongAdder evictions = new LongAdder();

    void hit() {
        hits.increment();
    }

    void miss() {
        misses.increment();
    }

    void eviction() {
        evictions.increment();
    }

    CacheStats snapshot() {
        return new CacheStats(hits.sum(), misses.sum(), evictions.sum());
    }
}
