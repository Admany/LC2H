package org.admany.lc2h.mixin;

import mcjty.lostcities.worldgen.NoiseChunkOpt;

import org.admany.lc2h.worldgen.async.generator.AsyncNoiseGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(NoiseChunkOpt.class)
public class MixinNoiseChunkOpt {

    @Inject(method = "optimizeNoise", at = @At("HEAD"), remap = false, require = 0)
    private void asyncOptimizeNoise(int chunkX, int chunkZ, CallbackInfo ci) {
        AsyncNoiseGenerator.generateNoiseAsync(chunkX, chunkZ);
    }
}
