package org.admany.lc2h.mixin.lostcities.async;

import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.IDimensionInfo;
import mcjty.lostcities.worldgen.lost.BuildingInfo;
import mcjty.lostcities.api.LostChunkCharacteristics;

import org.admany.lc2h.worldgen.async.generator.AsyncBuildingGenerator;
import org.admany.lc2h.worldgen.async.planner.AsyncBuildingInfoPlanner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = BuildingInfo.class, remap = false)
public abstract class MixinBuildingInfo {

    @Inject(method = "getBuildingInfo", at = @At("HEAD"), cancellable = true)
    private static void lc2h$checkCachedBuildingInfo(ChunkCoord coord, IDimensionInfo info, CallbackInfoReturnable<BuildingInfo> cir) {
        if (AsyncBuildingInfoPlanner.isInternalComputation()) {
            return;
        }
        Object cached = AsyncBuildingInfoPlanner.getIfReady(coord);
        if (cached instanceof BuildingInfo bi) {
            cir.setReturnValue(bi);
            cir.cancel();
        } else {
            AsyncBuildingInfoPlanner.preSchedule(info, coord);
        }
    }

    @Inject(method = "getChunkCharacteristics", at = @At("HEAD"), cancellable = true)
    private static void lc2h$checkCachedCharacteristics(ChunkCoord coord, IDimensionInfo info, CallbackInfoReturnable<LostChunkCharacteristics> cir) {
        if (!AsyncBuildingInfoPlanner.isInternalComputation()) {
            AsyncBuildingInfoPlanner.preSchedule(info, coord);
        }
    }

    @Inject(method = "getBuildingInfo", at = @At("RETURN"))
    private static void lc2h$asyncWarmBuildingCache(ChunkCoord coord, IDimensionInfo info, CallbackInfoReturnable<Object> cir) {
        Object result = cir.getReturnValue();
        if (result != null) {
            AsyncBuildingGenerator.generateBuildingAsync(result);
        }
    }
}
