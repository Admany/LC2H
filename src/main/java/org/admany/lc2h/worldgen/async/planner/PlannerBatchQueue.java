package org.admany.lc2h.worldgen.async.planner;

import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.IDimensionInfo;
import net.minecraft.server.MinecraftServer;
import org.admany.lc2h.LC2H;
import org.admany.lc2h.async.AsyncManager;
import org.admany.lc2h.async.Priority;
import org.admany.lc2h.frustum.ChunkPriorityManager;
import org.admany.lc2h.parallel.AdaptiveBatchController;
import org.admany.lc2h.parallel.ParallelWorkQueue;
import org.admany.lc2h.util.server.ServerRescheduler;
import org.admany.lc2h.util.log.RateLimitedLogger;
import org.admany.lc2h.worldgen.gpu.GPUMemoryManager;
import org.admany.lc2h.util.spawn.SpawnSearchScheduler;
import org.admany.lc2h.diagnostics.ViewCullingStats;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;

public final class PlannerBatchQueue {

    private static final long DEFERRED_FLUSH_DELAY_MS = Math.max(15L, Long.getLong("lc2h.plannerBatch.flushDelayMs", 40L));
    private static final int MAX_PENDING_TASKS = Math.max(256, Integer.getInteger("lc2h.planner.max_pending", 2048));
    private static final ConcurrentHashMap<PlannerBatchKey, PendingBatch> BATCHES = new ConcurrentHashMap<>();
    private static final AtomicBoolean DEFERRED_FLUSH_SCHEDULED = new AtomicBoolean(false);
    private static final AtomicInteger PENDING_TASKS = new AtomicInteger();
    private static final AtomicIntegerArray PENDING_BY_KIND = new AtomicIntegerArray(PlannerTaskKind.values().length);

    private PlannerBatchQueue() {
    }

    public record PlannerBatchStats(int batchCount, int pendingTasks, EnumMap<PlannerTaskKind, Integer> pendingByKind) {
        public static PlannerBatchStats empty() {
            return new PlannerBatchStats(0, 0, new EnumMap<>(PlannerTaskKind.class));
        }
    }

    public static PlannerBatchStats snapshotStats() {
        EnumMap<PlannerTaskKind, Integer> byKind = new EnumMap<>(PlannerTaskKind.class);
        for (PlannerTaskKind kind : PlannerTaskKind.values()) {
            int count = PENDING_BY_KIND.get(kind.ordinal());
            if (count > 0) {
                byKind.put(kind, count);
            }
        }
        return new PlannerBatchStats(BATCHES.size(), Math.max(0, PENDING_TASKS.get()), byKind);
    }

    public static void enqueue(IDimensionInfo provider, ChunkCoord coord, PlannerTaskKind kind, Runnable action) {
        if (provider == null || kind == null || action == null) {
            return;
        }
        if (shouldCullQueue() && coord != null && coord.dimension() != null) {
            if (!ChunkPriorityManager.isChunkWithinViewDistance(coord.dimension().location(), coord.chunkX(), coord.chunkZ())) {
                ViewCullingStats.recordPlannerQueue(1);
                return;
            }
        }
        int pending = PENDING_TASKS.incrementAndGet();
        if (pending > MAX_PENDING_TASKS) {
            PENDING_TASKS.decrementAndGet();
            RateLimitedLogger.warn(
                "lc2h-planner-queue-full",
                "LC2H planner queue full ({} >= {}). Dropping {} at {}",
                pending,
                MAX_PENDING_TASKS,
                kind.displayName(),
                describe(coord)
            );
            return;
        }
        PENDING_BY_KIND.incrementAndGet(kind.ordinal());
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
        PENDING_TASKS.set(0);
        for (PlannerTaskKind kind : PlannerTaskKind.values()) {
            PENDING_BY_KIND.set(kind.ordinal(), 0);
        }
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

        List<PlannerExecutable> filtered = filterBatchForView(batch);
        if (filtered.isEmpty()) {
            return;
        }

        if (LC2H.LOGGER.isDebugEnabled()) {
            EnumMap<PlannerTaskKind, Integer> kindCounts = new EnumMap<>(PlannerTaskKind.class);
            for (PlannerExecutable exec : filtered) {
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
        List<Runnable> runners = new ArrayList<>(filtered.size());
        for (PlannerExecutable exec : filtered) {
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
        private final ConcurrentLinkedQueue<PlannerExecutable> tasks = new ConcurrentLinkedQueue<>();
        private final AtomicInteger size = new AtomicInteger();
        private final AtomicBoolean draining = new AtomicBoolean(false);

        List<PlannerExecutable> add(PlannerExecutable exec) {
            Objects.requireNonNull(exec, "exec");
            tasks.add(exec);
            int threshold = AdaptiveBatchController.plannerFlushThreshold();
            int current = size.incrementAndGet();
            if (current >= threshold && draining.compareAndSet(false, true)) {
                return drainAllLocked();
            }
            return List.of();
        }

        List<PlannerExecutable> drainAll() {
            if (!draining.compareAndSet(false, true)) {
                return List.of();
            }
            return drainAllLocked();
        }

        List<PlannerExecutable> drainKind(PlannerTaskKind kind) {
            if (!draining.compareAndSet(false, true)) {
                return List.of();
            }
            List<PlannerExecutable> drained = new ArrayList<>();
            List<PlannerExecutable> keep = new ArrayList<>();
            PlannerExecutable next;
            int removed = 0;
            while ((next = tasks.poll()) != null) {
                removed++;
                if (next.kind == kind) {
                    drained.add(next);
                } else {
                    keep.add(next);
                }
            }
            if (removed > 0) {
                size.addAndGet(-removed);
                adjustPending(-removed);
            }
            if (!drained.isEmpty()) {
                adjustPendingByKind(kind, -drained.size());
            }
            for (PlannerExecutable exec : keep) {
                tasks.add(exec);
                size.incrementAndGet();
            }
            if (!keep.isEmpty()) {
                adjustPending(keep.size());
            }
            draining.set(false);
            return drained;
        }

        boolean isEmpty() {
            return size.get() == 0;
        }

        int snapshotInto(EnumMap<PlannerTaskKind, Integer> into) {
            int count = 0;
            for (PlannerExecutable exec : tasks) {
                if (exec == null || exec.kind == null) {
                    continue;
                }
                into.merge(exec.kind, 1, Integer::sum);
                count++;
            }
            return count;
        }

        private List<PlannerExecutable> drainAllLocked() {
            List<PlannerExecutable> drained = new ArrayList<>();
            PlannerExecutable next;
            int removed = 0;
            int[] kindCounts = new int[PlannerTaskKind.values().length];
            while ((next = tasks.poll()) != null) {
                drained.add(next);
                removed++;
                if (next.kind != null) {
                    kindCounts[next.kind.ordinal()]++;
                }
            }
            if (removed > 0) {
                size.addAndGet(-removed);
                adjustPending(-removed);
            }
            if (removed > 0) {
                PlannerTaskKind[] kinds = PlannerTaskKind.values();
                for (int i = 0; i < kindCounts.length; i++) {
                    int count = kindCounts[i];
                    if (count > 0) {
                        adjustPendingByKind(kinds[i], -count);
                    }
                }
            }
            draining.set(false);
            return drained;
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

    private static void adjustPending(int delta) {
        int updated = PENDING_TASKS.addAndGet(delta);
        if (updated < 0) {
            PENDING_TASKS.set(0);
        }
    }

    private static void adjustPendingByKind(PlannerTaskKind kind, int delta) {
        if (kind == null) {
            return;
        }
        int idx = kind.ordinal();
        int updated = PENDING_BY_KIND.addAndGet(idx, delta);
        if (updated < 0) {
            PENDING_BY_KIND.set(idx, 0);
        }
    }

    private static boolean shouldCullQueue() {
        MinecraftServer server = ServerRescheduler.getServer();
        if (server == null) {
            return false;
        }
        try {
            if (server.getPlayerList() == null || server.getPlayerList().getPlayerCount() == 0) {
                return false;
            }
        } catch (Throwable ignored) {
            return false;
        }
        if (SpawnSearchScheduler.isSearchActive(server)) {
            return false;
        }
        return true;
    }

    private static List<PlannerExecutable> filterBatchForView(List<PlannerExecutable> batch) {
        if (!shouldCullQueue() || batch.isEmpty()) {
            return batch;
        }
        List<PlannerExecutable> kept = new ArrayList<>(batch.size());
        for (PlannerExecutable exec : batch) {
            if (exec == null || exec.coord == null || exec.coord.dimension() == null) {
                kept.add(exec);
                continue;
            }
            if (ChunkPriorityManager.isChunkWithinViewDistance(exec.coord.dimension().location(), exec.coord.chunkX(), exec.coord.chunkZ())) {
                kept.add(exec);
            } else {
                ViewCullingStats.recordPlannerBatch(1);
            }
        }
        return kept;
    }
}
