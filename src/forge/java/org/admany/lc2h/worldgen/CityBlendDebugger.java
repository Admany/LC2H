package org.admany.lc2h.worldgen;

import mcjty.lostcities.config.LostCityProfile;
import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.IDimensionInfo;
import mcjty.lostcities.worldgen.LostCityTerrainFeature;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import org.admany.lc2h.util.server.DimensionInfoAccessor;
import org.admany.lc2h.worldgen.lostcities.ChunkRoleProbe;

import java.util.ArrayList;
import java.util.List;

/**
 * Explains, for one specific position, why the terrain blender did or did not
 * shape that column.
 *
 * <p>Aggregate counters can only say "N columns were rejected"; they cannot
 * say which test rejected the column you are standing on. This walks the same
 * decision sequence {@code MixinBlenderCityEdge} uses and reports every
 * intermediate value, so a bad input (most importantly a terrain height that
 * is not yet meaningful at the noise stage) is visible directly instead of
 * being inferred from ratios.</p>
 */
public final class CityBlendDebugger {

    private CityBlendDebugger() {
    }

    private static double runPerRise() {
        try {
            return Double.parseDouble(System.getProperty("lc2h.terrain.cityBlender.runPerRise", "2.0"));
        } catch (RuntimeException ignored) {
            return 2.0D;
        }
    }

    private static int minRange() {
        return Integer.getInteger("lc2h.terrain.cityBlender.minRangeBlocks", 32);
    }

    private static int maxRange() {
        return Integer.getInteger("lc2h.terrain.cityBlender.maxRangeBlocks", 160);
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

        // What actually happened when this chunk generated. Everything below
        // is a re-evaluation with today's (warm) caches and can disagree.
        String recorded = MountainCityBlendDiagnostics.recordedOutcome(level.dimension(), chunkX, chunkZ);
        out.add("=== AT GENERATION TIME: " + (recorded == null
            ? "NOT RECORDED (generated before this build, or blender never saw this chunk)"
            : recorded) + " ===");

        IDimensionInfo provider = DimensionInfoAccessor.getForLevel(level);
        if (provider == null || provider.getType() == null) {
            out.add("VERDICT: no Lost Cities provider for this dimension -> blender never runs here");
            return out;
        }
        ResourceKey<Level> dim = provider.getType();
        LostCityProfile profile;
        try {
            profile = provider.getProfile();
        } catch (Exception e) {
            out.add("VERDICT: getProfile() threw " + e.getClass().getSimpleName() + " -> blender bails");
            return out;
        }
        if (profile == null) {
            out.add("VERDICT: null profile -> blender bails");
            return out;
        }
        out.add("profileGround=" + profile.GROUNDLEVEL);

        ChunkRoleProbe.Probe own = ChunkRoleProbe.getStableTerrainProbe(provider, dim, chunkX, chunkZ);
        ChunkRoleProbe.Probe characteristicsRole = ChunkRoleProbe.get(provider, dim, chunkX, chunkZ);
        out.add("thisChunk(stableTerrain): isCity=" + own.isCity() + " cityLevel=" + own.cityLevel()
            + " highway=" + own.hasHighway() + " tunnel=" + own.highwayTunnel());
        out.add("thisChunk(characteristics): isCity=" + characteristicsRole.isCity()
            + " cityLevel=" + characteristicsRole.cityLevel()
            + " highway=" + characteristicsRole.hasHighway()
            + " tunnel=" + characteristicsRole.highwayTunnel());
        if (own.isCity()) {
            int cityGround = profile.GROUNDLEVEL + own.cityLevel() * LostCityTerrainFeature.FLOORHEIGHT;
            MountainCityReservationPlanner.CellPlan reservation =
                MountainCityReservationPlanner.plan(provider, dim, chunkX, chunkZ, profile);
            out.add("reservation=" + reservation.disposition()
                + " regionBudget=" + reservation.regionReserved() + "/" + reservation.regionBudget()
                + " coreDepth=" + reservation.coreDepth()
                + " patchDepth=" + reservation.patchDepth()
                + " cityShapeWeight=" + String.format(java.util.Locale.ROOT, "%.3f",
                    reservation.cityShapeWeight())
                + " component=" + reservation.componentSize());
            out.add("reservationReason=" + reservation.reason());
            if (reservation.removesBuildingCell()) {
                out.add("VERDICT: this cell belongs to one compact regional mountain envelope;"
                    + " large footprints crossing it are rejected and Lost Cities may repack"
                    + " remaining city cells with smaller candidates.");
                out.add("densityOwnership=" + (reservation.cityShapeWeight() <= 0.0D
                    ? "native Minecraft density retained"
                    : String.format(java.util.Locale.ROOT,
                        "%.1f%% city shift with %.1f%% native relief retained",
                        reservation.cityShapeWeight() * 100.0D,
                        (1.0D - reservation.cityShapeWeight()) * 100.0D)));
                if (reservation.disposition() == MountainCityReservationPlanner.Disposition.ENCLOSED_TUNNEL) {
                    out.add("tunnelSafety=both side walls covered and both portals reached inside the bounded scan");
                }
                return out;
            }
            MountainCityBlendDiagnostics.GenerationOutcome generationOutcome =
                MountainCityBlendDiagnostics.recordedGenerationOutcome(dim, chunkX, chunkZ);
            ConnectedMountainPlanner.MountainPlan plan = generationOutcome != null
                && generationOutcome.mountainPlan() != null
                ? generationOutcome.mountainPlan()
                : ConnectedMountainPlanner.plan(provider, dim, chunkX, chunkZ, cityGround);
            out.add("AUTHORITY: Minecraft's native density graph is vertically resampled; no flat density plane is created.");
            out.add("           Lost Cities' late hook only reuses the planned floor for block/floor placement.");
            out.add("mountainDecision=" + plan.decision() + " reason=" + plan.reason());
            out.add("heightEvidence: lostCitiesCoarse=" + plan.coarseHeight()
                + " sampledMaximum=" + plan.maximumSampleHeight()
                + " representativeBeforeShape=" + plan.naturalHeight());
            out.add("heights: naturalBeforeShape=" + plan.naturalHeight()
                + " baseCity=" + plan.cityGround()
                + " finalDensityTarget=" + plan.targetHeight());
            out.add("reshape: removed=" + plan.removedBlocks()
                + " blocks retainedAboveCity=" + plan.retainedBlocks()
                + " blocks floorStep=" + plan.floorStep());
            out.add("mountainMap: connectedChunks=" + plan.componentSize()
                + " peak=" + plan.peakHeight()
                + " distanceIntoCore=" + plan.edgeDistanceChunks() + " chunks"
                + " coreFactor=" + String.format("%.3f", plan.coreFactor()));
            out.add("retention: unquantizedRise=" + String.format("%.2f", plan.unquantizedRetainedRise())
                + " quantizedTarget=" + plan.targetHeight()
                + " nativeDensityVerticalShift=" + plan.removedBlocks());
            if (plan.preservesMountainCore()) {
                out.add("VERDICT: Minecraft's complete mountain density is retained and smoothly shifted down by "
                    + plan.removedBlocks() + " blocks toward floor " + plan.targetHeight() + ".");
            } else {
                out.add("VERDICT: Minecraft's native density is smoothly shifted down by "
                    + plan.removedBlocks() + " blocks because " + plan.reason() + ".");
            }
            if (generationOutcome == null || generationOutcome.mountainPlan() == null) {
                out.add("note: no stored generation-time mountain plan; values above were recomputed now.");
            } else {
                out.add("note: values above are the exact mountain plan recorded when this chunk generated.");
            }
            return out;
        }

        int gridRadius = Math.min(16, Math.max(2, (maxRange() + 15) / 16 + 1));
        ChunkRoleProbe.RoleGrid grid = ChunkRoleProbe.getStableTerrainGrid(provider, dim, chunkX, chunkZ, gridRadius);

        double weightedHeight = 0.0D;
        double totalWeight = 0.0D;
        double nearest = Double.POSITIVE_INFINITY;
        int nearestCX = 0;
        int nearestCZ = 0;
        int cityChunksFound = 0;
        for (int cz = grid.centerZ() - grid.radius(); cz <= grid.centerZ() + grid.radius(); cz++) {
            for (int cx = grid.centerX() - grid.radius(); cx <= grid.centerX() + grid.radius(); cx++) {
                ChunkRoleProbe.Probe probe = grid.get(cx, cz);
                if (!probe.isCity() && !probe.hasSurfaceHighway()) {
                    continue;
                }
                cityChunksFound++;
                int minX = cx << 4;
                int minZ = cz << 4;
                int dx = blockX < minX ? minX - blockX : blockX > minX + 15 ? blockX - (minX + 15) : 0;
                int dz = blockZ < minZ ? minZ - blockZ : blockZ > minZ + 15 ? blockZ - (minZ + 15) : 0;
                double distance = Mth.length((double) dx, (double) dz);
                if (distance > maxRange()) {
                    continue;
                }
                int cityGround = probe.isCity()
                    ? profile.GROUNDLEVEL + probe.cityLevel() * LostCityTerrainFeature.FLOORHEIGHT
                    : profile.GROUNDLEVEL + Math.max(0, probe.highwayLevel()) * LostCityTerrainFeature.FLOORHEIGHT;
                if (distance < nearest) {
                    nearest = distance;
                    nearestCX = cx;
                    nearestCZ = cz;
                }
                if (distance < 1.0E-6D) {
                    continue;
                }
                double d2 = distance * distance;
                double weight = 1.0D / (d2 * d2);
                weightedHeight += cityGround * weight;
                totalWeight += weight;
            }
        }
        out.add("gridRadius=" + gridRadius + " cityOrHighwayChunksInGrid=" + cityChunksFound);
        if (!Double.isFinite(nearest) || totalWeight <= 0.0D) {
            out.add("VERDICT: no city/highway chunk within " + maxRange() + " blocks -> untouched vanilla terrain.");
            out.add("         (raise lc2h.terrain.cityBlender.maxRangeBlocks to reach further)");
            return out;
        }
        double cityHeight = weightedHeight / totalWeight;
        out.add("nearestCityChunk=" + nearestCX + "," + nearestCZ + " distance=" + String.format("%.1f", nearest));
        out.add("weightedCityFloorHeight=" + String.format("%.1f", cityHeight));

        // The two candidate height sources, side by side. If these disagree
        // badly, the blender is deciding on a height that is not the terrain
        // the player is actually standing on.
        int lcHeight;
        String lcNote = "";
        try {
            lcHeight = provider.getHeightmap(new ChunkCoord(dim, chunkX, chunkZ)).getHeight();
        } catch (Throwable t) {
            lcHeight = Integer.MIN_VALUE;
            lcNote = " (threw " + t.getClass().getSimpleName() + ")";
        }
        int liveHeight = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, blockX, blockZ);
        out.add("heightSource: lostCitiesHeightmap=" + lcHeight + lcNote + "  liveWorldSurface=" + liveHeight);
        if (lcHeight != Integer.MIN_VALUE && Math.abs(lcHeight - liveHeight) > 8) {
            out.add("  note: LC height is the ORIGINAL pre-blend terrain, live is the CURRENT surface;");
            out.add("        a gap here means this column WAS lowered by " + (lcHeight - liveHeight) + " blocks.");
        } else if (lcHeight != Integer.MIN_VALUE) {
            out.add("  note: both agree -> this column was NOT lowered.");
        }

        double rise = lcHeight == Integer.MIN_VALUE ? 0.0D : lcHeight - cityHeight;
        double liveRise = liveHeight - cityHeight;
        out.add("rise(usedByBlender)=" + String.format("%.1f", rise)
            + "  rise(fromLiveTerrain)=" + String.format("%.1f", liveRise));
        if (rise <= 0.0D) {
            out.add("VERDICT: rise<=0 -> blender returns 'leave untouched'.");
            if (liveRise > 0.0D) {
                out.add("         BUT live terrain is " + String.format("%.0f", liveRise) + " blocks above the city floor,");
                out.add("         so this is a BAD HEIGHT SOURCE, not genuinely flat ground.");
            }
            return out;
        }

        double range = Mth.clamp(rise * runPerRise(), minRange(), maxRange());
        out.add("runPerRise=" + runPerRise() + " -> requiredRange=" + String.format("%.1f", rise * runPerRise())
            + " clampedRange=" + String.format("%.1f", range) + " (min=" + minRange() + " max=" + maxRange() + ")");
        if (rise * runPerRise() > maxRange()) {
            out.add("  note: clamped by maxRangeBlocks - slope is steeper than runPerRise asks for");
        }
        if (nearest >= range) {
            out.add("VERDICT: distance " + String.format("%.1f", nearest) + " >= range " + String.format("%.1f", range)
                + " -> past the foot of the slope, untouched by design.");
            return out;
        }

        double t = Mth.clamp(nearest / range, 0.0D, 1.0D);
        double eased = 3.0D * t * t - 2.0D * t * t * t;
        double target = cityHeight + rise * eased;
        out.add("t=" + String.format("%.3f", t) + " easedAlpha=" + String.format("%.3f", eased));
        out.add("VERDICT(recomputed now): would blend to targetHeight=" + String.format("%.1f", target)
            + " (city=" + String.format("%.1f", cityHeight) + " natural=" + lcHeight + ")");
        out.add("reshape(recomputed): density would remove "
            + String.format("%.1f", Math.max(0.0D, lcHeight - target))
            + " blocks here because the column is " + String.format("%.1f", nearest)
            + " blocks from the nearest city/highway anchor inside a "
            + String.format("%.1f", range) + "-block transition.");
        if (recorded != null && recorded.startsWith("NO_NEARBY_CITY")) {
            out.add("!! MISMATCH: it would blend now, but at generation time the probe saw no city.");
            out.add("   That is the bug - ChunkRoleProbe is not authoritative during noise generation.");
        }
        return out;
    }
}
