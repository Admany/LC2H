package org.admany.lc2h.mixin.minecraft.worldgen;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import mcjty.lostcities.config.LostCityProfile;
import mcjty.lostcities.worldgen.IDimensionInfo;
import mcjty.lostcities.worldgen.LostCityTerrainFeature;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.admany.lc2h.mixin.accessor.minecraft.BlenderFactory;
import org.admany.lc2h.util.server.DimensionInfoAccessor;
import org.admany.lc2h.worldgen.ConnectedMountainPlanner;
import org.admany.lc2h.worldgen.CityDensityTransform;
import org.admany.lc2h.worldgen.CityDensityShiftField;
import org.admany.lc2h.worldgen.MountainCityBlendDiagnostics;
import org.admany.lc2h.worldgen.MountainCityReservationPlanner;
import org.admany.lc2h.worldgen.lostcities.ChunkRoleProbe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Arrays;

/**
 * Carries a Lost Cities terrain plan into Minecraft's native density graph.
 *
 * <p>{@code Blender.of(WorldGenRegion)} is the same mechanism vanilla uses to
 * taper terrain at old chunk boundaries. LC2H previously reused its
 * target-height output directly, but that still blends against a flat plane.
 * The Blender is now only the per-NoiseChunk state carrier. The actual shape
 * is a smooth vertical coordinate shift applied by
 * {@link MixinNoiseChunkCityDensityTransform}, so Minecraft's complete native
 * density function remains authoritative.</p>
 *
 * <p>This logic lives entirely inside a mixin merged into {@code Blender}
 * itself (via {@code @Unique} fields and an {@code @Invoker} constructor,
 * see {@link BlenderFactory}) rather than a real subclass placed in
 * {@code net.minecraft.world.level.levelgen.blending}: this modpack's loader
 * (Connector) runs Minecraft as a real Java module, and a mod class living in
 * an existing vanilla package is an illegal split package there
 * (hard crash at launch, not just a style issue).</p>
 */
@Mixin(Blender.class)
public abstract class MixinBlenderCityEdge implements CityDensityTransform {

    private static final boolean ENABLED = Boolean.parseBoolean(
        System.getProperty("lc2h.terrain.cityBlender.enabled", "true"));
    // The transition is a constant-grade ramp, not a fixed-radius pull.
    //
    // A single range cannot serve both cases: 48 blocks leaves a 100-block
    // mountain as a cliff (it never reaches the bulk of the mass), while a
    // range wide enough for that mountain flattens ordinary hills. Instead
    // the run-out is derived from the height that actually has to be lost -
    // HORIZONTAL_RUN_PER_RISE blocks of horizontal distance per block of
    // descent - so a tall massif gets a correspondingly long slope and a
    // small rise gets a short one, from the same rule.
    private static final double HORIZONTAL_RUN_PER_RISE = clampProperty(
        "lc2h.terrain.cityBlender.runPerRise", 2.0D, 0.5D, 8.0D);
    private static final int MIN_RANGE_BLOCKS = Math.max(16, Math.min(96,
        Integer.getInteger("lc2h.terrain.cityBlender.minRangeBlocks", 32)));
    // Bounds the worst case: the role-grid scan is O(radius^2) per chunk, so
    // an unbounded run-out on a 200-block peak would be a real worldgen cost.
    private static final int MAX_RANGE_BLOCKS = Math.max(MIN_RANGE_BLOCKS, Math.min(240,
        Integer.getInteger("lc2h.terrain.cityBlender.maxRangeBlocks", 160)));
    private static final int GRID_RADIUS = Math.min(16, Math.max(2, (MAX_RANGE_BLOCKS + 15) / 16 + 1));

    private static double clampProperty(String key, double fallback, double min, double max) {
        try {
            return Mth.clamp(Double.parseDouble(System.getProperty(key, Double.toString(fallback))), min, max);
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    @Unique
    private boolean lc2h$active;
    @Unique
    private IDimensionInfo lc2h$provider;
    @Unique
    private ResourceKey<Level> lc2h$dim;
    /** Stable role grid shared by all density samples for this NoiseChunk. */
    @Unique
    private ChunkRoleProbe.RoleGrid lc2h$roleGrid;
    /** Cached shift control points on the chunk-centre lattice. */
    @Unique
    private double[] lc2h$shiftControls;
    @Unique
    private int lc2h$shiftControlMinX;
    @Unique
    private int lc2h$shiftControlMinZ;
    @Unique
    private int lc2h$shiftControlSide;
    /**
     * Final interpolated shifts repeat for every vertical density sample in a
     * column. Keep a small direct-mapped primitive cache so the hot density
     * loop does not redo four control lookups and the quintic interpolation
     * for every Y coordinate.
     */
    @Unique
    private long[] lc2h$sampleKeys;
    @Unique
    private double[] lc2h$sampleValues;
    @Unique
    private int lc2h$profileGround;
    @Unique
    private LostCityProfile lc2h$profile;

    @Inject(method = "of", at = @At("RETURN"), cancellable = true)
    private static void lc2h$replaceForCityEdge(WorldGenRegion region, CallbackInfoReturnable<Blender> cir) {
        if (!ENABLED || region == null) {
            return;
        }
        MountainCityBlendDiagnostics.seen();
        IDimensionInfo provider = DimensionInfoAccessor.getForLevel(region);
        if (provider == null || provider.getType() == null) {
            MountainCityBlendDiagnostics.noProvider();
            return;
        }
        ChunkPos center = region.getCenter();
        LostCityProfile profile;
        try {
            profile = provider.getProfile();
        } catch (Exception ignored) {
            MountainCityBlendDiagnostics.noProfile();
            return;
        }
        if (profile == null) {
            MountainCityBlendDiagnostics.noProfile();
            return;
        }
        ChunkRoleProbe.RoleGrid grid = ChunkRoleProbe.getStableTerrainGrid(provider, provider.getType(),
            center.x, center.z, GRID_RADIUS);
        // Use the same immutable grid snapshot for the center decision and the
        // anchors. A separate lookup here could observe a planner integration
        // between calls and classify one chunk from two different worldgen
        // states.
        boolean centerIsCity = grid.get(center.x, center.z).isCity()
            && !MountainCityReservationPlanner.plan(provider, provider.getType(),
                center.x, center.z, profile).removesBuildingCell();
        int cityChunks = lc2h$countNearbyCity(provider, profile, grid);
        if (cityChunks == 0) {
            MountainCityBlendDiagnostics.noNearbyCity(provider.getType(), center.x, center.z, GRID_RADIUS);
            return;
        }
        Blender blender = BlenderFactory.lc2h$create(new Long2ObjectOpenHashMap<>(), new Long2ObjectOpenHashMap<>());
        MixinBlenderCityEdge state = (MixinBlenderCityEdge) (Object) blender;
        state.lc2h$provider = provider;
        state.lc2h$dim = provider.getType();
        state.lc2h$roleGrid = grid;
        state.lc2h$profileGround = profile.GROUNDLEVEL;
        state.lc2h$profile = profile;
        int controlSide = grid.radius() * 2 + 1;
        state.lc2h$shiftControlMinX = grid.centerX() - grid.radius();
        state.lc2h$shiftControlMinZ = grid.centerZ() - grid.radius();
        state.lc2h$shiftControlSide = controlSide;
        state.lc2h$shiftControls = new double[controlSide * controlSide];
        Arrays.fill(state.lc2h$shiftControls, Double.NaN);
        state.lc2h$sampleKeys = new long[512];
        Arrays.fill(state.lc2h$sampleKeys, Long.MIN_VALUE);
        state.lc2h$sampleValues = new double[state.lc2h$sampleKeys.length];
        state.lc2h$active = true;
        if (centerIsCity) {
            /*
             * This is authoritative, not redundant. The later Lost Cities
             * characteristics can legitimately disagree with the stable raw
             * city predicate after multichunk planning. If density blending
             * skips here and the later path says non-city, neither path owns
             * the column and a raw mountain wall survives inside the city.
             * Applying the exact city-floor anchor now closes that handoff
             * gap; a later agreeing terrain correction only reinforces the
             * same target.
             */
            ChunkRoleProbe.Probe centerRole = grid.get(center.x, center.z);
            int centerCityGround = profile.GROUNDLEVEL
                + centerRole.cityLevel() * LostCityTerrainFeature.FLOORHEIGHT;
            ConnectedMountainPlanner.MountainPlan mountainPlan = ConnectedMountainPlanner.plan(
                provider, provider.getType(), center.x, center.z, centerCityGround);
            MountainCityBlendDiagnostics.appliedCenterCity(
                provider.getType(), center.x, center.z, cityChunks, mountainPlan);
        } else {
            MountainCityBlendDiagnostics.applied(provider.getType(), center.x, center.z, cityChunks);
        }
        cir.setReturnValue(blender);
    }

    @Unique
    private static int lc2h$countNearbyCity(IDimensionInfo provider,
                                            LostCityProfile profile,
                                            ChunkRoleProbe.RoleGrid grid) {
        int count = 0;
        for (int z = grid.centerZ() - grid.radius(); z <= grid.centerZ() + grid.radius(); z++) {
            for (int x = grid.centerX() - grid.radius(); x <= grid.centerX() + grid.radius(); x++) {
                ChunkRoleProbe.Probe probe = grid.get(x, z);
                boolean reserved = MountainCityReservationPlanner.plan(provider, provider.getType(),
                    x, z, profile).removesBuildingCell();
                if (!reserved && (probe.isCity() || probe.hasSurfaceHighway())) {
                    count++;
                }
            }
        }
        return count;
    }

    @Inject(method = "blendOffsetAndFactor", at = @At("HEAD"), cancellable = true)
    private void lc2h$blendOffsetAndFactor(int blockX, int blockZ, CallbackInfoReturnable<Blender.BlendingOutput> cir) {
        if (!this.lc2h$active) {
            return;
        }
        MountainCityBlendDiagnostics.blendCall();
        // Height/offset blending creates a plane. The NoiseChunk coordinate
        // transform owns all shaping now, so Blender itself must be neutral.
        cir.setReturnValue(new Blender.BlendingOutput(1.0D, 0.0D));
    }

    @Override
    public boolean lc2h$isDensityTransformActive() {
        return this.lc2h$active;
    }

    @Override
    public double lc2h$verticalDensityShift(int blockX, int blockZ) {
        if (!this.lc2h$active || this.lc2h$shiftControls == null) {
            return 0.0D;
        }
        long key = (((long) blockX) << 32) ^ (blockZ & 0xffffffffL);
        int sampleIndex = lc2h$sampleIndex(key, this.lc2h$sampleKeys.length);
        if (this.lc2h$sampleKeys[sampleIndex] == key) {
            return this.lc2h$sampleValues[sampleIndex];
        }
        /*
         * Chunk-centre controls are interpolated with Minecraft's quintic
         * smoothstep. Neighbouring NoiseChunks therefore evaluate the same
         * world-space function and cannot create a chunk seam.
         */
        double shift = CityDensityShiftField.sample(blockX, blockZ, this::lc2h$controlShift);
        this.lc2h$sampleValues[sampleIndex] = shift;
        this.lc2h$sampleKeys[sampleIndex] = key;
        return shift;
    }

    @Unique
    private double lc2h$controlShift(int chunkX, int chunkZ) {
        int localX = chunkX - this.lc2h$shiftControlMinX;
        int localZ = chunkZ - this.lc2h$shiftControlMinZ;
        int index = localZ * this.lc2h$shiftControlSide + localX;
        boolean cacheable = localX >= 0 && localZ >= 0
            && localX < this.lc2h$shiftControlSide && localZ < this.lc2h$shiftControlSide;
        if (cacheable) {
            double cached = this.lc2h$shiftControls[index];
            if (!Double.isNaN(cached)) {
                return cached;
            }
        }
        ChunkRoleProbe.Probe role = this.lc2h$roleGrid.get(chunkX, chunkZ);
        MountainCityReservationPlanner.CellPlan reservation = this.lc2h$profile == null
            ? null
            : MountainCityReservationPlanner.plan(
                this.lc2h$provider, this.lc2h$dim, chunkX, chunkZ, this.lc2h$profile);
        if (reservation != null && reservation.removesBuildingCell()) {
            /*
             * A reservation is a compact landform with its own density shell,
             * not a collection of binary preserved columns. The outer shell
             * still applies most of the city shift, the inner shell applies
             * less, and only the core/tunnel retains the native density.
             * CityDensityShiftField interpolates these shared world-space
             * controls, so the same slope crosses both sides of every chunk
             * boundary.
             */
            double shift = lc2h$directShift(chunkX, chunkZ, role)
                * reservation.cityShapeWeight();
            if (cacheable) {
                this.lc2h$shiftControls[index] = shift;
            }
            return shift;
        }
        double shift = lc2h$directShift(chunkX, chunkZ, role);
        if (shift <= 0.0D) {
            shift = lc2h$nearbyTransitionShift(chunkX, chunkZ);
        }
        if (cacheable) {
            this.lc2h$shiftControls[index] = shift;
        }
        return shift;
    }

    @Unique
    private static int lc2h$sampleIndex(long key, int capacity) {
        long mixed = key ^ (key >>> 33);
        mixed *= 0xff51afd7ed558ccDL;
        mixed ^= mixed >>> 33;
        return ((int) mixed) & (capacity - 1);
    }

    @Unique
    private double lc2h$directShift(int chunkX, int chunkZ, ChunkRoleProbe.Probe role) {
        if (role == null || (!role.isCity() && !role.hasSurfaceHighway())) {
            return 0.0D;
        }
        int ground = role.isCity()
            ? this.lc2h$profileGround + role.cityLevel() * LostCityTerrainFeature.FLOORHEIGHT
            : this.lc2h$profileGround + Math.max(0, role.highwayLevel()) * LostCityTerrainFeature.FLOORHEIGHT;
        ConnectedMountainPlanner.MountainPlan plan = ConnectedMountainPlanner.plan(
            this.lc2h$provider, this.lc2h$dim, chunkX, chunkZ, ground);
        return plan.removedBlocks();
    }

    @Unique
    private double lc2h$nearbyTransitionShift(int chunkX, int chunkZ) {
        double best = 0.0D;
        int radius = Math.min(this.lc2h$roleGrid.radius(), (MAX_RANGE_BLOCKS + 15) / 16 + 1);
        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                ChunkRoleProbe.Probe role = this.lc2h$roleGrid.get(chunkX + dx, chunkZ + dz);
                if (this.lc2h$profile != null
                    && MountainCityReservationPlanner.plan(this.lc2h$provider, this.lc2h$dim,
                        chunkX + dx, chunkZ + dz, this.lc2h$profile).removesBuildingCell()) {
                    continue;
                }
                if (!role.isCity() && !role.hasSurfaceHighway()) {
                    continue;
                }
                double direct = lc2h$directShift(chunkX + dx, chunkZ + dz, role);
                if (direct <= 0.0D) {
                    continue;
                }
                double distance = Math.max(0.0D, Math.hypot(dx, dz) * 16.0D - 8.0D);
                double range = Mth.clamp(direct * HORIZONTAL_RUN_PER_RISE,
                    MIN_RANGE_BLOCKS, MAX_RANGE_BLOCKS);
                if (distance >= range) {
                    continue;
                }
                double fade = 1.0D - CityDensityShiftField.quintic(distance / range);
                best = Math.max(best, direct * fade);
            }
        }
        return best;
    }

}
