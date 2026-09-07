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

import java.lang.StackWalker;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Mixin(ServerChunkCache.class)
public class MixinServerChunkCache {

    private static final boolean DEBUG_CHUNK_FUTURES = Boolean.getBoolean("lc2h.debugChunkFutures");
    private static final boolean DEBUG_SYNC_CHUNK_WAITS =
        DEBUG_CHUNK_FUTURES || Boolean.getBoolean("lc2h.debugSyncChunkWaits");
    private static final AtomicBoolean SYNC_WAIT_LOGGED = new AtomicBoolean(false);
    private static final AtomicInteger SYNC_WAIT_LOG_COUNT = new AtomicInteger(0);
    private static final int MAX_SYNC_WAIT_LOGS = Integer.getInteger("lc2h.debugSyncChunkWaitLimit", 8);
    private static final ConcurrentHashMap<String, Boolean> SYNC_WAIT_SIGNATURES = new ConcurrentHashMap<>();
    private static final StackWalker STACK_WALKER = StackWalker.getInstance();

    @Inject(method = "getChunk", at = @At("HEAD"))
    private void lc2h$logServerThreadChunkWait(int chunkX, int chunkZ, ChunkStatus status, boolean create, CallbackInfoReturnable<ChunkAccess> cir) {
        if (!DEBUG_SYNC_CHUNK_WAITS) {
            return;
        }
        Thread thread = Thread.currentThread();
        if (thread == null) {
            return;
        }
        String threadName = thread.getName();
        boolean interestingThread = "Server thread".equals(threadName) || (threadName != null && threadName.startsWith("Worker-Main-"));
        if (!interestingThread) {
            return;
        }
        String signature = findInterestingCaller();
        if ("Server thread".equals(threadName) && SYNC_WAIT_LOGGED.compareAndSet(false, true)) {
            LC2H.LOGGER.warn("[LC2H] First server-thread sync chunk wait at ({}, {}) status={} create={} caller={}",
                chunkX, chunkZ, status, create, signature == null ? "<unknown>" : signature);
        }
        String effectiveSignature = (threadName == null ? "<unknown>" : threadName) + "|" + (signature == null ? "<unknown>" : signature);
        if (SYNC_WAIT_SIGNATURES.putIfAbsent(effectiveSignature, Boolean.TRUE) != null) {
            return;
        }
        int count = SYNC_WAIT_LOG_COUNT.incrementAndGet();
        if (count > MAX_SYNC_WAIT_LOGS) {
            return;
        }
        LC2H.LOGGER.warn("[LC2H] Sync chunk wait #{} thread={} chunk=({}, {}) status={} create={} caller={}",
            count,
            threadName,
            chunkX,
            chunkZ,
            status,
            create,
            signature == null ? "<unknown>" : signature);
    }

    @Inject(method = "getChunkFuture(IILnet/minecraft/world/level/chunk/ChunkStatus;Z)Ljava/util/concurrent/CompletableFuture;", at = @At("RETURN"))
    private void lc2h$logChunkFutureCompletion(int chunkX, int chunkZ, ChunkStatus status, boolean create, CallbackInfoReturnable<CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>>> cir) {
        CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> future = cir.getReturnValue();
        if (future == null) {
            LC2H.LOGGER.warn("Chunk future null for ({}, {}) @ {} (create={})", chunkX, chunkZ, status, create);
            return;
        }
        if (!DEBUG_CHUNK_FUTURES) {
            return;
        }

        long startNanos = System.nanoTime();
        LC2H.LOGGER.debug("Chunk future requested for ({}, {}) @ {} (create={})", chunkX, chunkZ, status, create);

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
            LC2H.LOGGER.debug("Chunk future completed for ({}, {}) @ {} (create={}) in {} ms: success={}, failure={}", chunkX, chunkZ, status, create, durationMs, success, failed);
        });
    }

    private static String findInterestingCaller() {
        return STACK_WALKER.walk(stream -> stream
            .map(StackWalker.StackFrame::toStackTraceElement)
            .filter(frame -> {
                String className = frame.getClassName();
                return !className.startsWith("org.admany.lc2h.mixin.minecraft.server.MixinServerChunkCache")
                    && !className.startsWith("net.minecraft.server.level.ServerChunkCache")
                    && !className.startsWith("net.minecraft.util.thread.BlockableEventLoop")
                    && !className.startsWith("java.util.concurrent")
                    && !className.startsWith("java.lang.Thread")
                    && !className.startsWith("jdk.internal.misc.Unsafe");
            })
            .findFirst()
            .map(StackTraceElement::toString)
            .orElse("<unknown>"));
    }
}
