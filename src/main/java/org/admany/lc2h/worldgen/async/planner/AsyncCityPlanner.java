package org.admany.lc2h.worldgen.async.planner;

import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.IDimensionInfo;
import org.admany.lc2h.LC2H;
import org.admany.lc2h.concurrency.async.AsyncManager;
import org.admany.lc2h.data.cache.Lc2hCacheKeys;
import org.admany.lc2h.worldgen.dag.LostCityDagScheduler;
import org.admany.lc2h.worldgen.kernel.JavaScalarLostCityKernel;
import org.admany.lc2h.worldgen.kernel.LostCityKernelSignature;
import org.admany.lc2h.worldgen.kernel.LostCityKernelStage;
import org.admany.lc2h.worldgen.kernel.LostCityStagePayloads;
import org.admany.quantified.api.CacheRequest;
import org.admany.quantified.api.QuantifiedAPI;

import java.time.Duration;

public final class AsyncCityPlanner {

    private static final Duration CITY_LAYOUT_DISK_TTL = Duration.ofHours(
        Math.max(1L, Long.getLong("lc2h.cityLayout.diskTtlHours", 12L))
    );
    private static final long CITY_LAYOUT_MAX_ENTRIES = Math.max(512L,
        Long.getLong("lc2h.cityLayout.maxEntries", 24_576L)
    );

    private AsyncCityPlanner() {
    }

    public static void planCityAsync(IDimensionInfo info, int chunkX, int chunkZ) {
        ChunkCoord coord = new ChunkCoord(info.getType(), chunkX, chunkZ);
        LostCityKernelSignature signature = LostCityKernelSignature.from(info, JavaScalarLostCityKernel.INSTANCE.capabilities());
        String cacheKey = Lc2hCacheKeys.stageKey(signature, LostCityKernelStage.PLAN_CITY_LAYOUT, coord, Lc2hCacheKeys.chunkScope(coord));
        CacheRequest cache = cityLayoutDiskCache();
        cache.getAsync(cacheKey, () -> null)
            .thenAccept(cached -> {
            if (cached instanceof LostCityStagePayloads.CityLayoutPlan) {
                LC2H.LOGGER.debug("Using cached city plan for " + cacheKey);
                return;
            }
            if (LostCityDagScheduler.isEnabled()) {
                LostCityDagScheduler.submitCityLayout(info, coord)
                    .whenComplete((result, throwable) -> {
                        if (throwable != null) {
                            LC2H.LOGGER.error("Kernel city planning failed for {}: {}", cacheKey, throwable.getMessage());
                            return;
                        }
                        LostCityStagePayloads.CityLayoutPlan plan = result == null ? null : result.payloadAs(LostCityStagePayloads.CityLayoutPlan.class);
                        if (plan != null) {
                            cache.put(cacheKey, plan);
                        }
                        AsyncManager.syncToMain(() -> LC2H.LOGGER.debug("Kernel city planning completed for " + cacheKey));
                    });
                return;
            }
            PlannerBatchQueue.enqueue(info, coord, PlannerTaskKind.CITY_LAYOUT,
                () -> runCityPlanning(info, chunkX, chunkZ, cacheKey));
        }).exceptionally(t -> {
            LC2H.LOGGER.error("City plan cache lookup failed for {}: {}", cacheKey, t.getMessage());
            return null;
        });
    }

    public static void flushPendingCityBatches() {
        PlannerBatchQueue.flushKind(PlannerTaskKind.CITY_LAYOUT);
    }

    public static void shutdown() {
    }

    private static void runCityPlanning(IDimensionInfo info, int chunkX, int chunkZ, String cacheKey) {
        try {
            LC2H.LOGGER.info("Planning city asynchronously for chunk " + chunkX + "," + chunkZ);
            ChunkCoord coord = new ChunkCoord(info.getType(), chunkX, chunkZ);
            LostCityStagePayloads.CityLayoutPlan result = new LostCityStagePayloads.CityLayoutPlan(coord, chunkX, chunkZ);
            cityLayoutDiskCache().put(cacheKey, result);
            AsyncManager.syncToMain(() -> LC2H.LOGGER.debug("City planning completed for " + cacheKey));
        } catch (Throwable t) {
            LC2H.LOGGER.error("City planning failed for {}: {}", cacheKey, t.getMessage());
        }
    }

    private static CacheRequest cityLayoutDiskCache() {
        return QuantifiedAPI.cache(LC2H.MODID, Lc2hCacheKeys.stageBucket(LostCityKernelStage.PLAN_CITY_LAYOUT, Lc2hCacheKeys.CacheTier.DISK))
            .ttl(CITY_LAYOUT_DISK_TTL)
            .maxEntries(CITY_LAYOUT_MAX_ENTRIES)
            .diskPreferred()
            .compressed()
            .refreshOnAccess();
    }
}
