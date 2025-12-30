package org.admany.lc2h.mixin.lostcities;

import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.lost.Highway;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Mixin(value = Highway.class, remap = false)
public abstract class MixinHighwayThreadSafety {

    @Shadow @Final @Mutable
    private static Map<ChunkCoord, Integer> X_HIGHWAY_LEVEL_CACHE;

    @Shadow @Final @Mutable
    private static Map<ChunkCoord, Integer> Z_HIGHWAY_LEVEL_CACHE;

    @Inject(method = "<clinit>", at = @At("TAIL"))
    private static void lc2h$makeHighwayCachesConcurrent(CallbackInfo ci) {
        X_HIGHWAY_LEVEL_CACHE = new ConcurrentHashMap<>();
        Z_HIGHWAY_LEVEL_CACHE = new ConcurrentHashMap<>();
    }
}
