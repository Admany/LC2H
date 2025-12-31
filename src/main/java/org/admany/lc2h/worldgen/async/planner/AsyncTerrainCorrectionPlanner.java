package org.admany.lc2h.worldgen.async.planner;

import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.IDimensionInfo;
import org.admany.lc2h.LC2H;
import org.admany.lc2h.util.cache.CacheTtl;
import org.admany.lc2h.worldgen.gpu.GPUMemoryManager;
import org.admany.lc2h.worldgen.async.warmup.AsyncChunkWarmup;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class AsyncTerrainCorrectionPlanner {

    private static final ConcurrentHashMap<ChunkCoord, Long> COMPUTATION_CACHE = new ConcurrentHashMap<>();
    private static final long COMPUTATION_CACHE_TTL_MS = Math.max(30_000L,
        Long.getLong("lc2h.terrainCorrection.cacheTtlMs", TimeUnit.MINUTES.toMillis(20)));
    private static final int COMPUTATION_CACHE_PRUNE_SCAN = Math.max(128,
        Integer.getInteger("lc2h.terrainCorrection.cachePruneScan", 512));
    private static final int COMPUTATION_CACHE_PRUNE_EVERY = Math.max(64,
        Integer.getInteger("lc2h.terrainCorrection.cachePruneEvery", 128));
    private static final AtomicInteger COMPUTATION_CACHE_PRUNE_COUNTER = new AtomicInteger(0);

    public static final ConcurrentHashMap<ChunkCoord, float[]> GPU_DATA_CACHE = new ConcurrentHashMap<>();

    private AsyncTerrainCorrectionPlanner() {
    }

    public static void preSchedule(IDimensionInfo provider, ChunkCoord coord) {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(coord, "coord");

        long now = System.currentTimeMillis();
        if (CacheTtl.markIfFresh(COMPUTATION_CACHE, coord, COMPUTATION_CACHE_TTL_MS, now)) {
            return;
        }
        maybePrune(now);

        boolean debugLogging = AsyncChunkWarmup.isWarmupDebugLoggingEnabled();

        if (GPUMemoryManager.getGPUData(coord, GPU_DATA_CACHE) != null) {
            if (debugLogging) {
                LC2H.LOGGER.debug("Used GPU data for terrain correction in {}", coord);
            }
            return;
        }

        if (debugLogging) {
            LC2H.LOGGER.debug("Starting preSchedule for {}", coord);
        }
        long startTime = System.nanoTime();

        PlannerBatchQueue.enqueue(provider, coord, PlannerTaskKind.TERRAIN_CORRECTION,
            () -> runTerrainCorrectionComputation(coord, provider, debugLogging, startTime));
    }

    private static void computeTerrainCorrections(ChunkCoord coord, IDimensionInfo provider) {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int correction = calculateTerrainCorrection(coord, x, z, provider);
                cacheTerrainCorrection(coord, x, z, correction);
            }
        }

        computeAddonTerrainCorrections(coord, provider);
    }

    private static void computeAddonTerrainCorrections(ChunkCoord coord, IDimensionInfo provider) {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int addonCorrection = calculateAddonTerrainCorrection(coord, x, z, provider);
                if (addonCorrection != 0) {
                    cacheAddonTerrainCorrection(coord, x, z, addonCorrection);
                }
            }
        }
    }

    private static int calculateTerrainCorrection(ChunkCoord coord, int x, int z, IDimensionInfo provider) {
        return 0;
    }

    private static int calculateAddonTerrainCorrection(ChunkCoord coord, int x, int z, IDimensionInfo provider) {
        return 0;
    }

    private static void cacheTerrainCorrection(ChunkCoord coord, int x, int z, int correction) {
    }

    private static void cacheAddonTerrainCorrection(ChunkCoord coord, int x, int z, int correction) {
    }

    public static void flushPendingCorrectionBatches() {
        PlannerBatchQueue.flushKind(PlannerTaskKind.TERRAIN_CORRECTION);
    }

    public static void shutdown() {
        try {
            LC2H.LOGGER.info("AsyncTerrainCorrectionPlanner: Shutting down");
            PlannerBatchQueue.flushKind(PlannerTaskKind.TERRAIN_CORRECTION);

            COMPUTATION_CACHE.clear();
            GPU_DATA_CACHE.clear();

            LC2H.LOGGER.info("AsyncTerrainCorrectionPlanner: Shutdown complete");
        } catch (Exception e) {
            LC2H.LOGGER.error("AsyncTerrainCorrectionPlanner: Error during shutdown", e);
        }
    }

    public static void pruneExpiredEntries() {
        long now = System.currentTimeMillis();
        CacheTtl.pruneExpired(COMPUTATION_CACHE, COMPUTATION_CACHE_TTL_MS, COMPUTATION_CACHE_PRUNE_SCAN, now);
    }

    private static void maybePrune(long nowMs) {
        if (COMPUTATION_CACHE_TTL_MS <= 0L) {
            return;
        }
        int count = COMPUTATION_CACHE_PRUNE_COUNTER.incrementAndGet();
        if (count >= COMPUTATION_CACHE_PRUNE_EVERY) {
            COMPUTATION_CACHE_PRUNE_COUNTER.set(0);
            CacheTtl.pruneExpired(COMPUTATION_CACHE, COMPUTATION_CACHE_TTL_MS, COMPUTATION_CACHE_PRUNE_SCAN, nowMs);
        }
    }

    private static void runTerrainCorrectionComputation(ChunkCoord coord, IDimensionInfo provider, boolean debugLogging, long startTime) {
        try {
            computeTerrainCorrections(coord, provider);

            if (debugLogging) {
                long endTime = System.nanoTime();
                LC2H.LOGGER.debug("Finished terrain correction compute for {} in {} ms", coord, (endTime - startTime) / 1_000_000);
            }
        } catch (Throwable t) {
            LC2H.LOGGER.error("Terrain correction computation failed for {}: {}", coord, t.getMessage());
        }
    }
}
