package org.admany.lc2h.util.lostcities;

import mcjty.lostcities.varia.ChunkCoord;
import org.admany.lc2h.data.cache.BuildingInfoCacheRegistry;
import org.admany.lc2h.worldgen.lostcities.ChunkRoleProbe;

/**
 * Invalidates every LC2H cache that can retain pre-multichunk characteristics.
 *
 * The problem this addresses:
 * - If a chunk's characteristics/BuildingInfo are cached as "single" before the MultiChunk (multibuilding plan) is integrated, that chunk may never get re-evaluated as part of the multibuilding, causing ugly seams at multibuilding edges.
 */
public final class BuildingInfoCacheInvalidator {
    private static final int BORDER_RADIUS = Math.max(0, Math.min(2,
        Integer.getInteger("lc2h.multichunk.boundaryInvalidationRadius", 1)));

    private BuildingInfoCacheInvalidator() {
    }

    public static void invalidateArea(ChunkCoord topLeft, int areaSize) {
        if (topLeft == null || areaSize <= 0) {
            return;
        }

        for (int dx = -BORDER_RADIUS; dx < areaSize + BORDER_RADIUS; dx++) {
            for (int dz = -BORDER_RADIUS; dz < areaSize + BORDER_RADIUS; dz++) {
                ChunkRoleProbe.invalidate(new ChunkCoord(topLeft.dimension(), topLeft.chunkX() + dx, topLeft.chunkZ() + dz));
            }
        }
        BuildingInfoCacheRegistry.invalidateArea(topLeft, areaSize, BORDER_RADIUS);
    }
}
