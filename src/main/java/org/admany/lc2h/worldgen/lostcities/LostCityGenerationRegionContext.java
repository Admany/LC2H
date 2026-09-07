package org.admany.lc2h.worldgen.lostcities;

import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.WorldGenLevel;

/** Keeps the region passed to Lost Cities available to its post-todo callbacks. */
public final class LostCityGenerationRegionContext {
    private static final ThreadLocal<WorldGenLevel> CURRENT = new ThreadLocal<>();

    private LostCityGenerationRegionContext() {
    }

    public static WorldGenLevel current() {
        return CURRENT.get();
    }

    public static void with(WorldGenRegion region, Runnable work) {
        WorldGenLevel previous = CURRENT.get();
        CURRENT.set(region);
        try {
            work.run();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }
}
