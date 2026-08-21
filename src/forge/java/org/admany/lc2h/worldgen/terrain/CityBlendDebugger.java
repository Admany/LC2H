package org.admany.lc2h.worldgen.terrain;

import mcjty.lostcities.config.LostCityProfile;
import mcjty.lostcities.worldgen.IDimensionInfo;
import mcjty.lostcities.worldgen.LostCityTerrainFeature;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import org.admany.lc2h.util.server.DimensionInfoAccessor;
import org.admany.lc2h.worldgen.lostcities.ChunkRoleProbe;
import org.admany.lc2h.worldgen.terrain.CityShiftField;
import org.admany.lc2h.worldgen.terrain.NaturalHeightSampler;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Explains one terrain decision using the same order as the density hook. */
public final class CityBlendDebugger {

    private CityBlendDebugger() {
    }

    public static List<String> explain(ServerLevel level, BlockPos pos) {
        List<String> out = new ArrayList<>();
        if (level == null || pos == null) {
            out.add("no level/position");
            return out;
        }
        int blockX = pos.getX();
        int blockZ = pos.getZ();
        int chunkX = blockX >> 4;
        int chunkZ = blockZ >> 4;
        out.add("pos=" + blockX + "," + pos.getY() + "," + blockZ + " chunk=" + chunkX + "," + chunkZ);

        // The recorded result is what happened during generation. The values below
        // are a fresh lookup and can differ once caches are warm.
        String recorded = MountainCityBlendDiagnostics.recordedOutcome(level.dimension(), chunkX, chunkZ);
        out.add("=== AT GENERATION TIME: " + (recorded == null
            ? "NOT RECORDED (generated before this build, or the blender never saw this chunk)"
            : recorded) + " ===");

        IDimensionInfo provider = DimensionInfoAccessor.getForLevel(level);
        if (provider == null || provider.getType() == null) {
            out.add("VERDICT: no Lost Cities provider for this dimension -> the field never runs here");
            return out;
        }
        ResourceKey<Level> dim = provider.getType();
        LostCityProfile profile;
        try {
            profile = provider.getProfile();
        } catch (Exception e) {
            out.add("VERDICT: getProfile() threw " + e.getClass().getSimpleName() + " -> the field bails");
            return out;
        }
        if (profile == null) {
            out.add("VERDICT: null profile -> the field bails");
            return out;
        }

        NaturalHeightSampler.LevelSampler heights = NaturalHeightSampler.forLevel(level);
        CityShiftField.Context context = CityShiftField.context(provider, profile, heights);
        if (context == null) {
            out.add("VERDICT: no natural height sampler for this level -> the field bails");
            return out;
        }
        out.add("settings: " + context.settings().describe());
        if (!context.settings().enabled()) {
            out.add("VERDICT: the shift field is switched off -> raw vanilla terrain everywhere.");
            return out;
        }

        ChunkRoleProbe.Probe role = ChunkRoleProbe.getStableTerrainProbe(provider, dim, chunkX, chunkZ);
        MountainCityReservationPlanner.CellPlan reservation =
            MountainCityReservationPlanner.plan(provider, dim, chunkX, chunkZ, profile);
        out.add("thisChunk: isCity=" + role.isCity() + " cityLevel=" + role.cityLevel()
            + " highway=" + role.hasHighway() + " tunnel=" + role.highwayTunnel());
        out.add("reservation=" + reservation.disposition()
            + " regionBudget=" + reservation.regionReserved() + "/" + reservation.regionBudget()
            + " patchDepth=" + reservation.patchDepth()
            + " component=" + reservation.componentSize());
        out.add("reservationReason=" + reservation.reason());

        int natural = heights.blockHeight(blockX, blockZ);
        int naturalChunk = heights.chunkHeight(chunkX, chunkZ);
        double shift = CityShiftField.sample(context, blockX, blockZ);
        double controlShift = CityShiftField.shiftAtChunk(context, chunkX, chunkZ);
        int cityFloor = profile.GROUNDLEVEL + role.cityLevel() * LostCityTerrainFeature.FLOORHEIGHT;

        out.add("AUTHORITY: native density is resampled at y+shift; no flat blending plane is created.");
        out.add("heights: naturalHere=" + natural + " naturalChunkCentre=" + naturalChunk
            + " cityFloorForThisLevel=" + cityFloor);
        out.add("shift: interpolated=" + fmt(shift)
            + " chunkControl=" + fmt(controlShift)
            + " -> predictedSurface=" + fmt(natural - shift));

        int liveHeight = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, blockX, blockZ);
        out.add("liveWorldSurface=" + liveHeight
            + " (delta vs predicted = " + fmt(liveHeight - (natural - shift)) + ")");
        if (Math.abs(liveHeight - (natural - shift)) > 12) {
            out.add("  note: a large delta means this chunk was generated under different settings,"
                + " or Lost Cities placed structure blocks on top of the density result.");
        }

        double erosion = heights.erosionAt(blockX, blockZ);
        double localCap = context.settings().slopeForErosion(erosion);
        out.add("erosion=" + fmt(erosion)
            + " (negative=mountainous, positive=flat) -> localSlopeLimit=" + fmt(localCap)
            + " (" + fmt(Math.toDegrees(Math.atan(localCap))) + " deg)");

        if (shift <= 0.0D) {
            out.add("VERDICT: no demand reaches this column - it is beyond the foot of the skirt,"
                + " so terrain is untouched vanilla by design.");
            out.add("         on the gentlest ground a full-height demand runs out over "
                + context.settings().maxRunOutBlocks() + " blocks.");
            return out;
        }

        double east = CityShiftField.sample(context, blockX + 4, blockZ);
        double south = CityShiftField.sample(context, blockX, blockZ + 4);
        double fieldGradient = Math.hypot(east - shift, south - shift) / 4.0D;
        out.add("fieldGradient here = " + fmt(fieldGradient)
            + " (" + fmt(Math.toDegrees(Math.atan(fieldGradient))) + " deg)");
        if (fieldGradient > localCap * 1.5D) {
            out.add("  note: above the local slope limit. Relief compression deliberately adds"
                + " gradient to the shift field so it is removed from the surface,"
                + " so this on its own is not a fault - check the surface slope instead.");
        }
        if (role.isCity() && !reservation.removesBuildingCell()) {
            out.add("VERDICT: city chunk. Terrain is lowered by " + fmt(shift)
                + " onto floor " + (naturalChunk - (int) Math.round(controlShift)) + ".");
        } else if (reservation.removesBuildingCell()) {
            out.add("VERDICT: reserved mountain cell. It issues no demand of its own; the "
                + fmt(shift) + " blocks here are the skirt of neighbouring city demand.");
        } else {
            out.add("VERDICT: transition column. Lowered by " + fmt(shift)
                + " blocks as part of the bounded-gradient skirt around nearby city demand.");
        }
        return out;
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }
}
