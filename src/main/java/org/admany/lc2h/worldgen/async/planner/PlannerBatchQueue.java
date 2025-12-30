package org.admany.lc2h.worldgen.async.planner;

import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.IDimensionInfo;
import org.admany.lc2h.LC2H;
import org.admany.lc2h.async.AsyncManager;
import org.admany.lc2h.async.Priority;
import org.admany.lc2h.parallel.AdaptiveBatchController;
import org.admany.lc2h.parallel.ParallelWorkQueue;
import org.admany.lc2h.worldgen.gpu.GPUMemoryManager;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public final class PlannerBatchQueue {

    private static final long DEFERRED_FLUSH_DELAY_MS = Math.max(15L, Long.getLong("lc2h.plannerBatch.flushDelayMs", 40L));
    private static final ConcurrentHashMap<PlannerBatchKey, PendingBatch> BATCHES = new ConcurrentHashMap<>();
    private static final AtomicBoolean DEFERRED_FLUSH_SCHEDULED = new AtomicBoolean(false);

    private PlannerBatchQueue() {
    }

    public record PlannerBatchStats(int batchCount, int pendingTasks, EnumMap<PlannerTaskKind, Integer> pendingByKind) {
        public static PlannerBatchStats empty() {
            return new PlannerBatchStats(0, 0, new EnumMap<>(PlannerTaskKind.class));
        }
    }

    public static PlannerBatchStats snapshotStats() {
        if (BATCHES.isEmpty()) {
            return PlannerBatchStats.empty();
        }
        EnumMap<PlannerTaskKind, Integer> byKind = new EnumMap<>(PlannerTaskKind.class);
        int total = 0;
        int batches = 0;
        for (PendingBatch batch : BATCHES.values()) {
            if (batch == null) {
                continue;
            }
            batches++;
            total += batch.snapshotInto(byKind);
        }
        return new PlannerBatchStats(batches, total, byKind);
    }

    public static void enqueue(IDimensionInfo provider, ChunkCoord coord, PlannerTaskKind kind, Runnable action) {
        if (provider == null || kind == null || action == null) {
            return;
        }
        PlannerBatchKey key = new PlannerBatchKey(provider);
        PendingBatch batch = BATCHES.computeIfAbsent(key, PlannerBatchQueue::createBatch);
        List<PlannerExecutable> ready = batch.add(new PlannerExecutable(kind, coord, action));
        if (ready != null && !ready.isEmpty()) {
            dispatch(key, ready);
            return;
        }
        scheduleDeferredFlush();
    }

    public static void flushKind(PlannerTaskKind kind) {
        if (kind == null) {
            return;
        }
        Iterator<Map.Entry<PlannerBatchKey, PendingBatch>> it = BATCHES.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<PlannerBatchKey, PendingBatch> entry = it.next();
            PendingBatch batch = entry.getValue();
            List<PlannerExecutable> drained = batch.drainKind(kind);
            if (!drained.isEmpty()) {
                dispatch(entry.getKey(), drained);
            }
            if (batch.isEmpty()) {
                BATCHES.remove(entry.getKey(), batch);
            }
        }
    }

    public static void flushAll() {
        Iterator<Map.Entry<PlannerBatchKey, PendingBatch>> it = BATCHES.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<PlannerBatchKey, PendingBatch> entry = it.next();
            PendingBatch batch = entry.getValue();
            List<PlannerExecutable> drained = batch.drainAll();
            if (!drained.isEmpty()) {
                dispatch(entry.getKey(), drained);
            }
            if (batch.isEmpty()) {
                BATCHES.remove(entry.getKey(), batch);
            }
        }
    }

    public static void shutdown() {
        flushAll();
        BATCHES.clear();
    }

    private static PendingBatch createBatch(PlannerBatchKey key) {
        return new PendingBatch();
    }

    private static void scheduleDeferredFlush() {
        if (!DEFERRED_FLUSH_SCHEDULED.compareAndSet(false, true)) {
            return;
        }
        AsyncManager.runLater("planner-batch-flush", () -> {
            try {
                flushAll();
            } finally {
                DEFERRED_FLUSH_SCHEDULED.set(false);
            }
        }, DEFERRED_FLUSH_DELAY_MS, Priority.LOW);
    }

    private static void dispatch(PlannerBatchKey key, List<PlannerExecutable> batch) {
        if (batch.isEmpty()) {
            return;
        }

        if (LC2H.LOGGER.isDebugEnabled()) {
            EnumMap<PlannerTaskKind, Integer> kindCounts = new EnumMap<>(PlannerTaskKind.class);
            for (PlannerExecutable exec : batch) {
                kindCounts.merge(exec.kind, 1, Integer::sum);
            }
            StringBuilder log = new StringBuilder("planner batch dispatch [");
            log.append(key.label()).append("] size=").append(batch.size());
            kindCounts.forEach((kind, count) -> log.append(' ').append(kind.displayName()).append('=').append(count));
            LC2H.LOGGER.debug(log.toString());
        }

        try {
            GPUMemoryManager.continuousCleanup();
        } catch (Throwable t) {
            LC2H.LOGGER.debug("Deferred cleanup skipped before planner batch: {}", t.toString());
        }
        List<Runnable> runners = new ArrayList<>(batch.size());
        for (PlannerExecutable exec : batch) {
            runners.add(() -> runExecutable(exec.kind, exec));
        }

        ParallelWorkQueue.dispatchRunnables("planner-batch-" + key.label(), runners)
            .whenComplete((ignored, throwable) -> {
                if (throwable != null) {
                    LC2H.LOGGER.error("Planner batch execution failed for {}: {}", key.label(), throwable.getMessage());
                }
                try {
                    GPUMemoryManager.continuousCleanup();
                } catch (Throwable t) {
                    LC2H.LOGGER.debug("Deferred cleanup skipped after planner batch: {}", t.toString());
                }
            });
    }

    private static void runExecutable(PlannerTaskKind kind, PlannerExecutable exec) {
        try {
            exec.action.run();
        } catch (Throwable t) {
            LC2H.LOGGER.error("Planner task {} failed at {}: {}", kind.displayName(), describe(exec.coord), t.getMessage());
            LC2H.LOGGER.debug("Planner task error", t);
        }
    }

    private static String describe(ChunkCoord coord) {
        if (coord == null) {
            return "<unknown>";
        }
        try {
            return coord.toString();
        } catch (Throwable t) {
            return "<coord>";
        }
    }

    private static final class PlannerBatchKey {
        private final IDimensionInfo provider;
        private final int hash;
        private final String label;

        PlannerBatchKey(IDimensionInfo provider) {
            this.provider = provider;
            this.hash = System.identityHashCode(provider);
            this.label = resolveLabel(provider);
        }

        private static String resolveLabel(IDimensionInfo provider) {
            try {
                if (provider.getType() != null) {
                    return String.valueOf(provider.getType().location());
                }
            } catch (Throwable ignored) {
            }
            return "unknown";
        }

        String label() {
            return label;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof PlannerBatchKey other)) {
                return false;
            }
            return provider == other.provider;
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }

    private static final class PendingBatch {
        private final Object lock = new Object();
        private final ArrayDeque<PlannerExecutable> tasks = new ArrayDeque<>();

        List<PlannerExecutable> add(PlannerExecutable exec) {
            Objects.requireNonNull(exec, "exec");
            List<PlannerExecutable> ready = null;
            synchronized (lock) {
                tasks.add(exec);
                int threshold = AdaptiveBatchController.plannerFlushThreshold();
                if (tasks.size() >= threshold) {
                    ready = new ArrayList<>(tasks);
                    tasks.clear();
                }
            }
            return ready;
        }

        List<PlannerExecutable> drainAll() {
            synchronized (lock) {
                if (tasks.isEmpty()) {
                    return List.of();
                }
                List<PlannerExecutable> drained = new ArrayList<>(tasks);
                tasks.clear();
                return drained;
            }
        }

        List<PlannerExecutable> drainKind(PlannerTaskKind kind) {
            synchronized (lock) {
                if (tasks.isEmpty()) {
                    return List.of();
                }
                List<PlannerExecutable> drained = new ArrayList<>();
                Iterator<PlannerExecutable> it = tasks.iterator();
                while (it.hasNext()) {
                    PlannerExecutable next = it.next();
                    if (next.kind == kind) {
                        drained.add(next);
                        it.remove();
                    }
                }
                return drained;
            }
        }

        boolean isEmpty() {
            synchronized (lock) {
                return tasks.isEmpty();
            }
        }

        int snapshotInto(EnumMap<PlannerTaskKind, Integer> into) {
            synchronized (lock) {
                if (tasks.isEmpty()) {
                    return 0;
                }
                for (PlannerExecutable exec : tasks) {
                    if (exec == null || exec.kind == null) {
                        continue;
                    }
                    into.merge(exec.kind, 1, Integer::sum);
                }
                return tasks.size();
            }
        }
    }

    private static final class PlannerExecutable {
        private final PlannerTaskKind kind;
        private final ChunkCoord coord;
        private final Runnable action;

        PlannerExecutable(PlannerTaskKind kind, ChunkCoord coord, Runnable action) {
            this.kind = kind;
            this.coord = coord;
            this.action = action;
        }
    }
}
