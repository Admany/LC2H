package org.admany.lc2h.mixin.lostcities.railway;

import mcjty.lostcities.config.LostCityProfile;
import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.IDimensionInfo;
import mcjty.lostcities.worldgen.lost.CitySphere;
import mcjty.lostcities.worldgen.lost.Railway;
import org.admany.lc2h.data.cache.LostCitiesCacheBridge;
import org.admany.lc2h.data.cache.LostCitiesCacheBudgetManager;
import org.admany.lc2h.dev.diagnostics.Lc2hTimingRegistry;
import org.admany.lc2h.worldgen.lostcities.FastMultiChunkPlanner;
import org.admany.lc2h.worldgen.lostcities.LostCitiesGuiPreviewGuard;
import org.admany.lc2h.worldgen.lostcities.PlannerHotPath;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Mixin(value = Railway.class, remap = false)
public abstract class MixinRailway {

    private static final ConcurrentMap<ChunkCoord, Railway.RailChunkInfo> LC2H_RAIL_INFO = new ConcurrentHashMap<>();
    @Unique
    private static final Object LC2H_RAIL_COMPUTE_LOCK = new Object();
    private static final LostCitiesCacheBudgetManager.CacheGroup LC2H_RAIL_BUDGET =
        LostCitiesCacheBudgetManager.register("lc_rail_info", 64, 512, key -> LC2H_RAIL_INFO.remove(key) != null);

    @Shadow
    private static Railway.RailChunkInfo getRailChunkTypeInternal(ChunkCoord key, IDimensionInfo provider) { return null; }

    /** Uses a concurrent railway cache on the frequent building-placement path. */
    @Overwrite
    public static Railway.RailChunkInfo getRailChunkType(ChunkCoord coord, IDimensionInfo provider, LostCityProfile profile) {
        Railway.RailChunkInfo cached = LC2H_RAIL_INFO.get(coord);
        if (cached != null) {
            LostCitiesCacheBudgetManager.recordAccess(LC2H_RAIL_BUDGET, coord);
            return cached;
        }
        // Lost Cities normally builds the complete recursive rail graph and
        // only then throws the result away when both rail features are off.
        // That can touch thousands of heightmaps from one multibuilding check.
        // NOTHING is the exact final result in this configuration, so publish
        // it before entering the graph walker.
        if (!profile.RAILWAYS_ENABLED && !profile.RAILWAY_STATIONS_ENABLED) {
            Railway.RailChunkInfo previous = LC2H_RAIL_INFO.putIfAbsent(coord, Railway.RailChunkInfo.NOTHING);
            LostCitiesCacheBudgetManager.recordPut(
                LC2H_RAIL_BUDGET,
                coord,
                LC2H_RAIL_BUDGET.defaultEntryBytes(),
                previous == null);
            return previous != null ? previous : Railway.RailChunkInfo.NOTHING;
        }
        if (LostCitiesGuiPreviewGuard.shouldDefer(provider, profile, coord, "rail")) {
            return Railway.RailChunkInfo.NOTHING;
        }
        if (!PlannerHotPath.isActive()) {
            Railway.RailChunkInfo disk = LostCitiesCacheBridge.getDisk("rail_info", coord, Railway.RailChunkInfo.class);
            if (disk != null) {
                Railway.RailChunkInfo prev = LC2H_RAIL_INFO.putIfAbsent(coord, disk);
                LostCitiesCacheBudgetManager.recordPut(LC2H_RAIL_BUDGET, coord, LC2H_RAIL_BUDGET.defaultEntryBytes(), prev == null);
                return prev != null ? prev : disk;
            }
        }

        long waitStart = System.nanoTime();
        synchronized (LC2H_RAIL_COMPUTE_LOCK) {
            Lc2hTimingRegistry.record("rail.cache_miss_wait", System.nanoTime() - waitStart);
            cached = LC2H_RAIL_INFO.get(coord);
            if (cached != null) {
                LostCitiesCacheBudgetManager.recordAccess(LC2H_RAIL_BUDGET, coord);
                return cached;
            }

            long computeStart = System.nanoTime();
            Railway.RailChunkInfo info = getRailChunkTypeInternal(coord, provider);

            if ((provider.getProfile().isSpace() || provider.getProfile().isSpheres()) && CitySphere.onCitySphereBorder(coord, provider)) {
                info = Railway.RailChunkInfo.NOTHING;
            } else if (info.getType().isStation()) {
                if (!profile.RAILWAY_STATIONS_ENABLED) {
                    info = Railway.RailChunkInfo.NOTHING;
                }
            } else if (!profile.RAILWAYS_ENABLED) {
                info = Railway.RailChunkInfo.NOTHING;
            }

            LC2H_RAIL_INFO.put(coord, info);
            LostCitiesCacheBudgetManager.recordPut(LC2H_RAIL_BUDGET, coord, LC2H_RAIL_BUDGET.defaultEntryBytes(), true);
            if (!PlannerHotPath.isActive()) {
                LostCitiesCacheBridge.putDisk("rail_info", coord, info);
            }
            Lc2hTimingRegistry.record("rail.cache_miss_compute", System.nanoTime() - computeStart);
            return info;
        }
    }

    @Redirect(
        method = "getRailChunkTypeInternal",
        at = @At(
            value = "INVOKE",
            target = "Lmcjty/lostcities/worldgen/lost/BuildingInfo;isCityRaw(Lmcjty/lostcities/varia/ChunkCoord;Lmcjty/lostcities/worldgen/IDimensionInfo;Lmcjty/lostcities/config/LostCityProfile;)Z"
        ),
        require = 0
    )
    private static boolean lc2h$reusePlannerCityRaw(ChunkCoord coord, IDimensionInfo provider, LostCityProfile profile) {
        return FastMultiChunkPlanner.resolveRailCityRaw(coord, provider, profile);
    }

    /** Clears the railway cache at the lifecycle boundary. */
    @Overwrite
    public static void cleanCache() {
        LC2H_RAIL_INFO.clear();
        LostCitiesCacheBudgetManager.clear(LC2H_RAIL_BUDGET);
    }

    /** Publishes a railway result to the concurrent cache. */
    @Overwrite
    public static void removeRailChunkType(ChunkCoord coord) {
        Railway.RailChunkInfo prev = LC2H_RAIL_INFO.put(coord, Railway.RailChunkInfo.NOTHING);
        LostCitiesCacheBudgetManager.recordPut(LC2H_RAIL_BUDGET, coord, LC2H_RAIL_BUDGET.defaultEntryBytes(), prev == null);
        LostCitiesCacheBridge.putDisk("rail_info", coord, Railway.RailChunkInfo.NOTHING);
    }
}
