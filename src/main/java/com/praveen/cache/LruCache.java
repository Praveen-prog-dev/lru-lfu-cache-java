package com.praveen.cache;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * HashMap for O(1) lookup + a doubly linked list for O(1) recency updates.
 * The head side is the most recently used entry, the tail side is the next to be evicted.
 *
 * I didn't use LinkedHashMap(accessOrder=true) on purpose - the point was to own the list
 * manipulation, and it also makes the eviction callback easy to call outside the lock.
 *
 * A ReadWriteLock wouldn't help here because get() also reorders the list, so every
 * operation is a write.
 */
public class LruCache<K, V> implements Cache<K, V> {

    private final int capacity;
    private final Map<K, Node<K, V>> index;
    private final Node<K, V> head = new Node<>(null, null);
    private final Node<K, V> tail = new Node<>(null, null);
    private final ReentrantLock lock = new ReentrantLock();
    private final StatsCounter stats = new StatsCounter();
    private final EvictionListener<K, V> evictionListener;

    public LruCache(int capacity) {
        this(capacity, EvictionListener.noop());
    }

    public LruCache(int capacity, EvictionListener<K, V> evictionListener) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
        this.evictionListener = Objects.requireNonNull(evictionListener);
        this.index = new HashMap<>(capacity * 4 / 3 + 1);
        head.next = tail;
        tail.prev = head;
    }

    @Override
    public Optional<V> get(K key) {
        lock.lock();
        try {
            Node<K, V> node = index.get(key);
            if (node == null) {
                stats.miss();
                return Optional.empty();
            }
            moveToFront(node);
            stats.hit();
            return Optional.of(node.value);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void put(K key, V value) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        Node<K, V> evicted = null;

        lock.lock();
        try {
            Node<K, V> existing = index.get(key);
            if (existing != null) {
                existing.value = value;
                moveToFront(existing);
                return;
            }
            if (index.size() == capacity) {
                evicted = tail.prev;
                unlink(evicted);
                index.remove(evicted.key);
                stats.eviction();
            }
            Node<K, V> node = new Node<>(key, value);
            index.put(key, node);
            addFirst(node);
        } finally {
            lock.unlock();
        }

        // outside the lock - a slow listener (logging, metrics, write-back) must not block readers
        if (evicted != null) {
            evictionListener.onEviction(evicted.key, evicted.value);
        }
    }

    @Override
    public boolean remove(K key) {
        lock.lock();
        try {
            Node<K, V> node = index.remove(key);
            if (node == null) {
                return false;
            }
            unlink(node);
            return true;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public int size() {
        lock.lock();
        try {
            return index.size();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public CacheStats stats() {
        return stats.snapshot();
    }

    /** Keys from most to least recently used. Mostly useful for tests and debugging. */
    public List<K> keysByRecency() {
        lock.lock();
        try {
            List<K> keys = new ArrayList<>(index.size());
            for (Node<K, V> n = head.next; n != tail; n = n.next) {
                keys.add(n.key);
            }
            return keys;
        } finally {
            lock.unlock();
        }
    }

    private void moveToFront(Node<K, V> node) {
        unlink(node);
        addFirst(node);
    }

    private void addFirst(Node<K, V> node) {
        node.prev = head;
        node.next = head.next;
        head.next.prev = node;
        head.next = node;
    }

    private void unlink(Node<K, V> node) {
        node.prev.next = node.next;
        node.next.prev = node.prev;
        node.prev = null;
        node.next = null;
    }

    private static final class Node<K, V> {
        final K key;
        V value;
        Node<K, V> prev;
        Node<K, V> next;

        Node(K key, V value) {
            this.key = key;
            this.value = value;
        }
    }
}
