package org.admany.lc2h.worldgen;

/**
 * Marker carried by LC2H's per-chunk Blender into Minecraft's NoiseChunk.
 *
 * <p>The transform does not manufacture terrain density. It tells the native
 * density graph how far upward to sample its own density at a given column.
 * Sampling at {@code y + shift} moves the complete native landform downward
 * by {@code shift}: ridges, caves, slopes, surface variation, and modded noise
 * remain part of the original Minecraft density function.</p>
 */
public interface CityDensityTransform {

    boolean lc2h$isDensityTransformActive();

    double lc2h$verticalDensityShift(int blockX, int blockZ);
}
