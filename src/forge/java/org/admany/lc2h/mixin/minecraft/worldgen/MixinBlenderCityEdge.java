package org.admany.lc2h.mixin.minecraft.worldgen;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import mcjty.lostcities.config.LostCityProfile;
import mcjty.lostcities.worldgen.IDimensionInfo;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.admany.lc2h.mixin.accessor.minecraft.BlenderFactory;
import org.admany.lc2h.config.ConfigManager;
import org.admany.lc2h.util.server.DimensionInfoAccessor;
import org.admany.lc2h.util.chunk.ChunkPostProcessor;
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

/** Applies the Lost Cities terrain adjustment to Minecraft's Blender. */
@Mixin(Blender.class)
public abstract class MixinBlenderCityEdge implements CityDensityTransform {

    /* NoiseChunk interpolates cell corners. Keep the cell-constant variant for
     * comparison runs. */
    private static final boolean CHUNK_CONSTANT_SHIFT = Boolean.parseBoolean(
        System.getProperty("lc2h.terrain.shift.chunkConstant", "false"));

    /** Add a bounded surface adjustment without changing NoiseChunk's Y context. */
    private static final boolean DENSITY_OFFSET_MODE = Boolean.parseBoolean(
        System.getProperty("lc2h.terrain.shift.densityOffset", "false"));
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
        return ConfigManager.CITY_BLEND_ENABLED;
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
        MountainCityBlendDiagnostics.blenderSeen(provider.getType(), center.x, center.z);
        double centreShift = 0.0D;
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                centreShift = Math.max(centreShift,
                    CityShiftField.shiftAtChunk(context, center.x + dx, center.z + dz));
            }
        }
        // Check the cached halo around the Blender owner chunk.
        boolean cachedInfluence = CityShiftField.hasCachedPositiveShiftNear(
            context, center.x, center.z, context.settings().halo() + 1);
        if (centreShift <= 0.0D && !cachedInfluence) {
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
        MountainCityBlendDiagnostics.blenderApplied(provider.getType(), center.x, center.z);
        // Queue the owner for the final surface pass.
        ChunkPostProcessor.noteGeneratedChunk(provider.getType(), center.x, center.z);
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
        MountainCityBlendDiagnostics.densityPositiveShift();
        MountainCityBlendDiagnostics.densityInput(input);
        int nativeSurface = this.lc2h$nativeSurface(context.blockX(), context.blockZ());
        // Use resident heights only; do not load a cold chunk here.
        boolean surfaceKnown = nativeSurface != Integer.MIN_VALUE;
        if (!surfaceKnown) {
            // No resident height means no terrain adjustment yet.
            MountainCityBlendDiagnostics.densitySurfaceFallback();
        }
        // Apply the shift only near the native surface.
        int depthBelowSurface = surfaceKnown
            ? nativeSurface - context.blockY() : 0;
        if (surfaceKnown && (depthBelowSurface < 0
            || depthBelowSurface >= DENSITY_OFFSET_VERTICAL_BAND)) {
            MountainCityBlendDiagnostics.densityDepthReject();
            cir.setReturnValue(input);
            return;
        }
        // Leave deep solid and air regions unchanged.
        double band = DENSITY_OFFSET_INPUT_BAND;
        // Keep the air side of the density function unchanged.
        double gate = band <= 0.0D ? (input > 0.0D ? 1.0D : 0.0D)
            : (input > 0.0D ? Math.max(0.0D, 1.0D - input / band) : 0.0D);
        if (surfaceKnown) {
            gate *= 1.0D - (double) depthBelowSurface / DENSITY_OFFSET_VERTICAL_BAND;
        }
        if (gate > 0.0D) {
            MountainCityBlendDiagnostics.densityShifted(shift);
        }
        cir.setReturnValue(input - shift * DENSITY_OFFSET_SCALE * gate);
    }

    @Override
    public boolean lc2h$isDensityTransformActive() {
        return this.lc2h$active;
    }

    @Override
    public ResourceKey<Level> lc2h$dimension() {
        return this.lc2h$context == null ? null : this.lc2h$context.dimension();
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
        // Use the resident surface for the depth gate; the lattice can miss a
        // one-sided mountain.
        Integer cached = this.lc2h$context == null ? null
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
        Integer planned = this.lc2h$context == null ? null
            : CityShiftField.plannedReferenceSurface(this.lc2h$context, chunkX, chunkZ);
        if (planned != null) {
            this.lc2h$referenceValues[sampleIndex] = planned;
            this.lc2h$referenceKeys[sampleIndex] = key;
            return planned;
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
