package com.praveen.cache;

public record CacheStats(long hits, long misses, long evictions) {

    public double hitRate() {
        long total = hits + misses;
        return total == 0 ? 0.0 : (double) hits / total;
    }

    @Override
    public String toString() {
        return String.format("hits=%d misses=%d evictions=%d hitRate=%.1f%%", hits, misses, evictions, hitRate() * 100);
    }
}
