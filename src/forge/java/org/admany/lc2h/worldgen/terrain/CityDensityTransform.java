package org.admany.lc2h.worldgen.terrain;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/** Marker carried from LC2H's Blender into NoiseChunk. */
public interface CityDensityTransform {

    boolean lc2h$isDensityTransformActive();

    double lc2h$verticalDensityShift(int blockX, int blockZ);

    /** Dimension identity used by bounded per-chunk diagnostics. */
    default ResourceKey<Level> lc2h$dimension() {
        return null;
    }
}
