package org.admany.lc2h.mixin.lostcities;

import mcjty.lostcities.worldgen.ChunkHeightmap;
import mcjty.lostcities.worldgen.LostCityTerrainFeature;
import mcjty.lostcities.worldgen.lost.BuildingInfo;
import org.admany.lc2h.worldgen.CityEdgeBlender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = LostCityTerrainFeature.class, remap = false)
public class MixinCityEdgeBlend {

    @Inject(method = "getMinHeightAt", at = @At("HEAD"), cancellable = true)
    private void lc2h_blendCityEdge(BuildingInfo info, int x, int z, ChunkHeightmap heightmap, CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(CityEdgeBlender.blendedHeight(info, heightmap, x, z));
    }
}
