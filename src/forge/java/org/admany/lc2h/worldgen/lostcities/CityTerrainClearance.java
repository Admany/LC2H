package org.admany.lc2h.worldgen.lostcities;

import mcjty.lostcities.worldgen.IDimensionInfo;
import net.minecraft.world.level.chunk.ChunkAccess;

/**
 * Retired compatibility shim for the old blanket terrain-clearing experiment.
 * Native Lost Cities {@code correctTerrainShape} is height-aware and can place
 * appropriate underground highways. Clearing every city column above the road
 * level destroys that information and flattens hills, so this method is kept
 * intentionally inert for old callers/configs.
 */
public final class CityTerrainClearance {
    private CityTerrainClearance() {
    }

    public static int clear(IDimensionInfo provider, ChunkAccess chunk) {
        return 0;
    }

    public static String diagnostics() {
        return "retired=true legacyConfigIgnored=true chunks=0 columns=0 blocks=0";
    }
}
