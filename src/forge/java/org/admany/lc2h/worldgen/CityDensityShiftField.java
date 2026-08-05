package org.admany.lc2h.worldgen;

import net.minecraft.util.Mth;

/**
 * World-space interpolation for native-density vertical shifts.
 *
 * <p>Control values live at chunk centres. Because every generated chunk
 * samples the same lattice, both sides of a chunk border calculate the same
 * shift and cannot form a generation seam.</p>
 */
public final class CityDensityShiftField {

    private CityDensityShiftField() {
    }

    @FunctionalInterface
    public interface ControlSampler {
        double shiftAtChunkCentre(int chunkX, int chunkZ);
    }

    public static double sample(int blockX, int blockZ, ControlSampler controls) {
        int lowerChunkX = Math.floorDiv(blockX - 8, 16);
        int lowerChunkZ = Math.floorDiv(blockZ - 8, 16);
        double tx = quintic(Math.floorMod(blockX - 8, 16) / 16.0D);
        double tz = quintic(Math.floorMod(blockZ - 8, 16) / 16.0D);
        double s00 = controls.shiftAtChunkCentre(lowerChunkX, lowerChunkZ);
        double s10 = controls.shiftAtChunkCentre(lowerChunkX + 1, lowerChunkZ);
        double s01 = controls.shiftAtChunkCentre(lowerChunkX, lowerChunkZ + 1);
        double s11 = controls.shiftAtChunkCentre(lowerChunkX + 1, lowerChunkZ + 1);
        double sx0 = Mth.lerp(tx, s00, s10);
        double sx1 = Mth.lerp(tx, s01, s11);
        return Math.max(0.0D, Mth.lerp(tz, sx0, sx1));
    }

    public static double quintic(double value) {
        double t = Mth.clamp(value, 0.0D, 1.0D);
        return t * t * t * (t * (t * 6.0D - 15.0D) + 10.0D);
    }
}
