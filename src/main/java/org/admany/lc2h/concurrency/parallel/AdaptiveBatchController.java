package org.admany.lc2h.concurrency.parallel;

import org.admany.quantified.core.common.parallel.config.ParallelConfig;
import org.admany.quantified.core.common.parallel.metrics.ParallelMetrics;

import java.time.Duration;

public final class AdaptiveBatchController {
    private static final int MULTI_MIN = 8;
    private static final int MULTI_MAX = 64;
    private static final int PLANNER_MIN = 64;
    private static final int PLANNER_MAX = 256;

    private static volatile int cachedMulti = 32;
    private static volatile int cachedPlanner = 128;
    private static volatile long lastUpdateNanos = 0L;

    private AdaptiveBatchController() {
    }

    public static int multiChunkBatchSize() {
        refresh();
        return cachedMulti;
    }

    public static int plannerFlushThreshold() {
        refresh();
        return cachedPlanner;
    }

    private static void refresh() {
        long now = System.nanoTime();
        if ((now - lastUpdateNanos) < Duration.ofMillis(250).toNanos()) {
            return;
        }
        lastUpdateNanos = now;

        try {
            ParallelMetrics.Snapshot snapshot = ParallelMetrics.snapshot();
            long activeSlices = snapshot.modActiveSlices().values().stream().mapToLong(Long::longValue).sum();
            long totalSubmitted = Math.max(1L, snapshot.submissions());
            double load = Math.min(1.0, activeSlices / (double) ParallelConfig.queueLimit());
            double utilization = Math.min(1.0, snapshot.dispatched() / (double) totalSubmitted);

            int desiredMulti = (int) Math.round(MULTI_MAX - (MULTI_MAX - MULTI_MIN) * load);
            if (utilization > 0.9) {
                desiredMulti = Math.max(MULTI_MIN, desiredMulti / 2);
            }
            cachedMulti = clamp(desiredMulti, MULTI_MIN, MULTI_MAX);

            int desiredPlanner = (int) Math.round(PLANNER_MAX - (PLANNER_MAX - PLANNER_MIN) * load);
            cachedPlanner = clamp(desiredPlanner, PLANNER_MIN, PLANNER_MAX);
        } catch (Throwable ignored) {
            cachedMulti = 32;
            cachedPlanner = 128;
        }
    }

    private static int clamp(int value, int min, int max) {
        if (value < min) {
            return min;
        }
        if (value > max) {
            return max;
        }
        return value;
    }
}
