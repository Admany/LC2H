package org.admany.lc2h.mixin.lostcities.biome;

import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.IDimensionInfo;
import mcjty.lostcities.worldgen.lost.BiomeInfo;
import org.admany.lc2h.data.cache.BiomeInfoRuntimeCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Replaces Lost Cities' global, synchronised/disk-backed biome lookup with a
 * bounded world-scoped runtime cache suitable for concurrent world generation.
 */
@Mixin(value = BiomeInfo.class, remap = false)
public class MixinBiomeInfo {

    @Inject(method = "getBiomeInfo", at = @At("HEAD"), cancellable = true)
    private static void lc2h$getBiomeInfoThreadSafe(IDimensionInfo provider,
                                                     ChunkCoord coord,
                                                     CallbackInfoReturnable<BiomeInfo> cir) {
        if (provider == null || coord == null) {
            cir.setReturnValue(null);
            return;
        }
        cir.setReturnValue(BiomeInfoRuntimeCache.get(provider, coord));
    }

    @Inject(method = "cleanCache", at = @At("HEAD"))
    private static void lc2h$clearBiomeCache(CallbackInfo ci) {
        BiomeInfoRuntimeCache.clear();
    }
}
