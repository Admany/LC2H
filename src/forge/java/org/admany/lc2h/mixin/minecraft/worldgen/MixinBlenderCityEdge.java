package org.admany.lc2h.mixin.minecraft.worldgen;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import mcjty.lostcities.config.LostCityProfile;
import mcjty.lostcities.worldgen.IDimensionInfo;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.admany.lc2h.mixin.accessor.minecraft.BlenderFactory;
import org.admany.lc2h.config.ConfigManager;
import org.admany.lc2h.util.server.DimensionInfoAccessor;
import org.admany.lc2h.worldgen.terrain.CityDensityTransform;
import org.admany.lc2h.worldgen.terrain.CityDensityShiftField;
import org.admany.lc2h.worldgen.terrain.MountainCityBlendDiagnostics;
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
 * The Blender now carries the city plan and applies only a bounded correction
 * at the native surface envelope, so Minecraft's complete density graph and
 * its interpolation remain authoritative.</p>
 *
 * <p>The normal path adds only a bounded, native-surface envelope correction
 * inside {@code Blender#blendDensity}; Minecraft's density graph and its
 * cell interpolation remain authoritative. The legacy coordinate-warp arm
 * is still available only through the explicit diagnostic property.</p>
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

    /**
     * Keep an explicit JVM escape hatch for diagnostics, but let the live
     * LC2H config be the source of truth. The old default was false, which
     * silently disabled the shaper in every fresh integrated-server run.
     * An absent property must not override the in-game runtime toggle.
     */
    private static final Boolean PROPERTY_ENABLED = readPropertyEnabled();

    /* NoiseChunk caches density at cell corners and interpolates those values
     * across the column.  A per-block vertical warp can therefore make the
     * native graph expose a vertical feature halfway through one noise cell.
     * Keep the diagnostic A/B switch here so the integrated harness can prove
     * whether a cell-constant transform removes that artefact. */
    private static final boolean CHUNK_CONSTANT_SHIFT = Boolean.parseBoolean(
        System.getProperty("lc2h.terrain.shift.chunkConstant", "false"));

    /** Add a bounded surface scalar to the native density graph instead of
     * changing NoiseChunk's interpolation context Y. The property remains an
     * explicit escape hatch for A/B comparison; the safe path is the default. */
    private static final boolean DENSITY_OFFSET_MODE = Boolean.parseBoolean(
        System.getProperty("lc2h.terrain.shift.densityOffset", "true"));
    private static final double DENSITY_OFFSET_SCALE = readDensityOffsetScale();
    private static final double DENSITY_OFFSET_INPUT_BAND = readDensityOffsetInputBand();
    private static final int DENSITY_OFFSET_VERTICAL_BAND = readDensityOffsetVerticalBand();

    @Unique
    private boolean lc2h$active;
    @Unique
    private CityShiftField.Context lc2h$context;

    @Unique
    private long[] lc2h$sampleKeys;
    @Unique
    private double[] lc2h$sampleValues;
    @Unique
    private long[] lc2h$surfaceKeys;
    @Unique
    private int[] lc2h$surfaceValues;
    @Unique
    private long[] lc2h$referenceKeys;
    @Unique
    private int[] lc2h$referenceValues;

    @Unique
    private static boolean lc2h$isEnabled() {
        return ConfigManager.CITY_BLEND_ENABLED
            && (PROPERTY_ENABLED == null || PROPERTY_ENABLED);
    }

    @Unique
    private static Boolean readPropertyEnabled() {
        String raw = System.getProperty("lc2h.terrain.cityBlender.enabled");
        return raw == null ? null : Boolean.parseBoolean(raw);
    }

    @Inject(method = "of", at = @At("RETURN"), cancellable = true)
    private static void lc2h$replaceForCityEdge(WorldGenRegion region, CallbackInfoReturnable<Blender> cir) {
        if (!lc2h$isEnabled() || region == null) {
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
        state.lc2h$surfaceKeys = new long[state.lc2h$sampleKeys.length];
        Arrays.fill(state.lc2h$surfaceKeys, Long.MIN_VALUE);
        state.lc2h$surfaceValues = new int[state.lc2h$surfaceKeys.length];
        state.lc2h$referenceKeys = new long[state.lc2h$sampleKeys.length];
        Arrays.fill(state.lc2h$referenceKeys, Long.MIN_VALUE);
        state.lc2h$referenceValues = new int[state.lc2h$referenceKeys.length];
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

    @Inject(method = "blendDensity", at = @At("HEAD"), cancellable = true)
    private void lc2h$blendNativeDensity(net.minecraft.world.level.levelgen.DensityFunction.FunctionContext context,
                                         double input,
                                         CallbackInfoReturnable<Double> cir) {
        if (!this.lc2h$active || !DENSITY_OFFSET_MODE || context == null) {
            return;
        }
        MountainCityBlendDiagnostics.densityOffsetCall();
        double shift = this.lc2h$verticalDensityShift(context.blockX(), context.blockZ());
        if (shift <= 0.0D || DENSITY_OFFSET_SCALE == 0.0D) {
            cir.setReturnValue(input);
            return;
        }
        int nativeSurface = this.lc2h$nativeSurface(context.blockX(), context.blockZ());
        /* Never enter the vanilla height sampler from Blender.  blendDensity
         * runs while NoiseChunk is evaluating the same graph; asking for a
         * cold chunk height here can recursively re-enter generation or join
         * another worker's sampler flight.  A resident value is sufficient
         * for the bounded envelope; if it is not available, preserve the
         * native density for this sample. */
        if (nativeSurface == Integer.MIN_VALUE) {
            cir.setReturnValue(input);
            return;
        }
        /* A city demand is a lower-bound request, not permission to flatten a
         * naturally smooth mountain.  Only consume the part of the demand
         * that is above the local native surface envelope.  The envelope is
         * a small Gaussian over the same generator height samples, so a broad
         * Minecraft ridge is preserved while a one-sided city/mountain step
         * gets a bounded, shape-preserving correction. */
        int referenceSurface = this.lc2h$referenceSurface(
            Math.floorDiv(context.blockX(), 16), Math.floorDiv(context.blockZ(), 16));
        if (referenceSurface == Integer.MIN_VALUE) {
            cir.setReturnValue(input);
            return;
        }
        shift = CityDensityShiftField.capToNativeSurfaceEnvelope(
            shift, nativeSurface, referenceSurface);
        if (shift <= 0.0D) {
            cir.setReturnValue(input);
            return;
        }
        int depthBelowSurface = nativeSurface - context.blockY();
        if (depthBelowSurface < 0 || depthBelowSurface >= DENSITY_OFFSET_VERTICAL_BAND) {
            cir.setReturnValue(input);
            return;
        }
        /* Positive terrain shift lowers the native surface.  Native final
         * density is positive below the isosurface, so subtracting a bounded
         * scalar reproduces that displacement without changing the sampled Y
         * coordinate used by NoiseChunk's cell interpolators.  Taper it to
         * the native zero-crossing: deep solid/air and cave interiors must not
         * be rewritten just because a surface transition is being smoothed. */
        double band = DENSITY_OFFSET_INPUT_BAND;
        /* The cave/air side is input <= 0.  It must remain bit-for-bit
         * native: applying an offset there turns existing caves into new
         * columns of air (the failure caught by the integrated A/B).  On the
         * solid side, taper from the isosurface into the unchanged interior. */
        double gate = band <= 0.0D ? (input > 0.0D ? 1.0D : 0.0D)
            : (input > 0.0D ? Math.max(0.0D, 1.0D - input / band) : 0.0D);
        gate *= 1.0D - (double) depthBelowSurface / DENSITY_OFFSET_VERTICAL_BAND;
        cir.setReturnValue(input - shift * DENSITY_OFFSET_SCALE * gate);
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
        double shift = CHUNK_CONSTANT_SHIFT
            ? CityShiftField.shiftAtChunk(this.lc2h$context,
                Math.floorDiv(blockX - 8, 16), Math.floorDiv(blockZ - 8, 16))
            : CityShiftField.sample(this.lc2h$context, blockX, blockZ);
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

    @Unique
    private int lc2h$nativeSurface(int blockX, int blockZ) {
        int chunkX = Math.floorDiv(blockX, 16);
        int chunkZ = Math.floorDiv(blockZ, 16);
        long key = (((long) chunkX) << 32) ^ (chunkZ & 0xffffffffL);
        int sampleIndex = lc2h$sampleIndex(key, this.lc2h$surfaceKeys.length);
        if (this.lc2h$surfaceKeys[sampleIndex] == key) {
            return this.lc2h$surfaceValues[sampleIndex];
        }
        Integer cached = this.lc2h$context == null
            ? null
            : this.lc2h$context.terrain().cachedChunkHeight(chunkX, chunkZ);
        int surface = cached == null ? Integer.MIN_VALUE : cached;
        this.lc2h$surfaceValues[sampleIndex] = surface;
        this.lc2h$surfaceKeys[sampleIndex] = key;
        return surface;
    }

    @Unique
    private int lc2h$referenceSurface(int chunkX, int chunkZ) {
        long key = (((long) chunkX) << 32) ^ (chunkZ & 0xffffffffL);
        int sampleIndex = lc2h$sampleIndex(key, this.lc2h$referenceKeys.length);
        if (this.lc2h$referenceKeys[sampleIndex] == key) {
            return this.lc2h$referenceValues[sampleIndex];
        }
        int weighted = 0;
        int total = 0;
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                int weight = (dx == 0 && dz == 0) ? 4 : (dx == 0 || dz == 0 ? 2 : 1);
                Integer cached = this.lc2h$context == null
                    ? null
                    : this.lc2h$context.terrain().cachedChunkHeight(chunkX + dx, chunkZ + dz);
                if (cached == null) {
                    this.lc2h$referenceValues[sampleIndex] = Integer.MIN_VALUE;
                    this.lc2h$referenceKeys[sampleIndex] = key;
                    return Integer.MIN_VALUE;
                }
                weighted += cached * weight;
                total += weight;
            }
        }
        int reference = Math.round((float) weighted / (float) total);
        this.lc2h$referenceValues[sampleIndex] = reference;
        this.lc2h$referenceKeys[sampleIndex] = key;
        return reference;
    }

    @Unique
    private static double readDensityOffsetScale() {
        try {
            return Math.max(0.0D, Math.min(1.0D,
                Double.parseDouble(System.getProperty("lc2h.terrain.shift.densityOffsetScale", "0.02"))));
        } catch (RuntimeException ignored) {
            return 0.02D;
        }
    }

    @Unique
    private static double readDensityOffsetInputBand() {
        try {
            return Math.max(0.01D, Math.min(4.0D,
                Double.parseDouble(System.getProperty("lc2h.terrain.shift.densityOffsetInputBand", "0.35"))));
        } catch (RuntimeException ignored) {
            return 0.35D;
        }
    }

    @Unique
    private static int readDensityOffsetVerticalBand() {
        try {
            return Math.max(1, Math.min(64,
                Integer.parseInt(System.getProperty("lc2h.terrain.shift.densityOffsetVerticalBand", "16"))));
        } catch (RuntimeException ignored) {
            return 16;
        }
    }
}
