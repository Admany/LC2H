package org.admany.lc2h.mixin.minecraft.worldgen;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

import org.admany.lc2h.util.chunk.ChunkPostProcessor;
import org.admany.lc2h.worldgen.lostcities.DeferredTreeCaptureContext;
import org.admany.lc2h.worldgen.seams.SeamOwnershipJournal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Predicate;

@Mixin(WorldGenRegion.class)
public class MixinWorldGenRegion {
    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
            at = @At("RETURN"))
    private void onBlockSet(BlockPos pos, BlockState state, int flags, int recursionLeft, CallbackInfoReturnable<Boolean> cir) {
        try {
            if (cir.getReturnValue() && ChunkPostProcessor.isTracked(state.getBlock())) {
                WorldGenRegion region = (WorldGenRegion)(Object)this;
                ChunkPostProcessor.markForRemovalIfFloating(region, pos);
            }
        } catch (Throwable ignored) {
        }
    }

    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
            at = @At("HEAD"), cancellable = true)
    private void lc2h$preventTreeAirOverwrite(BlockPos pos, BlockState state, int flags, int recursionLeft, CallbackInfoReturnable<Boolean> cir) {
        try {
            if (DeferredTreeCaptureContext.isCapturing()) {
                DeferredTreeCaptureContext.capture(pos, state);
                cir.setReturnValue(true);
                return;
            }
            WorldGenRegion region = (WorldGenRegion)(Object)this;
            if (SeamOwnershipJournal.deferCrossChunkWrite(region, pos, state, flags)) {
                cir.setReturnValue(false);
                return;
            }
            // WorldGenRegion only clears a block entity when the previous block state
            // owned one. Structure/template NBT can outlive that state, leaving an
            // invalid pending entity for air (or another ordinary block) to deserialize
            // later. Clear only an existing pending tag, and only for this immediate
            // local write; captured and seam-owned writes above remain untouched.
            if (!state.hasBlockEntity() && region.ensureCanWrite(pos)) {
                ChunkAccess chunk = region.getChunk(pos);
                if (chunk.getBlockEntityNbt(pos) != null) {
                    chunk.removeBlockEntity(pos);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    @Inject(method = "m_8055_", at = @At("HEAD"), cancellable = true, remap = false, require = 0, expect = 0)
    private void lc2h$virtualizeTreeCaptureReads(BlockPos pos, CallbackInfoReturnable<BlockState> cir) {
        try {
            if (!DeferredTreeCaptureContext.isCapturing()) {
                return;
            }
            WorldGenRegion region = (WorldGenRegion)(Object)this;
            BlockState actual = region.getChunk(pos).getBlockState(pos);
            cir.setReturnValue(DeferredTreeCaptureContext.getVirtualState(pos, actual));
        } catch (Throwable ignored) {
        }
    }

    @Inject(method = "m_7433_", at = @At("HEAD"), cancellable = true, remap = false, require = 0, expect = 0)
    private void lc2h$virtualizeTreeCaptureStatePredicates(BlockPos pos, Predicate<BlockState> predicate, CallbackInfoReturnable<Boolean> cir) {
        try {
            if (!DeferredTreeCaptureContext.isCapturing() || predicate == null) {
                return;
            }
            WorldGenRegion region = (WorldGenRegion)(Object)this;
            BlockState actual = region.getChunk(pos).getBlockState(pos);
            BlockState virtual = DeferredTreeCaptureContext.getVirtualState(pos, actual);
            cir.setReturnValue(predicate.test(virtual));
        } catch (Throwable ignored) {
        }
    }

    @Inject(method = "m_6425_", at = @At("HEAD"), cancellable = true, remap = false, require = 0, expect = 0)
    private void lc2h$virtualizeTreeCaptureFluidReads(BlockPos pos, CallbackInfoReturnable<FluidState> cir) {
        try {
            if (!DeferredTreeCaptureContext.isCapturing()) {
                return;
            }
            WorldGenRegion region = (WorldGenRegion)(Object)this;
            BlockState actual = region.getChunk(pos).getBlockState(pos);
            BlockState virtual = DeferredTreeCaptureContext.getVirtualState(pos, actual);
            cir.setReturnValue(virtual.getFluidState());
        } catch (Throwable ignored) {
        }
    }
}
