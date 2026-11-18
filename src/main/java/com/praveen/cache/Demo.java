package com.praveen.cache;

import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

public class Demo {

    public static void main(String[] args) {
        // Product lookups follow a skewed distribution: a few products are very popular.
        // Compare how LRU and LFU do with the same small cache.
        runWorkload("LRU", new LruCache<>(20));
        runWorkload("LFU", new LfuCache<>(20));

        System.out.println();
        singleFlightExample();
    }

    private static void runWorkload(String name, Cache<Integer, String> cache) {
        Random random = new Random(42);
        for (int i = 0; i < 20_000; i++) {
            int productId = skewedId(random);
            if (cache.get(productId).isEmpty()) {
                cache.put(productId, "product-" + productId);
            }
            // every so often a one-off scan touches lots of cold products (think: a report job)
            if (i % 2_000 == 0) {
                for (int cold = 1_000; cold < 1_030; cold++) {
                    cache.put(cold, "cold-" + cold);
                }
            }
        }
        System.out.printf("%s -> %s%n", name, cache.stats());
    }

    private static int skewedId(Random random) {
        // ~80% of traffic goes to 15 hot products
        return random.nextDouble() < 0.8 ? random.nextInt(15) : 15 + random.nextInt(500);
    }

    private static void singleFlightExample() {
        AtomicInteger dbCalls = new AtomicInteger();
        LoadingCache<String, String> users = new LoadingCache<>(new LruCache<>(100), id -> {
            dbCalls.incrementAndGet();
            sleepQuietly(200);
            return "User(" + id + ")";
        });

        Thread[] threads = new Thread[20];
        for (int i = 0; i < threads.length; i++) {
            threads[i] = Thread.ofVirtual().start(() -> users.get("u-42"));
        }
        for (Thread t : threads) {
            try {
                t.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        System.out.println("20 concurrent requests for the same user -> DB calls: " + dbCalls.get());
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
