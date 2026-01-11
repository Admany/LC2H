package org.admany.lc2h.worldgen.async.planner;

import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.IDimensionInfo;
import org.admany.lc2h.LC2H;
import org.admany.lc2h.async.AsyncManager;
import org.admany.lc2h.data.cache.CacheBudgetManager;
import org.admany.lc2h.parallel.AdaptiveBatchController;
import org.admany.lc2h.parallel.AdaptiveConcurrencyLimiter;
import org.admany.lc2h.util.server.ServerTickLoad;
import org.admany.lc2h.worldgen.async.warmup.AsyncChunkWarmup;
import org.admany.lc2h.worldgen.gpu.GPUMemoryManager;
import org.admany.quantified.core.common.parallel.config.ParallelConfig;
import org.admany.quantified.core.common.parallel.metrics.ParallelMetrics;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ThreadLocalRandom;

public final class AsyncBuildingInfoPlanner {

    private static final ConcurrentHashMap<ChunkCoord, Object> BUILDING_INFO_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<ChunkCoord, Long> BUILDING_INFO_CACHE_TS = new ConcurrentHashMap<>();
    private static final CacheBudgetManager.CacheGroup BUILDING_INFO_BUDGET =
        CacheBudgetManager.register("lc2h_buildinginfo", 8192, 512,
            key -> BUILDING_INFO_CACHE.remove(key) != null);
    private static final long BUILDING_INFO_CACHE_TTL_MS = Math.max(30_000L,
        Long.getLong("lc2h.buildinginfo.cacheTtlMs", TimeUnit.MINUTES.toMillis(20)));
    private static final int BUILDING_INFO_CACHE_PRUNE_SCAN = Math.max(128,
        Integer.getInteger("lc2h.buildinginfo.cachePruneScan", 512));
    private static final int BUILDING_INFO_CACHE_PRUNE_EVERY = Math.max(64,
        Integer.getInteger("lc2h.buildinginfo.cachePruneEvery", 128));
    private static final AtomicInteger BUILDING_INFO_PRUNE_COUNTER = new AtomicInteger(0);
    private static final long FAILURE_RETRY_DELAY_NS = TimeUnit.SECONDS.toNanos(5);
    private static final long LIMITER_RETRY_BASE_MS = Math.max(2L, Long.getLong("lc2h.buildinginfo.limiterRetryMs", 8L));
    private static final int LIMITER_RETRY_JITTER_MS = Math.max(0, Integer.getInteger("lc2h.buildinginfo.limiterRetryJitterMs", 4));

    private static final class InFlightMarker {
        private InFlightMarker() {
        }
    }

    private static final class FailureMarker {
        private final long nextRetryNs;

        private FailureMarker(long nextRetryNs) {
            this.nextRetryNs = nextRetryNs;
        }

        private boolean canRetry(long nowNs) {
            return nowNs >= nextRetryNs;
        }
    }
    private static final ThreadLocal<Integer> INTERNAL_DEPTH = ThreadLocal.withInitial(() -> 0);

    private static final int BUILDING_INFO_OVERRIDE = Integer.getInteger("lc2h.buildinginfo.parallelism", -1);
    private static final int BUILDING_INFO_MAX = Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors()));
    private static final AdaptiveConcurrencyLimiter LIMITER = new AdaptiveConcurrencyLimiter(2, 1, BUILDING_INFO_MAX);

    private static final AtomicLong EWMA_NANOS_PER_TASK = new AtomicLong(0L);
    private static volatile long lastTuneNs = 0L;

    public static final ConcurrentHashMap<ChunkCoord, float[]> GPU_DATA_CACHE = new ConcurrentHashMap<>();
    private static final int SPAWN_PREFETCH_LIMIT = Math.max(32,
        Integer.getInteger("lc2h.spawnsearch.prefetchLimit", 256));
    private static final ConcurrentHashMap<ChunkCoord, Boolean> SPAWN_PREFETCH = new ConcurrentHashMap<>();
    private static final AtomicInteger SPAWN_PREFETCH_COUNT = new AtomicInteger();

    private AsyncBuildingInfoPlanner() {
    }

    public static boolean isInternalComputation() {
        return INTERNAL_DEPTH.get() > 0;
    }

    public static void preSchedule(IDimensionInfo provider, ChunkCoord coord) {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(coord, "coord");

        long now = System.currentTimeMillis();
        Object existing = getCachedEntry(coord, now);
        if (existing != null) {
            if (existing instanceof FailureMarker fm) {
                long nowNs = System.nanoTime();
                if (fm.canRetry(nowNs)) {
                    removeCachedEntry(coord, existing);
                } else {
                    return;
                }
            } else if (existing instanceof InFlightMarker) {
                return;
            } else {
                return;
            }
        }

        // Reserve this coord so we don't enqueue duplicate warmup tasks.
        if (BUILDING_INFO_CACHE.putIfAbsent(coord, new InFlightMarker()) != null) {
            return;
        }
        BUILDING_INFO_CACHE_TS.put(coord, now);
        CacheBudgetManager.recordPut(BUILDING_INFO_BUDGET, coord, 64L, true);
        maybePrune(now);

        boolean debugLogging = AsyncChunkWarmup.isWarmupDebugLoggingEnabled();

        if (GPUMemoryManager.getGPUData(coord, GPU_DATA_CACHE) != null) {
            if (debugLogging) {
                LC2H.LOGGER.debug("Used GPU data for building info in {}", coord);
            }
            return;
        }

        if (debugLogging) {
            LC2H.LOGGER.debug("Starting preSchedule for {}", coord);
        }
        long startTime = System.nanoTime();

        PlannerBatchQueue.enqueue(provider, coord, PlannerTaskKind.BUILDING_INFO,
            () -> runBuildingInfo(provider, coord, debugLogging, startTime, false));
    }

    public static void preSchedulePriority(IDimensionInfo provider, ChunkCoord coord) {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(coord, "coord");

        long now = System.currentTimeMillis();
        Object existing = getCachedEntry(coord, now);
        if (existing != null) {
            if (existing instanceof FailureMarker fm) {
                long nowNs = System.nanoTime();
                if (fm.canRetry(nowNs)) {
                    removeCachedEntry(coord, existing);
                } else {
                    return;
                }
            } else if (existing instanceof InFlightMarker) {
                return;
            } else {
                return;
            }
        }

        if (SPAWN_PREFETCH_COUNT.get() >= SPAWN_PREFETCH_LIMIT) {
            return;
        }
        if (SPAWN_PREFETCH.putIfAbsent(coord, Boolean.TRUE) != null) {
            return;
        }
        SPAWN_PREFETCH_COUNT.incrementAndGet();

        // Reserve this coord so we don't enqueue duplicate warmup tasks.
        if (BUILDING_INFO_CACHE.putIfAbsent(coord, new InFlightMarker()) != null) {
            SPAWN_PREFETCH.remove(coord);
            SPAWN_PREFETCH_COUNT.decrementAndGet();
            return;
        }
        BUILDING_INFO_CACHE_TS.put(coord, now);
        CacheBudgetManager.recordPut(BUILDING_INFO_BUDGET, coord, 64L, true);
        maybePrune(now);

        if (GPUMemoryManager.getGPUData(coord, GPU_DATA_CACHE) != null) {
            SPAWN_PREFETCH.remove(coord);
            SPAWN_PREFETCH_COUNT.decrementAndGet();
            return;
        }

        long startTime = System.nanoTime();
        AsyncManager.submitTask("spawn-buildinginfo", () -> runBuildingInfo(provider, coord, false, startTime, true),
            null, org.admany.lc2h.async.Priority.HIGH)
            .whenComplete((ignored, throwable) -> {
                SPAWN_PREFETCH.remove(coord);
                SPAWN_PREFETCH_COUNT.decrementAndGet();
            });
    }

    public static Object getIfReady(ChunkCoord coord) {
        long now = System.currentTimeMillis();
        Object cached = getCachedEntry(coord, now);
        if (cached == null) {
            return null;
        }
        if (cached instanceof FailureMarker fm) {
            long nowNs = System.nanoTime();
            if (fm.canRetry(nowNs)) {
                removeCachedEntry(coord, cached);
            }
            return null;
        }
        if (cached instanceof InFlightMarker) {
            return null;
        }
        if (cached instanceof mcjty.lostcities.worldgen.lost.BuildingInfo) {
            touchTimestamp(coord, now);
            return cached;
        }
        // Defensive: don't ever expose sentinel values to callers.
        return null;
    }

    public static void syncWarmup(IDimensionInfo provider, ChunkCoord coord) {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(coord, "coord");

        long now = System.currentTimeMillis();
        Object existing = getCachedEntry(coord, now);
        if (existing != null) {
            if (existing instanceof FailureMarker fm) {
                long nowNs = System.nanoTime();
                if (fm.canRetry(nowNs)) {
                    removeCachedEntry(coord, existing);
                } else {
                    return;
                }
            } else if (existing instanceof InFlightMarker) {
                return;
            } else {
                return;
            }
        }

        if (GPUMemoryManager.getGPUData(coord, GPU_DATA_CACHE) != null) {
            return;
        }

        ensureMultiChunkReady(provider, coord);
        tuneIfNeeded();

        AdaptiveConcurrencyLimiter.Token token = LIMITER.tryEnter();
        if (token == null) {
            preSchedule(provider, coord);
            return;
        }

        // Reserve while we compute synchronously.
        if (BUILDING_INFO_CACHE.putIfAbsent(coord, new InFlightMarker()) != null) {
            token.close();
            return;
        }
        BUILDING_INFO_CACHE_TS.put(coord, now);
        CacheBudgetManager.recordPut(BUILDING_INFO_BUDGET, coord, 64L, true);
        maybePrune(now);

        long startNs = System.nanoTime();
        try {
            INTERNAL_DEPTH.set(INTERNAL_DEPTH.get() + 1);
            mcjty.lostcities.worldgen.lost.BuildingInfo.getChunkCharacteristics(coord, provider);
            Object buildingInfo = mcjty.lostcities.worldgen.lost.BuildingInfo.getBuildingInfo(coord, provider);
            acceptBuildingInfoResult(coord, buildingInfo, System.currentTimeMillis());
        } catch (Throwable t) {
            boolean debugLogging = AsyncChunkWarmup.isWarmupDebugLoggingEnabled()
                || org.admany.lc2h.logging.config.ConfigManager.ENABLE_DEBUG_LOGGING;
            if (debugLogging) {
                LC2H.LOGGER.debug("Sync building info warmup failed for {}", coord, t);
            }
            acceptBuildingInfoResult(coord, new FailureMarker(System.nanoTime() + FAILURE_RETRY_DELAY_NS), System.currentTimeMillis());
        } finally {
            INTERNAL_DEPTH.set(INTERNAL_DEPTH.get() - 1);
            recordTiming(System.nanoTime() - startNs);
            token.close();
        }
    }

    public static void flushPendingBuildingBatches() {
        PlannerBatchQueue.flushKind(PlannerTaskKind.BUILDING_INFO);
    }

    /**
     * Invalidate warmup markers for a multichunk area.
     *
     * LC2H may pre-warm BuildingInfo/characteristics before the corresponding MultiChunk (multibuilding
     * plan) is available. When the multichunk plan arrives, we must allow those chunks to be warmed
     * again so BuildingInfo caches can be rebuilt consistently.
     */
    public static void invalidateArea(ChunkCoord topLeft, int areaSize) {
        if (topLeft == null || areaSize <= 0) {
            return;
        }

        for (int dx = 0; dx < areaSize; dx++) {
            for (int dz = 0; dz < areaSize; dz++) {
                ChunkCoord key = new ChunkCoord(topLeft.dimension(), topLeft.chunkX() + dx, topLeft.chunkZ() + dz);
                if (BUILDING_INFO_CACHE.remove(key) != null) {
                    CacheBudgetManager.recordRemove(BUILDING_INFO_BUDGET, key);
                }
                BUILDING_INFO_CACHE_TS.remove(key);
                GPU_DATA_CACHE.remove(key);
            }
        }
    }

    public static void shutdown() {
        try {
            LC2H.LOGGER.info("AsyncBuildingInfoPlanner: Shutting down");

            PlannerBatchQueue.flushKind(PlannerTaskKind.BUILDING_INFO);

            BUILDING_INFO_CACHE.clear();
            BUILDING_INFO_CACHE_TS.clear();
            GPU_DATA_CACHE.clear();
            CacheBudgetManager.clear(BUILDING_INFO_BUDGET);

            LC2H.LOGGER.info("AsyncBuildingInfoPlanner: Shutdown complete");
        } catch (Exception e) {
            LC2H.LOGGER.error("AsyncBuildingInfoPlanner: Error during shutdown", e);
        }
    }

    private static void runBuildingInfo(IDimensionInfo provider,
                                        ChunkCoord coord,
                                        boolean debugLogging,
                                        long startTime,
                                        boolean highPriority) {
        ensureMultiChunkReady(provider, coord);
        tuneIfNeeded();
        AdaptiveConcurrencyLimiter.Token token = LIMITER.tryEnter();
        if (token == null) {
            rescheduleLimiterBlocked(provider, coord, debugLogging, startTime, highPriority);
            return;
        }
        long startNs = System.nanoTime();
        try {
            INTERNAL_DEPTH.set(INTERNAL_DEPTH.get() + 1);
            mcjty.lostcities.worldgen.lost.BuildingInfo.getChunkCharacteristics(coord, provider);
            Object buildingInfo = mcjty.lostcities.worldgen.lost.BuildingInfo.getBuildingInfo(coord, provider);
            acceptBuildingInfoResult(coord, buildingInfo, System.currentTimeMillis());
            if (debugLogging) {
                long endTime = System.nanoTime();
                LC2H.LOGGER.debug("Finished BuildingInfo compute for {} in {} ms", coord, (endTime - startTime) / 1_000_000);
            }
        } catch (Throwable t) {
            if (debugLogging) {
                LC2H.LOGGER.debug("Async building info warmup failed for {}", coord, t);
            }
            acceptBuildingInfoResult(coord, new FailureMarker(System.nanoTime() + FAILURE_RETRY_DELAY_NS), System.currentTimeMillis());
        } finally {
            INTERNAL_DEPTH.set(INTERNAL_DEPTH.get() - 1);
            recordTiming(System.nanoTime() - startNs);
            token.close();
        }
    }

    private static void rescheduleLimiterBlocked(IDimensionInfo provider,
                                                 ChunkCoord coord,
                                                 boolean debugLogging,
                                                 long startTime,
                                                 boolean highPriority) {
        if (coord == null || provider == null) {
            return;
        }
        Object cached = BUILDING_INFO_CACHE.get(coord);
        if (!(cached instanceof InFlightMarker)) {
            return;
        }
        long delayMs;
        if (highPriority) {
            delayMs = 1L;
        } else {
            delayMs = LIMITER_RETRY_BASE_MS;
            if (LIMITER_RETRY_JITTER_MS > 0) {
                delayMs += ThreadLocalRandom.current().nextInt(LIMITER_RETRY_JITTER_MS + 1);
            }
        }
        if (debugLogging) {
            LC2H.LOGGER.debug("BuildingInfo limiter saturated; retrying {} in {} ms", coord, delayMs);
        }
        if (highPriority) {
            AsyncManager.runLater("spawn-buildinginfo-retry",
                () -> runBuildingInfo(provider, coord, debugLogging, startTime, true),
                delayMs,
                org.admany.lc2h.async.Priority.HIGH);
            return;
        }
        AsyncManager.runLater("buildinginfo-limiter-retry",
            () -> PlannerBatchQueue.enqueue(provider, coord, PlannerTaskKind.BUILDING_INFO,
                () -> runBuildingInfo(provider, coord, debugLogging, startTime, false)),
            delayMs,
            org.admany.lc2h.async.Priority.LOW);
    }

    private static void acceptBuildingInfoResult(ChunkCoord coord, Object value, long nowMs) {
        if (coord == null) {
            return;
        }
        Runnable applier = () -> putCachedEntry(coord, value, nowMs);
        if ("Server thread".equals(Thread.currentThread().getName())) {
            applier.run();
        } else {
            AsyncManager.syncToMain(applier);
        }
    }

    private static void ensureMultiChunkReady(IDimensionInfo provider, ChunkCoord coord) {
        if (provider == null || coord == null) {
            return;
        }
        if (AsyncMultiChunkPlanner.isInternalComputation()) {
            return;
        }
        try {
            AsyncMultiChunkPlanner.syncWarmup(provider, coord);
        } catch (Throwable ignored) {
        }
    }

    private static void recordTiming(long elapsedNs) {
        if (elapsedNs <= 0L) {
            return;
        }
        while (true) {
            long old = EWMA_NANOS_PER_TASK.get();
            long updated = old == 0L ? elapsedNs : (old - (old >> 3)) + (elapsedNs >> 3);
            if (EWMA_NANOS_PER_TASK.compareAndSet(old, updated)) {
                return;
            }
        }
    }

    private static void tuneIfNeeded() {
        if (BUILDING_INFO_OVERRIDE > 0) {
            LIMITER.setLimit(BUILDING_INFO_OVERRIDE);
            return;
        }

        long now = System.nanoTime();
        if ((now - lastTuneNs) < TimeUnit.MILLISECONDS.toNanos(250)) {
            return;
        }
        lastTuneNs = now;

        int desired = 2;
        int cpu = Runtime.getRuntime().availableProcessors();
        int max = Math.max(1, Math.min(BUILDING_INFO_MAX, Math.max(2, cpu - 1)));

        long ewma = EWMA_NANOS_PER_TASK.get();
        long ewmaMs = ewma <= 0L ? 0L : (ewma / 1_000_000L);

        double tickMs = ServerTickLoad.getSmoothedTickMs();
        if (tickMs >= 45.0D) {
            desired = 1;
        } else if (tickMs >= 35.0D) {
            desired = 2;
        } else {
            desired = 3;
        }

        if (ewmaMs >= 18L) {
            desired = Math.min(desired, 1);
        } else if (ewmaMs >= 10L) {
            desired = Math.min(desired, 2);
        }

        try {
            ParallelMetrics.Snapshot snapshot = ParallelMetrics.snapshot();
            long activeSlices = snapshot.modActiveSlices().values().stream().mapToLong(Long::longValue).sum();
            double load = Math.min(1.0, activeSlices / (double) ParallelConfig.queueLimit());
            if (load >= 0.85) {
                desired = Math.max(1, desired - 1);
            } else if (load <= 0.35) {
                desired = Math.min(max, desired + 1);
            }
        } catch (Throwable ignored) {
        }

        try {
            PlannerBatchQueue.PlannerBatchStats stats = PlannerBatchQueue.snapshotStats();
            Integer pending = stats.pendingByKind().get(PlannerTaskKind.BUILDING_INFO);
            int pendingBuilding = pending == null ? 0 : pending;
            int flushThreshold = AdaptiveBatchController.plannerFlushThreshold();
            if (pendingBuilding >= flushThreshold) {
                desired = Math.min(max, desired + 1);
            } else if (pendingBuilding <= (flushThreshold / 4)) {
                desired = Math.max(1, desired - 1);
            }
        } catch (Throwable ignored) {
        }

        if (desired > max) {
            desired = max;
        }
        if (desired < 1) {
            desired = 1;
        }

        LIMITER.setLimit(desired);
    }

    public static void pruneExpiredEntries() {
        pruneExpiredEntries(System.currentTimeMillis(), BUILDING_INFO_CACHE_PRUNE_SCAN);
    }

    private static Object getCachedEntry(ChunkCoord coord, long nowMs) {
        Object cached = BUILDING_INFO_CACHE.get(coord);
        if (cached == null) {
            return null;
        }
        if (!isFresh(coord, nowMs)) {
            removeCachedEntry(coord, cached);
            return null;
        }
        if (cached instanceof mcjty.lostcities.worldgen.lost.BuildingInfo) {
            CacheBudgetManager.recordAccess(BUILDING_INFO_BUDGET, coord);
        }
        return cached;
    }

    private static void putCachedEntry(ChunkCoord coord, Object value, long nowMs) {
        if (coord == null) {
            return;
        }
        if (value == null) {
            removeCachedEntry(coord, null);
            return;
        }
        Object prev = BUILDING_INFO_CACHE.put(coord, value);
        BUILDING_INFO_CACHE_TS.put(coord, nowMs);
        if (prev == null) {
            CacheBudgetManager.recordPut(BUILDING_INFO_BUDGET, coord, estimateEntryBytes(value), true);
        } else if (prev != value) {
            CacheBudgetManager.recordRemove(BUILDING_INFO_BUDGET, coord);
            CacheBudgetManager.recordPut(BUILDING_INFO_BUDGET, coord, estimateEntryBytes(value), true);
        } else {
            CacheBudgetManager.recordAccess(BUILDING_INFO_BUDGET, coord);
        }
        maybePrune(nowMs);
    }

    private static void touchTimestamp(ChunkCoord coord, long nowMs) {
        if (coord == null) {
            return;
        }
        if (BUILDING_INFO_CACHE_TS.containsKey(coord)) {
            BUILDING_INFO_CACHE_TS.put(coord, nowMs);
        }
    }

    private static boolean isFresh(ChunkCoord coord, long nowMs) {
        if (BUILDING_INFO_CACHE_TTL_MS <= 0L) {
            return true;
        }
        Long ts = BUILDING_INFO_CACHE_TS.get(coord);
        return ts != null && (nowMs - ts) <= BUILDING_INFO_CACHE_TTL_MS;
    }

    private static void maybePrune(long nowMs) {
        if (BUILDING_INFO_CACHE_TTL_MS <= 0L) {
            return;
        }
        int count = BUILDING_INFO_PRUNE_COUNTER.incrementAndGet();
        if (count >= BUILDING_INFO_CACHE_PRUNE_EVERY) {
            BUILDING_INFO_PRUNE_COUNTER.set(0);
            pruneExpiredEntries(nowMs, BUILDING_INFO_CACHE_PRUNE_SCAN);
        }
    }

    private static void pruneExpiredEntries(long nowMs, int maxScan) {
        if (BUILDING_INFO_CACHE_TS.isEmpty() || BUILDING_INFO_CACHE_TTL_MS <= 0L) {
            return;
        }
        int scanned = 0;
        for (Map.Entry<ChunkCoord, Long> entry : BUILDING_INFO_CACHE_TS.entrySet()) {
            if (maxScan > 0 && scanned >= maxScan) {
                break;
            }
            scanned++;
            Long ts = entry.getValue();
            if (ts != null && (nowMs - ts) > BUILDING_INFO_CACHE_TTL_MS) {
                ChunkCoord key = entry.getKey();
                BUILDING_INFO_CACHE_TS.remove(key, ts);
                if (BUILDING_INFO_CACHE.remove(key) != null) {
                    CacheBudgetManager.recordRemove(BUILDING_INFO_BUDGET, key);
                }
            }
        }
    }

    private static void removeCachedEntry(ChunkCoord coord, Object expected) {
        boolean removed = expected == null ? BUILDING_INFO_CACHE.remove(coord) != null
            : BUILDING_INFO_CACHE.remove(coord, expected);
        if (removed) {
            CacheBudgetManager.recordRemove(BUILDING_INFO_BUDGET, coord);
        }
        BUILDING_INFO_CACHE_TS.remove(coord);
    }

    private static long estimateEntryBytes(Object value) {
        if (value == null) {
            return 64L;
        }
        if (value instanceof mcjty.lostcities.worldgen.lost.BuildingInfo) {
            return 8192L;
        }
        return 128L;
    }
}
