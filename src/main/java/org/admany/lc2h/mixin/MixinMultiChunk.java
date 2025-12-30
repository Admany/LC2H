package org.admany.lc2h.mixin;

import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.IDimensionInfo;
import mcjty.lostcities.worldgen.lost.MultiChunk;

import org.admany.lc2h.worldgen.async.planner.AsyncMultiChunkPlanner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = MultiChunk.class, remap = false)
public class MixinMultiChunk {

    @Inject(method = "getOrCreate", at = @At("HEAD"), cancellable = true)
    private static void lc2h$asyncGetOrCreate(IDimensionInfo provider, ChunkCoord coord, CallbackInfoReturnable<MultiChunk> cir) {
        if (AsyncMultiChunkPlanner.isInternalComputation()) {
            return;
        }

        AsyncMultiChunkPlanner.ensureScheduled(provider, coord);

        MultiChunk prepared = AsyncMultiChunkPlanner.tryConsumePrepared(provider, coord);
        if (prepared != null) {
            cir.setReturnValue(prepared);
            cir.cancel();
        }
    }

    @Inject(method = "getOrCreate", at = @At("RETURN"))
    private static void lc2h$afterGetOrCreate(IDimensionInfo provider, ChunkCoord coord, CallbackInfoReturnable<MultiChunk> cir) {
        AsyncMultiChunkPlanner.onSynchronousResult(provider, coord, cir.getReturnValue());
    }
}
