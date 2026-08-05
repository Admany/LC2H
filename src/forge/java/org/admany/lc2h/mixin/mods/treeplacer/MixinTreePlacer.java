package org.admany.lc2h.mixin.mods.treeplacer;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import org.admany.lc2h.worldgen.lostcities.DeferredTreeCaptureContext;
import org.admany.lc2h.worldgen.lostcities.DeferredTreeQueue;
import org.admany.lc2h.worldgen.lostcities.TreeCapturePolicy;
import org.admany.lc2h.worldgen.lostcities.TreeCompatTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "com.outrightwings.growth.TreePlacer", remap = false)
public class MixinTreePlacer {

    @Unique
    private static final ThreadLocal<Boolean> LC2H_CAPTURE_STARTED = ThreadLocal.withInitial(() -> false);

    @Inject(
        method = "growTree(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/ChunkGenerator;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/util/RandomSource;Z)I",
        at = @At("HEAD"),
        cancellable = true,
        require = 0
    )
    private static void lc2h$beginTreePlacerCapture(net.minecraft.server.level.ServerLevel level,
                                                    ChunkGenerator generator,
                                                    BlockPos pos,
                                                    BlockState state,
                                                    RandomSource random,
                                                    boolean mega,
                                                    CallbackInfoReturnable<Integer> cir) {
        TreeCompatTracker.markHookObserved("treeplacer.grow_tree");
        LC2H_CAPTURE_STARTED.set(false);
        if (DeferredTreeQueue.isReplaying() || DeferredTreeCaptureContext.isCapturing()) {
            return;
        }
        TreeCapturePolicy.Decision decision = TreeCapturePolicy.decideAt(level, pos);
        if (decision == TreeCapturePolicy.Decision.REJECT) {
            cir.setReturnValue(0);
            return;
        }
        if (decision != TreeCapturePolicy.Decision.CAPTURE) {
            return;
        }
        DeferredTreeCaptureContext.CaptureSource source = TreeCompatTracker.classifyTreePlacerSource(state);
        DeferredTreeCaptureContext.begin(pos, level.dimension(), level.getSeed(), source);
        LC2H_CAPTURE_STARTED.set(true);
    }

    @Inject(
        method = "growTree(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/ChunkGenerator;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/util/RandomSource;Z)I",
        at = @At("RETURN"),
        require = 0
    )
    private static void lc2h$finishTreePlacerCapture(net.minecraft.server.level.ServerLevel level,
                                                     ChunkGenerator generator,
                                                     BlockPos pos,
                                                     BlockState state,
                                                     RandomSource random,
                                                     boolean mega,
                                                     CallbackInfoReturnable<Integer> cir) {
        boolean started = LC2H_CAPTURE_STARTED.get();
        LC2H_CAPTURE_STARTED.remove();
        if (!started) {
            return;
        }
        DeferredTreeCaptureContext.CapturedTree captured = DeferredTreeCaptureContext.finish();
        if (captured == null) {
            return;
        }
        if (cir.getReturnValue() == null || cir.getReturnValue() <= 0 || captured.blocks().isEmpty()) {
            return;
        }
        TreeCompatTracker.recordCapture(captured.source(), captured.blocks().size());
        DeferredTreeQueue.enqueue(
            captured.dim(),
            DeferredTreeQueue.PendingTree.captured(captured.origin(), captured.blocks(), captured.dim(), captured.seed(), captured.source()));
    }
}
