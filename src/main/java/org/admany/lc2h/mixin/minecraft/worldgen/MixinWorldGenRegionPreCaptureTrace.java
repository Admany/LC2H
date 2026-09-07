package org.admany.lc2h.mixin.minecraft.worldgen;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.block.state.BlockState;
import org.admany.lc2h.dev.debug.PreCaptureTargetTraceRegistry;
import org.admany.lc2h.worldgen.lostcities.DeferredTreeCaptureContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Installed only for an explicitly enabled pre-capture trace run. */
@Mixin(WorldGenRegion.class)
public final class MixinWorldGenRegionPreCaptureTrace {
    private static final ThreadLocal<BlockState> LC2H_TRACE_OLD_STATE = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> LC2H_TRACE_CAPTURED_WRITE = ThreadLocal.withInitial(() -> false);

    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z", at = @At("HEAD"))
    private void lc2h$captureOldTargetState(BlockPos pos, BlockState state, int flags, int recursionLeft,
                                            CallbackInfoReturnable<Boolean> cir) {
        if (!PreCaptureTargetTraceRegistry.shouldTracePosition(pos)) {
            return;
        }
        try {
            WorldGenRegion region = (WorldGenRegion) (Object) this;
            LC2H_TRACE_OLD_STATE.set(region.getChunk(pos).getBlockState(pos));
            LC2H_TRACE_CAPTURED_WRITE.set(DeferredTreeCaptureContext.isCapturing());
        } catch (Throwable ignored) {
            LC2H_TRACE_OLD_STATE.remove();
            LC2H_TRACE_CAPTURED_WRITE.remove();
        }
    }

    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z", at = @At("RETURN"))
    private void lc2h$recordTargetState(BlockPos pos, BlockState state, int flags, int recursionLeft,
                                        CallbackInfoReturnable<Boolean> cir) {
        if (!PreCaptureTargetTraceRegistry.shouldTracePosition(pos)) {
            return;
        }
        try {
            if (cir.getReturnValue() && !Boolean.TRUE.equals(LC2H_TRACE_CAPTURED_WRITE.get())) {
                PreCaptureTargetTraceRegistry.recordTargetWrite(pos, LC2H_TRACE_OLD_STATE.get(), state,
                    PreCaptureTargetTraceRegistry.captureCaller());
            }
        } finally {
            LC2H_TRACE_OLD_STATE.remove();
            LC2H_TRACE_CAPTURED_WRITE.remove();
        }
    }

    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
        at = @At("HEAD"), cancellable = true)
    private void lc2h$traceDeferredWrite(BlockPos pos, BlockState state, int flags, int recursionLeft,
                                         CallbackInfoReturnable<Boolean> cir) {
        if (!PreCaptureTargetTraceRegistry.shouldTracePosition(pos)) {
            return;
        }
        try {
            WorldGenRegion region = (WorldGenRegion) (Object) this;
            BlockState oldState = region.getChunk(pos).getBlockState(pos);
            if (DeferredTreeCaptureContext.isCapturing()) {
                PreCaptureTargetTraceRegistry.recordSkippedTargetWrite(pos, oldState, state,
                    PreCaptureTargetTraceRegistry.captureCaller(), "tree-captured");
            }
        } catch (Throwable ignored) {
        }
    }
}
