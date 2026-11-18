package com.praveen.cache;

import java.time.Duration;
import java.util.Optional;

/**
 * Decorator that adds a time-to-live on top of any eviction policy.
 * Expired entries are removed lazily when they're read, so there's no cleanup thread;
 * the underlying LRU/LFU policy still bounds the memory.
 */
public class ExpiringCache<K, V> implements Cache<K, V> {

    private final Cache<K, Stamped<V>> delegate;
    private final long ttlMillis;
    private final Ticker ticker;
    private final StatsCounter stats = new StatsCounter();

    public ExpiringCache(Cache<K, Stamped<V>> delegate, Duration ttl, Ticker ticker) {
        if (ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("ttl must be positive");
        }
        this.delegate = delegate;
        this.ttlMillis = ttl.toMillis();
        this.ticker = ticker;
    }

    public static <K, V> ExpiringCache<K, V> lru(int capacity, Duration ttl) {
        return new ExpiringCache<>(new LruCache<>(capacity), ttl, Ticker.SYSTEM);
    }

    @Override
    public Optional<V> get(K key) {
        Optional<Stamped<V>> stamped = delegate.get(key);
        if (stamped.isEmpty()) {
            stats.miss();
            return Optional.empty();
        }
        if (stamped.get().expiresAt() <= ticker.nowMillis()) {
            delegate.remove(key);
            stats.miss();
            return Optional.empty();
        }
        stats.hit();
        return Optional.of(stamped.get().value());
    }

    @Override
    public void put(K key, V value) {
        delegate.put(key, new Stamped<>(value, ticker.nowMillis() + ttlMillis));
    }

    @Override
    public boolean remove(K key) {
        return delegate.remove(key);
    }

    /** Includes entries that have expired but haven't been read since. */
    @Override
    public int size() {
        return delegate.size();
    }

    @Override
    public CacheStats stats() {
        CacheStats own = stats.snapshot();
        return new CacheStats(own.hits(), own.misses(), delegate.stats().evictions());
    }

    public record Stamped<V>(V value, long expiresAt) {
    }
}
