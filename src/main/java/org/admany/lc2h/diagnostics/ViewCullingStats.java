package org.admany.lc2h.diagnostics;

import java.util.concurrent.atomic.LongAdder;

public final class ViewCullingStats {
    private static final LongAdder TOTAL = new LongAdder();
    private static final LongAdder PLANNER_QUEUE = new LongAdder();
    private static final LongAdder PLANNER_BATCH = new LongAdder();
    private static final LongAdder MULTICHUNK_PENDING = new LongAdder();
    private static final LongAdder WARMUP_QUEUE = new LongAdder();
    private static final LongAdder WARMUP_BATCH = new LongAdder();
    private static final LongAdder MAINTHREAD_APPLY = new LongAdder();

    private ViewCullingStats() {
    }

    public static void recordPlannerQueue(long count) {
        record(count, PLANNER_QUEUE);
    }

    public static void recordPlannerBatch(long count) {
        record(count, PLANNER_BATCH);
    }

    public static void recordMultiChunkPending(long count) {
        record(count, MULTICHUNK_PENDING);
    }

    public static void recordWarmupQueue(long count) {
        record(count, WARMUP_QUEUE);
    }

    public static void recordWarmupBatch(long count) {
        record(count, WARMUP_BATCH);
    }

    public static void recordMainThreadApply(long count) {
        record(count, MAINTHREAD_APPLY);
    }

    private static void record(long count, LongAdder target) {
        if (count <= 0) {
            return;
        }
        target.add(count);
        TOTAL.add(count);
    }

    public static Snapshot snapshot() {
        return new Snapshot(
            TOTAL.sum(),
            PLANNER_QUEUE.sum(),
            PLANNER_BATCH.sum(),
            MULTICHUNK_PENDING.sum(),
            WARMUP_QUEUE.sum(),
            WARMUP_BATCH.sum(),
            MAINTHREAD_APPLY.sum()
        );
    }

    public record Snapshot(
        long total,
        long plannerQueue,
        long plannerBatch,
        long multiChunkPending,
        long warmupQueue,
        long warmupBatch,
        long mainThreadApply
    ) {
    }
}
