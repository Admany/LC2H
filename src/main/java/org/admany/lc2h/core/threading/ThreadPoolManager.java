package org.admany.lc2h.core.threading;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;

public class ThreadPoolManager {
    private static boolean enabled = true;

    public static boolean isEnabled() {
        return enabled;
    }

    public static CompletableFuture<Void> submitNoise(Runnable task) {
        return CompletableFuture.runAsync(task, ForkJoinPool.commonPool());
    }

    public static CompletableFuture<?> submitBuilding(Runnable task) {
        return CompletableFuture.runAsync(task, ForkJoinPool.commonPool());
    }

    public static CompletableFuture<?> submitCache(Runnable task) {
        return CompletableFuture.runAsync(task, ForkJoinPool.commonPool());
    }
}