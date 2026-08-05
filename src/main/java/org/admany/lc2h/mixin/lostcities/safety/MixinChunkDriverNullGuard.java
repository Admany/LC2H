package org.admany.lc2h.mixin.lostcities.safety;

import mcjty.lostcities.worldgen.ChunkDriver;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.admany.lc2h.worldgen.lostcities.ChunkDriverBlockEntityCleanup;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = ChunkDriver.class, remap = false)
public class MixinChunkDriverNullGuard {

    @Inject(method = "correct", at = @At("HEAD"), cancellable = true)
    private void lc2h$nullGuard(BlockState state, CallbackInfoReturnable<BlockState> cir) {
        if (state == null) {
            cir.setReturnValue(null);
        }
    }

    /**
     * ChunkDriver writes its SectionCache directly into chunk sections, bypassing
     * WorldGenRegion#setBlock. Pending structure block-entity NBT can therefore
     * survive after the final state became air or another ordinary block. Forge
     * later tries to deserialize those stale tags on the server thread, producing
     * hundreds of warnings and a large chunk-promotion stall.
     *
     * getBlockEntitiesPos() already returns a defensive copy. This cleanup is
     * bounded by the number of block-entity tags in the chunk and never scans
     * ordinary block positions.
     */
    @Inject(method = "actuallyGenerate", at = @At("RETURN"))
    private void lc2h$removeStalePendingBlockEntities(ChunkAccess chunk, CallbackInfo ci) {
        if (chunk == null) {
            return;
        }
        try {
            for (BlockPos pos : chunk.getBlockEntitiesPos()) {
                ChunkDriverBlockEntityCleanup.checked();
                if (!chunk.getBlockState(pos).hasBlockEntity()) {
                    chunk.removeBlockEntity(pos);
                    ChunkDriverBlockEntityCleanup.removed();
                }
            }
        } catch (Throwable ignored) {
            // World generation must remain fail-open. The failure counter makes a
            // mapping/runtime incompatibility visible without spamming hot paths.
            ChunkDriverBlockEntityCleanup.failed();
        }
    }
}
