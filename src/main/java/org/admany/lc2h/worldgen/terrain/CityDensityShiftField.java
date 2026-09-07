package org.admany.lc2h.worldgen.terrain;

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

    public static double sampleSmooth(int blockX, int blockZ, ControlSampler controls) {
        int baseChunkX = Math.floorDiv(blockX - 8, 16);
        int baseChunkZ = Math.floorDiv(blockZ - 8, 16);
        double tx = Math.floorMod(blockX - 8, 16) / 16.0D;
        double tz = Math.floorMod(blockZ - 8, 16) / 16.0D;

        /*
         * Catmull-Rom is visually pleasant for ordinary curves, but it is not
         * shape preserving: a single tall city cell can make the interpolant
         * overshoot its two neighbouring controls.  That overshoot becomes a
         * real vertical density offset and shows up as a thin stone wall at a
         * mountain edge.  Use the monotone cubic Hermite segment on both axes
         * instead.  It has the same C1 continuity at normal transitions while
         * guaranteeing that every segment stays inside its endpoint envelope.
         */
        int z0 = baseChunkZ - 1;
        double row0 = monotoneCubic(
            controls.shiftAtChunkCentre(baseChunkX - 1, z0),
            controls.shiftAtChunkCentre(baseChunkX, z0),
            controls.shiftAtChunkCentre(baseChunkX + 1, z0),
            controls.shiftAtChunkCentre(baseChunkX + 2, z0),
            tx);
        int z1 = baseChunkZ;
        double row1 = monotoneCubic(
            controls.shiftAtChunkCentre(baseChunkX - 1, z1),
            controls.shiftAtChunkCentre(baseChunkX, z1),
            controls.shiftAtChunkCentre(baseChunkX + 1, z1),
            controls.shiftAtChunkCentre(baseChunkX + 2, z1),
            tx);
        int z2 = baseChunkZ + 1;
        double row2 = monotoneCubic(
            controls.shiftAtChunkCentre(baseChunkX - 1, z2),
            controls.shiftAtChunkCentre(baseChunkX, z2),
            controls.shiftAtChunkCentre(baseChunkX + 1, z2),
            controls.shiftAtChunkCentre(baseChunkX + 2, z2),
            tx);
        int z3 = baseChunkZ + 2;
        double row3 = monotoneCubic(
            controls.shiftAtChunkCentre(baseChunkX - 1, z3),
            controls.shiftAtChunkCentre(baseChunkX, z3),
            controls.shiftAtChunkCentre(baseChunkX + 1, z3),
            controls.shiftAtChunkCentre(baseChunkX + 2, z3),
            tx);
        return Math.max(0.0D, monotoneCubic(row0, row1, row2, row3, tz));
    }

    /**
     * Shape-preserving cubic interpolation for the segment {@code [p1,p2]}.
     * The control points are equally spaced and {@code t} is clamped to that
     * segment.  The Fritsch-Carlson limiter removes tangent components that
     * point against the segment slope and scales the remaining pair when the
     * monotonicity circle would otherwise be exceeded.
     */
    public static double monotoneCubic(double p0, double p1, double p2, double p3, double t) {
        double u = Mth.clamp(t, 0.0D, 1.0D);
        double delta = p2 - p1;
        if (delta == 0.0D) {
            return p1;
        }

        double m1 = 0.5D * (p2 - p0);
        double m2 = 0.5D * (p3 - p1);
        if (m1 * delta <= 0.0D) {
            m1 = 0.0D;
        }
        if (m2 * delta <= 0.0D) {
            m2 = 0.0D;
        }

        double a = m1 / delta;
        double b = m2 / delta;
        double radius = a * a + b * b;
        if (radius > 9.0D) {
            double scale = 3.0D / Math.sqrt(radius);
            m1 = scale * a * delta;
            m2 = scale * b * delta;
        }

        double u2 = u * u;
        double u3 = u2 * u;
        double h00 = 2.0D * u3 - 3.0D * u2 + 1.0D;
        double h10 = u3 - 2.0D * u2 + u;
        double h01 = -2.0D * u3 + 3.0D * u2;
        double h11 = u3 - u2;
        return h00 * p1 + h10 * m1 + h01 * p2 + h11 * m2;
    }

    /**
     * Restricts a requested downward correction to the part of the native
     * surface that protrudes above its local envelope. A broad ridge has a
     * zero correction; an isolated high cell can only be lowered by its
     * measured excess, never by the full city-floor demand.
     */
    public static double capToNativeSurfaceEnvelope(double requestedShift,
                                                     int nativeSurface,
                                                     int referenceSurface) {
        if (!(requestedShift > 0.0D)) {
            return 0.0D;
        }
        return Math.min(requestedShift, Math.max(0.0D, nativeSurface - referenceSurface));
    }

    /**
     * Kept as a source-compatible helper for diagnostics and old callers. It
     * intentionally uses the same bounded interpolant as the production
     * sampler; no raw Catmull-Rom path is allowed to reintroduce overshoot.
     */
    public static double catmullRom(double p0, double p1, double p2, double p3, double t) {
        return monotoneCubic(p0, p1, p2, p3, t);
    }

    public static double quintic(double value) {
        double t = Mth.clamp(value, 0.0D, 1.0D);
        return t * t * t * (t * (t * 6.0D - 15.0D) + 10.0D);
    }
}
