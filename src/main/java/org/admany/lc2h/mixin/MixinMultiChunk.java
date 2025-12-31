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

        if (provider == null || coord == null) {
            return;
        }

        String threadName = Thread.currentThread().getName();
        boolean isServerThread = "Server thread".equals(threadName);
        boolean holdsMultiChunkLock = Thread.holdsLock(MultiChunk.class);

        if (isServerThread || holdsMultiChunkLock) {
            AsyncMultiChunkPlanner.ensureScheduled(provider, coord);
            MultiChunk prepared = AsyncMultiChunkPlanner.tryConsumePrepared(provider, coord);
            if (prepared != null) {
                cir.setReturnValue(prepared);
                cir.cancel();
            }
            return;
        }

        MultiChunk resolved = AsyncMultiChunkPlanner.resolve(provider, coord);
        if (resolved != null) {
            cir.setReturnValue(resolved);
            cir.cancel();
        }
    }

    @Inject(method = "getOrCreate", at = @At("RETURN"))
    private static void lc2h$afterGetOrCreate(IDimensionInfo provider, ChunkCoord coord, CallbackInfoReturnable<MultiChunk> cir) {
        AsyncMultiChunkPlanner.onSynchronousResult(provider, coord, cir.getReturnValue());
    }
}
