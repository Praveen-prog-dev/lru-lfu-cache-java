package com.praveen.cache;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * O(1) LFU cache.
 *
 * keysByFrequency groups keys by how often they were used. Each group is a LinkedHashSet,
 * so within the same frequency the least recently used key is evicted first (ties go LRU).
 * minFrequency always points at the group to evict from, which is what makes eviction O(1)
 * instead of scanning for the smallest count.
 */
public class LfuCache<K, V> implements Cache<K, V> {

    private final int capacity;
    private final Map<K, Entry<V>> entries = new HashMap<>();
    private final Map<Integer, LinkedHashSet<K>> keysByFrequency = new HashMap<>();
    private int minFrequency;

    private final ReentrantLock lock = new ReentrantLock();
    private final StatsCounter stats = new StatsCounter();
    private final EvictionListener<K, V> evictionListener;

    public LfuCache(int capacity) {
        this(capacity, EvictionListener.noop());
    }

    public LfuCache(int capacity, EvictionListener<K, V> evictionListener) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
        this.evictionListener = Objects.requireNonNull(evictionListener);
    }

    @Override
    public Optional<V> get(K key) {
        lock.lock();
        try {
            Entry<V> entry = entries.get(key);
            if (entry == null) {
                stats.miss();
                return Optional.empty();
            }
            touch(key, entry);
            stats.hit();
            return Optional.of(entry.value);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void put(K key, V value) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        K evictedKey = null;
        V evictedValue = null;

        lock.lock();
        try {
            Entry<V> existing = entries.get(key);
            if (existing != null) {
                existing.value = value;
                touch(key, existing);
                return;
            }
            if (entries.size() == capacity) {
                evictedKey = evictLeastFrequent();
                evictedValue = entries.remove(evictedKey).value;
                stats.eviction();
            }
            entries.put(key, new Entry<>(value));
            keysByFrequency.computeIfAbsent(1, f -> new LinkedHashSet<>()).add(key);
            minFrequency = 1;
        } finally {
            lock.unlock();
        }

        if (evictedKey != null) {
            evictionListener.onEviction(evictedKey, evictedValue);
        }
    }

    @Override
    public boolean remove(K key) {
        lock.lock();
        try {
            Entry<V> entry = entries.remove(key);
            if (entry == null) {
                return false;
            }
            LinkedHashSet<K> group = keysByFrequency.get(entry.frequency);
            group.remove(key);
            if (group.isEmpty()) {
                keysByFrequency.remove(entry.frequency);
                // minFrequency may now be stale; it's fixed lazily in evictLeastFrequent()
            }
            return true;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public int size() {
        lock.lock();
        try {
            return entries.size();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public CacheStats stats() {
        return stats.snapshot();
    }

    public int frequencyOf(K key) {
        lock.lock();
        try {
            Entry<V> entry = entries.get(key);
            return entry == null ? 0 : entry.frequency;
        } finally {
            lock.unlock();
        }
    }

    private void touch(K key, Entry<V> entry) {
        int oldFrequency = entry.frequency;
        LinkedHashSet<K> oldGroup = keysByFrequency.get(oldFrequency);
        oldGroup.remove(key);
        if (oldGroup.isEmpty()) {
            keysByFrequency.remove(oldFrequency);
            if (minFrequency == oldFrequency) {
                minFrequency++;
            }
        }
        entry.frequency++;
        keysByFrequency.computeIfAbsent(entry.frequency, f -> new LinkedHashSet<>()).add(key);
    }

    private K evictLeastFrequent() {
        LinkedHashSet<K> group = keysByFrequency.get(minFrequency);
        while (group == null) {
            // only happens after remove() emptied the min group
            minFrequency++;
            group = keysByFrequency.get(minFrequency);
        }
        Iterator<K> it = group.iterator();
        K victim = it.next();
        it.remove();
        if (group.isEmpty()) {
            keysByFrequency.remove(minFrequency);
        }
        return victim;
    }

    private static final class Entry<V> {
        V value;
        int frequency = 1;

        Entry(V value) {
            this.value = value;
        }
    }
}
