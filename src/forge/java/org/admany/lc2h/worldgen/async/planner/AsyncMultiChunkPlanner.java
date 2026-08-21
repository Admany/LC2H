package org.admany.lc2h.worldgen.async.planner;

import mcjty.lostcities.config.LostCityProfile;
import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.IDimensionInfo;
import mcjty.lostcities.worldgen.lost.BuildingInfo;
import mcjty.lostcities.worldgen.lost.City;
import mcjty.lostcities.worldgen.lost.MultiChunk;
import mcjty.lostcities.worldgen.lost.Railway;
import org.admany.lc2h.LC2H;
import org.admany.lc2h.data.cache.Lc2hCacheKeys;
import org.admany.lc2h.data.cache.LostCitiesCacheBudgetManager;
import org.admany.lc2h.client.frustum.ChunkPriorityManager;
import org.admany.lc2h.mixin.accessor.lostcities.MultiChunkAccessor;
import org.admany.lc2h.mixin.accessor.lostcities.MultiChunkInvoker;
import org.admany.lc2h.concurrency.parallel.AdaptiveBatchController;
import org.admany.lc2h.concurrency.parallel.AdaptiveConcurrencyLimiter;
import org.admany.lc2h.concurrency.parallel.ParallelWorkOptions;
import org.admany.lc2h.concurrency.parallel.ParallelWorkQueue;
import org.admany.lc2h.util.lostcities.MultiChunkCacheAccess;
import org.admany.lc2h.util.server.ServerRescheduler;
import org.admany.lc2h.util.server.ServerTickLoad;
import org.admany.lc2h.worldgen.async.snapshot.MultiChunkSnapshot;
import org.admany.lc2h.worldgen.gpu.GPUMemoryManager;
import org.admany.lc2h.worldgen.gpu.CityCenterGpuCache;
import org.admany.lc2h.worldgen.async.warmup.AsyncChunkWarmup;
import org.admany.lc2h.dev.diagnostics.ChunkGenTracker;
import org.admany.lc2h.dev.diagnostics.Lc2hTimingRegistry;
import org.admany.lc2h.dev.diagnostics.ViewCullingStats;
import org.admany.lc2h.worldgen.lostcities.FastMultiChunkPlanner;
import org.admany.lc2h.worldgen.lostcities.MultiBuildingFootprintRegistry;
import org.admany.lc2h.worldgen.lostcities.MultiChunkBoundaryRegistry;
import org.admany.lc2h.worldgen.dag.LostCityDagScheduler;
import org.admany.lc2h.worldgen.kernel.JavaScalarLostCityKernel;
import org.admany.lc2h.worldgen.kernel.LostCityKernelSignature;
import org.admany.lc2h.worldgen.kernel.LostCityKernelStage;
import org.admany.lc2h.worldgen.scope.WorldGenScope;
import org.admany.quantified.core.common.util.TaskScheduler;
import org.admany.quantified.core.common.parallel.config.ParallelConfig;
import org.admany.quantified.core.common.parallel.metrics.ParallelMetrics;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;

public final class AsyncMultiChunkPlanner {

    /*
     * Native BuildingInfo holds its dimension lock while it can enter Railway.
     * A background MultiChunk calculation can take Railway first and then enter
     * BuildingInfo, which is the opposite order and deadlocks worldgen. The
     * concurrent BuildingInfo replacement owns that paired execution model.
     * Keep native BuildingInfo free of asynchronous MultiChunk work.
     */
    private static final boolean ASYNC_PLANNER_ENABLED =
        Boolean.parseBoolean(System.getProperty("lc2h.concurrentBuildingInfo", "true"));

    private record PlannerKey(String scope, ResourceKey<net.minecraft.world.level.Level> dimension, int areaSize, int multiX, int multiZ) {
        private PlannerKey {
            scope = scope == null ? "unknown" : scope;
            areaSize = Math.max(1, areaSize);
        }

        private ChunkCoord multiCoord() {
            return new ChunkCoord(dimension, multiX, multiZ);
        }
    }

    private static final ConcurrentHashMap<PlannerKey, CompletableFuture<MultiChunk>> PLANNED = new ConcurrentHashMap<>();
    private static final ThreadLocal<Integer> INTERNAL_CALL_DEPTH = ThreadLocal.withInitial(() -> 0);
    private static final ThreadLocal<Integer> WARMUP_CALL_DEPTH = ThreadLocal.withInitial(() -> 0);
    private static final ConcurrentHashMap<PlannerKey, Boolean> WARM_BUILDING_INFO_SUBMITTED = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<PlannerKey, Long> WARM_BUILDING_INFO_DONE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<PlannerKey, WarmupPlan> WARM_PLANS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<PlannerKey, Boolean> INTEGRATION_HOOKED = new ConcurrentHashMap<>();
    private static final boolean WARM_BUILDING_INFO_ENABLED =
        Boolean.parseBoolean(System.getProperty("lc2h.multichunk.warmBuildingInfo.enabled", "false"));
    private static final long WARM_BUILDING_INFO_TTL_MS = Math.max(30_000L,
        Long.getLong("lc2h.multichunk.warmup_ttl_ms", java.util.concurrent.TimeUnit.MINUTES.toMillis(10)));
    private static final Semaphore WARM_SEMAPHORE = new Semaphore(2);
    private static final long WARM_RETRY_BASE_MS = Math.max(5L, Long.getLong("lc2h.multichunk.warmupRetryMs", 50L));
    private static final int WARM_RETRY_JITTER_MS = Math.max(0, Integer.getInteger("lc2h.multichunk.warmupRetryJitterMs", 25));
    private static final int WARM_RETRY_MAX_ATTEMPTS = Math.max(1,
        Integer.getInteger("lc2h.multichunk.warmupRetryMaxAttempts", 32));
    private static final long WARM_RETRY_TTL_MS = Math.max(1_000L,
        Long.getLong("lc2h.multichunk.warmupRetryTtlMs", TimeUnit.MINUTES.toMillis(2)));
    private static final long WARM_RETRY_DRAIN_BUDGET_NS = TimeUnit.MICROSECONDS.toNanos(
        Math.max(50L, Long.getLong("lc2h.multichunk.warmupRetryBudgetUs", 500L)));
    private static final int WARM_RETRY_DRAIN_MAX = Math.max(8,
        Integer.getInteger("lc2h.multichunk.warmupRetryDrainMax", 128));
    private static final ConcurrentHashMap<PlannerKey, WarmRetryEntry> WARM_RETRY_ENTRIES = new ConcurrentHashMap<>();
    private static final ConcurrentLinkedQueue<PlannerKey> WARM_RETRY_QUEUE = new ConcurrentLinkedQueue<>();
    private static final AtomicLong WARM_RETRY_TOTAL = new AtomicLong(0L);
    private static final AtomicLong LAST_WARM_RETRY_MS = new AtomicLong(0L);

    private static final int RECENT_MULTI_MAX = Math.max(64, Integer.getInteger("lc2h.multichunk.recentMax", 192));
    private static final long RECENT_MULTI_TTL_MS = Math.max(TimeUnit.SECONDS.toMillis(30),
        Long.getLong("lc2h.multichunk.recentTtlMs", TimeUnit.MINUTES.toMillis(2)));
    private static final boolean RECENT_MULTI_ENABLED =
        Boolean.parseBoolean(System.getProperty("lc2h.multichunk.recentSnapshot.enabled", "true"));
    private static final ConcurrentHashMap<PlannerKey, RecentMulti> RECENT_MULTI = new ConcurrentHashMap<>();
    private static final ConcurrentLinkedQueue<PlannerKey> RECENT_MULTI_ORDER = new ConcurrentLinkedQueue<>();

    private static final class RecentMulti {
        private final MultiChunk multiChunk;
        private final long timestampMs;

        private RecentMulti(MultiChunk multiChunk, long timestampMs) {
            this.multiChunk = multiChunk;
            this.timestampMs = timestampMs;
        }
    }

    private static final class WarmupPlan {
        private final ChunkCoord[] coords;
        private final AtomicInteger cursor = new AtomicInteger(0);

        private WarmupPlan(ChunkCoord topLeft, int areaSize) {
            int total = areaSize * areaSize;
            this.coords = new ChunkCoord[total];
            int center = (areaSize - 1) / 2;
            int index = 0;
            int maxRing = Math.max(center, areaSize - 1 - center);
            for (int ring = 0; ring <= maxRing; ring++) {
                for (int ox = -ring; ox <= ring; ox++) {
                    for (int oz = -ring; oz <= ring; oz++) {
                        if (Math.max(Math.abs(ox), Math.abs(oz)) != ring) {
                            continue;
                        }
                        int x = center + ox;
                        int z = center + oz;
                        if (x < 0 || x >= areaSize || z < 0 || z >= areaSize) {
                            continue;
                        }
                        if (index >= total) {
                            break;
                        }
                        coords[index++] = new ChunkCoord(topLeft.dimension(), topLeft.chunkX() + x, topLeft.chunkZ() + z);
                    }
                }
            }
        }

        private int nextIndex() {
            return cursor.getAndIncrement();
        }

        private int total() {
            return coords.length;
        }

        private ChunkCoord coordAt(int index) {
            if (index < 0 || index >= coords.length) {
                return null;
            }
            return coords[index];
        }
    }

    private static final class WarmRetryEntry {
        private final IDimensionInfo provider;
        private final PlannerKey key;
        private final ChunkCoord multiCoord;
        private final long firstEnqueuedMs;
        private volatile long nextRetryMs;
        private final AtomicInteger attempts = new AtomicInteger(0);

        private WarmRetryEntry(IDimensionInfo provider, PlannerKey key, ChunkCoord multiCoord, long firstEnqueuedMs, long nextRetryMs) {
            this.provider = provider;
            this.key = key;
            this.multiCoord = multiCoord;
            this.firstEnqueuedMs = firstEnqueuedMs;
            this.nextRetryMs = nextRetryMs;
        }
    }

    private static final int MULTICHUNK_PARALLELISM_OVERRIDE = Integer.getInteger("lc2h.multichunk.parallelism", -1);
    private static final int MULTICHUNK_MAX = Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors()));
    /**
     * The pending queue deliberately coalesces region plans, so keeping this
     * limiter at its old fixed two permits silently turned every 32-64 item
     * batch into a two-item batch. Start with a conservative useful width and
     * tune from server/load pressure before each queue drain.
     */
    private static final AdaptiveConcurrencyLimiter MULTICHUNK_LIMITER = new AdaptiveConcurrencyLimiter(
        Math.min(4, MULTICHUNK_MAX), 1, MULTICHUNK_MAX);
    private static final AtomicLong LAST_MULTICHUNK_TUNE_NS = new AtomicLong();
    private static final AtomicInteger LAST_MULTICHUNK_LIMIT = new AtomicInteger(Math.min(4, MULTICHUNK_MAX));
    private static final AtomicInteger MAX_MULTICHUNK_DRAIN = new AtomicInteger();
    private static final int MULTICHUNK_PRECOMPUTE_PARALLELISM = Math.max(1,
        Math.min(Integer.getInteger("lc2h.multichunk.precomputeParallelism", Math.max(2, MULTICHUNK_MAX)), MULTICHUNK_MAX));
    private static final java.util.concurrent.ExecutorService MULTICHUNK_PRECOMPUTE_POOL =
        MULTICHUNK_PRECOMPUTE_PARALLELISM > 1 ? new java.util.concurrent.ForkJoinPool(MULTICHUNK_PRECOMPUTE_PARALLELISM) : null;

    private static final int MULTICHUNK_MIN_RETAIN = Math.max(64,
        Integer.getInteger("lc2h.multichunk.cacheMinRetain", 192));
    private static final LostCitiesCacheBudgetManager.CacheGroup MULTICHUNK_BUDGET =
        LostCitiesCacheBudgetManager.register("lc_multichunk", 4096, MULTICHUNK_MIN_RETAIN, MultiChunkCacheAccess::remove);

    public static final ConcurrentHashMap<ChunkCoord, float[]> GPU_DATA_CACHE = new ConcurrentHashMap<>();

    private static final ConcurrentLinkedQueue<PendingEntry> PENDING = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger PENDING_SIZE = new AtomicInteger();
    private static final AtomicBoolean PENDING_DRAINING = new AtomicBoolean(false);
    private static final int MAX_PENDING = 128;
    private static final AtomicBoolean PENDING_FLUSH_SCHEDULED = new AtomicBoolean(false);
    private static final long PENDING_FLUSH_DELAY_MS = 15;
    private static final long PENDING_FLUSH_DELAY_LATENCY_SENSITIVE_MS = 15;
    private static final boolean MULTICHUNK_SLICE_CACHE_ENABLED =
        Boolean.parseBoolean(System.getProperty("lc2h.multichunk.sliceCache.enabled", "false"));
    private static final int MULTICHUNK_SMALL_BATCH_SERIAL_THRESHOLD = Math.max(1,
        Integer.getInteger("lc2h.multichunk.smallBatchSerialThreshold", 2));

    private static final LongAdder TELEMETRY_SCHEDULED = new LongAdder();
    private static final LongAdder TELEMETRY_PENDING_DRAINED = new LongAdder();
    private static final LongAdder TELEMETRY_PENDING_STALE = new LongAdder();
    private static final LongAdder TELEMETRY_PENDING_CACHE_HIT = new LongAdder();
    private static final LongAdder TELEMETRY_CACHE_HIT = new LongAdder();
    private static final LongAdder TELEMETRY_SYNC_FALLBACK = new LongAdder();
    private static final LongAdder TELEMETRY_SINGLE_LANE = new LongAdder();
    private static final LongAdder TELEMETRY_PARALLEL_BATCHES = new LongAdder();
    private static final LongAdder TELEMETRY_PARALLEL_TASKS = new LongAdder();
    private static final LongAdder TELEMETRY_KERNEL_BATCHES = new LongAdder();
    private static final LongAdder TELEMETRY_KERNEL_TASKS = new LongAdder();
    private static final LongAdder TELEMETRY_INTEGRATION_TASKS = new LongAdder();
    private static final LongAdder TELEMETRY_SNAPSHOT_ENCODED = new LongAdder();
    private static final LongAdder TELEMETRY_SNAPSHOT_MISSED = new LongAdder();
    private static final LongAdder TELEMETRY_PREPARED_CACHE_HIT = new LongAdder();
    private static final LongAdder TELEMETRY_PREPARED_FUTURE_HIT = new LongAdder();
    private static final LongAdder TELEMETRY_PREPARED_RECENT_HIT = new LongAdder();
    private static final LongAdder TELEMETRY_PREPARED_MISS = new LongAdder();
    private static final LongAdder TELEMETRY_NATIVE_FALLBACK = new LongAdder();
    private static final LongAdder TELEMETRY_NATIVE_CANCELLED_PENDING = new LongAdder();
    private static final LongAdder TELEMETRY_NATIVE_RACE_HIT = new LongAdder();

    private AsyncMultiChunkPlanner() {
    }

    public static boolean isWarmupInProgress() {
        return WARMUP_CALL_DEPTH.get() > 0;
    }

    public static MultiChunk tryConsumePrepared(IDimensionInfo provider, ChunkCoord coord) {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(coord, "coord");

        int areaSize = areaSize(provider);
        ChunkCoord multiCoord = toMultiCoord(coord, areaSize);
        PlannerKey plannerKey = plannerKey(provider, multiCoord, areaSize);
        Object cacheLock = MultiChunkCacheAccess.lock();
        synchronized (cacheLock) {
            MultiChunk existing = MultiChunkCacheAccess.get(multiCoord);
            if (existing != null) {
                TELEMETRY_PREPARED_CACHE_HIT.increment();
                LostCitiesCacheBudgetManager.recordAccess(MULTICHUNK_BUDGET, multiCoord);
                return existing;
            }
        }

        CompletableFuture<MultiChunk> future = PLANNED.get(plannerKey);
        if (future != null && future.isDone() && !future.isCompletedExceptionally() && !future.isCancelled()) {
            MultiChunk prepared = future.getNow(null);
            if (prepared != null) {
                TELEMETRY_PREPARED_FUTURE_HIT.increment();
                return integrateResult(provider, multiCoord, prepared);
            }
        }
        MultiChunk recent = loadRecentMulti(provider, multiCoord, areaSize);
        if (recent != null) {
            TELEMETRY_PREPARED_RECENT_HIT.increment();
            return integrateResult(provider, multiCoord, recent);
        }
        TELEMETRY_PREPARED_MISS.increment();
        return null;
    }

    /**
     * Claims a native synchronous calculation after a non-blocking prepared lookup missed.
     * The Lost Cities entry point is synchronized, so scheduling a new async calculation
     * from that entry point only duplicates the native calculation that must happen now.
     * Explicit look-ahead warmups still use {@link #ensureScheduled(IDimensionInfo, ChunkCoord)}.
     */
    public static MultiChunk claimSynchronousFallback(IDimensionInfo provider, ChunkCoord coord) {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(coord, "coord");

        int areaSize = areaSize(provider);
        ChunkCoord multiCoord = toMultiCoord(coord, areaSize);
        PlannerKey plannerKey = plannerKey(provider, multiCoord, areaSize);
        CompletableFuture<MultiChunk> future = PLANNED.get(plannerKey);
        if (future != null) {
            if (future.isDone() && !future.isCompletedExceptionally() && !future.isCancelled()) {
                MultiChunk prepared = future.getNow(null);
                if (prepared != null) {
                    TELEMETRY_NATIVE_RACE_HIT.increment();
                    return integrateResult(provider, multiCoord, prepared);
                }
            } else if (future.cancel(false)) {
                TELEMETRY_NATIVE_CANCELLED_PENDING.increment();
            }
            PLANNED.remove(plannerKey, future);
        }
        TELEMETRY_NATIVE_FALLBACK.increment();
        return null;
    }

    public static MultiChunk resolve(IDimensionInfo provider, ChunkCoord coord) {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(coord, "coord");

        int areaSize = areaSize(provider);
        ChunkCoord multiCoord = toMultiCoord(coord, areaSize);
        PlannerKey plannerKey = plannerKey(provider, multiCoord, areaSize);

        Object cacheLock = MultiChunkCacheAccess.lock();
        synchronized (cacheLock) {
            MultiChunk existing = MultiChunkCacheAccess.get(multiCoord);
            if (existing != null) {
                LostCitiesCacheBudgetManager.recordAccess(MULTICHUNK_BUDGET, multiCoord);
                return existing;
            }
        }
        if (!isInternalComputation()) {
            CompletableFuture<MultiChunk> future = PLANNED.get(plannerKey);
            try {
                if (future != null && !future.isCancelled() && !future.isCompletedExceptionally()) {
                    MultiChunk prepared = future.getNow(null);
                    if (prepared != null) {
                        return integrateResult(provider, multiCoord, prepared);
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        MultiChunk recent = loadRecentMulti(provider, multiCoord, areaSize);
        if (recent != null) {
            return integrateResult(provider, multiCoord, recent);
        }

        TELEMETRY_SYNC_FALLBACK.increment();
        MultiChunk computed = executeInternal(() -> computeMultiChunk(provider, areaSize, multiCoord));
        return integrateResult(provider, multiCoord, computed);
    }

    public static void ensureIntegrated(IDimensionInfo provider, ChunkCoord coord) {
        if (provider == null || coord == null || isInternalComputation()) {
            return;
        }

        int areaSize = areaSize(provider);
        ChunkCoord multiCoord = toMultiCoord(coord, areaSize);
        PlannerKey plannerKey = plannerKey(provider, multiCoord, areaSize);

        Object cacheLock = MultiChunkCacheAccess.lock();
        synchronized (cacheLock) {
            if (MultiChunkCacheAccess.contains(multiCoord)) {
                return;
            }
        }

        MultiChunk prepared = null;
        CompletableFuture<MultiChunk> future = PLANNED.get(plannerKey);
        if (future != null) {
            if (future.isDone() && !future.isCompletedExceptionally() && !future.isCancelled()) {
                prepared = future.getNow(null);
                if (prepared != null) {
                    integrateResult(provider, multiCoord, prepared, ServerRescheduler::runOnServer);
                }
                return;
            }
            if (INTEGRATION_HOOKED.putIfAbsent(plannerKey, Boolean.TRUE) == null) {
                future.whenComplete((result, error) -> {
                    INTEGRATION_HOOKED.remove(plannerKey);
                    if (error != null || result == null) {
                        return;
                    }
                    integrateResult(provider, multiCoord, result, ServerRescheduler::runOnServer);
                });
            }
            return;
        }

        MultiChunk recent = loadRecentMulti(provider, multiCoord, areaSize);
        if (recent != null) {
            integrateResult(provider, multiCoord, recent, ServerRescheduler::runOnServer);
            return;
        }

        // This method is called by normal BuildingInfo lookups too. Starting a
        // calculation here races the synchronized MultiChunk#getOrCreate call and
        // used to execute the same exact planner twice. Only explicit warmup APIs
        // are allowed to create a new plan.
    }

    public static void ensureScheduled(IDimensionInfo provider, ChunkCoord coord) {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(coord, "coord");

        if (!ASYNC_PLANNER_ENABLED) {
            return;
        }

        if (isInternalComputation()) {
            return;
        }

        int areaSize = areaSize(provider);
        ChunkCoord multiCoord = toMultiCoord(coord, areaSize);
        PlannerKey plannerKey = plannerKey(provider, multiCoord, areaSize);

        Object cacheLock = MultiChunkCacheAccess.lock();
        synchronized (cacheLock) {
            if (MultiChunkCacheAccess.contains(multiCoord)) {
                return;
            }
        }

        PLANNED.computeIfAbsent(plannerKey, key -> submitMultiChunkCompute(provider, areaSize, key));
    }

    public static void onSynchronousResult(IDimensionInfo provider, ChunkCoord coord, MultiChunk multiChunk) {
        if (provider == null || coord == null || multiChunk == null || isInternalComputation()) {
            return;
        }

        try {
            if (!org.admany.lc2h.util.server.ServerRescheduler.isServerAvailable()) {
                return;
            }
        } catch (Throwable ignored) {}

        int areaSize = areaSize(provider);
        ChunkCoord multiCoord = toMultiCoord(coord, areaSize);
        PlannerKey plannerKey = plannerKey(provider, multiCoord, areaSize);

        Object cacheLock = MultiChunkCacheAccess.lock();
        synchronized (cacheLock) {
            if (!MultiChunkCacheAccess.contains(multiCoord)) {
                MultiChunkCacheAccess.put(multiCoord, multiChunk);
                LostCitiesCacheBudgetManager.recordPut(MULTICHUNK_BUDGET, multiCoord, MULTICHUNK_BUDGET.defaultEntryBytes(), true);
            } else {
                LostCitiesCacheBudgetManager.recordAccess(MULTICHUNK_BUDGET, multiCoord);
            }
        }
        PLANNED.remove(plannerKey);

        try {
            ChunkCoord topLeft = new ChunkCoord(multiCoord.dimension(), multiCoord.chunkX() * areaSize, multiCoord.chunkZ() * areaSize);
            MultiBuildingFootprintRegistry.register(provider, multiCoord, multiChunk);
            org.admany.lc2h.util.lostcities.BuildingInfoCacheInvalidator.invalidateArea(topLeft, areaSize);
            AsyncBuildingInfoPlanner.invalidateArea(topLeft, areaSize);
            MultiChunkBoundaryRegistry.invalidateArea(topLeft, areaSize);
            MultiChunkBoundaryRegistry.register(provider, multiCoord, multiChunk);
        } catch (Throwable ignored) {
        }

        scheduleWarmBuildingInfo(provider, multiChunk, multiCoord);
    }

    public static void preSchedule(IDimensionInfo provider, ChunkCoord coord) {
        if (provider == null || coord == null) {
            return;
        }

        if (!ASYNC_PLANNER_ENABLED) {
            return;
        }

        if (!AsyncChunkWarmup.shouldAcceptPreschedule()) {
            return;
        }

        boolean debugLogging = AsyncChunkWarmup.isWarmupDebugLoggingEnabled();

        if (debugLogging) {
            LC2H.LOGGER.debug("Starting preSchedule for {}", coord);
        }
        long startTime = System.nanoTime();

        try {
            ensureScheduled(provider, coord);
            long endTime = System.nanoTime();
            if (debugLogging) {
                LC2H.LOGGER.debug("Finished preSchedule for {} in {} ms", coord, (endTime - startTime) / 1_000_000);
            }
        } catch (Throwable t) {
            LC2H.LOGGER.error("Synchronous multichunk planning failed for {}: {}", coord, t.getMessage());
            throw t;
        }
    }

    public static int getPlannedCount() {
        return PLANNED.size();
    }

    public static int getGpuDataCacheSize() {
        return GPU_DATA_CACHE.size();
    }

    public static MultichunkTelemetrySnapshot telemetrySnapshot() {
        return new MultichunkTelemetrySnapshot(
            TELEMETRY_SCHEDULED.sum(),
            TELEMETRY_PENDING_DRAINED.sum(),
            TELEMETRY_PENDING_STALE.sum(),
            TELEMETRY_PENDING_CACHE_HIT.sum(),
            TELEMETRY_CACHE_HIT.sum(),
            TELEMETRY_SYNC_FALLBACK.sum(),
            TELEMETRY_SINGLE_LANE.sum(),
            TELEMETRY_PARALLEL_BATCHES.sum(),
            TELEMETRY_PARALLEL_TASKS.sum(),
            TELEMETRY_KERNEL_BATCHES.sum(),
            TELEMETRY_KERNEL_TASKS.sum(),
            TELEMETRY_INTEGRATION_TASKS.sum(),
            TELEMETRY_SNAPSHOT_ENCODED.sum(),
            TELEMETRY_SNAPSHOT_MISSED.sum(),
            TELEMETRY_PREPARED_CACHE_HIT.sum(),
            TELEMETRY_PREPARED_FUTURE_HIT.sum(),
            TELEMETRY_PREPARED_RECENT_HIT.sum(),
            TELEMETRY_PREPARED_MISS.sum(),
            TELEMETRY_NATIVE_FALLBACK.sum(),
            TELEMETRY_NATIVE_CANCELLED_PENDING.sum(),
            TELEMETRY_NATIVE_RACE_HIT.sum(),
            PENDING_SIZE.get(),
            PLANNED.size()
        );
    }

    public static String telemetrySummary() {
        return telemetrySnapshot().summary();
    }

    public static void flushPendingBatches() {
        if (PENDING_SIZE.get() > 0) {
            submitBatch();
        }
    }

    public static void shutdown() {
        try {
            LC2H.LOGGER.info("AsyncMultiChunkPlanner: Shutting down warmup executor");
            for (PendingEntry entry : drainPending()) {
                entry.future().cancel(true);
            }

            PLANNED.clear();
            WARM_BUILDING_INFO_SUBMITTED.clear();
            WARM_BUILDING_INFO_DONE.clear();
            WARM_PLANS.clear();
            INTEGRATION_HOOKED.clear();
            WARM_RETRY_ENTRIES.clear();
            WARM_RETRY_QUEUE.clear();
            WARM_RETRY_TOTAL.set(0L);
            LAST_WARM_RETRY_MS.set(0L);
            RECENT_MULTI.clear();
            RECENT_MULTI_ORDER.clear();
            GPU_DATA_CACHE.clear();
            if (MULTICHUNK_PRECOMPUTE_POOL != null) {
                MULTICHUNK_PRECOMPUTE_POOL.shutdownNow();
            }
            PENDING.clear();
            PENDING_SIZE.set(0);
            PENDING_DRAINING.set(false);
            PENDING_FLUSH_SCHEDULED.set(false);

            LC2H.LOGGER.info("AsyncMultiChunkPlanner: Shutdown complete");
        } catch (Exception e) {
            LC2H.LOGGER.error("AsyncMultiChunkPlanner: Error during shutdown", e);
        }
    }

    private static MultiChunk computeMultiChunkSync(IDimensionInfo provider, int areaSize, ChunkCoord multiCoord) {
        return executeInternal(() -> computeMultiChunk(provider, areaSize, multiCoord));
    }

    private static ChunkCoord toMultiCoord(IDimensionInfo provider, ChunkCoord coord) {
        int areaSize = provider.getWorldStyle().getMultiSettings().areasize();
        return toMultiCoord(coord, areaSize);
    }

    private static ChunkCoord toMultiCoord(ChunkCoord coord, int areaSize) {
        return new ChunkCoord(coord.dimension(), Math.floorDiv(coord.chunkX(), areaSize), Math.floorDiv(coord.chunkZ(), areaSize));
    }

    private static PlannerKey plannerKey(IDimensionInfo provider, ChunkCoord multiCoord, int areaSize) {
        ResourceKey<net.minecraft.world.level.Level> dimension = multiCoord != null ? multiCoord.dimension() : null;
        String scope = WorldGenScope.cache(provider).stableText();
        int x = multiCoord != null ? multiCoord.chunkX() : 0;
        int z = multiCoord != null ? multiCoord.chunkZ() : 0;
        return new PlannerKey(scope, dimension, areaSize, x, z);
    }

    private static int areaSize(IDimensionInfo provider) {
        try {
            return Math.max(1, provider.getWorldStyle().getMultiSettings().areasize());
        } catch (Throwable ignored) {
            return 1;
        }
    }

    /**
     * Schedules an aligned square of Lost Cities multichunk plans through the
     * normal bounded queue.  This is intentionally expressed in multichunk
     * coordinates, not arbitrary chunk radius: a 5x5 chunk warmup cell cannot
     * cover even one complete 16x16 Lost Cities layout, while an aligned window
     * creates reusable, cache-keyed planning products without touching world
     * state or performing a blocking world lookup.
     */
    public static void preScheduleWindow(IDimensionInfo provider, ChunkCoord coord, int requestedSide) {
        if (!ASYNC_PLANNER_ENABLED) {
            return;
        }

        if (provider == null || coord == null || isInternalComputation()) {
            return;
        }
        int areaSize = areaSize(provider);
        int side = Math.max(1, Math.min(6, requestedSide));
        ChunkCoord centerMulti = toMultiCoord(coord, areaSize);
        int baseMultiX = Math.floorDiv(centerMulti.chunkX(), side) * side;
        int baseMultiZ = Math.floorDiv(centerMulti.chunkZ(), side) * side;
        for (int x = 0; x < side; x++) {
            for (int z = 0; z < side; z++) {
                ChunkCoord topLeft = new ChunkCoord(centerMulti.dimension(),
                    (baseMultiX + x) * areaSize, (baseMultiZ + z) * areaSize);
                ensureScheduled(provider, topLeft);
            }
        }
    }

    private static MultiChunk computeMultiChunk(IDimensionInfo provider, int areaSize, ChunkCoord multiCoord) {
        long start = System.nanoTime();
        try {
            boolean trace = Boolean.parseBoolean(System.getProperty("lc2h.multichunkTrace", "false"));
            if (trace || org.admany.lc2h.config.ConfigManager.ENABLE_DEBUG_LOGGING) {
                try {
                    var settings = provider.getWorldStyle().getMultiSettings();
                    LC2H.LOGGER.debug("[AsyncMultiChunkPlanner] computeMultiChunk start coord={} areaSize={} min={} max={} attempts={} correctStyleFactor={}",
                        multiCoord, areaSize,
                        settings.minimum(), settings.maximum(), settings.attempts(), settings.correctStyleFactor());
                } catch (Throwable ignored) {
                }
            }

            if (!FastMultiChunkPlanner.isEnabled()) {
                precomputeMultiChunkLookups(provider, multiCoord, areaSize);
            }
            MultiChunk multiChunk = new MultiChunk(multiCoord, areaSize);
            MultiChunk result = ((MultiChunkInvoker) multiChunk).lc2h$calculateBuildings(provider);
            MultiBuildingFootprintRegistry.register(provider, multiCoord, result);
            MultiChunkBoundaryRegistry.register(provider, multiCoord, result);

            long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
            if (trace || org.admany.lc2h.config.ConfigManager.ENABLE_DEBUG_LOGGING) {
                LC2H.LOGGER.debug("[AsyncMultiChunkPlanner] computeMultiChunk({}) finished in {} ms", multiCoord, elapsedMs);
            }
            return result;
        } finally {
            Lc2hTimingRegistry.record("multichunk.compute", System.nanoTime() - start);
        }
    }

    private static void precomputeMultiChunkLookups(IDimensionInfo provider, ChunkCoord multiCoord, int areaSize) {
        if (provider == null || multiCoord == null || areaSize <= 0) {
            return;
        }
        ChunkCoord topLeft = new ChunkCoord(multiCoord.dimension(), multiCoord.chunkX() * areaSize, multiCoord.chunkZ() * areaSize);
        LostCityProfile profile;
        try {
            profile = provider.getProfile();
        } catch (Throwable t) {
            return;
        }

        try {
            City.isChunkOccupied(provider, topLeft);
        } catch (Throwable ignored) {
        }

        int baseX = topLeft.chunkX();
        int baseZ = topLeft.chunkZ();
        int parallelism = computePrecomputeParallelism(areaSize);
        java.util.concurrent.ExecutorService precomputePool = MULTICHUNK_PRECOMPUTE_POOL;
        if (parallelism <= 1 || precomputePool == null || precomputePool.isShutdown() || precomputePool.isTerminated() || areaSize <= 1) {
            for (int x = 0; x < areaSize; x++) {
                for (int z = 0; z < areaSize; z++) {
                    ChunkCoord coord = new ChunkCoord(topLeft.dimension(), baseX + x, baseZ + z);
                    warmChunkLookups(provider, profile, coord);
                }
            }
            return;
        }

        int stride = Math.max(1, (int) Math.ceil(areaSize / (double) parallelism));
        java.util.List<java.util.concurrent.Callable<Void>> tasks = new java.util.ArrayList<>(Math.max(1, (int) Math.ceil(areaSize / (double) stride)));
        for (int x = 0; x < areaSize; x += stride) {
            final int startX = x;
            final int endX = Math.min(areaSize, x + stride);
            tasks.add(() -> {
                for (int localX = startX; localX < endX; localX++) {
                    for (int z = 0; z < areaSize; z++) {
                        ChunkCoord coord = new ChunkCoord(topLeft.dimension(), baseX + localX, baseZ + z);
                        warmChunkLookups(provider, profile, coord);
                    }
                }
                return null;
            });
        }

        try {
            java.util.List<java.util.concurrent.Future<Void>> futures = precomputePool.invokeAll(tasks);
            for (java.util.concurrent.Future<Void> future : futures) {
                try {
                    future.get();
                } catch (Throwable ignored) {
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (java.util.concurrent.RejectedExecutionException rejected) {
            for (int x = 0; x < areaSize; x++) {
                for (int z = 0; z < areaSize; z++) {
                    ChunkCoord coord = new ChunkCoord(topLeft.dimension(), baseX + x, baseZ + z);
                    warmChunkLookups(provider, profile, coord);
                }
            }
        }
    }

    private static void warmChunkLookups(IDimensionInfo provider, LostCityProfile profile, ChunkCoord coord) {
        boolean cityRaw = false;
        try {
            cityRaw = BuildingInfo.isCityRaw(coord, provider, profile);
        } catch (Throwable ignored) {
        }
        if (!cityRaw) {
            return;
        }
        try {
            City.getCityStyle(coord, provider, profile);
        } catch (Throwable ignored) {
        }
        try {
            Railway.getRailChunkType(coord, provider, profile);
        } catch (Throwable ignored) {
        }
        try {
            BuildingInfo.hasHighway(coord, provider, profile);
        } catch (Throwable ignored) {
        }
    }

    private static int computePrecomputeParallelism(int areaSize) {
        if (areaSize <= 1) {
            return 1;
        }
        int base = MULTICHUNK_PRECOMPUTE_PARALLELISM;
        double tickMs = ServerTickLoad.getSmoothedTickMs();
        if (tickMs >= 40.0D) {
            base = 1;
        } else if (tickMs >= 30.0D) {
            base = Math.max(1, base / 2);
        }
        return Math.max(1, Math.min(base, areaSize));
    }

    private static MultiChunk integrateResult(IDimensionInfo provider, ChunkCoord multiCoord, MultiChunk prepared) {
        return integrateResult(provider, multiCoord, prepared, null);
    }

    private static MultiChunk integrateResult(IDimensionInfo provider, ChunkCoord multiCoord, MultiChunk prepared, java.util.function.Consumer<Runnable> runnableCollector) {
        long startNs = System.nanoTime();
        String threadName = Thread.currentThread().getName();
        boolean onServerThread = "Server thread".equals(threadName);

        try {
            if (runnableCollector != null) {
                if (onServerThread) {
                    MultiChunk gameCompatible = translateToGameCompatible(prepared, provider);
                    runnableCollector.accept(() -> applyIntegrated(provider, multiCoord, gameCompatible));
                    return gameCompatible;
                }

                byte[] snapshot = null;
                try {
                    snapshot = MultiChunkSnapshot.encode(prepared);
                } catch (Throwable ignored) {
                }
                final byte[] finalSnapshot = snapshot;
                runnableCollector.accept(() -> {
                    MultiChunk gameCompatible = prepared;
                    if (finalSnapshot != null) {
                        try {
                            MultiChunk decoded = MultiChunkSnapshot.decode(finalSnapshot);
                            if (decoded != null) {
                                gameCompatible = decoded;
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                    applyIntegrated(provider, multiCoord, gameCompatible);
                });
                return prepared;
            }

            if (onServerThread) {
                MultiChunk gameCompatible = translateToGameCompatible(prepared, provider);
                applyIntegrated(provider, multiCoord, gameCompatible);
                return gameCompatible;
            }

            byte[] snapshot = null;
            try {
                snapshot = MultiChunkSnapshot.encode(prepared);
            } catch (Throwable ignored) {
            }
            final byte[] finalSnapshot = snapshot;
            org.admany.lc2h.worldgen.apply.MainThreadChunkApplier.enqueueChunkApplication(multiCoord, () -> {
                MultiChunk gameCompatible = prepared;
                if (finalSnapshot != null) {
                    try {
                        MultiChunk decoded = MultiChunkSnapshot.decode(finalSnapshot);
                        if (decoded != null) {
                            gameCompatible = decoded;
                        }
                    } catch (Throwable ignored) {
                    }
                }
                applyIntegrated(provider, multiCoord, gameCompatible);
            });
            return prepared;
        } finally {
            Lc2hTimingRegistry.record("multichunk.integrate", System.nanoTime() - startNs);
        }
    }

    private static void applyIntegrated(IDimensionInfo provider, ChunkCoord multiCoord, MultiChunk gameCompatible) {
        long startNs = System.nanoTime();
        Object cacheLock = MultiChunkCacheAccess.lock();
        boolean inserted = false;
        try {
            synchronized (cacheLock) {
                if (!MultiChunkCacheAccess.contains(multiCoord)) {
                    MultiChunkCacheAccess.put(multiCoord, gameCompatible);
                    inserted = true;
                }
            }
            if (inserted) {
                LostCitiesCacheBudgetManager.recordPut(MULTICHUNK_BUDGET, multiCoord, MULTICHUNK_BUDGET.defaultEntryBytes(), true);
            } else {
                LostCitiesCacheBudgetManager.recordAccess(MULTICHUNK_BUDGET, multiCoord);
            }
            int areaSize = areaSize(provider);
            try {
                ChunkCoord topLeft = new ChunkCoord(multiCoord.dimension(), multiCoord.chunkX() * areaSize, multiCoord.chunkZ() * areaSize);
                MultiBuildingFootprintRegistry.register(provider, multiCoord, gameCompatible);
                org.admany.lc2h.util.lostcities.BuildingInfoCacheInvalidator.invalidateArea(topLeft, areaSize);
                org.admany.lc2h.worldgen.async.planner.AsyncBuildingInfoPlanner.invalidateArea(topLeft, areaSize);
                MultiChunkBoundaryRegistry.invalidateArea(topLeft, areaSize);
                MultiChunkBoundaryRegistry.register(provider, multiCoord, gameCompatible);
                ChunkGenTracker.recordMultiChunkIntegrated(topLeft, areaSize);
            } catch (Throwable ignored) {
            }

            PlannerKey plannerKey = plannerKey(provider, multiCoord, areaSize);
            PLANNED.remove(plannerKey);
            cacheRecentMulti(plannerKey, gameCompatible);
            scheduleWarmBuildingInfo(provider, gameCompatible, multiCoord);
        } finally {
            Lc2hTimingRegistry.record("multichunk.apply_integrated", System.nanoTime() - startNs);
        }
    }

    private static void cacheRecentMulti(PlannerKey plannerKey, MultiChunk multiChunk) {
        if (!RECENT_MULTI_ENABLED) {
            return;
        }
        if (plannerKey == null || multiChunk == null) {
            return;
        }
        RECENT_MULTI.put(plannerKey, new RecentMulti(multiChunk, System.currentTimeMillis()));
        RECENT_MULTI_ORDER.add(plannerKey);
        pruneRecentMulti();
    }

    private static MultiChunk loadRecentMulti(IDimensionInfo provider, ChunkCoord multiCoord, int areaSize) {
        if (!RECENT_MULTI_ENABLED) {
            return null;
        }
        if (multiCoord == null) {
            return null;
        }
        PlannerKey plannerKey = plannerKey(provider, multiCoord, areaSize);
        RecentMulti cached = RECENT_MULTI.get(plannerKey);
        if (cached == null) {
            return null;
        }
        long now = System.currentTimeMillis();
        if ((now - cached.timestampMs) > RECENT_MULTI_TTL_MS) {
            RECENT_MULTI.remove(plannerKey, cached);
            return null;
        }
        return cached.multiChunk;
    }

    private static void pruneRecentMulti() {
        if (RECENT_MULTI.size() <= RECENT_MULTI_MAX) {
            return;
        }
        int attempts = 0;
        while (RECENT_MULTI.size() > RECENT_MULTI_MAX && attempts < RECENT_MULTI_MAX * 2) {
            PlannerKey key = RECENT_MULTI_ORDER.poll();
            if (key == null) {
                break;
            }
            RECENT_MULTI.remove(key);
            attempts++;
        }
    }

    private static MultiChunk translateToGameCompatible(MultiChunk asyncResult, IDimensionInfo provider) {
        if (asyncResult == null) {
            return null;
        }
        try {
            byte[] snapshot = MultiChunkSnapshot.encode(asyncResult);
            if (snapshot != null) {
                MultiChunk decoded = MultiChunkSnapshot.decode(snapshot);
                if (decoded != null) {
                    return decoded;
                }
            }
        } catch (Throwable ignored) {
        }

        return asyncResult;
    }

    private static boolean warmBuildingInfo(IDimensionInfo provider, MultiChunk prepared, ChunkCoord multiCoord) {
        WARMUP_CALL_DEPTH.set(WARMUP_CALL_DEPTH.get() + 1);
        try {
            MultiChunkAccessor accessor = (MultiChunkAccessor) prepared;
            ChunkCoord topLeft = accessor.lc2h$getTopLeft();
            int areaSize = accessor.lc2h$getAreaSize();

            if (multiCoord == null) {
                multiCoord = toMultiCoord(topLeft, areaSize);
            }
            PlannerKey plannerKey = plannerKey(provider, multiCoord, areaSize);
            WarmupPlan plan = WARM_PLANS.computeIfAbsent(plannerKey, key -> new WarmupPlan(topLeft, areaSize));
            int total = plan.total();
            int remaining = total - plan.cursor.get();
            if (remaining <= 0) {
                WARM_PLANS.remove(plannerKey);
                return true;
            }

            MinecraftServer server = ServerRescheduler.getServer();
            if (server != null && ServerTickLoad.shouldPauseNonCritical(server)) {
                return false;
            }

            double scale = ServerTickLoad.getBudgetScale(server);
            int pending = 0;
            int availableSlots = 0;
            try {
                AsyncBuildingInfoPlanner.BuildingInfoPressureSnapshot snapshot = AsyncBuildingInfoPlanner.snapshotPressure();
                pending = snapshot.pendingBuildingInfo();
                availableSlots = snapshot.limiterAvailable();
            } catch (Throwable ignored) {
            }
            double pressure = pending <= 0 ? 0.0 : Math.min(1.0, pending / 4096.0);
            int maxPerRun = (int) Math.round(4 + (32 - 4) * scale * (1.0 - pressure));
            if (availableSlots > 0) {
                maxPerRun = Math.min(maxPerRun, Math.max(2, availableSlots * 4));
            }
            maxPerRun = Math.max(2, Math.min(32, maxPerRun));
            maxPerRun = Math.min(maxPerRun, remaining);

            boolean cullForView = shouldCullQueue();
            int scheduled = 0;
            while (scheduled < maxPerRun) {
                int index = plan.nextIndex();
                if (index >= total) {
                    break;
                }
                ChunkCoord target = plan.coordAt(index);
                if (target == null) {
                    continue;
                }
                if (cullForView && !isChunkInView(target)) {
                    continue;
                }
                AsyncBuildingInfoPlanner.preSchedule(provider, target);
                scheduled++;
            }

            if (plan.cursor.get() >= total) {
                WARM_PLANS.remove(plannerKey);
                return true;
            }
            return false;
        } finally {
            WARMUP_CALL_DEPTH.set(WARMUP_CALL_DEPTH.get() - 1);
        }
    }

    public static <T> T runInternal(java.util.function.Supplier<T> supplier) {
        INTERNAL_CALL_DEPTH.set(INTERNAL_CALL_DEPTH.get() + 1);
        try {
            return supplier.get();
        } finally {
            INTERNAL_CALL_DEPTH.set(INTERNAL_CALL_DEPTH.get() - 1);
        }
    }

    private static <T> T executeInternal(java.util.function.Supplier<T> supplier) {
        return runInternal(supplier);
    }

    public static void syncWarmup(IDimensionInfo provider, ChunkCoord coord) {
        if (!ASYNC_PLANNER_ENABLED) {
            return;
        }

        if (provider == null || coord == null) {
            return;
        }

        int areaSize = areaSize(provider);
        ChunkCoord multiCoord = toMultiCoord(coord, areaSize);
        PlannerKey plannerKey = plannerKey(provider, multiCoord, areaSize);

        Object cacheLock = MultiChunkCacheAccess.lock();
        synchronized (cacheLock) {
            if (MultiChunkCacheAccess.contains(multiCoord)) {
                return;
            }
        }

        CompletableFuture<MultiChunk> future = PLANNED.get(plannerKey);
        if (future != null) {
            try {
                if (!future.isCancelled() && !future.isCompletedExceptionally()) {
                    MultiChunk prepared = future.getNow(null);
                    if (prepared == null) {
                        prepared = future.join();
                    }
                    if (prepared != null) {
                        integrateResult(provider, multiCoord, prepared);
                        return;
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        MultiChunk recent = loadRecentMulti(provider, multiCoord, areaSize);
        if (recent != null) {
            integrateResult(provider, multiCoord, recent);
            return;
        }

        try {
            MultiChunk computed = LostCityDagScheduler.isEnabled()
                ? LostCityDagScheduler.computeMultiChunkDirect(provider, multiCoord, areaSize)
                : computeMultiChunkSync(provider, areaSize, multiCoord);
            integrateResult(provider, multiCoord, computed);
        } catch (Throwable t) {
            boolean debugLogging = AsyncChunkWarmup.isWarmupDebugLoggingEnabled()
                || org.admany.lc2h.config.ConfigManager.ENABLE_DEBUG_LOGGING;
            if (debugLogging) {
                LC2H.LOGGER.debug("Sync warmup failed for {}", coord, t);
            }
        }
    }

    public static boolean isInternalComputation() {
        return INTERNAL_CALL_DEPTH.get() > 0;
    }

    private static CompletableFuture<MultiChunk> submitMultiChunkCompute(IDimensionInfo provider, int areaSize, PlannerKey key) {
        TELEMETRY_SCHEDULED.increment();
        long startNs = System.nanoTime();
        ChunkCoord multiCoord = key.multiCoord();
        java.util.function.Supplier<MultiChunk> supplier = LostCityDagScheduler.isEnabled()
            ? () -> LostCityDagScheduler.computeMultiChunkDirect(provider, multiCoord, areaSize)
            : () -> computeMultiChunk(provider, areaSize, multiCoord);
        CompletableFuture<MultiChunk> future = new CompletableFuture<>();
        int pending = PENDING_SIZE.incrementAndGet();
        if (pending >= MAX_PENDING) {
            LC2H.LOGGER.debug("Pending suppliers at limit (" + MAX_PENDING + "), forcing flush before enqueue for " + multiCoord);
        }
        PENDING.add(new PendingEntry(supplier, future, multiCoord, provider, null));

        int dynamicBatchSize = Math.max(8, AdaptiveBatchController.multiChunkBatchSize());
        if (pending >= MAX_PENDING || pending >= dynamicBatchSize) {
            submitBatch();
        } else {
            long delay = isLatencySensitiveThread() ? PENDING_FLUSH_DELAY_LATENCY_SENSITIVE_MS : PENDING_FLUSH_DELAY_MS;
            schedulePendingFlush(delay);
        }

        Lc2hTimingRegistry.record("multichunk.submit_enqueue", System.nanoTime() - startNs);
        return future;
    }

    public static MultiChunk computeLegacyForBenchmark(IDimensionInfo provider, ChunkCoord coord) {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(coord, "coord");
        int areaSize = areaSize(provider);
        ChunkCoord multiCoord = toMultiCoord(coord, areaSize);
        PlannerKey plannerKey = plannerKey(provider, multiCoord, areaSize);
        return computeMultiChunkSync(provider, areaSize, multiCoord);
    }

    public static MultiChunk computeKernelForBenchmark(IDimensionInfo provider, ChunkCoord coord) {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(coord, "coord");
        int areaSize = areaSize(provider);
        ChunkCoord multiCoord = toMultiCoord(coord, areaSize);
        PlannerKey plannerKey = plannerKey(provider, multiCoord, areaSize);
        return LostCityDagScheduler.submitMultiChunk(provider, multiCoord, areaSize).join();
    }

    public static MultiChunk computeDirectKernelForBenchmark(IDimensionInfo provider, ChunkCoord coord) {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(coord, "coord");
        int areaSize = areaSize(provider);
        ChunkCoord multiCoord = toMultiCoord(coord, areaSize);
        PlannerKey plannerKey = plannerKey(provider, multiCoord, areaSize);
        return LostCityDagScheduler.computeMultiChunkDirect(provider, multiCoord, areaSize);
    }

    public static List<MultiChunk> computeKernelBatchForBenchmark(IDimensionInfo provider, List<ChunkCoord> coords) {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(coords, "coords");
        int areaSize = provider.getWorldStyle().getMultiSettings().areasize();
        ArrayList<ChunkCoord> multiCoords = new ArrayList<>(coords.size());
        for (ChunkCoord coord : coords) {
            if (coord != null) {
                multiCoords.add(toMultiCoord(coord, areaSize));
            }
        }
        return LostCityDagScheduler.submitMultiChunkBatch(provider, multiCoords, areaSize).join();
    }

    private static void submitBatch() {
        long startNs = System.nanoTime();
        List<PendingEntry> drained = drainPending();
        if (drained.isEmpty()) {
            if (PENDING_SIZE.get() > 0) {
                schedulePendingFlush(0L);
            }
            Lc2hTimingRegistry.record("multichunk.batch_submit_empty", System.nanoTime() - startNs);
            return;
        }

        if (PENDING_SIZE.get() > 0) {
            schedulePendingFlush(15L);
        }

        List<PendingEntry> active = discardFinishedOrCachedEntries(drained);
        if (active.isEmpty()) {
            Lc2hTimingRegistry.record("multichunk.batch_submit_skipped", System.nanoTime() - startNs);
            return;
        }
        if (active.size() == 1) {
            submitSingleDrained(active.get(0));
            Lc2hTimingRegistry.record("multichunk.batch_submit_single", System.nanoTime() - startNs);
            return;
        }
        if (active.size() <= MULTICHUNK_SMALL_BATCH_SERIAL_THRESHOLD) {
            submitSmallBatchSequential(active, startNs);
            return;
        }

        ArrayList<CityCenterGpuCache.Request> centerRequests = new ArrayList<>(active.size());
        for (PendingEntry entry : active) {
            CityCenterGpuCache.Request request = CityCenterGpuCache.request(
                entry.provider(), entry.key(), areaSize(entry.provider()));
            if (request != null) {
                centerRequests.add(request);
            }
        }
        CompletableFuture<Void> centerFacts = CityCenterGpuCache.prepareBatch(centerRequests);
        if (!centerFacts.isDone()) {
            centerFacts.whenComplete((ignored, failure) -> submitPreparedBatch(active, startNs));
            Lc2hTimingRegistry.record("multichunk.batch_city_center_gpu_deferred", System.nanoTime() - startNs);
            return;
        }
        submitPreparedBatch(active, startNs);
    }

    private static void submitPreparedBatch(List<PendingEntry> active, long startNs) {

        java.util.List<java.util.function.Supplier<MultiChunk>> suppliers = new java.util.ArrayList<>(active.size());
        java.util.List<CompletableFuture<MultiChunk>> futures = new java.util.ArrayList<>(active.size());
        java.util.List<ChunkCoord> keys = new java.util.ArrayList<>(active.size());
        java.util.List<IDimensionInfo> providers = new java.util.ArrayList<>(active.size());
        for (PendingEntry entry : active) {
            AdaptiveConcurrencyLimiter.Token token = entry.token();
            CompletableFuture<MultiChunk> entryFuture = entry.future();
            suppliers.add(() -> {
                try {
                    if (entryFuture.isCancelled()) {
                        return null;
                    }
                    MultiChunk cached = cachedMultiChunk(entry.key());
                    if (cached != null) {
                        return cached;
                    }
                    return entry.supplier().get();
                } finally {
                    if (token != null) {
                        token.close();
                    }
                }
            });
            futures.add(entry.future());
            keys.add(entry.key());
            providers.add(entry.provider());
        }

        LC2H.LOGGER.debug("Submitting batched multi-chunk compute with " + suppliers.size() + " tasks");

        try {
            org.admany.lc2h.worldgen.gpu.GPUMemoryManager.continuousCleanup();
        } catch (Throwable t) {
            LC2H.LOGGER.debug("Skipped GPU cleanup before batch submit: {}", t.toString());
        }

        if (LostCityDagScheduler.isEnabled() && LostCityDagScheduler.isMultiChunkBatchDagEnabled()) {
            TELEMETRY_KERNEL_BATCHES.increment();
            TELEMETRY_KERNEL_TASKS.add(active.size());
            submitKernelBatch(active, futures, keys, providers);
            Lc2hTimingRegistry.record("multichunk.batch_submit_kernel", System.nanoTime() - startNs);
            return;
        }

        TELEMETRY_PARALLEL_BATCHES.increment();
        TELEMETRY_PARALLEL_TASKS.add(active.size());
        ParallelWorkOptions<MultiChunk> options = MULTICHUNK_SLICE_CACHE_ENABLED
            ? buildMultiChunkCacheOptions(keys, providers)
            : ParallelWorkOptions.none();
        AtomicIntegerArray acceptedResults = new AtomicIntegerArray(active.size());
        ParallelWorkQueue.dispatch("multi-chunk-batch", suppliers, event -> {
                MultiChunk result = event.result();
                int index = event.index();
                CompletableFuture<MultiChunk> future = futures.get(index);
                ChunkCoord key = keys.get(index);
                if (result != null) {
                    if (future.complete(result)) {
                        acceptedResults.set(index, 1);
                    }
                } else {
                    future.completeExceptionally(new RuntimeException("Batch compute failed for " + key));
                }
            }, options)
            .thenAccept(results -> {
                Lc2hTimingRegistry.record("multichunk.parallel_batch_complete", System.nanoTime() - startNs);
                scheduleAcceptedIntegration(providers, keys, results, acceptedResults);
                GPUMemoryManager.continuousCleanup();
            }).exceptionally(t -> {
                Throwable root = t;
                while (root instanceof java.util.concurrent.CompletionException && root.getCause() != null) {
                    root = root.getCause();
                }
                LC2H.LOGGER.error("Batched multi-chunk compute failed (tasks={}, firstKey={})", keys.size(), keys.isEmpty() ? "<none>" : keys.get(0), root);
                for (PendingEntry entry : active) {
                    AdaptiveConcurrencyLimiter.Token token = entry.token();
                    if (token != null) {
                        token.close();
                    }
                }
                for (CompletableFuture<MultiChunk> f : futures) {
                    f.completeExceptionally(t);
                }
                return null;
            });
        Lc2hTimingRegistry.record("multichunk.batch_submit_parallel", System.nanoTime() - startNs);
    }

    private static void submitSmallBatchSequential(List<PendingEntry> active, long startNs) {
        TELEMETRY_SINGLE_LANE.add(active.size());
        ArrayList<CompletableFuture<MultiChunk>> futures = new ArrayList<>(active.size());
        ArrayList<ChunkCoord> keys = new ArrayList<>(active.size());
        ArrayList<IDimensionInfo> providers = new ArrayList<>(active.size());
        for (PendingEntry entry : active) {
            futures.add(entry.future());
            keys.add(entry.key());
            providers.add(entry.provider());
        }

        org.admany.lc2h.concurrency.async.AsyncManager.submitSupplier("multichunk-small-batch", () -> {
                ArrayList<MultiChunk> results = new ArrayList<>(active.size());
                for (PendingEntry entry : active) {
                    try {
                        if (entry.future().isCancelled()) {
                            results.add(null);
                            continue;
                        }
                        MultiChunk cached = cachedMultiChunk(entry.key());
                        results.add(cached != null ? cached : entry.supplier().get());
                    } finally {
                        releaseToken(entry);
                    }
                }
                return results;
            }, org.admany.lc2h.concurrency.async.Priority.HIGH)
            .whenComplete((results, throwable) -> {
                try {
                    if (throwable != null || results == null || results.size() != active.size()) {
                        Throwable failure = throwable != null ? throwable
                            : new RuntimeException("Small multi-chunk batch returned invalid result set");
                        for (CompletableFuture<MultiChunk> future : futures) {
                            future.completeExceptionally(failure);
                        }
                        return;
                    }
                    ArrayList<IDimensionInfo> acceptedProviders = new ArrayList<>();
                    ArrayList<ChunkCoord> acceptedKeys = new ArrayList<>();
                    ArrayList<MultiChunk> acceptedResults = new ArrayList<>();
                    for (int i = 0; i < results.size(); i++) {
                        MultiChunk result = results.get(i);
                        if (result == null) {
                            futures.get(i).completeExceptionally(new RuntimeException("Small multi-chunk batch returned null for " + keys.get(i)));
                            continue;
                        }
                        if (futures.get(i).complete(result)) {
                            acceptedProviders.add(providers.get(i));
                            acceptedKeys.add(keys.get(i));
                            acceptedResults.add(result);
                        }
                    }
                    scheduleBatchIntegration(acceptedProviders, acceptedKeys, acceptedResults);
                    try {
                        GPUMemoryManager.continuousCleanup();
                    } catch (Throwable ignored) {
                    }
                } finally {
                    Lc2hTimingRegistry.record("multichunk.small_batch_complete", System.nanoTime() - startNs);
                }
            });
        Lc2hTimingRegistry.record("multichunk.batch_submit_small_serial", System.nanoTime() - startNs);
    }

    private static List<PendingEntry> discardFinishedOrCachedEntries(List<PendingEntry> drained) {
        long startNs = System.nanoTime();
        if (drained == null || drained.isEmpty()) {
            return List.of();
        }
        ArrayList<PendingEntry> active = new ArrayList<>(drained.size());
        for (PendingEntry entry : drained) {
            if (entry == null) {
                continue;
            }
            CompletableFuture<MultiChunk> future = entry.future();
            if (future == null || future.isDone() || future.isCancelled()) {
                TELEMETRY_PENDING_STALE.increment();
                releaseToken(entry);
                continue;
            }
            MultiChunk cached = cachedMultiChunk(entry.key());
            if (cached != null) {
                TELEMETRY_PENDING_CACHE_HIT.increment();
                future.complete(cached);
                PLANNED.remove(plannerKey(entry.provider(), entry.key(), areaSize(entry.provider())), future);
                releaseToken(entry);
                continue;
            }
            active.add(entry);
        }
        Lc2hTimingRegistry.record("multichunk.pending_filter", System.nanoTime() - startNs);
        return active;
    }

    private static void submitSingleDrained(PendingEntry entry) {
        TELEMETRY_SINGLE_LANE.increment();
        long startNs = System.nanoTime();
        CompletableFuture<MultiChunk> future = entry.future();
        ChunkCoord key = entry.key();
        IDimensionInfo provider = entry.provider();
        org.admany.lc2h.concurrency.async.AsyncManager.submitSupplier("multichunk-single", () -> {
                if (future.isCancelled()) {
                    return null;
                }
                MultiChunk cached = cachedMultiChunk(key);
                return cached != null ? cached : entry.supplier().get();
            }, org.admany.lc2h.concurrency.async.Priority.HIGH)
            .whenComplete((result, throwable) -> {
                try {
                    if (throwable != null) {
                        future.completeExceptionally(throwable);
                        return;
                    }
                    if (result == null) {
                        future.completeExceptionally(new RuntimeException("Single multi-chunk compute returned null for " + key));
                        return;
                    }
                    if (future.complete(result)) {
                        scheduleBatchIntegration(List.of(provider), List.of(key), List.of(result));
                    }
                    try {
                        GPUMemoryManager.continuousCleanup();
                    } catch (Throwable ignored) {
                    }
                } finally {
                    Lc2hTimingRegistry.record("multichunk.single_lane_complete", System.nanoTime() - startNs);
                    releaseToken(entry);
                }
            });
    }

    private static MultiChunk cachedMultiChunk(ChunkCoord key) {
        long startNs = System.nanoTime();
        if (key == null) {
            return null;
        }
        Object cacheLock = MultiChunkCacheAccess.lock();
        synchronized (cacheLock) {
            MultiChunk cached = MultiChunkCacheAccess.get(key);
            if (cached != null) {
                TELEMETRY_CACHE_HIT.increment();
            }
            Lc2hTimingRegistry.record("multichunk.cache_lookup", System.nanoTime() - startNs);
            return cached;
        }
    }

    private static void releaseToken(PendingEntry entry) {
        if (entry == null || entry.token() == null) {
            return;
        }
        try {
            entry.token().close();
        } catch (Throwable ignored) {
        }
    }

    private static List<PendingEntry> drainPending() {
        long startNs = System.nanoTime();
        if (!PENDING_DRAINING.compareAndSet(false, true)) {
            return List.of();
        }
        tuneMultiChunkLimiter();
        int batchTarget = Math.max(1, AdaptiveBatchController.multiChunkBatchSize());
        int availableSlots = MULTICHUNK_LIMITER.availableSlots();
        int maxDrain = Math.min(batchTarget, availableSlots);
        if (maxDrain <= 0) {
            PENDING_DRAINING.set(false);
            return List.of();
        }

        List<PendingEntry> drained = new ArrayList<>(maxDrain);
        List<PendingEntry> keep = new ArrayList<>();
        int dropped = 0;
        PendingEntry entry;
        while ((entry = PENDING.poll()) != null) {
            if (shouldCullQueue() && !isChunkInView(entry.key())) {
                dropped++;
                try {
                    CompletableFuture<MultiChunk> future = entry.future();
                    if (future != null) {
                        future.cancel(false);
                    }
                } catch (Throwable ignored) {
                }
                try {
                    PLANNED.remove(plannerKey(entry.provider(), entry.key(), areaSize(entry.provider())), entry.future());
                } catch (Throwable ignored) {
                }
                continue;
            }
            if (drained.size() >= maxDrain) {
                keep.add(entry);
                continue;
            }
            AdaptiveConcurrencyLimiter.Token token = MULTICHUNK_LIMITER.tryEnter();
            if (token == null) {
                keep.add(entry);
                continue;
            }
            drained.add(new PendingEntry(entry.supplier(), entry.future(), entry.key(), entry.provider(), token));
        }

        if (!keep.isEmpty()) {
            for (PendingEntry pending : keep) {
                PENDING.add(pending);
            }
        }
        if (!drained.isEmpty() || dropped > 0) {
            PENDING_SIZE.addAndGet(-(drained.size() + dropped));
        }
        if (dropped > 0) {
            ViewCullingStats.recordMultiChunkPending(dropped);
        }
        TELEMETRY_PENDING_DRAINED.add(drained.size());
        MAX_MULTICHUNK_DRAIN.accumulateAndGet(drained.size(), Math::max);
        Lc2hTimingRegistry.record("multichunk.pending_drain", System.nanoTime() - startNs);
        PENDING_DRAINING.set(false);
        return drained;
    }

    /**
     * Keeps multichunk work region-sized when the server has headroom without
     * allowing worldgen to consume every worker when the server is already
     * late. This gate owns admission only. Execution remains on the existing
     * QAPI/LC2H scheduler and no new executor is introduced here.
     */
    private static void tuneMultiChunkLimiter() {
        if (MULTICHUNK_PARALLELISM_OVERRIDE > 0) {
            MULTICHUNK_LIMITER.setLimit(MULTICHUNK_PARALLELISM_OVERRIDE);
            LAST_MULTICHUNK_LIMIT.set(MULTICHUNK_LIMITER.getLimit());
            return;
        }
        long now = System.nanoTime();
        long previous = LAST_MULTICHUNK_TUNE_NS.get();
        if (now - previous < TimeUnit.MILLISECONDS.toNanos(250L)
            || !LAST_MULTICHUNK_TUNE_NS.compareAndSet(previous, now)) {
            return;
        }

        int max = Math.max(1, Math.min(MULTICHUNK_MAX,
            Math.max(2, Runtime.getRuntime().availableProcessors() - 2)));
        double tickMs = ServerTickLoad.getSmoothedTickMs();
        int desired;
        if (tickMs >= 45.0D) {
            desired = 1;
        } else if (tickMs >= 38.0D) {
            desired = 2;
        } else if (tickMs >= 30.0D) {
            desired = Math.min(max, 3);
        } else {
            desired = max;
        }

        int pending = Math.max(0, PENDING_SIZE.get());
        if (pending >= 16) {
            desired = max;
        } else if (pending <= 2 && tickMs >= 25.0D) {
            desired = Math.min(desired, 2);
        }
        try {
            ParallelMetrics.Snapshot snapshot = ParallelMetrics.snapshot();
            long activeSlices = snapshot.modActiveSlices().values().stream().mapToLong(Long::longValue).sum();
            double queueLoad = Math.min(1.0D, activeSlices / (double) Math.max(1, ParallelConfig.queueLimit()));
            if (queueLoad >= 0.85D) {
                desired = Math.max(1, desired - 2);
            } else if (queueLoad >= 0.65D) {
                desired = Math.max(1, desired - 1);
            }
        } catch (Throwable ignored) {
            // The tick and queue-depth gates above remain sufficient when QAPI
            // metrics are unavailable during early bootstrap.
        }
        MULTICHUNK_LIMITER.setLimit(desired);
        LAST_MULTICHUNK_LIMIT.set(MULTICHUNK_LIMITER.getLimit());
    }

    private static boolean shouldCullQueue() {
        var server = ServerRescheduler.getServer();
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
        return true;
    }

    private static boolean isChunkInView(ChunkCoord coord) {
        if (coord == null || coord.dimension() == null) {
            return true;
        }
        return ChunkPriorityManager.isChunkWithinViewDistance(
            coord.dimension().location(),
            coord.chunkX(),
            coord.chunkZ()
        );
    }

    private static void scheduleBatchIntegration(java.util.List<IDimensionInfo> providers,
                                                 java.util.List<ChunkCoord> keys,
                                                 java.util.List<MultiChunk> results) {
        long prepareStartNs = System.nanoTime();
        if (providers == null || keys == null || results == null) {
            return;
        }
        int total = Math.min(Math.min(providers.size(), keys.size()), results.size());
        if (total <= 0) {
            return;
        }

        final byte[][] snapshots = new byte[total][];
        for (int i = 0; i < total; i++) {
            MultiChunk result = results.get(i);
            if (result == null) {
                TELEMETRY_SNAPSHOT_MISSED.increment();
                continue;
            }
            try {
                snapshots[i] = MultiChunkSnapshot.encode(result);
                if (snapshots[i] != null) {
                    TELEMETRY_SNAPSHOT_ENCODED.increment();
                } else {
                    TELEMETRY_SNAPSHOT_MISSED.increment();
                }
            } catch (Throwable ignored) {
                TELEMETRY_SNAPSHOT_MISSED.increment();
            }
        }
        Lc2hTimingRegistry.record("multichunk.batch_snapshot_prepare", System.nanoTime() - prepareStartNs);

        final java.util.concurrent.atomic.AtomicInteger cursor = new java.util.concurrent.atomic.AtomicInteger(0);
        final int step = Math.max(64, Integer.getInteger("lc2h.batchIntegration.step", 256));
        Runnable integrator = new Runnable() {
            @Override
            public void run() {
                long stepStartNs = System.nanoTime();
                int start = cursor.getAndAdd(step);
                if (start >= total) {
                    return;
                }
                int end = Math.min(total, start + step);
                for (int i = start; i < end; i++) {
                    MultiChunk result = results.get(i);
                    if (result == null) {
                        continue;
                    }
                    ChunkCoord key = keys.get(i);
                    IDimensionInfo provider = providers.get(i);
                    try {
                        long entryStartNs = System.nanoTime();
                        MultiChunk gameCompatible = result;
                        byte[] snapshot = snapshots[i];
                        if (snapshot != null) {
                            try {
                                MultiChunk decoded = MultiChunkSnapshot.decode(snapshot);
                                if (decoded != null) {
                                    gameCompatible = decoded;
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                        applyIntegrated(provider, key, gameCompatible);
                        TELEMETRY_INTEGRATION_TASKS.increment();
                        Lc2hTimingRegistry.record("multichunk.batch_apply_entry", System.nanoTime() - entryStartNs);
                    } catch (Throwable t) {
                        LC2H.LOGGER.debug("Batch integration failed for {}: {}", key, t.getMessage());
                    } finally {
                        try {
                            GPUMemoryManager.removeGPUData(key);
                        } catch (Throwable ignored) {
                        }
                    }
                }
                if (end < total) {
                    org.admany.lc2h.util.server.ServerRescheduler.runOnServer(this);
                }
                Lc2hTimingRegistry.record("multichunk.batch_apply_step", System.nanoTime() - stepStartNs);
            }
        };

        org.admany.lc2h.util.server.ServerRescheduler.runOnServer(integrator);
    }

    private static void schedulePendingFlush(long delayMs) {
        if (!PENDING_FLUSH_SCHEDULED.compareAndSet(false, true)) {
            return;
        }
        long effectiveDelay = Math.max(0L, delayMs);
        org.admany.lc2h.concurrency.async.AsyncManager.runLater("multichunk-pending-flush", () -> {
            try {
                if (PENDING_SIZE.get() > 0) {
                    submitBatch();
                }
            } finally {
                PENDING_FLUSH_SCHEDULED.set(false);
            }
        }, effectiveDelay, org.admany.lc2h.concurrency.async.Priority.LOW);
    }

    private static boolean isLatencySensitiveThread() {
        String name = Thread.currentThread().getName();
        if (name == null) return false;
        return "Server thread".equals(name)
            || name.contains("Render thread")
            || name.contains("Client thread")
            || name.contains("main");
    }

    private static ParallelWorkOptions<MultiChunk> buildMultiChunkCacheOptions(List<ChunkCoord> keys,
                                                                               List<IDimensionInfo> providers) {
        List<ChunkCoord> coordSnapshot = List.copyOf(keys);
        List<LostCityKernelSignature> signatureSnapshot = providers == null ? List.of() : providers.stream()
            .map(provider -> LostCityKernelSignature.from(provider, JavaScalarLostCityKernel.INSTANCE.capabilities()))
            .toList();
        return ParallelWorkOptions.persistentCache(
            Lc2hCacheKeys.stageBucket(LostCityKernelStage.PLAN_MULTICHUNK, Lc2hCacheKeys.CacheTier.DISK),
            index -> {
                if (index == null || index < 0 || index >= coordSnapshot.size()) {
                    return null;
                }
                ChunkCoord coord = coordSnapshot.get(index);
                LostCityKernelSignature signature = index < signatureSnapshot.size() ? signatureSnapshot.get(index) : null;
                if (signature == null) {
                    return null;
                }
                return Lc2hCacheKeys.stageKey(
                    signature,
                    LostCityKernelStage.PLAN_MULTICHUNK,
                    coord,
                    Lc2hCacheKeys.multiChunkScope(coord, 1)
                );
            },
            MultiChunkSnapshot::encode,
            MultiChunkSnapshot::decode,
            Duration.ofMinutes(20),
            4096,
            true,
            true
        );
    }

    public record MultichunkTelemetrySnapshot(long scheduled,
                                              long pendingDrained,
                                              long pendingStale,
                                              long pendingCacheHits,
                                              long cacheHits,
                                              long syncFallbacks,
                                              long singleLane,
                                              long parallelBatches,
                                              long parallelTasks,
                                              long kernelBatches,
                                              long kernelTasks,
                                              long integrationTasks,
                                              long snapshotsEncoded,
                                              long snapshotsMissed,
                                              long preparedCacheHits,
                                              long preparedFutureHits,
                                              long preparedRecentHits,
                                              long preparedMisses,
                                              long nativeFallbacks,
                                              long nativeCancelledPending,
                                              long nativeRaceHits,
                                              int pendingQueue,
                                              int planned) {
        public String summary() {
            long batches = parallelBatches + kernelBatches;
            long tasks = parallelTasks + kernelTasks;
            double avgBatchSize = batches <= 0L ? 0.0D : tasks / (double) batches;
            return String.format(Locale.ROOT,
                "scheduled=%d planned=%d pending=%d drained=%d stale=%d cacheHits=%d pendingCacheHits=%d syncFallbacks=%d singleLane=%d parallelBatches=%d kernelBatches=%d avgBatch=%.2f limiter=%d/%d maxDrain=%d integrated=%d snapshots=%d/%d prepared[cache=%d future=%d recent=%d miss=%d race=%d] native[fallback=%d cancelledPending=%d]",
                scheduled,
                planned,
                pendingQueue,
                pendingDrained,
                pendingStale,
                cacheHits,
                pendingCacheHits,
                syncFallbacks,
                singleLane,
                parallelBatches,
                kernelBatches,
                avgBatchSize,
                LAST_MULTICHUNK_LIMIT.get(),
                MULTICHUNK_LIMITER.availableSlots(),
                MAX_MULTICHUNK_DRAIN.get(),
                integrationTasks,
                snapshotsEncoded,
                snapshotsEncoded + snapshotsMissed,
                preparedCacheHits,
                preparedFutureHits,
                preparedRecentHits,
                preparedMisses,
                nativeRaceHits,
                nativeFallbacks,
                nativeCancelledPending);
        }
    }

    private static void scheduleAcceptedIntegration(List<IDimensionInfo> providers,
                                                    List<ChunkCoord> keys,
                                                    List<MultiChunk> results,
                                                    AtomicIntegerArray accepted) {
        int total = Math.min(Math.min(providers.size(), keys.size()), results.size());
        ArrayList<IDimensionInfo> acceptedProviders = new ArrayList<>();
        ArrayList<ChunkCoord> acceptedKeys = new ArrayList<>();
        ArrayList<MultiChunk> acceptedResults = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            if (accepted.get(i) == 0 || results.get(i) == null) {
                continue;
            }
            acceptedProviders.add(providers.get(i));
            acceptedKeys.add(keys.get(i));
            acceptedResults.add(results.get(i));
        }
        scheduleBatchIntegration(acceptedProviders, acceptedKeys, acceptedResults);
    }

    private record PendingEntry(java.util.function.Supplier<MultiChunk> supplier,
                                CompletableFuture<MultiChunk> future,
                                ChunkCoord key,
                                IDimensionInfo provider,
                                AdaptiveConcurrencyLimiter.Token token) {
    }

    private static long computeWarmRetryDelayMs() {
        long delay = WARM_RETRY_BASE_MS;
        if (WARM_RETRY_JITTER_MS > 0) {
            delay += ThreadLocalRandom.current().nextInt(WARM_RETRY_JITTER_MS + 1);
        }
        return delay;
    }

    private static void enqueueWarmRetry(IDimensionInfo provider, ChunkCoord multiCoord, long delayMs) {
        if (provider == null || multiCoord == null) {
            return;
        }
        PlannerKey plannerKey = plannerKey(provider, multiCoord, areaSize(provider));
        long nowMs = System.currentTimeMillis();
        long delay = Math.max(1L, delayMs);
        WarmRetryEntry entry = WARM_RETRY_ENTRIES.compute(plannerKey, (key, existing) -> {
            if (existing == null) {
                return new WarmRetryEntry(provider, plannerKey, multiCoord, nowMs, nowMs + delay);
            }
            if (nowMs + delay < existing.nextRetryMs) {
                existing.nextRetryMs = nowMs + delay;
            }
            return existing;
        });
        if (entry != null) {
            entry.attempts.incrementAndGet();
            WARM_RETRY_QUEUE.add(plannerKey);
            WARM_RETRY_TOTAL.incrementAndGet();
            LAST_WARM_RETRY_MS.set(nowMs);
        }
    }

    private static void requeueWarmRetry(WarmRetryEntry entry, long nextRetryMs) {
        if (entry == null) {
            return;
        }
        entry.nextRetryMs = nextRetryMs;
        WARM_RETRY_QUEUE.add(entry.key);
    }

    public static void drainWarmRetries(MinecraftServer server) {
        if (WARM_RETRY_QUEUE.isEmpty()) {
            return;
        }
        if (server != null && ServerTickLoad.shouldPauseNonCritical(server)) {
            return;
        }

        double scale = ServerTickLoad.getBudgetScale(server);
        long budgetNs = Math.max(1L, (long) (WARM_RETRY_DRAIN_BUDGET_NS * scale));
        int maxDrain = Math.max(1, (int) Math.round(WARM_RETRY_DRAIN_MAX * scale));

        long startNs = System.nanoTime();
        int drained = 0;
        long nowMs = System.currentTimeMillis();
        PlannerKey key;
        while (drained < maxDrain && (key = WARM_RETRY_QUEUE.poll()) != null) {
            WarmRetryEntry entry = WARM_RETRY_ENTRIES.get(key);
            if (entry == null) {
                continue;
            }
            if ((nowMs - entry.firstEnqueuedMs) > WARM_RETRY_TTL_MS
                || entry.attempts.get() > WARM_RETRY_MAX_ATTEMPTS) {
                WARM_RETRY_ENTRIES.remove(key);
                drained++;
                continue;
            }
            Long lastDone = WARM_BUILDING_INFO_DONE.get(key);
            if (lastDone != null && (nowMs - lastDone) <= WARM_BUILDING_INFO_TTL_MS) {
                WARM_RETRY_ENTRIES.remove(key);
                drained++;
                continue;
            }
            if (WARM_BUILDING_INFO_SUBMITTED.containsKey(key)) {
                requeueWarmRetry(entry, nowMs + computeWarmRetryDelayMs());
                drained++;
            } else if (nowMs < entry.nextRetryMs) {
                requeueWarmRetry(entry, entry.nextRetryMs);
                drained++;
            } else {
                MultiChunk cached = getCachedMultiChunk(entry.multiCoord);
                if (cached == null) {
                    WARM_RETRY_ENTRIES.remove(key);
                } else {
                    WARM_RETRY_ENTRIES.remove(key);
                    scheduleWarmBuildingInfo(entry.provider, cached, entry.multiCoord);
                }
                drained++;
            }
            if ((System.nanoTime() - startNs) >= budgetNs) {
                break;
            }
            if (server != null && ServerTickLoad.shouldPauseNonCritical(server)) {
                break;
            }
        }
    }

    private static MultiChunk getCachedMultiChunk(ChunkCoord multiCoord) {
        Object cacheLock = MultiChunkCacheAccess.lock();
        synchronized (cacheLock) {
            return MultiChunkCacheAccess.get(multiCoord);
        }
    }

    private static void scheduleWarmBuildingInfo(IDimensionInfo provider, MultiChunk multiChunk, ChunkCoord multiCoord) {
        if (!WARM_BUILDING_INFO_ENABLED) {
            return;
        }
        if (provider == null || multiChunk == null || multiCoord == null) {
            return;
        }
        PlannerKey plannerKey = plannerKey(provider, multiCoord, areaSize(provider));

        Long lastDone = WARM_BUILDING_INFO_DONE.get(plannerKey);
        long nowMs = System.currentTimeMillis();
        if (lastDone != null && (nowMs - lastDone) <= WARM_BUILDING_INFO_TTL_MS) {
            return;
        }

        if (WARM_BUILDING_INFO_SUBMITTED.putIfAbsent(plannerKey, Boolean.TRUE) != null) {
            return;
        }

        if (!WARM_SEMAPHORE.tryAcquire()) {
            WARM_BUILDING_INFO_SUBMITTED.remove(plannerKey);
            enqueueWarmRetry(provider, multiCoord, computeWarmRetryDelayMs());
            return;
        }

        org.admany.lc2h.concurrency.async.AsyncManager.submitSupplier("warmBuildingInfo", () -> {
                boolean completed = false;
                try {
                    completed = warmBuildingInfo(provider, multiChunk, multiCoord);
                } finally {
                    WARM_SEMAPHORE.release();
                }
                return completed;
            }, org.admany.lc2h.concurrency.async.Priority.LOW)
            .whenComplete((completed, throwable) -> {
                WARM_BUILDING_INFO_SUBMITTED.remove(plannerKey);
                if (throwable == null && Boolean.TRUE.equals(completed)) {
                    WARM_BUILDING_INFO_DONE.put(plannerKey, System.currentTimeMillis());
                    WARM_PLANS.remove(plannerKey);
                } else {
                    enqueueWarmRetry(provider, multiCoord, computeWarmRetryDelayMs());
                }
                if (throwable != null) {
                    LC2H.LOGGER.error("Warm building info failed for {}: {}", multiCoord, throwable.getMessage());
                }
            });
    }

    private static void submitKernelBatch(List<PendingEntry> drained,
                                          List<CompletableFuture<MultiChunk>> futures,
                                          List<ChunkCoord> keys,
                                          List<IDimensionInfo> providers) {
        if (keys.isEmpty()) {
            return;
        }
        IDimensionInfo provider = providers.get(0);
        int areaSize = 1;
        try {
            areaSize = provider.getWorldStyle().getMultiSettings().areasize();
        } catch (Throwable ignored) {
        }
        LostCityDagScheduler.submitMultiChunkBatch(provider, keys, areaSize)
            .thenAccept(results -> {
                int total = Math.min(results.size(), futures.size());
                ArrayList<IDimensionInfo> acceptedProviders = new ArrayList<>();
                ArrayList<ChunkCoord> acceptedKeys = new ArrayList<>();
                ArrayList<MultiChunk> acceptedResults = new ArrayList<>();
                for (int i = 0; i < total; i++) {
                    MultiChunk result = results.get(i);
                    if (result != null) {
                        if (futures.get(i).complete(result)) {
                            acceptedProviders.add(providers.get(i));
                            acceptedKeys.add(keys.get(i));
                            acceptedResults.add(result);
                        }
                    } else {
                        futures.get(i).completeExceptionally(new RuntimeException("Kernel batch compute failed for " + keys.get(i)));
                    }
                }
                for (int i = total; i < futures.size(); i++) {
                    futures.get(i).completeExceptionally(new RuntimeException("Kernel batch result missing for " + keys.get(i)));
                }
                scheduleBatchIntegration(acceptedProviders, acceptedKeys, acceptedResults);
                GPUMemoryManager.continuousCleanup();
            })
            .exceptionally(t -> {
                Throwable root = t;
                while (root instanceof java.util.concurrent.CompletionException && root.getCause() != null) {
                    root = root.getCause();
                }
                LC2H.LOGGER.error("Kernel multi-chunk batch failed (tasks={}, firstKey={})", keys.size(), keys.isEmpty() ? "<none>" : keys.get(0), root);
                for (CompletableFuture<MultiChunk> future : futures) {
                    future.completeExceptionally(t);
                }
                return null;
            })
            .whenComplete((ignored, throwable) -> {
                for (PendingEntry entry : drained) {
                    AdaptiveConcurrencyLimiter.Token token = entry.token();
                    if (token != null) {
                        token.close();
                    }
                }
            });
    }
}
