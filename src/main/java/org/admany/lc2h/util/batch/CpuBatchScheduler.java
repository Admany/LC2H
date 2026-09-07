package org.admany.lc2h.util.batch;

import org.admany.lc2h.LC2H;
import org.admany.lc2h.concurrency.async.AsyncManager;
import org.admany.lc2h.concurrency.async.Priority;
import org.admany.lc2h.dev.diagnostics.Lc2hTimingRegistry;
import org.admany.quantified.api.QuantifiedAPI;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

public final class CpuBatchScheduler {
    private record NamedTask(String name, Runnable action) {}
    private static final int MAX_BATCH = Math.max(128, Integer.getInteger("lc.cpu_batch.size", 2048));
    private static final int TARGET_BATCH = Math.max(
        64,
        Math.min(
            MAX_BATCH,
            Integer.getInteger("lc.cpu_batch.target", MAX_BATCH / 2)
        )
    );
    private static final long FLUSH_DELAY_MS = Math.max(0L, Long.getLong("lc.cpu_batch.flush_delay_ms", 4L));
    private static final Queue<NamedTask> QUEUE = new ConcurrentLinkedQueue<>();
    private static final AtomicBoolean FLUSH_SCHEDULED = new AtomicBoolean(false);
    private static final AtomicBoolean FLUSH_RUNNING = new AtomicBoolean(false);

    private CpuBatchScheduler() {
    }

    public static void submit(String name, Runnable task) {
        if (task == null) {
            return;
        }
        String bucket = (name == null || name.isBlank()) ? "cpu_batch.task" : "cpu_batch." + name;
        QUEUE.add(new NamedTask(bucket, task));
        int backlog = QUEUE.size();
        if (backlog >= MAX_BATCH) {
            requestFlush(name, true);
        } else {
            requestFlush(name, false);
        }
    }

    private static void requestFlush(String name, boolean immediate) {
        if (immediate) {
            FLUSH_SCHEDULED.set(true);
            scheduleFlush(name, true);
            return;
        }
        if (FLUSH_SCHEDULED.compareAndSet(false, true)) {
            scheduleFlush(name, false);
        }
    }

    private static void scheduleFlush(String name, boolean immediate) {
        Runnable dispatcher = CpuBatchScheduler::runScheduledFlush;
        if (!immediate && FLUSH_DELAY_MS > 0L) {
            AsyncManager.runLater("cpu-batch-flush", dispatcher, FLUSH_DELAY_MS, Priority.HIGH);
            return;
        }
        try {
            QuantifiedAPI.<Void>compute(LC2H.MODID, name)
                .foreground()
                .submit(() -> {
                    runScheduledFlush();
                    return null;
                });
        } catch (Throwable t) {
            LC2H.LOGGER.debug("[LC2H] CpuBatchScheduler submit fallback: {}", t.toString());
            runScheduledFlush();
        }
    }

    private static void runScheduledFlush() {
        if (!FLUSH_SCHEDULED.get()) {
            return;
        }
        if (!FLUSH_RUNNING.compareAndSet(false, true)) {
            return;
        }
        try {
            flush();
        } finally {
            FLUSH_RUNNING.set(false);
        }
    }

    private static void flush() {
        long flushStartNs = System.nanoTime();
        try {
            List<NamedTask> batch = new ArrayList<>(MAX_BATCH);
            NamedTask r;
            while ((r = QUEUE.poll()) != null) {
                batch.add(r);
                if (batch.size() >= MAX_BATCH) {
                    runBatch(batch);
                    batch.clear();
                }
            }
            if (!batch.isEmpty()) {
                runBatch(batch);
            }
        } finally {
            Lc2hTimingRegistry.record("cpu_batch.flush", System.nanoTime() - flushStartNs);
            FLUSH_SCHEDULED.set(false);
            if (!QUEUE.isEmpty()) {
                requestFlush("cpu_batch", QUEUE.size() >= TARGET_BATCH);
            }
        }
    }

    private static void runBatch(List<NamedTask> batch) {
        for (NamedTask task : batch) {
            long startNs = System.nanoTime();
            try {
                task.action().run();
            } catch (Throwable t) {
                LC2H.LOGGER.debug("[LC2H] CpuBatchScheduler task error: {}", t.toString());
            } finally {
                Lc2hTimingRegistry.record(task.name(), System.nanoTime() - startNs);
            }
        }
    }
}
