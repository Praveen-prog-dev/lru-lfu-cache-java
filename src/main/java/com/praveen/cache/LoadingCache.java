package com.praveen.cache;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Read-through cache with "single flight" loading: if 50 threads miss on the same key at
 * the same time, the loader (DB query, HTTP call) runs once and the other 49 wait for
 * that result. Without this, a popular key expiring can stampede the database.
 */
public class LoadingCache<K, V> {

    private final Cache<K, V> cache;
    private final Function<K, V> loader;
    private final ConcurrentHashMap<K, CompletableFuture<V>> inFlight = new ConcurrentHashMap<>();

    public LoadingCache(Cache<K, V> cache, Function<K, V> loader) {
        this.cache = cache;
        this.loader = loader;
    }

    public V get(K key) {
        Optional<V> cached = cache.get(key);
        if (cached.isPresent()) {
            return cached.get();
        }

        CompletableFuture<V> ours = new CompletableFuture<>();
        CompletableFuture<V> someoneElses = inFlight.putIfAbsent(key, ours);
        if (someoneElses != null) {
            return await(someoneElses);
        }

        try {
            // another thread may have finished loading between our miss and putIfAbsent
            V value = cache.get(key).orElseGet(() -> {
                V loaded = loader.apply(key);
                cache.put(key, loaded);
                return loaded;
            });
            ours.complete(value);
            return value;
        } catch (RuntimeException e) {
            ours.completeExceptionally(e);
            throw e;
        } finally {
            inFlight.remove(key, ours);
        }
    }

    public CacheStats stats() {
        return cache.stats();
    }

    private V await(CompletableFuture<V> future) {
        try {
            return future.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            throw e;
        }
    }
}
