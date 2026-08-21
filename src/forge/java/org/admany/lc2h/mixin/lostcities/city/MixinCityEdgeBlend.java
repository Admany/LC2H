package org.admany.lc2h.mixin.lostcities.city;

import mcjty.lostcities.worldgen.ChunkHeightmap;
import mcjty.lostcities.worldgen.LostCityTerrainFeature;
import mcjty.lostcities.worldgen.lost.BuildingInfo;
import org.admany.lc2h.worldgen.terrain.CityTerrainPlanBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Keeps Lost Cities' later terrain/block pass on the exact floor selected by
 * the authoritative Minecraft density plan. This hook does not shape terrain
 * or run an independent detector.
 */
@Mixin(value = LostCityTerrainFeature.class, remap = false)
public class MixinCityEdgeBlend {

    private static final boolean ENABLED = Boolean.parseBoolean(
        System.getProperty("lc2h.cityBlend.enabled", "false"));

    @Inject(method = "getMinHeightAt", at = @At("HEAD"), cancellable = true)
    private void lc2h_blendCityEdge(BuildingInfo info, int x, int z, ChunkHeightmap heightmap, CallbackInfoReturnable<Integer> cir) {
        if (!ENABLED) {
            return;
        }
        Integer planned = CityTerrainPlanBridge.plannedMinHeight(info);
        if (planned != null) {
            cir.setReturnValue(planned);
        }
    }
}
