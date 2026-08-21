package org.admany.lc2h.worldgen.terrain;

/** Marker carried from LC2H's Blender into NoiseChunk. Sampling the native
 * graph at {@code y + shift} moves the complete native landform without
 * replacing its ridges, caves, slopes, or modded noise. */
public interface CityDensityTransform {

    boolean lc2h$isDensityTransformActive();

    double lc2h$verticalDensityShift(int blockX, int blockZ);
}
