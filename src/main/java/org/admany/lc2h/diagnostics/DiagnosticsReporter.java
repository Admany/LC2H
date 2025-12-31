package org.admany.lc2h.diagnostics;

import net.minecraft.server.MinecraftServer;
import org.admany.lc2h.LC2H;
import org.admany.lc2h.core.MainThreadChunkApplier;
import org.admany.lc2h.data.cache.FeatureCache;
import org.admany.lc2h.worldgen.async.generator.AsyncDebrisGenerator;
import org.admany.lc2h.worldgen.async.generator.AsyncPaletteGenerator;
import org.admany.lc2h.worldgen.async.planner.AsyncMultiChunkPlanner;
import org.admany.lc2h.worldgen.async.planner.AsyncBuildingInfoPlanner;
import org.admany.lc2h.worldgen.async.planner.AsyncTerrainCorrectionPlanner;
import org.admany.lc2h.worldgen.async.planner.AsyncTerrainFeaturePlanner;
import org.admany.lc2h.worldgen.async.planner.PlannerBatchQueue;
import org.admany.lc2h.worldgen.async.warmup.AsyncChunkWarmup;
import org.admany.lc2h.tweaks.TweaksActorSystem;
import org.admany.lc2h.util.chunk.ChunkPostProcessor;
import org.admany.lc2h.worldgen.gpu.GPUMemoryManager;
import org.admany.lc2h.worldgen.lostcities.LostCityFeatureGuards;
import org.admany.lc2h.worldgen.lostcities.LostCityTerrainFeatureGuards;
import org.admany.quantified.api.QuantifiedAPI;
import org.admany.quantified.api.interfaces.ModCacheManager;
import org.admany.quantified.core.common.parallel.metrics.ParallelMetrics;
import org.admany.quantified.core.common.parallel.throttle.ParallelBackpressure;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;


public final class DiagnosticsReporter {

    private static final ScheduledExecutorService SCHED = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "lc2h-diagnostics");
        t.setDaemon(true);
        return t;
    });

    private static volatile boolean running = false;
    private static volatile MinecraftServer SERVER = null;
    private static volatile boolean startupWatchdogEnabled = true;
    private static final long STARTUP_WATCHDOG_PERIOD_SEC = Math.max(1L, Long.getLong("lc2h.startupWatchdog.period_sec", 2L));
    private static final long STARTUP_WATCHDOG_MAX_SEC = Math.max(30L, Long.getLong("lc2h.startupWatchdog.max_sec", 10 * 60L));
    private static final long STARTUP_WATCHDOG_BACKLOG_WARN_SEC = Math.max(5L, Long.getLong("lc2h.startupWatchdog.warn_after_sec", 20L));
    private static volatile long startupWatchdogStartNs = 0L;

    private DiagnosticsReporter() {}

    public static void start(MinecraftServer server) {
        if (running) return;
        running = true;
        SERVER = server;
        startupWatchdogEnabled = Boolean.parseBoolean(System.getProperty("lc2h.startupWatchdog", "true"));
        startupWatchdogStartNs = System.nanoTime();

        long periodSec = Math.max(5, Long.getLong("lc.diagnostics.period_sec", 60L));

        SCHED.scheduleAtFixedRate(() -> {
            try {
                tickStartupWatchdog();
            } catch (Throwable t) {
                LC2H.LOGGER.debug("[LC2H] Startup watchdog error: {}", t.getMessage());
            }
        }, 1, STARTUP_WATCHDOG_PERIOD_SEC, TimeUnit.SECONDS);

        SCHED.scheduleAtFixedRate(() -> {
            try {
                int scans = ChunkPostProcessor.getPendingScanCount();
                int regions = AsyncChunkWarmup.getRegionBufferSize();
                int active = AsyncChunkWarmup.getActiveBatchCount();
                long hits = AsyncChunkWarmup.getCacheHits();
                long misses = AsyncChunkWarmup.getCacheMisses();
                int planned = AsyncMultiChunkPlanner.getPlannedCount();
                int gpuData = AsyncMultiChunkPlanner.getGpuDataCacheSize();
                int applyQueue = MainThreadChunkApplier.getQueueSize();
                PlannerBatchQueue.PlannerBatchStats plannerStats = PlannerBatchQueue.snapshotStats();
                int inflight = TweaksActorSystem.getInFlightCount();
                int validated = TweaksActorSystem.getValidatedCount();
                ParallelMetrics.Snapshot parallel = ParallelMetrics.snapshot();
                long parallelActive = parallel.modActiveSlices().values().stream().mapToLong(Long::longValue).sum();
                long parallelQueued = ParallelBackpressure.queued();
                long cacheHits = parallel.cacheHits();
                long cacheMisses = parallel.cacheMisses();

                if (org.admany.lc2h.logging.config.ConfigManager.ENABLE_DEBUG_LOGGING) {
                    LC2H.LOGGER.info("[LC2H] diagnostics: scans={}, regionsQueued={}, regionActiveBatches={}, regionHits={}, regionMisses={}, planned={}, gpuData={}, applyQueue={}, plannerBatches={}, plannerPendingTasks={}, tweaksInFlight={}, tweaksValidated={}, parallelActiveSlices={}, parallelQueuedSlices={}, parallelCacheHits={}, parallelCacheMisses={}",
                        scans, regions, active, hits, misses, planned, gpuData, applyQueue, plannerStats.batchCount(), plannerStats.pendingTasks(), inflight, validated, parallelActive, parallelQueued, cacheHits, cacheMisses);
                } else {
                    LC2H.LOGGER.debug("[LC2H] diagnostics: scans={}, regionsQueued={}, regionActiveBatches={}, regionHits={}, regionMisses={}, planned={}, gpuData={}, applyQueue={}, plannerBatches={}, plannerPendingTasks={}, tweaksInFlight={}, tweaksValidated={}, parallelActiveSlices={}, parallelQueuedSlices={}, parallelCacheHits={}, parallelCacheMisses={}",
                        scans, regions, active, hits, misses, planned, gpuData, applyQueue, plannerStats.batchCount(), plannerStats.pendingTasks(), inflight, validated, parallelActive, parallelQueued, cacheHits, cacheMisses);
                }

                if (scans > 200 || regions > 50 || inflight > 100 || planned > 50 || parallelQueued > 2048 || parallelActive > 512 || applyQueue > 100 || plannerStats.pendingTasks() > 200) {
                    if (org.admany.lc2h.logging.config.ConfigManager.ENABLE_DEBUG_LOGGING) {
                        LC2H.LOGGER.warn("[LC2H] diagnostics: backlog detected (scans={}, regionsQueued={}, inflight={}, planned={}, applyQueue={}, plannerPendingTasks={}, parallelActive={}, parallelQueued={})",
                            scans, regions, inflight, planned, applyQueue, plannerStats.pendingTasks(), parallelActive, parallelQueued);
                    } else {
                        LC2H.LOGGER.debug("[LC2H] diagnostics: backlog detected (scans={}, regionsQueued={}, inflight={}, planned={}, applyQueue={}, plannerPendingTasks={}, parallelActive={}, parallelQueued={})",
                            scans, regions, inflight, planned, applyQueue, plannerStats.pendingTasks(), parallelActive, parallelQueued);
                    }
                }

                reportCacheUsage();
                pruneInternalCaches();
            } catch (Throwable t) {
                LC2H.LOGGER.error("[LC2H] DiagnosticsReporter error: {}", t.getMessage());
            }
        }, 1, periodSec, TimeUnit.SECONDS);
    }

    private static void tickStartupWatchdog() {
        if (!startupWatchdogEnabled) {
            return;
        }
        MinecraftServer server = SERVER;
        if (server == null) {
            return;
        }
        long elapsedSec = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - startupWatchdogStartNs);
        if (elapsedSec > STARTUP_WATCHDOG_MAX_SEC) {
            startupWatchdogEnabled = false;
            return;
        }

        if (server.getPlayerList() != null && server.getPlayerList().getPlayerCount() > 0) {
            startupWatchdogEnabled = false;
            return;
        }

        int regions = AsyncChunkWarmup.getRegionBufferSize();
        int active = AsyncChunkWarmup.getActiveBatchCount();
        int planned = AsyncMultiChunkPlanner.getPlannedCount();
        int applyQueue = MainThreadChunkApplier.getQueueSize();
        PlannerBatchQueue.PlannerBatchStats planner = PlannerBatchQueue.snapshotStats();
        ParallelMetrics.Snapshot parallel = ParallelMetrics.snapshot();
        long parallelActive = parallel.modActiveSlices().values().stream().mapToLong(Long::longValue).sum();
        long parallelQueued = ParallelBackpressure.queued();

        boolean backlog = regions > 0 || active > 0 || planned > 0 || applyQueue > 0 || planner.pendingTasks() > 0 || parallelQueued > 0 || parallelActive > 0;
        if (!backlog) {
            return;
        }

        boolean warn = elapsedSec >= STARTUP_WATCHDOG_BACKLOG_WARN_SEC;
        if (warn) {
            LC2H.LOGGER.debug("[LC2H] startup-watchdog: t={}s planned={}, applyQueue={}, plannerPending={}, plannerBatches={}, warmupQueuedRegions={}, warmupActiveBatches={}, parallelActiveSlices={}, parallelQueuedSlices={}, plannerKinds={}",
                elapsedSec, planned, applyQueue, planner.pendingTasks(), planner.batchCount(), regions, active, parallelActive, parallelQueued, planner.pendingByKind());
        } else {
            LC2H.LOGGER.debug("[LC2H] startup-watchdog: t={}s planned={}, applyQueue={}, plannerPending={}, plannerBatches={}, warmupQueuedRegions={}, warmupActiveBatches={}, parallelActiveSlices={}, parallelQueuedSlices={}, plannerKinds={}",
                elapsedSec, planned, applyQueue, planner.pendingTasks(), planner.batchCount(), regions, active, parallelActive, parallelQueued, planner.pendingByKind());
        }
    }

    private static void reportCacheUsage() {
        try {
            long quantifiedEntries = 0L;
            long quantifiedBytes = 0L;
            try {
                ModCacheManager cacheManager = QuantifiedAPI.getCacheManager(LC2H.MODID);
                if (cacheManager != null) {
                    quantifiedEntries = cacheManager.getTotalCacheEntryCount();
                    quantifiedBytes = cacheManager.getTotalCacheSizeMB() * 1024L * 1024L;
                }
            } catch (Throwable ignored) {
            }

            long internalEntries = 0L;
            long internalBytes = 0L;

            internalEntries += GPUMemoryManager.getCachedEntryCount();
            internalBytes += GPUMemoryManager.getCachedBytes();

            internalEntries += GPUMemoryManager.getDiskCacheEntryCount();
            internalBytes += GPUMemoryManager.getDiskCacheBytes();

            long featureEntries = FeatureCache.getLocalEntryCount();
            internalEntries += featureEntries;
            internalBytes += FeatureCache.getLocalMemoryBytes();

            long totalEntries = quantifiedEntries + internalEntries;
            long totalBytes = quantifiedBytes + internalBytes;
            QuantifiedAPI.reportCacheUsage(LC2H.MODID, totalEntries, totalBytes);
        } catch (Throwable t) {
            LC2H.LOGGER.debug("[LC2H] Failed to report cache usage: {}", t.getMessage());
        }
    }

    private static void pruneInternalCaches() {
        long now = System.currentTimeMillis();
        try {
            FeatureCache.pruneExpiredEntries();
        } catch (Throwable ignored) {
        }
        try {
            AsyncBuildingInfoPlanner.pruneExpiredEntries();
        } catch (Throwable ignored) {
        }
        try {
            AsyncTerrainFeaturePlanner.pruneExpiredEntries();
        } catch (Throwable ignored) {
        }
        try {
            AsyncTerrainCorrectionPlanner.pruneExpiredEntries();
        } catch (Throwable ignored) {
        }
        try {
            AsyncPaletteGenerator.pruneExpiredEntries();
        } catch (Throwable ignored) {
        }
        try {
            AsyncDebrisGenerator.pruneExpiredEntries();
        } catch (Throwable ignored) {
        }
        try {
            TweaksActorSystem.pruneExpiredEntries();
        } catch (Throwable ignored) {
        }
        try {
            LostCityFeatureGuards.pruneExpired(now);
        } catch (Throwable ignored) {
        }
        try {
            LostCityTerrainFeatureGuards.pruneExpired(now);
        } catch (Throwable ignored) {
        }
        try {
            AsyncChunkWarmup.pruneExpiredEntries();
        } catch (Throwable ignored) {
        }
    }

    public static void stop() {
        if (!running) return;
        running = false;
        SERVER = null;
        try {
            SCHED.shutdownNow();
        } catch (Throwable ignored) {}
    }
}
