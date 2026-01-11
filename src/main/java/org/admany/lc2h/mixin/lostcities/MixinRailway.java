package org.admany.lc2h.mixin.lostcities;

import mcjty.lostcities.config.LostCityProfile;
import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.IDimensionInfo;
import mcjty.lostcities.worldgen.lost.CitySphere;
import mcjty.lostcities.worldgen.lost.Railway;
import org.admany.lc2h.data.cache.LostCitiesCacheBridge;
import org.admany.lc2h.data.cache.LostCitiesCacheBudgetManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Mixin(value = Railway.class, remap = false)
public abstract class MixinRailway {

    private static final ConcurrentMap<ChunkCoord, Railway.RailChunkInfo> LC2H_RAIL_INFO = new ConcurrentHashMap<>();
    private static final LostCitiesCacheBudgetManager.CacheGroup LC2H_RAIL_BUDGET =
        LostCitiesCacheBudgetManager.register("lc_rail_info", 64, 2048, key -> LC2H_RAIL_INFO.remove(key) != null);

    @Shadow
    private static Railway.RailChunkInfo getRailChunkTypeInternal(ChunkCoord key, IDimensionInfo provider) { return null; }

    /**
     * Remove synchronized map and redundant contains/get/put.
     * This method gets called a lot from multibuilding placement logic.
     *
     * @author LC2H
     * @reason Make railway cache concurrent and non-blocking
     */
    @Overwrite
    public static Railway.RailChunkInfo getRailChunkType(ChunkCoord coord, IDimensionInfo provider, LostCityProfile profile) {
        Railway.RailChunkInfo cached = LC2H_RAIL_INFO.get(coord);
        if (cached != null) {
            LostCitiesCacheBudgetManager.recordAccess(LC2H_RAIL_BUDGET, coord);
            return cached;
        }
        Railway.RailChunkInfo disk = LostCitiesCacheBridge.getDisk("rail_info", coord, Railway.RailChunkInfo.class);
        if (disk != null) {
            Railway.RailChunkInfo prev = LC2H_RAIL_INFO.putIfAbsent(coord, disk);
            LostCitiesCacheBudgetManager.recordPut(LC2H_RAIL_BUDGET, coord, LC2H_RAIL_BUDGET.defaultEntryBytes(), prev == null);
            return prev != null ? prev : disk;
        }

        Railway.RailChunkInfo info = getRailChunkTypeInternal(coord, provider);

        if ((provider.getProfile().isSpace() || provider.getProfile().isSpheres()) && CitySphere.onCitySphereBorder(coord, provider)) {
            info = Railway.RailChunkInfo.NOTHING;
        } else if (info.getType().isStation()) {
            if (!profile.RAILWAY_STATIONS_ENABLED) {
                info = Railway.RailChunkInfo.NOTHING;
            }
        } else {
            if (!profile.RAILWAYS_ENABLED) {
                info = Railway.RailChunkInfo.NOTHING;
            }
        }

        Railway.RailChunkInfo prev = LC2H_RAIL_INFO.putIfAbsent(coord, info);
        LostCitiesCacheBudgetManager.recordPut(LC2H_RAIL_BUDGET, coord, LC2H_RAIL_BUDGET.defaultEntryBytes(), prev == null);
        LostCitiesCacheBridge.putDisk("rail_info", coord, info);
        return prev != null ? prev : info;
    }

    /**
     * Clear concurrent cache.
     *
     * @author LC2H
     * @reason Keep cache coherent
     */
    @Overwrite
    public static void cleanCache() {
        LC2H_RAIL_INFO.clear();
        LostCitiesCacheBudgetManager.clear(LC2H_RAIL_BUDGET);
    }

    /**
     * Update concurrent cache.
     *
     * @author LC2H
     * @reason Keep cache coherent
     */
    @Overwrite
    public static void removeRailChunkType(ChunkCoord coord) {
        Railway.RailChunkInfo prev = LC2H_RAIL_INFO.put(coord, Railway.RailChunkInfo.NOTHING);
        LostCitiesCacheBudgetManager.recordPut(LC2H_RAIL_BUDGET, coord, LC2H_RAIL_BUDGET.defaultEntryBytes(), prev == null);
        LostCitiesCacheBridge.putDisk("rail_info", coord, Railway.RailChunkInfo.NOTHING);
    }
}
