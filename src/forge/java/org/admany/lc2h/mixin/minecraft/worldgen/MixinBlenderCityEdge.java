package org.admany.lc2h.mixin.minecraft.worldgen;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import mcjty.lostcities.config.LostCityProfile;
import mcjty.lostcities.worldgen.IDimensionInfo;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.admany.lc2h.mixin.accessor.minecraft.BlenderFactory;
import org.admany.lc2h.util.server.DimensionInfoAccessor;
import org.admany.lc2h.worldgen.CityDensityTransform;
import org.admany.lc2h.worldgen.MountainCityBlendDiagnostics;
import org.admany.lc2h.worldgen.terrain.CityShiftField;
import org.admany.lc2h.worldgen.terrain.NaturalHeightSampler;
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

    @Unique
    private boolean lc2h$active;
    @Unique
    private CityShiftField.Context lc2h$context;

    @Unique
    private long[] lc2h$sampleKeys;
    @Unique
    private double[] lc2h$sampleValues;

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
        NaturalHeightSampler.LevelSampler heights = NaturalHeightSampler.forLevel(region);
        if (heights == null) {
            MountainCityBlendDiagnostics.noProvider();
            return;
        }
        CityShiftField.Context context = CityShiftField.context(provider, profile, heights);
        if (context == null || !context.settings().enabled()) {
            return;
        }

        ChunkPos center = region.getCenter();
        double centreShift = 0.0D;
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                centreShift = Math.max(centreShift,
                    CityShiftField.shiftAtChunk(context, center.x + dx, center.z + dz));
            }
        }
        if (centreShift <= 0.0D) {
            return;
        }

        Blender blender = BlenderFactory.lc2h$create(new Long2ObjectOpenHashMap<>(), new Long2ObjectOpenHashMap<>());
        MixinBlenderCityEdge state = (MixinBlenderCityEdge) (Object) blender;
        state.lc2h$context = context;
        state.lc2h$sampleKeys = new long[512];
        Arrays.fill(state.lc2h$sampleKeys, Long.MIN_VALUE);
        state.lc2h$sampleValues = new double[state.lc2h$sampleKeys.length];
        state.lc2h$active = true;

        MountainCityBlendDiagnostics.applied(provider.getType(), center.x, center.z,
            state.lc2h$verticalDensityShift((center.x << 4) + 8, (center.z << 4) + 8));
        cir.setReturnValue(blender);
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
        if (!this.lc2h$active) {
            return 0.0D;
        }
        long key = (((long) blockX) << 32) ^ (blockZ & 0xffffffffL);
        int sampleIndex = lc2h$sampleIndex(key, this.lc2h$sampleKeys.length);
        if (this.lc2h$sampleKeys[sampleIndex] == key) {
            return this.lc2h$sampleValues[sampleIndex];
        }
        double shift = CityShiftField.sample(this.lc2h$context, blockX, blockZ);
        this.lc2h$sampleValues[sampleIndex] = shift;
        this.lc2h$sampleKeys[sampleIndex] = key;
        return shift;
    }

    @Unique
    private static int lc2h$sampleIndex(long key, int capacity) {
        long mixed = key ^ (key >>> 33);
        mixed *= 0xff51afd7ed558ccDL;
        mixed ^= mixed >>> 33;
        return ((int) mixed) & (capacity - 1);
    }
}
