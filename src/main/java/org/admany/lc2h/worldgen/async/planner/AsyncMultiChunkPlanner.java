package org.admany.lc2h.worldgen.async.planner;

import mcjty.lostcities.config.LostCityProfile;
import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.IDimensionInfo;
import mcjty.lostcities.worldgen.lost.BuildingInfo;
import mcjty.lostcities.worldgen.lost.City;
import mcjty.lostcities.worldgen.lost.MultiChunk;
import mcjty.lostcities.worldgen.lost.Railway;
import org.admany.lc2h.LC2H;
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
import org.admany.lc2h.worldgen.async.warmup.AsyncChunkWarmup;
import org.admany.lc2h.dev.diagnostics.ChunkGenTracker;
import org.admany.lc2h.dev.diagnostics.ViewCullingStats;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;

public final class AsyncMultiChunkPlanner {

    private static final ConcurrentHashMap<ChunkCoord, CompletableFuture<MultiChunk>> PLANNED = new ConcurrentHashMap<>();
    private static final ThreadLocal<Integer> INTERNAL_CALL_DEPTH = ThreadLocal.withInitial(() -> 0);
    private static final ThreadLocal<Integer> WARMUP_CALL_DEPTH = ThreadLocal.withInitial(() -> 0);
    private static final ConcurrentHashMap<ChunkCoord, Boolean> WARM_BUILDING_INFO_SUBMITTED = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<ChunkCoord, Long> WARM_BUILDING_INFO_DONE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<ChunkCoord, WarmupPlan> WARM_PLANS = new ConcurrentHashMap<>();
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
    private static final ConcurrentHashMap<ChunkCoord, WarmRetryEntry> WARM_RETRY_ENTRIES = new ConcurrentHashMap<>();
    private static final ConcurrentLinkedQueue<ChunkCoord> WARM_RETRY_QUEUE = new ConcurrentLinkedQueue<>();
    private static final AtomicLong WARM_RETRY_TOTAL = new AtomicLong(0L);
    private static final AtomicLong LAST_WARM_RETRY_MS = new AtomicLong(0L);

    private static final int RECENT_MULTI_MAX = 256;
    private static final long RECENT_MULTI_TTL_MS = TimeUnit.MINUTES.toMillis(3);
    private static final ConcurrentHashMap<ChunkCoord, RecentMulti> RECENT_MULTI = new ConcurrentHashMap<>();
    private static final ConcurrentLinkedQueue<ChunkCoord> RECENT_MULTI_ORDER = new ConcurrentLinkedQueue<>();

    private static final class RecentMulti {
        private final byte[] snapshot;
        private final long timestampMs;

        private RecentMulti(byte[] snapshot, long timestampMs) {
            this.snapshot = snapshot;
            this.timestampMs = timestampMs;
        }
    }

    private static final class WarmupPlan {
        private final int areaSize;
        private final int[] dx;
        private final int[] dz;
        private final AtomicInteger cursor = new AtomicInteger(0);

        private WarmupPlan(ChunkCoord topLeft, int areaSize) {
            this.areaSize = areaSize;
            int total = areaSize * areaSize;
            this.dx = new int[total];
            this.dz = new int[total];
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
                        dx[index] = x;
                        dz[index] = z;
                        index++;
                    }
                }
            }
        }

        private int nextIndex() {
            return cursor.getAndIncrement();
        }

        private int total() {
            return areaSize * areaSize;
        }
    }

    private static final class WarmRetryEntry {
        private final IDimensionInfo provider;
        private final ChunkCoord multiCoord;
        private final long firstEnqueuedMs;
        private volatile long nextRetryMs;
        private final AtomicInteger attempts = new AtomicInteger(0);

        private WarmRetryEntry(IDimensionInfo provider, ChunkCoord multiCoord, long firstEnqueuedMs, long nextRetryMs) {
            this.provider = provider;
            this.multiCoord = multiCoord;
            this.firstEnqueuedMs = firstEnqueuedMs;
            this.nextRetryMs = nextRetryMs;
        }
    }

    private static final int MULTICHUNK_PARALLELISM_OVERRIDE = Integer.getInteger("lc2h.multichunk.parallelism", -1);
    private static final int MULTICHUNK_MAX = Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors()));
    private static final AdaptiveConcurrencyLimiter MULTICHUNK_LIMITER = new AdaptiveConcurrencyLimiter(2, 1, MULTICHUNK_MAX);
    private static final int MULTICHUNK_PRECOMPUTE_PARALLELISM = Math.max(1,
        Math.min(Integer.getInteger("lc2h.multichunk.precomputeParallelism", Math.max(2, MULTICHUNK_MAX)), MULTICHUNK_MAX));
    private static final java.util.concurrent.ExecutorService MULTICHUNK_PRECOMPUTE_POOL =
        MULTICHUNK_PRECOMPUTE_PARALLELISM > 1 ? new java.util.concurrent.ForkJoinPool(MULTICHUNK_PRECOMPUTE_PARALLELISM) : null;

    private static final LostCitiesCacheBudgetManager.CacheGroup MULTICHUNK_BUDGET =
        LostCitiesCacheBudgetManager.register("lc_multichunk", 4096, 256, MultiChunkCacheAccess::remove);

    public static final ConcurrentHashMap<ChunkCoord, float[]> GPU_DATA_CACHE = new ConcurrentHashMap<>();

    private static final ConcurrentLinkedQueue<PendingEntry> PENDING = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger PENDING_SIZE = new AtomicInteger();
    private static final AtomicBoolean PENDING_DRAINING = new AtomicBoolean(false);
    private static final int MAX_PENDING = 128;
    private static final AtomicBoolean PENDING_FLUSH_SCHEDULED = new AtomicBoolean(false);
    private static final long PENDING_FLUSH_DELAY_MS = 15;
    private static final long PENDING_FLUSH_DELAY_LATENCY_SENSITIVE_MS = 15;

    private AsyncMultiChunkPlanner() {
    }

    public static boolean isWarmupInProgress() {
        return WARMUP_CALL_DEPTH.get() > 0;
    }

    public static MultiChunk tryConsumePrepared(IDimensionInfo provider, ChunkCoord coord) {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(coord, "coord");

        ChunkCoord multiCoord = toMultiCoord(provider, coord);
        Object cacheLock = MultiChunkCacheAccess.lock();
        synchronized (cacheLock) {
            MultiChunk existing = MultiChunkCacheAccess.get(multiCoord);
            if (existing != null) {
                LostCitiesCacheBudgetManager.recordAccess(MULTICHUNK_BUDGET, multiCoord);
                return existing;
            }
        }

        CompletableFuture<MultiChunk> future = PLANNED.get(multiCoord);
        if (future != null && future.isDone() && !future.isCompletedExceptionally() && !future.isCancelled()) {
            MultiChunk prepared = future.getNow(null);
            if (prepared != null) {
                return integrateResult(provider, multiCoord, prepared);
            }
        }
        MultiChunk recent = loadRecentMulti(provider, multiCoord);
        if (recent != null) {
            return integrateResult(provider, multiCoord, recent);
        }
        return null;
    }

    public static MultiChunk resolve(IDimensionInfo provider, ChunkCoord coord) {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(coord, "coord");

        int areaSize = provider.getWorldStyle().getMultiSettings().areasize();
        ChunkCoord multiCoord = toMultiCoord(coord, areaSize);

        Object cacheLock = MultiChunkCacheAccess.lock();
        synchronized (cacheLock) {
            MultiChunk existing = MultiChunkCacheAccess.get(multiCoord);
            if (existing != null) {
                LostCitiesCacheBudgetManager.recordAccess(MULTICHUNK_BUDGET, multiCoord);
                return existing;
            }
        }
        if (!isInternalComputation()) {
            CompletableFuture<MultiChunk> future = PLANNED.computeIfAbsent(multiCoord, key -> submitMultiChunkCompute(provider, areaSize, key));
            try {
                if (!future.isCancelled() && !future.isCompletedExceptionally()) {
                    MultiChunk prepared = future.getNow(null);
                    if (prepared == null && !future.isDone()) {
                        prepared = future.join();
                    }
                    if (prepared != null) {
                        return integrateResult(provider, multiCoord, prepared);
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        MultiChunk recent = loadRecentMulti(provider, multiCoord);
        if (recent != null) {
            return integrateResult(provider, multiCoord, recent);
        }

        MultiChunk computed = executeInternal(() -> computeMultiChunk(provider, areaSize, multiCoord));
        return integrateResult(provider, multiCoord, computed);
    }

    public static void ensureIntegrated(IDimensionInfo provider, ChunkCoord coord) {
        if (provider == null || coord == null || isInternalComputation()) {
            return;
        }

        int areaSize = provider.getWorldStyle().getMultiSettings().areasize();
        ChunkCoord multiCoord = toMultiCoord(coord, areaSize);

        Object cacheLock = MultiChunkCacheAccess.lock();
        synchronized (cacheLock) {
            if (MultiChunkCacheAccess.contains(multiCoord)) {
                return;
            }
        }

        MultiChunk prepared = null;
        CompletableFuture<MultiChunk> future = PLANNED.get(multiCoord);
        if (future != null) {
            try {
                if (!future.isCancelled() && !future.isCompletedExceptionally()) {
                    if (future.isDone()) {
                        prepared = future.getNow(null);
                    } else {
                        prepared = future.join();
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        if (prepared == null) {
            MultiChunk recent = loadRecentMulti(provider, multiCoord);
            if (recent != null) {
                integrateResult(provider, multiCoord, recent, Runnable::run);
                return;
            }
            prepared = computeMultiChunkSync(provider, areaSize, multiCoord);
        }
        if (prepared != null) {
            integrateResult(provider, multiCoord, prepared, Runnable::run);
        }
    }

    public static void ensureScheduled(IDimensionInfo provider, ChunkCoord coord) {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(coord, "coord");

        if (isInternalComputation()) {
            return;
        }

        int areaSize = provider.getWorldStyle().getMultiSettings().areasize();
        ChunkCoord multiCoord = toMultiCoord(coord, areaSize);

        Object cacheLock = MultiChunkCacheAccess.lock();
        synchronized (cacheLock) {
            if (MultiChunkCacheAccess.contains(multiCoord)) {
                return;
            }
        }

        PLANNED.computeIfAbsent(multiCoord, key -> submitMultiChunkCompute(provider, areaSize, key));
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

        int areaSize = provider.getWorldStyle().getMultiSettings().areasize();
        ChunkCoord multiCoord = toMultiCoord(coord, areaSize);

        Object cacheLock = MultiChunkCacheAccess.lock();
        synchronized (cacheLock) {
            if (!MultiChunkCacheAccess.contains(multiCoord)) {
                MultiChunkCacheAccess.put(multiCoord, multiChunk);
                LostCitiesCacheBudgetManager.recordPut(MULTICHUNK_BUDGET, multiCoord, MULTICHUNK_BUDGET.defaultEntryBytes(), true);
            } else {
                LostCitiesCacheBudgetManager.recordAccess(MULTICHUNK_BUDGET, multiCoord);
            }
        }
        PLANNED.remove(multiCoord);

        try {
            ChunkCoord topLeft = new ChunkCoord(multiCoord.dimension(), multiCoord.chunkX() * areaSize, multiCoord.chunkZ() * areaSize);
            org.admany.lc2h.util.lostcities.BuildingInfoCacheInvalidator.invalidateArea(topLeft, areaSize);
            AsyncBuildingInfoPlanner.invalidateArea(topLeft, areaSize);
        } catch (Throwable ignored) {
        }

        scheduleWarmBuildingInfo(provider, multiChunk, multiCoord);
    }

    public static void preSchedule(IDimensionInfo provider, ChunkCoord coord) {
        if (provider == null || coord == null) {
            return;
        }

        boolean debugLogging = AsyncChunkWarmup.isWarmupDebugLoggingEnabled();

        if (GPUMemoryManager.getGPUData(coord, GPU_DATA_CACHE) != null) {
            if (debugLogging) {
                LC2H.LOGGER.debug("Used GPU data for multichunk planning in {}", coord);
            }
            return;
        }

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
            GPU_DATA_CACHE.clear();
            if (MULTICHUNK_PRECOMPUTE_POOL != null) {
                MULTICHUNK_PRECOMPUTE_POOL.shutdown();
            }

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

            precomputeMultiChunkLookups(provider, multiCoord, areaSize);
            MultiChunk multiChunk = new MultiChunk(multiCoord, areaSize);
            MultiChunk result = ((MultiChunkInvoker) multiChunk).lc2h$calculateBuildings(provider);

            long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
            if (trace || org.admany.lc2h.config.ConfigManager.ENABLE_DEBUG_LOGGING) {
                LC2H.LOGGER.debug("[AsyncMultiChunkPlanner] computeMultiChunk({}) finished in {} ms", multiCoord, elapsedMs);
            }
            return result;
        } finally {
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
        String threadName = Thread.currentThread().getName();
        boolean onServerThread = "Server thread".equals(threadName);

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
    }

    private static void applyIntegrated(IDimensionInfo provider, ChunkCoord multiCoord, MultiChunk gameCompatible) {
        Object cacheLock = MultiChunkCacheAccess.lock();
        boolean inserted = false;
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

        try {
            int areaSize = provider.getWorldStyle().getMultiSettings().areasize();
            ChunkCoord topLeft = new ChunkCoord(multiCoord.dimension(), multiCoord.chunkX() * areaSize, multiCoord.chunkZ() * areaSize);
            org.admany.lc2h.util.lostcities.BuildingInfoCacheInvalidator.invalidateArea(topLeft, areaSize);
            org.admany.lc2h.worldgen.async.planner.AsyncBuildingInfoPlanner.invalidateArea(topLeft, areaSize);
            ChunkGenTracker.recordMultiChunkIntegrated(topLeft, areaSize);
        } catch (Throwable ignored) {
        }

        PLANNED.remove(multiCoord);
        cacheRecentMulti(multiCoord, gameCompatible);
        scheduleWarmBuildingInfo(provider, gameCompatible, multiCoord);
    }

    private static void cacheRecentMulti(ChunkCoord multiCoord, MultiChunk multiChunk) {
        if (multiCoord == null || multiChunk == null) {
            return;
        }
        byte[] snapshot;
        try {
            snapshot = MultiChunkSnapshot.encode(multiChunk);
        } catch (Throwable t) {
            return;
        }
        if (snapshot == null || snapshot.length == 0) {
            return;
        }
        RECENT_MULTI.put(multiCoord, new RecentMulti(snapshot, System.currentTimeMillis()));
        RECENT_MULTI_ORDER.add(multiCoord);
        pruneRecentMulti();
    }

    private static MultiChunk loadRecentMulti(IDimensionInfo provider, ChunkCoord multiCoord) {
        if (multiCoord == null) {
            return null;
        }
        RecentMulti cached = RECENT_MULTI.get(multiCoord);
        if (cached == null) {
            return null;
        }
        long now = System.currentTimeMillis();
        if ((now - cached.timestampMs) > RECENT_MULTI_TTL_MS) {
            RECENT_MULTI.remove(multiCoord, cached);
            return null;
        }
        try {
            MultiChunk decoded = MultiChunkSnapshot.decode(cached.snapshot);
            if (decoded != null) {
                return decoded;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static void pruneRecentMulti() {
        if (RECENT_MULTI.size() <= RECENT_MULTI_MAX) {
            return;
        }
        int attempts = 0;
        while (RECENT_MULTI.size() > RECENT_MULTI_MAX && attempts < RECENT_MULTI_MAX * 2) {
            ChunkCoord coord = RECENT_MULTI_ORDER.poll();
            if (coord == null) {
                break;
            }
            RECENT_MULTI.remove(coord);
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
            WarmupPlan plan = WARM_PLANS.computeIfAbsent(multiCoord, key -> new WarmupPlan(topLeft, areaSize));
            int total = plan.total();
            int remaining = total - plan.cursor.get();
            if (remaining <= 0) {
                WARM_PLANS.remove(multiCoord);
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
                int dx = plan.dx[index];
                int dz = plan.dz[index];
                ChunkCoord target = new ChunkCoord(topLeft.dimension(), topLeft.chunkX() + dx, topLeft.chunkZ() + dz);
                if (cullForView && !isChunkInView(target)) {
                    continue;
                }
                AsyncBuildingInfoPlanner.preSchedule(provider, target);
                scheduled++;
            }

            if (plan.cursor.get() >= total) {
                WARM_PLANS.remove(multiCoord);
                return true;
            }
            return false;
        } finally {
            WARMUP_CALL_DEPTH.set(WARMUP_CALL_DEPTH.get() - 1);
        }
    }

    private static <T> T executeInternal(java.util.function.Supplier<T> supplier) {
        INTERNAL_CALL_DEPTH.set(INTERNAL_CALL_DEPTH.get() + 1);
        try {
            return supplier.get();
        } finally {
            INTERNAL_CALL_DEPTH.set(INTERNAL_CALL_DEPTH.get() - 1);
        }
    }

    public static void syncWarmup(IDimensionInfo provider, ChunkCoord coord) {
        if (provider == null || coord == null) {
            return;
        }

        int areaSize = provider.getWorldStyle().getMultiSettings().areasize();
        ChunkCoord multiCoord = toMultiCoord(coord, areaSize);

        Object cacheLock = MultiChunkCacheAccess.lock();
        synchronized (cacheLock) {
            if (MultiChunkCacheAccess.contains(multiCoord)) {
                return;
            }
        }

        try {
            MultiChunk computed = computeMultiChunkSync(provider, areaSize, multiCoord);
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

    private static CompletableFuture<MultiChunk> submitMultiChunkCompute(IDimensionInfo provider, int areaSize, ChunkCoord key) {
        java.util.function.Supplier<MultiChunk> supplier = () -> computeMultiChunk(provider, areaSize, key);
        CompletableFuture<MultiChunk> future = new CompletableFuture<>();
        int pending = PENDING_SIZE.incrementAndGet();
        if (pending >= MAX_PENDING) {
            LC2H.LOGGER.debug("Pending suppliers at limit (" + MAX_PENDING + "), forcing flush before enqueue for " + key);
        }
        PENDING.add(new PendingEntry(supplier, future, key, provider, null));

        int dynamicBatchSize = Math.max(8, AdaptiveBatchController.multiChunkBatchSize());
        if (pending >= MAX_PENDING || pending >= dynamicBatchSize) {
            submitBatch();
        } else {
            long delay = isLatencySensitiveThread() ? PENDING_FLUSH_DELAY_LATENCY_SENSITIVE_MS : PENDING_FLUSH_DELAY_MS;
            schedulePendingFlush(delay);
        }

        return future;
    }

    private static void submitBatch() {
        List<PendingEntry> drained = drainPending();
        if (drained.isEmpty()) {
            if (PENDING_SIZE.get() > 0) {
                schedulePendingFlush(0L);
            }
            return;
        }

        java.util.List<java.util.function.Supplier<MultiChunk>> suppliers = new java.util.ArrayList<>(drained.size());
        java.util.List<CompletableFuture<MultiChunk>> futures = new java.util.ArrayList<>(drained.size());
        java.util.List<ChunkCoord> keys = new java.util.ArrayList<>(drained.size());
        java.util.List<IDimensionInfo> providers = new java.util.ArrayList<>(drained.size());
        for (PendingEntry entry : drained) {
            AdaptiveConcurrencyLimiter.Token token = entry.token();
            suppliers.add(() -> {
                try {
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

        ParallelWorkOptions<MultiChunk> options = buildMultiChunkCacheOptions(keys, providers);
        ParallelWorkQueue.dispatch("multi-chunk-batch", suppliers, event -> {
                MultiChunk result = event.result();
                int index = event.index();
                CompletableFuture<MultiChunk> future = futures.get(index);
                ChunkCoord key = keys.get(index);
                if (result != null) {
                    future.complete(result);
                } else {
                    future.completeExceptionally(new RuntimeException("Batch compute failed for " + key));
                }
            }, options)
            .thenAccept(results -> {
                scheduleBatchIntegration(providers, keys, results);
                GPUMemoryManager.continuousCleanup();
            }).exceptionally(t -> {
                Throwable root = t;
                while (root instanceof java.util.concurrent.CompletionException && root.getCause() != null) {
                    root = root.getCause();
                }
                LC2H.LOGGER.error("Batched multi-chunk compute failed (tasks={}, firstKey={})", keys.size(), keys.isEmpty() ? "<none>" : keys.get(0), root);
                for (PendingEntry entry : drained) {
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
    }

    private static List<PendingEntry> drainPending() {
        if (!PENDING_DRAINING.compareAndSet(false, true)) {
            return List.of();
        }
        if (MULTICHUNK_PARALLELISM_OVERRIDE > 0) {
            MULTICHUNK_LIMITER.setLimit(MULTICHUNK_PARALLELISM_OVERRIDE);
        }
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
                    PLANNED.remove(entry.key(), entry.future());
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
        PENDING_DRAINING.set(false);
        return drained;
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
        if (providers == null || keys == null || results == null) {
            return;
        }
        int total = Math.min(Math.min(providers.size(), keys.size()), results.size());
        if (total <= 0) {
            return;
        }

        final java.util.concurrent.atomic.AtomicInteger cursor = new java.util.concurrent.atomic.AtomicInteger(0);
        final int step = Math.max(64, Integer.getInteger("lc2h.batchIntegration.step", 256));
        Runnable integrator = new Runnable() {
            @Override
            public void run() {
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
                        integrateResult(provider, key, result);
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
        List<SeedDescriptor> seedSnapshot = providers == null ? List.of() : providers.stream()
            .map(AsyncMultiChunkPlanner::descriptorForProvider)
            .toList();
        return ParallelWorkOptions.persistentCache(
            "multichunk-snapshots",
            index -> {
                if (index == null || index < 0 || index >= coordSnapshot.size()) {
                    return null;
                }
                ChunkCoord coord = coordSnapshot.get(index);
                SeedDescriptor descriptor = index < seedSnapshot.size() ? seedSnapshot.get(index) : SeedDescriptor.UNKNOWN;
                return cacheKey(descriptor, coord);
            },
            MultiChunkSnapshot::encode,
            MultiChunkSnapshot::decode,
            Duration.ofMinutes(20),
            4096,
            true,
            true
        );
    }

    private static SeedDescriptor descriptorForProvider(IDimensionInfo provider) {
        if (provider == null) {
            return SeedDescriptor.UNKNOWN;
        }
        long seed = provider.getSeed();
        String dimensionId = "unknown";
        String profileName = "unknown";
        String worldStyleName = "unknown";
        String multiSettingsSignature = "unknown";
        try {
            ResourceKey<net.minecraft.world.level.Level> type = provider.getType();
            if (type != null) {
                dimensionId = String.valueOf(type.location());
            }
        } catch (Throwable ignored) {
        }
        try {
            var profile = provider.getProfile();
            if (profile != null && profile.getName() != null) {
                profileName = profile.getName();
            }
        } catch (Throwable ignored) {
        }
        try {
            var worldStyle = provider.getWorldStyle();
            if (worldStyle != null && worldStyle.getName() != null) {
                worldStyleName = worldStyle.getName();
            }
            if (worldStyle != null) {
                var settings = worldStyle.getMultiSettings();
                if (settings != null) {
                    multiSettingsSignature = settings.areasize() + ":"
                        + settings.minimum() + ":"
                        + settings.maximum() + ":"
                        + settings.attempts() + ":"
                        + String.format(java.util.Locale.ROOT, "%.3f", settings.correctStyleFactor());
                }
            }
        } catch (Throwable ignored) {
        }
        return new SeedDescriptor(dimensionId, seed, profileName, worldStyleName, multiSettingsSignature);
    }

    private static String cacheKey(SeedDescriptor descriptor, ChunkCoord coord) {
        String dimension = descriptor.dimension();
        long seed = descriptor.seed();
        String coordDim = coord != null && coord.dimension() != null ? String.valueOf(coord.dimension().location()) : "unknown";
        if (!"unknown".equals(coordDim)) {
            dimension = coordDim;
        }
        int chunkX = coord != null ? coord.chunkX() : 0;
        int chunkZ = coord != null ? coord.chunkZ() : 0;
        return dimension + ":" + seed + ":" + sanitize(descriptor.profile()) + ":" + sanitize(descriptor.worldStyle())
            + ":" + sanitize(descriptor.multiSettings()) + ":" + chunkX + ":" + chunkZ;
    }

    private record SeedDescriptor(String dimension, long seed, String profile, String worldStyle, String multiSettings) {
        private static final SeedDescriptor UNKNOWN = new SeedDescriptor("unknown", 0L, "unknown", "unknown", "unknown");
    }

    private record PendingEntry(java.util.function.Supplier<MultiChunk> supplier,
                                CompletableFuture<MultiChunk> future,
                                ChunkCoord key,
                                IDimensionInfo provider,
                                AdaptiveConcurrencyLimiter.Token token) {
    }

    private static String sanitize(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value.replace(':', '_').replace('|', '_').replace(' ', '_');
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
        long nowMs = System.currentTimeMillis();
        long delay = Math.max(1L, delayMs);
        WarmRetryEntry entry = WARM_RETRY_ENTRIES.compute(multiCoord, (key, existing) -> {
            if (existing == null) {
                return new WarmRetryEntry(provider, multiCoord, nowMs, nowMs + delay);
            }
            if (nowMs + delay < existing.nextRetryMs) {
                existing.nextRetryMs = nowMs + delay;
            }
            return existing;
        });
        if (entry != null) {
            entry.attempts.incrementAndGet();
            WARM_RETRY_QUEUE.add(multiCoord);
            WARM_RETRY_TOTAL.incrementAndGet();
            LAST_WARM_RETRY_MS.set(nowMs);
        }
    }

    private static void requeueWarmRetry(WarmRetryEntry entry, long nextRetryMs) {
        if (entry == null) {
            return;
        }
        entry.nextRetryMs = nextRetryMs;
        WARM_RETRY_QUEUE.add(entry.multiCoord);
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
        ChunkCoord key;
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
        if (provider == null || multiChunk == null || multiCoord == null) {
            return;
        }

        Long lastDone = WARM_BUILDING_INFO_DONE.get(multiCoord);
        long nowMs = System.currentTimeMillis();
        if (lastDone != null && (nowMs - lastDone) <= WARM_BUILDING_INFO_TTL_MS) {
            return;
        }

        if (WARM_BUILDING_INFO_SUBMITTED.putIfAbsent(multiCoord, Boolean.TRUE) != null) {
            return;
        }

        if (!WARM_SEMAPHORE.tryAcquire()) {
            WARM_BUILDING_INFO_SUBMITTED.remove(multiCoord);
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
                WARM_BUILDING_INFO_SUBMITTED.remove(multiCoord);
                if (throwable == null && Boolean.TRUE.equals(completed)) {
                    WARM_BUILDING_INFO_DONE.put(multiCoord, System.currentTimeMillis());
                    WARM_PLANS.remove(multiCoord);
                } else {
                    enqueueWarmRetry(provider, multiCoord, computeWarmRetryDelayMs());
                }
                if (throwable != null) {
                    LC2H.LOGGER.error("Warm building info failed for {}: {}", multiCoord, throwable.getMessage());
                }
            });
    }
}
