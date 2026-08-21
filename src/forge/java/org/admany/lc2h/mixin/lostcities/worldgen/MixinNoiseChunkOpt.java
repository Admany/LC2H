package org.admany.lc2h.mixin.lostcities.worldgen;

import mcjty.lostcities.worldgen.NoiseChunkOpt;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;
import org.admany.lc2h.worldgen.async.generator.AsyncNoiseGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.Function;

@Mixin(NoiseChunkOpt.class)
public class MixinNoiseChunkOpt {

    private static final boolean ENABLE_ASYNC_NOISE_WARMUP =
        Boolean.parseBoolean(System.getProperty("lc2h.noise.async_warmup", "false"));

    private static final boolean USE_IDENTITY_WRAP =
        // Minecraft's density graph nodes are immutable for a NoiseChunk.
        // Identity lookup avoids recursively comparing large CubicSpline trees
        // on every wrap call during cold highway and terrain planning.
        Boolean.parseBoolean(System.getProperty("lc2h.noise.identity_wrap", "true"));

    private static final int IDENTITY_INITIAL_CAPACITY = Math.max(64, Math.min(8192,
        Integer.getInteger("lc2h.noise.identityInitialCapacity", 1024)));

    @Unique
    private Map<DensityFunction, DensityFunction> lc2h$identityWrapped;

    @Inject(method = "optimizeNoise", at = @At("HEAD"), remap = false, require = 0, expect = 0)
    private void asyncOptimizeNoise(int chunkX, int chunkZ, CallbackInfo ci) {
        if (!ENABLE_ASYNC_NOISE_WARMUP) {
            return;
        }
        AsyncNoiseGenerator.generateNoiseAsync(chunkX, chunkZ);
    }

    @Inject(method = "<init>", at = @At("RETURN"), remap = false)
    private void lc2h$useIdentityWrapMap(int cellCountXZ,
                                         RandomState randomState,
                                         int chunkX,
                                         int chunkZ,
                                         NoiseSettings noiseSettings,
                                         DensityFunctions.BeardifierOrMarker beardifier,
                                         NoiseGeneratorSettings noiseGeneratorSettings,
                                         NoiseChunkOpt.FluidStatusV fluidStatus,
                                         CallbackInfo ci) {
        if (USE_IDENTITY_WRAP) {
            // NoiseChunkOpt.wrap visits a large immutable density graph. A
            // default IdentityHashMap starts at 32 slots and repeatedly
            // resizes while the graph is being wrapped, which showed up as a
            // hot allocation path in worldgen dumps. Reserve a modest table
            // up front while keeping the size configurable for unusual packs.
            lc2h$identityWrapped = new IdentityHashMap<>(IDENTITY_INITIAL_CAPACITY);
        }
    }

    @Redirect(
        method = "wrap",
        at = @At(
            value = "INVOKE",
            target = "Ljava/util/Map;computeIfAbsent(Ljava/lang/Object;Ljava/util/function/Function;)Ljava/lang/Object;"
        ),
        remap = false
    )
    private Object lc2h$wrapWithIdentityCache(Map<DensityFunction, DensityFunction> map,
                                              Object key,
                                              Function<Object, Object> mappingFunction) {
        if (!USE_IDENTITY_WRAP) {
            return map.computeIfAbsent((DensityFunction) key, k -> (DensityFunction) mappingFunction.apply(k));
        }
        // Some Lost Cities builds construct the object through a path where
        // the constructor injection is not reached before wrap().  Do not
        // silently fall back to HashMap in that case: equals() on a density
        // graph walks the whole CubicSpline tree and recreates the stall this
        // cache is meant to remove.
        Map<DensityFunction, DensityFunction> identity = lc2h$identityWrapped;
        if (identity == null) {
            identity = new IdentityHashMap<>(IDENTITY_INITIAL_CAPACITY);
            lc2h$identityWrapped = identity;
        }
        DensityFunction dfKey = (DensityFunction) key;
        DensityFunction cached = identity.get(dfKey);
        if (cached != null) {
            return cached;
        }
        DensityFunction created = (DensityFunction) mappingFunction.apply(dfKey);
        identity.put(dfKey, created);
        return created;
    }
}
