package org.admany.lc2h.mixin.mods.dh;

import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import org.admany.lc2h.compat.DHCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Reuses DH's read-only LevelChunk wrapper inside one generated region.
 *
 * <p>DH already caches the underlying ChunkAccess by coordinate, but its
 * 1.20.1 Forge bridge constructs a new ImposterProtoChunk for every getChunk
 * call. Lost Cities performs many neighbour reads, so that otherwise cheap
 * conversion becomes a dominant allocation and ThreadingDetector cost.</p>
 */
@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.common.wrappers.worldGeneration.mimicObject.DhLitWorldGenRegion_forge", remap = false)
public abstract class MixinDhLitWorldGenRegionChunkCache {

    @Unique
    private final ConcurrentHashMap<Long, ImposterProtoChunk> lc2h$imposterChunks = new ConcurrentHashMap<>();

    /**
     * DH's public FULL-chunk lookup acquires its region lock before delegating
     * to getChunk(...). A cached immutable wrapper can be returned before that
     * lock without changing chunk ownership or generation semantics.
     */
    @Inject(method = "m_6325_(II)Lnet/minecraft/world/level/chunk/ChunkAccess;",
        at = @At("HEAD"), cancellable = true, require = 1)
    private void lc2h$reuseFullImposterChunk(int chunkX,
                                            int chunkZ,
                                            CallbackInfoReturnable<ChunkAccess> cir) {
        lc2h$returnCached(chunkX, chunkZ, cir);
    }

    /**
     * DH has a second status-aware entry point which also locks before calling
     * the four-argument lookup. ImposterProtoChunk wraps a completed
     * LevelChunk, so it satisfies every requested status.
     */
    @Inject(method = "m_46819_(IILnet/minecraft/world/level/chunk/ChunkStatus;)Lnet/minecraft/world/level/chunk/ChunkAccess;",
        at = @At("HEAD"), cancellable = true, require = 1)
    private void lc2h$reuseStatusImposterChunk(int chunkX,
                                              int chunkZ,
                                              ChunkStatus requiredStatus,
                                              CallbackInfoReturnable<ChunkAccess> cir) {
        lc2h$returnCached(chunkX, chunkZ, cir);
    }

    @Inject(method = "m_6522_(IILnet/minecraft/world/level/chunk/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;",
        at = @At("HEAD"), cancellable = true, require = 1)
    private void lc2h$reuseImposterChunk(int chunkX,
                                        int chunkZ,
                                        ChunkStatus requiredStatus,
                                        boolean create,
                                        CallbackInfoReturnable<ChunkAccess> cir) {
        lc2h$returnCached(chunkX, chunkZ, cir);
    }

    @Inject(method = "m_6522_(IILnet/minecraft/world/level/chunk/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;",
        at = @At("RETURN"), cancellable = true, require = 1)
    private void lc2h$cacheImposterChunk(int chunkX,
                                        int chunkZ,
                                        ChunkStatus requiredStatus,
                                        boolean create,
                                        CallbackInfoReturnable<ChunkAccess> cir) {
        ChunkAccess returned = cir.getReturnValue();
        if (!(returned instanceof ImposterProtoChunk imposter)) {
            return;
        }
        long key = ChunkPos.asLong(chunkX, chunkZ);
        ImposterProtoChunk existing = lc2h$imposterChunks.putIfAbsent(key, imposter);
        if (existing == null) {
            DHCompat.recordImposterCacheMiss();
        } else if (existing != imposter) {
            DHCompat.recordImposterCacheHit();
            cir.setReturnValue(existing);
        }
    }

    @Unique
    private void lc2h$returnCached(int chunkX,
                                  int chunkZ,
                                  CallbackInfoReturnable<ChunkAccess> cir) {
        ImposterProtoChunk cached = lc2h$imposterChunks.get(ChunkPos.asLong(chunkX, chunkZ));
        if (cached == null) {
            return;
        }
        DHCompat.recordImposterCacheHit();
        cir.setReturnValue(cached);
    }
}
