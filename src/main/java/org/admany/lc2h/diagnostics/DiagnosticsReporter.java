package org.admany.lc2h.diagnostics;

import net.minecraft.server.MinecraftServer;
import org.admany.lc2h.LC2H;
import org.admany.lc2h.core.MainThreadChunkApplier;
import org.admany.lc2h.worldgen.async.planner.AsyncMultiChunkPlanner;
import org.admany.lc2h.worldgen.async.planner.PlannerBatchQueue;
import org.admany.lc2h.worldgen.async.warmup.AsyncChunkWarmup;
import org.admany.lc2h.tweaks.TweaksActorSystem;
import org.admany.lc2h.util.chunk.ChunkPostProcessor;
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

        // Only run during spawn prep / pre-join.
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
            LC2H.LOGGER.warn("[LC2H] startup-watchdog: t={}s planned={}, applyQueue={}, plannerPending={}, plannerBatches={}, warmupQueuedRegions={}, warmupActiveBatches={}, parallelActiveSlices={}, parallelQueuedSlices={}, plannerKinds={}",
                elapsedSec, planned, applyQueue, planner.pendingTasks(), planner.batchCount(), regions, active, parallelActive, parallelQueued, planner.pendingByKind());
        } else {
            LC2H.LOGGER.debug("[LC2H] startup-watchdog: t={}s planned={}, applyQueue={}, plannerPending={}, plannerBatches={}, warmupQueuedRegions={}, warmupActiveBatches={}, parallelActiveSlices={}, parallelQueuedSlices={}, plannerKinds={}",
                elapsedSec, planned, applyQueue, planner.pendingTasks(), planner.batchCount(), regions, active, parallelActive, parallelQueued, planner.pendingByKind());
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
