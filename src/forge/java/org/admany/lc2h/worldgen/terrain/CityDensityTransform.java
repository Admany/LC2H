package org.admany.lc2h.worldgen.terrain;

/** Marker carried from LC2H's Blender into NoiseChunk. */
public interface CityDensityTransform {

    boolean lc2h$isDensityTransformActive();

    double lc2h$verticalDensityShift(int blockX, int blockZ);
}
