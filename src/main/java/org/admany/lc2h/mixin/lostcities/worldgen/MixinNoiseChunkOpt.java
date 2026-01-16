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

    private static final boolean USE_IDENTITY_WRAP =
        Boolean.parseBoolean(System.getProperty("lc2h.noise.identity_wrap", "true"));

    @Unique
    private Map<DensityFunction, DensityFunction> lc2h$identityWrapped;

    @Inject(method = "optimizeNoise", at = @At("HEAD"), remap = false, require = 0)
    private void asyncOptimizeNoise(int chunkX, int chunkZ, CallbackInfo ci) {
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
            lc2h$identityWrapped = new IdentityHashMap<>();
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
        if (!USE_IDENTITY_WRAP || lc2h$identityWrapped == null) {
            return map.computeIfAbsent((DensityFunction) key, k -> (DensityFunction) mappingFunction.apply(k));
        }
        DensityFunction dfKey = (DensityFunction) key;
        DensityFunction cached = lc2h$identityWrapped.get(dfKey);
        if (cached != null) {
            return cached;
        }
        DensityFunction created = (DensityFunction) mappingFunction.apply(dfKey);
        lc2h$identityWrapped.put(dfKey, created);
        return created;
    }
}
