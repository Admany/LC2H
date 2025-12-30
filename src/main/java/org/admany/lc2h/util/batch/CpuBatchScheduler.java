package org.admany.lc2h.util.batch;

import org.admany.lc2h.LC2H;
import org.admany.quantified.api.QuantifiedAPI;
import org.admany.quantified.api.model.QuantifiedTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

public final class CpuBatchScheduler {
        private static final int MAX_BATCH = Math.max(128, Integer.getInteger("lc.cpu_batch.size", 2048));
        private static final int TARGET_BATCH = Math.max(
            64,
            Math.min(
                MAX_BATCH,
                Integer.getInteger("lc.cpu_batch.target", MAX_BATCH / 2)
            )
        );
    private static final long SPIN_WAIT_MS = Math.max(0L, Long.getLong("lc.cpu_batch.spin_wait_ms", 4L));
    private static final Queue<Runnable> QUEUE = new ConcurrentLinkedQueue<>();
    private static final AtomicBoolean FLUSH_SCHEDULED = new AtomicBoolean(false);

    private CpuBatchScheduler() {
    }

    public static void submit(String name, Runnable task) {
        QUEUE.add(task);
        int backlog = QUEUE.size();
        if (backlog >= MAX_BATCH) {
            requestFlush(name, true);
        } else {
            requestFlush(name, false);
        }
    }

    private static void requestFlush(String name, boolean immediate) {
        if (immediate) {
            if (FLUSH_SCHEDULED.compareAndSet(false, true)) {
                scheduleFlush(name, true);
            }
            return;
        }
        if (FLUSH_SCHEDULED.compareAndSet(false, true)) {
            scheduleFlush(name, false);
        }
    }

    private static void scheduleFlush(String name, boolean immediate) {
        try {
            QuantifiedAPI.submit(QuantifiedTask.<Void>builder(LC2H.MODID, name, () -> {
                if (!immediate && SPIN_WAIT_MS > 0L) {
                    final long deadline = System.nanoTime() + SPIN_WAIT_MS * 1_000_000L;
                    int spinSleeps = 0;
                    while (QUEUE.size() < TARGET_BATCH && System.nanoTime() < deadline) {
                        if (QUEUE.size() >= MAX_BATCH) {
                            break;
                        }
                        try {
                            Thread.sleep(1L);
                        } catch (InterruptedException ignored) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                        if (++spinSleeps >= SPIN_WAIT_MS) {
                            break;
                        }
                    }
                }
                flush();
                return null;
            }).priorityForeground());
        } catch (Throwable t) {
            LC2H.LOGGER.debug("[LC2H] CpuBatchScheduler submit fallback: {}", t.toString());
            flush();
        }
    }

    private static void flush() {
        try {
            List<Runnable> batch = new ArrayList<>(MAX_BATCH);
            Runnable r;
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
            FLUSH_SCHEDULED.set(false);
            if (!QUEUE.isEmpty()) {
                if (FLUSH_SCHEDULED.compareAndSet(false, true)) {
                    scheduleFlush("cpu_batch", false);
                }
            }
        }
    }

    private static void runBatch(List<Runnable> batch) {
        for (Runnable r : batch) {
            try {
                r.run();
            } catch (Throwable t) {
                LC2H.LOGGER.debug("[LC2H] CpuBatchScheduler task error: {}", t.toString());
            }
        }
    }
}
