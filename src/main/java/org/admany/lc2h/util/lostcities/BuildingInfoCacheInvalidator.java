package org.admany.lc2h.util.lostcities;

import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.lost.BuildingInfo;

import java.lang.reflect.Field;
import java.util.Map;

/**
 * This provides best-effort cache invalidation for LC2H's BuildingInfo/characteristics caches.
 *
 * The problem this addresses:
 * - If a chunk's characteristics/BuildingInfo are cached as "single" before the MultiChunk (multibuilding plan) is integrated, that chunk may never get re-evaluated as part of the multibuilding, causing ugly seams at multibuilding edges.
 */
public final class BuildingInfoCacheInvalidator {

    private static final String[] CACHE_FIELDS = {
        "LC2H_CITY_INFO_MAP",
        "LC2H_BUILDING_INFO_MAP",
        "LC2H_CITY_LEVEL_CACHE",
        "LC2H_IS_CITY_RAW_CACHE"
    };

    private BuildingInfoCacheInvalidator() {
    }

    public static void invalidateArea(ChunkCoord topLeft, int areaSize) {
        if (topLeft == null || areaSize <= 0) {
            return;
        }

        for (String fieldName : CACHE_FIELDS) {
            Map<?, ?> map = getStaticMap(fieldName);
            if (map == null || map.isEmpty()) {
                continue;
            }

            for (int dx = 0; dx < areaSize; dx++) {
                for (int dz = 0; dz < areaSize; dz++) {
                    ChunkCoord key = new ChunkCoord(topLeft.dimension(), topLeft.chunkX() + dx, topLeft.chunkZ() + dz);
                    try {
                        map.remove(key);
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
    }

    private static Map<?, ?> getStaticMap(String fieldName) {
        try {
            Field f = BuildingInfo.class.getDeclaredField(fieldName);
            f.setAccessible(true);
            Object v = f.get(null);
            if (v instanceof Map<?, ?> map) {
                return map;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
