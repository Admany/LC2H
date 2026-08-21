package org.admany.lc2h.mixin.lostcities.highway;

import mcjty.lostcities.worldgen.highway.HighwayHub;
import mcjty.lostcities.worldgen.highway.HubKey;
import mcjty.lostcities.worldgen.highway.IntercityHighwayPlanner;
import org.admany.lc2h.worldgen.lostcities.HighwayHeightmapPrefetchContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

@Mixin(value = IntercityHighwayPlanner.class, remap = false)
public abstract class MixinIntercityHighwayHeightmapPrefetch {
    @Inject(method = "calculateHub", at = @At("HEAD"))
    private void lc2h$beginHeightmapPrefetch(HubKey key,
                                             CallbackInfoReturnable<Optional<HighwayHub>> cir) {
        HighwayHeightmapPrefetchContext.begin(new int[0]);
    }

    @Inject(method = "calculateHub", at = @At("RETURN"))
    private void lc2h$clearHeightmapPrefetch(HubKey key,
                                             CallbackInfoReturnable<Optional<HighwayHub>> cir) {
        HighwayHeightmapPrefetchContext.clear();
    }

    @Inject(method = "calculateOwnedRoutes", at = @At("HEAD"))
    private void lc2h$beginRouteHeightGate(HubKey key,
                                           CallbackInfoReturnable<?> cir) {
        HighwayHeightmapPrefetchContext.begin(new int[0]);
    }

    @Inject(method = "calculateOwnedRoutes", at = @At("RETURN"))
    private void lc2h$clearRouteHeightGate(HubKey key,
                                           CallbackInfoReturnable<?> cir) {
        HighwayHeightmapPrefetchContext.clear();
    }
}
