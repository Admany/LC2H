package org.admany.lc2h.worldgen.lostcities;

import mcjty.lostcities.config.LostCityProfile;
import mcjty.lostcities.setup.Registration;
import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.IDimensionInfo;
import mcjty.lostcities.worldgen.lost.BuildingInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.WorldGenLevel;

import java.util.HashMap;
import java.util.Map;

/**
 * Shared tree-safety checks for Lost Cities worldgen.
 *
 * We consider city, highway, and railway chunks as unsafe for cross-chunk tree growth.
 * Trees near chunk edges that would cross into an unsafe neighbor are vetoed up front.
 */
public final class LostCityTreeSafety {

    private LostCityTreeSafety() {
    }

    public static boolean shouldBlockTreeAt(WorldGenLevel level, BlockPos pos, int buffer) {
        if (level == null || pos == null) {
            return false;
        }
        IDimensionInfo dimInfo;
        try {
            dimInfo = Registration.LOSTCITY_FEATURE.get().getDimensionInfo(level);
        } catch (Throwable ignored) {
            return false;
        }
        if (dimInfo == null) {
            return false;
        }
        ResourceKey<Level> dim = dimInfo.getType();
        if (dim == null) {
            return false;
        }
        return shouldBlockTreeAt(dimInfo, dim, pos.getX(), pos.getZ(), buffer);
    }

    public static boolean shouldBlockTreeAt(IDimensionInfo dimInfo, ResourceKey<Level> dim, int worldX, int worldZ, int buffer) {
        if (dimInfo == null || dim == null) {
            return false;
        }

        int edgeBuffer = Math.max(1, Math.min(15, buffer));
        int localX = worldX & 15;
        int localZ = worldZ & 15;
        int cx = worldX >> 4;
        int cz = worldZ >> 4;

        boolean west = localX < edgeBuffer;
        boolean east = localX >= 16 - edgeBuffer;
        boolean north = localZ < edgeBuffer;
        boolean south = localZ >= 16 - edgeBuffer;
        if (!west && !east && !north && !south) {
            return false;
        }

        ChunkUnsafeLookup unsafeLookup = new ChunkUnsafeLookup(dimInfo, dim);

        if (west && unsafeLookup.isUnsafe(cx - 1, cz)) {
            return true;
        }
        if (east && unsafeLookup.isUnsafe(cx + 1, cz)) {
            return true;
        }
        if (north && unsafeLookup.isUnsafe(cx, cz - 1)) {
            return true;
        }
        if (south && unsafeLookup.isUnsafe(cx, cz + 1)) {
            return true;
        }

        if (west && north && unsafeLookup.isUnsafe(cx - 1, cz - 1)) {
            return true;
        }
        if (west && south && unsafeLookup.isUnsafe(cx - 1, cz + 1)) {
            return true;
        }
        if (east && north && unsafeLookup.isUnsafe(cx + 1, cz - 1)) {
            return true;
        }
        if (east && south && unsafeLookup.isUnsafe(cx + 1, cz + 1)) {
            return true;
        }

        return false;
    }

    private static final class ChunkUnsafeLookup {
        private final IDimensionInfo dimInfo;
        private final ResourceKey<Level> dim;
        private final LostCityProfile profile;
        private final Map<Long, Boolean> cache = new HashMap<>(8);

        private ChunkUnsafeLookup(IDimensionInfo dimInfo, ResourceKey<Level> dim) {
            this.dimInfo = dimInfo;
            this.dim = dim;
            LostCityProfile p = null;
            try {
                p = dimInfo.getProfile();
            } catch (Throwable ignored) {
            }
            this.profile = p;
        }

        private boolean isUnsafe(int cx, int cz) {
            long key = (((long) cx) << 32) ^ (cz & 0xffffffffL);
            Boolean cached = cache.get(key);
            if (cached != null) {
                return cached;
            }

            ChunkCoord coord = new ChunkCoord(dim, cx, cz);
            boolean unsafe = false;
            try {
                unsafe = BuildingInfo.isCity(coord, dimInfo);
            } catch (Throwable ignored) {
            }
            if (!unsafe && profile != null) {
                try {
                    unsafe = BuildingInfo.hasHighway(coord, dimInfo, profile);
                } catch (Throwable ignored) {
                    unsafe = false;
                }
                if (!unsafe) {
                    try {
                        unsafe = BuildingInfo.hasRailway(coord, dimInfo, profile);
                    } catch (Throwable ignored) {
                        unsafe = false;
                    }
                }
            }

            cache.put(key, unsafe);
            return unsafe;
        }
    }
}
