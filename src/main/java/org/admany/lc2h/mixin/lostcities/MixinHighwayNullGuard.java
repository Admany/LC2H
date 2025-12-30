package org.admany.lc2h.mixin.lostcities;

import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.lost.Highway;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Map;

@Mixin(value = Highway.class, remap = false)
public abstract class MixinHighwayNullGuard {

    @Redirect(
        method = "getHighwayLevel",
        at = @At(
            value = "INVOKE",
            target = "Ljava/util/Map;get(Ljava/lang/Object;)Ljava/lang/Object;"
        )
    )
    private static Object lc2h$safeCacheGet(Map<ChunkCoord, Integer> cache, Object key) {
        Object v;
        try {
            v = cache.get(key);
        } catch (Throwable t) {
            return Integer.valueOf(-1);
        }
        return v != null ? v : Integer.valueOf(-1);
    }
}
