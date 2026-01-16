package org.admany.lc2h.worldgen.async.planner;

import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.IDimensionInfo;
import org.admany.lc2h.LC2H;
import org.admany.lc2h.concurrency.async.AsyncManager;
import org.admany.lc2h.data.cache.FeatureCache;

public final class AsyncCityPlanner {

    private AsyncCityPlanner() {
    }

    public static void planCityAsync(IDimensionInfo info, int chunkX, int chunkZ) {
        String cacheKey = "city_" + chunkX + "_" + chunkZ;

        Object cached = FeatureCache.get(cacheKey, true);
        if (cached != null) {
            LC2H.LOGGER.debug("Using cached city plan for " + cacheKey);
            return;
        }

        ChunkCoord coord = new ChunkCoord(info.getType(), chunkX, chunkZ);
        PlannerBatchQueue.enqueue(info, coord, PlannerTaskKind.CITY_LAYOUT,
            () -> runCityPlanning(info, chunkX, chunkZ, cacheKey));
    }

    public static void flushPendingCityBatches() {
        PlannerBatchQueue.flushKind(PlannerTaskKind.CITY_LAYOUT);
    }

    public static void shutdown() {
        PlannerBatchQueue.flushKind(PlannerTaskKind.CITY_LAYOUT);
    }

    private static void runCityPlanning(IDimensionInfo info, int chunkX, int chunkZ, String cacheKey) {
        try {
            LC2H.LOGGER.info("Planning city asynchronously for chunk " + chunkX + "," + chunkZ);
            Object result = new Object();
            FeatureCache.put(cacheKey, result, true);
            AsyncManager.syncToMain(() -> LC2H.LOGGER.debug("City planning completed for " + cacheKey));
        } catch (Throwable t) {
            LC2H.LOGGER.error("City planning failed for {}: {}", cacheKey, t.getMessage());
        }
    }
}
