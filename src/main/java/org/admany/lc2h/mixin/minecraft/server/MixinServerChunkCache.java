package org.admany.lc2h.mixin.minecraft.server;

import com.mojang.datafixers.util.Either;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import org.admany.lc2h.LC2H;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;

@Mixin(ServerChunkCache.class)
public class MixinServerChunkCache {

    @Inject(method = "getChunkFuture(IILnet/minecraft/world/level/chunk/ChunkStatus;Z)Ljava/util/concurrent/CompletableFuture;", at = @At("RETURN"))
    private void lc2h$logChunkFutureCompletion(int chunkX, int chunkZ, ChunkStatus status, boolean create, CallbackInfoReturnable<CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>>> cir) {
        CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> future = cir.getReturnValue();
        if (future == null) {
            LC2H.LOGGER.warn("Chunk future null for ({}, {}) @ {} (create={})", chunkX, chunkZ, status, create);
            return;
        }

        long startNanos = System.nanoTime();
        LC2H.LOGGER.info("Chunk future requested for ({}, {}) @ {} (create={})", chunkX, chunkZ, status, create);

        // This warns if the future is still not done after a short delay to pinpoint stuck coordinates.
        CompletableFuture.delayedExecutor(5, java.util.concurrent.TimeUnit.SECONDS).execute(() -> {
            if (!future.isDone()) {
                LC2H.LOGGER.warn("Chunk future still pending after 5s for ({}, {}) @ {} (create={})", chunkX, chunkZ, status, create);
            }
        });

        future.whenComplete((result, throwable) -> {
            long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
            if (throwable != null) {
                LC2H.LOGGER.error("Chunk future failed for ({}, {}) @ {} (create={}) after {} ms: {}", chunkX, chunkZ, status, create, durationMs, throwable.getMessage());
                return;
            }

            boolean success = result != null && result.left().isPresent();
            boolean failed = result != null && result.right().isPresent();
            LC2H.LOGGER.info("Chunk future completed for ({}, {}) @ {} (create={}) in {} ms: success={}, failure={}", chunkX, chunkZ, status, create, durationMs, success, failed);
        });
    }
}
