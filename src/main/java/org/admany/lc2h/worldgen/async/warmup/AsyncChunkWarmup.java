package org.admany.lc2h.worldgen.async.warmup;

import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.IDimensionInfo;
import net.minecraft.server.MinecraftServer;
import org.admany.lc2h.LC2H;
import org.admany.lc2h.concurrency.async.AsyncManager;
import org.admany.lc2h.concurrency.async.Priority;
import org.admany.lc2h.client.frustum.ChunkPriorityManager;
import org.admany.lc2h.util.server.ServerRescheduler;
import org.admany.lc2h.util.log.RateLimitedLogger;
import org.admany.lc2h.worldgen.async.generator.AsyncDebrisGenerator;
import org.admany.lc2h.worldgen.async.generator.AsyncPaletteGenerator;
import org.admany.lc2h.worldgen.async.planner.AsyncBuildingInfoPlanner;
import org.admany.lc2h.worldgen.async.planner.AsyncMultiChunkPlanner;
import org.admany.lc2h.worldgen.async.planner.AsyncTerrainCorrectionPlanner;
import org.admany.lc2h.worldgen.async.planner.AsyncTerrainFeaturePlanner;
import org.admany.lc2h.worldgen.coord.RegionCoord;
import org.admany.lc2h.worldgen.gpu.RegionProcessingGPUTask;
import org.admany.lc2h.dev.diagnostics.ViewCullingStats;
import org.admany.quantified.api.opencl.QuantifiedOpenCL;
import org.admany.quantified.core.common.opencl.core.OpenCLManager;
import org.admany.quantified.core.common.util.TaskScheduler;
import org.admany.quantified.core.common.util.TaskScheduler.ResourceHint;
import org.admany.quantified.core.common.util.TaskScheduler.TaskBatchItem;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.Map;

record RegionProviderPair(IDimensionInfo provider, RegionCoord region) {}

public final class AsyncChunkWarmup {

    private static final int REGION_SIZE = 5;
    private static final int CHUNKS_PER_REGION = REGION_SIZE * REGION_SIZE;

    private static final long STARTUP_DELAY_MS = 0;
    private static long firstCallTime = -1;

    private static final boolean VERBOSE_LOGGING = Boolean.parseBoolean(System.getProperty("lc2h.warmupDebug", "false"));
    private static final int REGION_BATCH_SIZE = Math.max(1, Math.min(4,
        Integer.getInteger("lc2h.warmup.regionBatchSize", Math.max(1, Runtime.getRuntime().availableProcessors() / 4))));
    private static final int MAX_REGION_QUEUE = Math.max(REGION_BATCH_SIZE, Integer.getInteger("lc2h.warmup.maxRegionQueue", 512));
    private static final long PREFETCH_COOLDOWN_MS = Math.max(250L, Long.getLong("lc2h.warmup.prefetchCooldownMs", 2000L));
    private static final int PREFETCH_RADIUS = Math.max(1, Integer.getInteger("lc2h.warmup.prefetchRadius", 2));
    private static final int MAX_CONCURRENT_BATCHES = Math.max(1, Math.min(4,
        Integer.getInteger("lc2h.warmup.maxConcurrentBatches", Math.max(1, Runtime.getRuntime().availableProcessors() / 4))));

    // This encodes timestamps - the sign bit indicates GPU-ready schedule.
    private static final ConcurrentHashMap<RegionCoord, Long> PRE_SCHEDULE_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentLinkedQueue<RegionProviderPair> REGION_BUFFER = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger REGION_BUFFER_SIZE = new AtomicInteger(0);
    private static final AtomicBoolean flushScheduled = new AtomicBoolean(false);

    private static volatile long lastPrefetchTime = 0;
    private static RegionCoord lastPrefetchRegion = null;

    private static final java.util.concurrent.atomic.AtomicLong cacheHits = new java.util.concurrent.atomic.AtomicLong(0);
    private static final java.util.concurrent.atomic.AtomicLong cacheMisses = new java.util.concurrent.atomic.AtomicLong(0);
    private static final java.util.concurrent.atomic.AtomicLong regionsProcessed = new java.util.concurrent.atomic.AtomicLong(0);
    private static final java.util.concurrent.atomic.AtomicInteger activeBatches = new java.util.concurrent.atomic.AtomicInteger(0);
    private static final AtomicBoolean gpuProbeWarningLogged = new AtomicBoolean(false);
    private static final AtomicLong lastFailedGpuProbe = new AtomicLong(0);
    private static final AtomicLong lastOpenClStatusLogMs = new AtomicLong(0);
    private static final AtomicReference<String> lastOpenClStatusKey = new AtomicReference<>("");
    private static final AtomicBoolean openClProbeStarted = new AtomicBoolean(false);
    private static volatile boolean gpuReadyOnce = false;
    private static final AtomicBoolean gpuAvailabilityListenerRegistered = new AtomicBoolean(false);
    private static final boolean GPU_ENABLED = Boolean.parseBoolean(System.getProperty("lc2h.gpu.enable", "true"));
    private static final long PRE_SCHEDULE_TTL_MS = Math.max(0L, Long.getLong("lc2h.warmup.preScheduleTtlMs", TimeUnit.MINUTES.toMillis(10)));
    private static final int PRE_SCHEDULE_PRUNE_SCAN = Math.max(10, Integer.getInteger("lc2h.warmup.preSchedulePruneScan", 512));
    private static final int PRE_SCHEDULE_PRUNE_EVERY = Math.max(10, Integer.getInteger("lc2h.warmup.preSchedulePruneEvery", 512));
    private static final AtomicInteger preScheduleOps = new AtomicInteger(0);
    private static final long GPU_READY_FLAG = Long.MIN_VALUE;
    private static final AtomicLong lastFlushKickMs = new AtomicLong(0);

    private AsyncChunkWarmup() {
    }

    public static void initializeGpuWarmup() {
        initializeGpuWarmup(false);
    }

    private static void initializeGpuWarmup(boolean fromRetry) {
        if (!GPU_ENABLED) {
            if (VERBOSE_LOGGING) {
                LC2H.LOGGER.debug("LC2H: GPU warmup disabled via system property");
            }
            return;
        }
        if (!QuantifiedOpenCL.isGpuReady()) {
            if (gpuAvailabilityListenerRegistered.compareAndSet(false, true)) {
                QuantifiedOpenCL.registerGpuAvailabilityListener(AsyncChunkWarmup::onGpuAvailable);
            }
            if (!fromRetry) {
                LC2H.LOGGER.info("LC2H: Deferring GPU warmup until Quantified probe confirms availability");
            }
            logOpenClStatusMaybe("initializeGpuWarmup");
            return;
        }
        ensureOpenClProbeScheduled();
        if (!isOpenClAvailable()) {
            if (!fromRetry) {
                LC2H.LOGGER.info("LC2H: Deferring GPU warmup until Quantified OpenCL is available");
            }
            logOpenClStatusMaybe("initializeGpuWarmup");
            return;
        }
        try {
            if (!gpuReadyOnce) {
                LC2H.LOGGER.info("LC2H: Starting early GPU warmup initialization");
                ensureGpuReady();
                if (gpuReadyOnce) {
                    LC2H.LOGGER.info("LC2H: GPU warmup initialized successfully");
                } else {
                    LC2H.LOGGER.info("LC2H: GPU warmup initialization deferred (OpenCL not available yet)");
                }
            }
        } catch (Throwable t) {
            LC2H.LOGGER.warn("LC2H: Failed to initialize GPU warmup: {}", t.getMessage());
        }
    }

    private static void onGpuAvailable() {
        initializeGpuWarmup(true);
    }

    public static boolean isWarmupDebugLoggingEnabled() {
        return VERBOSE_LOGGING;
    }

    public static boolean isPreScheduled(ChunkCoord center) {
        RegionCoord region = RegionCoord.fromChunk(center);
        Long cached = PRE_SCHEDULE_CACHE.get(region);
        if (cached == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (isExpired(cached, now)) {
            PRE_SCHEDULE_CACHE.remove(region, cached);
            return false;
        }
        if (isGpuReadyFlag(cached)) {
            return true;
        }
        // If the GPU is ready now and this region was only CPU-scheduled earlier, we allow a GPU reschedule.
        return !isOpenClAvailable();
    }

    public static void preSchedule(IDimensionInfo provider, ChunkCoord center) {
        if (provider == null || center == null) {
            return;
        }

        try {
            if (!org.admany.lc2h.util.server.ServerRescheduler.isServerAvailable()) {
                if (VERBOSE_LOGGING) LC2H.LOGGER.debug("Skipping preSchedule because server is not yet available: {}", center);
                return;
            }

            var server = org.admany.lc2h.util.server.ServerRescheduler.getServer();
            if (server != null && server.getPlayerList().getPlayerCount() == 0) {
                return;
            }
        } catch (Throwable ignored) {}

        if (firstCallTime == -1) {
            firstCallTime = System.currentTimeMillis();
        }

        if (System.currentTimeMillis() - firstCallTime < STARTUP_DELAY_MS) {
            if (VERBOSE_LOGGING) {
                LC2H.LOGGER.debug("Skipping preSchedule due to startup delay ({} ms remaining)",
                    STARTUP_DELAY_MS - (System.currentTimeMillis() - firstCallTime));
            }
            return;
        }

        RegionCoord region = RegionCoord.fromChunk(center);

        long now = System.currentTimeMillis();
        maybePrunePreScheduleCache(now);
        boolean gpuReady = isOpenClAvailable();
        java.util.concurrent.atomic.AtomicBoolean shouldSchedule = new java.util.concurrent.atomic.AtomicBoolean(false);
        PRE_SCHEDULE_CACHE.compute(region, (key, existing) -> {
            if (existing == null) {
                shouldSchedule.set(true);
                return encodeTimestamp(now, gpuReady);
            }
            if (isExpired(existing, now)) {
                shouldSchedule.set(true);
                return encodeTimestamp(now, gpuReady);
            }
            if (isGpuReadyFlag(existing)) {
                return existing;
            }
            if (gpuReady) {
                shouldSchedule.set(true);
                return encodeTimestamp(now, true);
            }
            return existing;
        });

        if (!shouldSchedule.get()) {
            cacheHits.incrementAndGet();
            if (VERBOSE_LOGGING) {
                LC2H.LOGGER.debug("Skipping preSchedule for region {} (cached, gpuReady={})", region, gpuReady);
            }
            return;
        }

        cacheMisses.incrementAndGet();
        if (VERBOSE_LOGGING && gpuReady) {
            LC2H.LOGGER.debug("Scheduling region {} with GPU-ready warmup", region);
        }

        if (!enqueueRegion(provider, region)) {
            return;
        }

        int buffered = REGION_BUFFER_SIZE.get();
        if (VERBOSE_LOGGING) {
            LC2H.LOGGER.debug("Queued region {} for warmup (buffer size {})", region, buffered);
        }

        if (buffered >= REGION_BATCH_SIZE) {
            requestFlush(true);
        } else {
            requestFlush(false);
        }
    }

    public static boolean isWarmupActive() {
        long f = firstCallTime;
        if (f <= 0) return false;
        return System.currentTimeMillis() - f >= STARTUP_DELAY_MS;
    }

    private static void flushRegionBatch() {
        if (REGION_BUFFER_SIZE.get() == 0) {
            return;
        }

        int pendingBeforeFlush = REGION_BUFFER_SIZE.get();
        if (VERBOSE_LOGGING) {
            LC2H.LOGGER.debug("Flushing region warmup queue (pending={})", pendingBeforeFlush);
        } else if (pendingBeforeFlush > 0) {
            if (VERBOSE_LOGGING) {
                LC2H.LOGGER.info("LC2H warmup: submitting {} regions for background processing", pendingBeforeFlush);
            } else if (org.admany.lc2h.config.ConfigManager.ENABLE_DEBUG_LOGGING) {
                LC2H.LOGGER.info("LC2H warmup: submitting {} regions for background processing", pendingBeforeFlush);
            } else {
                LC2H.LOGGER.debug("LC2H warmup: submitting {} regions for background processing", pendingBeforeFlush);
            }
        }
        long startTime = System.nanoTime();

        try {
            List<TaskBatchItem<Boolean>> regionBatchTasks = new ArrayList<>();
            List<RegionProviderPair> regionsToProcess;
            regionsToProcess = new ArrayList<>();
            RegionProviderPair next;
            while ((next = REGION_BUFFER.poll()) != null) {
                regionsToProcess.add(next);
            }
            int drained = regionsToProcess.size();
            if (drained > 0) {
                int remaining = REGION_BUFFER_SIZE.addAndGet(-drained);
                if (remaining < 0) {
                    REGION_BUFFER_SIZE.set(0);
                }
            }

            for (RegionProviderPair pair : regionsToProcess) {
                IDimensionInfo provider = pair.provider();
                RegionCoord region = pair.region();
                if (shouldCullWarmup() && !isRegionInView(region)) {
                    ViewCullingStats.recordWarmupBatch(1);
                    continue;
                }

                if (VERBOSE_LOGGING) {
                    LC2H.LOGGER.debug("Scheduling region warmup task for {}", region);
                }

                regionBatchTasks.add(new TaskBatchItem<Boolean>(
                    "region-" + region,
                    () -> { processEntireRegion(provider, region); return true; },
                    createRegionGPUTask(provider, region), 
                    CHUNKS_PER_REGION * 2048L, 
                    CHUNKS_PER_REGION * 256   
                ));
            }
            if (!regionBatchTasks.isEmpty()) {
                activeBatches.incrementAndGet();
                CompletableFuture<List<Boolean>> regionBatchFuture = TaskScheduler.submitBatch(
                    "lc2h", "region-warmup", regionBatchTasks,
                    task -> task.gpuTask() != null ? ResourceHint.GPU : ResourceHint.CPU
                );

                regionBatchFuture.whenComplete((results, throwable) -> {
                    activeBatches.decrementAndGet();
                    if (throwable != null) {
                        LC2H.LOGGER.error("Region batch warmup failed: {}", throwable.getMessage());
                    } else {
                        long endTime = System.nanoTime();
                        regionsProcessed.incrementAndGet();
                        if (VERBOSE_LOGGING) {
                            LC2H.LOGGER.debug("Completed region batch warmup for {} regions in {} ms",
                                regionsToProcess.size(), (endTime - startTime) / 1_000_000);
                        } else {
                            LC2H.LOGGER.info("LC2H warmup: processed {} regions in {} ms",
                                regionsToProcess.size(), (endTime - startTime) / 1_000_000);
                        }

                        if (regionsProcessed.get() % 10 == 0) {
                            long total = cacheHits.get() + cacheMisses.get();
                            double hitRate = total > 0 ? (cacheHits.get() * 100.0) / total : 0.0;
                            LC2H.LOGGER.info("Region Warmup Stats - Cache hit rate: {}%, Regions: {}, Active: {}",
                                String.format(java.util.Locale.ROOT, "%.1f", hitRate), regionsProcessed.get(), activeBatches.get());
                        }
                    }
                });
            }
        } catch (Throwable t) {
            LC2H.LOGGER.error("Region flush batch failed: {}", t.getMessage());
        }
    }

    private static void requestFlush(boolean immediate) {
        if (!flushScheduled.compareAndSet(false, true)) {
            return;
        }

        Runnable dispatcher = () -> {
            if (activeBatches.get() >= MAX_CONCURRENT_BATCHES) {
                if (VERBOSE_LOGGING) {
                    LC2H.LOGGER.debug("Deferring region warmup flush; active batches={}", activeBatches.get());
                }
                flushScheduled.set(false);
                AsyncManager.runLater("region-warmup-flush-retry", () -> requestFlush(false), 50L, Priority.LOW);
                return;
            }

            if (VERBOSE_LOGGING) {
                LC2H.LOGGER.debug("Dispatching region warmup flush (inflight batches={})", activeBatches.get());
            }
            AsyncManager.submitTask("region-warmup-flush", () -> {
                flushRegionBatch();
            }, null, Priority.LOW).whenComplete((ignored, throwable) -> {
                flushScheduled.set(false);
                if (VERBOSE_LOGGING) {
                    LC2H.LOGGER.debug("Region warmup flush complete (inflight batches={})", activeBatches.get());
                }
            });
        };

        if (immediate) {
            if (VERBOSE_LOGGING) {
                LC2H.LOGGER.debug("Immediate flush requested (buffer size={})", REGION_BUFFER_SIZE.get());
            }
            dispatcher.run();
        } else {
            if (VERBOSE_LOGGING) {
                LC2H.LOGGER.debug("Scheduled delayed flush (buffer size={})", REGION_BUFFER_SIZE.get());
            }
            AsyncManager.runLater("region-warmup-flush", dispatcher, 50L, Priority.LOW);
        }
    }

    public static void kickFlushMaybe() {
        if (REGION_BUFFER_SIZE.get() == 0) {
            return;
        }
        if (activeBatches.get() >= MAX_CONCURRENT_BATCHES) {
            return;
        }
        long now = System.currentTimeMillis();
        long last = lastFlushKickMs.get();
        if (now - last < 50L) {
            return;
        }
        if (!lastFlushKickMs.compareAndSet(last, now)) {
            return;
        }
        requestFlush(true);
    }

    private static void processEntireRegion(IDimensionInfo provider, RegionCoord region) {
        if (VERBOSE_LOGGING) {
            LC2H.LOGGER.debug("Processing entire region {}", region);
        }

        long startTime = System.nanoTime();

        try {
            for (int localX = 0; localX < REGION_SIZE; localX++) {
                for (int localZ = 0; localZ < REGION_SIZE; localZ++) {
                    ChunkCoord chunk = region.getChunk(localX, localZ);

                    AsyncMultiChunkPlanner.preSchedule(provider, chunk);
                    AsyncBuildingInfoPlanner.preSchedule(provider, chunk);
                    AsyncTerrainFeaturePlanner.preSchedule(provider, chunk);
                    AsyncPaletteGenerator.preSchedule(provider, chunk);
                    AsyncDebrisGenerator.preSchedule(provider, chunk);
                    AsyncTerrainCorrectionPlanner.preSchedule(provider, chunk);
                }
            }

            long endTime = System.nanoTime();
            if (VERBOSE_LOGGING) {
                LC2H.LOGGER.debug("Completed region {} in {} ms", region, (endTime - startTime) / 1_000_000);
            }

        } catch (Throwable t) {
            LC2H.LOGGER.error("Failed to process region {}: {}", region, t.getMessage());
            throw t;
        }
    }


    private static Object createRegionGPUTask(IDimensionInfo provider, RegionCoord region) {
        if (!GPU_ENABLED) {
            return null;
        }
        if (!isOpenClAvailable()) {
            logOpenClStatusMaybe("createRegionGPUTask");
            return null;
        }
        try {
            RegionProcessingGPUTask workload = new RegionProcessingGPUTask(provider, region);
            long taskKey = workload.taskKey();

            Object gpuTask = QuantifiedOpenCL.<Boolean>builder(LC2H.MODID, "region-processing-" + region, taskKey)
                .cpuFallback(() -> RegionProcessingGPUTask.processRegionOnCPU(provider, region))
                .workload(workload)
                .dataSizeBytes(RegionProcessingGPUTask.VRAM_BYTES)
                .parallelUnits(RegionProcessingGPUTask.PARALLEL_UNITS)
                .complexity(QuantifiedOpenCL.Complexity.COMPLEX)
                .kind(QuantifiedOpenCL.WorkloadKind.SPATIAL_ANALYSIS)
                .timeout(Duration.ofSeconds(30))
                .buildTask();
            return gpuTask;
        } catch (LinkageError linkageError) {
            if (VERBOSE_LOGGING) {
                LC2H.LOGGER.debug("GPU task initialization skipped for {} due to missing dependencies: {}", region, linkageError.toString());
            } else {
                LC2H.LOGGER.warn("GPU acceleration unavailable for region {} (missing dependencies: {}). Falling back to CPU processing.", region, linkageError.getMessage());
            }
            return null;
        } catch (Exception e) {
            LC2H.LOGGER.warn("Failed to create GPU task for region {}, falling back to CPU: {}", region, e.getMessage());
            return null; 
        }
    }

    private static boolean ensureGpuReady() {
        boolean available = isOpenClAvailable();
        if (available) {
            gpuReadyOnce = true;
            lastFailedGpuProbe.set(0);
            return true;
        }

        lastFailedGpuProbe.set(System.currentTimeMillis());
        return false;
    }

    private static boolean isOpenClAvailable() {
        if (QuantifiedOpenCL.isGpuReady()) {
            return true;
        }
        try {
            return OpenCLManager.isAvailable();
        } catch (Throwable t) {
            if (VERBOSE_LOGGING) {
                LC2H.LOGGER.debug("OpenCLManager.isAvailable() failed: {}", t.toString());
            }
            return false;
        }
    }

    private static void ensureOpenClProbeScheduled() {
        if (openClProbeStarted.get()) {
            return;
        }

        if (!isLwjglOpenClPresent()) {
            openClProbeStarted.set(true);
            return;
        }
        try {
            OpenCLManager.initialize();
            openClProbeStarted.set(true);
        } catch (Throwable t) {
            // If the core is not present or OpenCL initialization fails, we continue running CPU-only.
            if (VERBOSE_LOGGING) {
                LC2H.LOGGER.debug("OpenCLManager.initialize() failed: {}", t.toString());
            }
            openClProbeStarted.set(true);
        }
    }

    private static boolean isLwjglOpenClPresent() {
        try {
            ClassLoader cl = AsyncChunkWarmup.class.getClassLoader();
            Class.forName("org.lwjgl.opencl.CL", false, cl);
            // Quantified's current OpenCL probe may reference this - if it's missing, probing will throw.
            Class.forName("org.lwjgl.system.CustomBuffer", false, cl);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void logOpenClStatusMaybe(String source) {
        // If OpenCL is now available, we avoid logging stale status and allow future failures to be logged again.
        if (isOpenClAvailable()) {
            gpuProbeWarningLogged.set(false);
            return;
        }

        long now = System.currentTimeMillis();
        try {
            OpenCLManager.RuntimeStatus status = OpenCLManager.runtimeStatus();
            if (status != null && !status.isAvailable()) {
                String reason = status.failureReason();
                if (reason == null || reason.isBlank()) {
                    reason = "unknown";
                }

                String normalizedReason = reason.trim().toLowerCase(java.util.Locale.ROOT);
                boolean initInProgress = normalizedReason.contains("initialization in progress")
                    || normalizedReason.contains("initialising")
                    || normalizedReason.contains("initializing");

                // We log only when the status changes, and rate-limit repeat messages.
                String key = source + "|" + reason;
                String prev = lastOpenClStatusKey.get();
                boolean changed = !key.equals(prev);

                long last = lastOpenClStatusLogMs.get();
                long minIntervalMs = initInProgress ? 30_000L : 5_000L;
                if (!changed && now - last < minIntervalMs) {
                    return;
                }
                lastOpenClStatusKey.set(key);
                lastOpenClStatusLogMs.set(now);

                // "Initialization in progress" is a transient state - we avoid spamming INFO with "not available".
                if (initInProgress) {
                    if (VERBOSE_LOGGING) {
                        LC2H.LOGGER.debug("LC2H: OpenCL initialization in progress ({}): {}", source, reason);
                    }
                    return;
                }

                if (gpuProbeWarningLogged.compareAndSet(false, true) || VERBOSE_LOGGING) {
                    LC2H.LOGGER.info("LC2H: OpenCL not available ({}): {}", source, reason);
                }
            }
        } catch (Throwable ignored) {
        }
    }


    public static void startBackgroundPrefetch(IDimensionInfo provider, ChunkCoord playerChunk) {
        long now = System.currentTimeMillis();

        if (now - lastPrefetchTime < PREFETCH_COOLDOWN_MS) {
            return; 
        }

        RegionCoord playerRegion = RegionCoord.fromChunk(playerChunk);

        if (lastPrefetchRegion != null) {
            int distance = Math.abs(playerRegion.regionX() - lastPrefetchRegion.regionX()) +
                           Math.abs(playerRegion.regionZ() - lastPrefetchRegion.regionZ());
            if (distance < 1) { 
                return; 
            }
        }

        lastPrefetchTime = now;
        lastPrefetchRegion = playerRegion;

        if (VERBOSE_LOGGING) {
            LC2H.LOGGER.debug("Starting background prefetch for region {}", playerRegion);
        }

        try {
            for (int dx = -PREFETCH_RADIUS; dx <= PREFETCH_RADIUS; dx++) {
                for (int dz = -PREFETCH_RADIUS; dz <= PREFETCH_RADIUS; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    RegionCoord targetRegion = new RegionCoord(
                        playerRegion.regionX() + dx,
                        playerRegion.regionZ() + dz,
                        playerRegion.dimension()
                    );
                    ChunkCoord prefetchChunk = targetRegion.getChunk(0, 0);
                    preSchedule(provider, prefetchChunk);
                }
            }
        } catch (Throwable t) {
            LC2H.LOGGER.error("Background region prefetch setup failed for {}: {}", playerRegion, t.getMessage());
        }
    }

    public static int getRegionBufferSize() {
        return REGION_BUFFER_SIZE.get();
    }

    public static int getActiveBatchCount() {
        return activeBatches.get();
    }

    public static long getCacheHits() {
        return cacheHits.get();
    }

    public static long getCacheMisses() {
        return cacheMisses.get();
    }

    public static int pruneExpiredEntries() {
        return pruneExpiredEntries(System.currentTimeMillis());
    }

    private static int pruneExpiredEntries(long now) {
        if (PRE_SCHEDULE_TTL_MS <= 0 || PRE_SCHEDULE_CACHE.isEmpty()) {
            return 0;
        }
        int removed = 0;
        int scanned = 0;
        for (Map.Entry<RegionCoord, Long> entry : PRE_SCHEDULE_CACHE.entrySet()) {
            if (PRE_SCHEDULE_PRUNE_SCAN > 0 && scanned >= PRE_SCHEDULE_PRUNE_SCAN) {
                break;
            }
            scanned++;
            Long value = entry.getValue();
            if (value != null && isExpired(value, now)) {
                if (PRE_SCHEDULE_CACHE.remove(entry.getKey(), value)) {
                    removed++;
                }
            }
        }
        return removed;
    }

    public static void shutdown() {
        REGION_BUFFER.clear();
        REGION_BUFFER_SIZE.set(0);
        PRE_SCHEDULE_CACHE.clear();
        flushScheduled.set(false);
        activeBatches.set(0);
    }

    private static boolean enqueueRegion(IDimensionInfo provider, RegionCoord region) {
        if (shouldCullWarmup() && !isRegionInView(region)) {
            ViewCullingStats.recordWarmupQueue(1);
            return false;
        }
        int current;
        while (true) {
            current = REGION_BUFFER_SIZE.get();
            if (current >= MAX_REGION_QUEUE) {
                RateLimitedLogger.warn(
                    "lc2h-warmup-queue-full",
                    "LC2H warmup: region queue full ({} >= {}). Dropping {}",
                    current,
                    MAX_REGION_QUEUE,
                    region
                );
                return false;
            }
            if (REGION_BUFFER_SIZE.compareAndSet(current, current + 1)) {
                break;
            }
        }
        REGION_BUFFER.add(new RegionProviderPair(provider, region));
        return true;
    }

    private static boolean shouldCullWarmup() {
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
        return true;
    }

    private static boolean isRegionInView(RegionCoord region) {
        if (region == null || region.dimension() == null) {
            return true;
        }
        return ChunkPriorityManager.isRegionWithinViewDistance(
            region.dimension().location(),
            region.getMinChunkX(),
            region.getMaxChunkX(),
            region.getMinChunkZ(),
            region.getMaxChunkZ()
        );
    }

    private static long encodeTimestamp(long nowMs, boolean gpuReady) {
        long ts = nowMs & Long.MAX_VALUE;
        return gpuReady ? (ts | GPU_READY_FLAG) : ts;
    }

    private static boolean isGpuReadyFlag(long encoded) {
        return (encoded & GPU_READY_FLAG) != 0;
    }

    private static boolean isExpired(long encoded, long nowMs) {
        if (PRE_SCHEDULE_TTL_MS <= 0) {
            return false;
        }
        long ts = encoded & Long.MAX_VALUE;
        return (nowMs - ts) > PRE_SCHEDULE_TTL_MS;
    }

    private static void maybePrunePreScheduleCache(long nowMs) {
        if (PRE_SCHEDULE_TTL_MS <= 0) {
            return;
        }
        int ops = preScheduleOps.incrementAndGet();
        if (ops < PRE_SCHEDULE_PRUNE_EVERY) {
            return;
        }
        if (!preScheduleOps.compareAndSet(ops, 0)) {
            return;
        }
        pruneExpiredEntries(nowMs);
    }
}
