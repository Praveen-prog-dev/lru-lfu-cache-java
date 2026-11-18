package com.praveen.cache;

@FunctionalInterface
public interface Ticker {

    long nowMillis();

    Ticker SYSTEM = () -> System.nanoTime() / 1_000_000;
}
