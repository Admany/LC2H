package org.admany.lc2h.mixin.minecraft.server;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.FluidState;
import org.admany.lc2h.worldgen.lostcities.DeferredTreeCaptureContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// ServerLevel inherits these methods from Level. Mixing into ServerLevel left
// direct WorldGenLevel#setBlock dispatches untouched, so structure trees could
// enter capture and still write straight into the level.
@Mixin(Level.class)
public class MixinServerLevelTreeCapture {

    private static BlockState lc2h$getLoadedState(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null) {
            return Blocks.AIR.defaultBlockState();
        }
        LevelChunk chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        return chunk == null ? Blocks.AIR.defaultBlockState() : chunk.getBlockState(pos);
    }

    @Inject(method = "m_7731_", at = @At("HEAD"), cancellable = true, remap = false, require = 0, expect = 0)
    private void lc2h$captureServerLevelTreeWrites(BlockPos pos,
                                                   BlockState state,
                                                   int flags,
                                                   CallbackInfoReturnable<Boolean> cir) {
        if (!DeferredTreeCaptureContext.isCapturing() || pos == null || state == null) {
            return;
        }
        DeferredTreeCaptureContext.capture(pos, state);
        cir.setReturnValue(true);
    }

    @Inject(method = "m_8055_", at = @At("HEAD"), cancellable = true, remap = false, require = 0, expect = 0)
    private void lc2h$virtualizeServerLevelTreeReads(BlockPos pos, CallbackInfoReturnable<BlockState> cir) {
        if (!DeferredTreeCaptureContext.isCapturing() || pos == null) {
            return;
        }
        if (!((Object) this instanceof ServerLevel level)) {
            return;
        }
        BlockState actual = lc2h$getLoadedState(level, pos);
        cir.setReturnValue(DeferredTreeCaptureContext.getVirtualState(pos, actual));
    }

    @Inject(method = "m_6425_", at = @At("HEAD"), cancellable = true, remap = false, require = 0, expect = 0)
    private void lc2h$virtualizeServerLevelFluidReads(BlockPos pos, CallbackInfoReturnable<FluidState> cir) {
        if (!DeferredTreeCaptureContext.isCapturing() || pos == null) {
            return;
        }
        if (!((Object) this instanceof ServerLevel level)) {
            return;
        }
        BlockState actual = lc2h$getLoadedState(level, pos);
        BlockState virtual = DeferredTreeCaptureContext.getVirtualState(pos, actual);
        cir.setReturnValue(virtual.getFluidState());
    }
}
