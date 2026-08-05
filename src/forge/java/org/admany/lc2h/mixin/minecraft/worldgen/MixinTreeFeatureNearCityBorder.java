package org.admany.lc2h.mixin.minecraft.worldgen;

import mcjty.lostcities.setup.Registration;
import mcjty.lostcities.worldgen.IDimensionInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.TreeFeature;
import net.minecraft.world.level.levelgen.feature.configurations.TreeConfiguration;
import org.admany.lc2h.worldgen.lostcities.DeferredTreeCaptureContext;
import org.admany.lc2h.worldgen.lostcities.DeferredTreeQueue;
import org.admany.lc2h.worldgen.lostcities.TreeCompatTracker;
import org.admany.lc2h.worldgen.lostcities.TreeCapturePolicy;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(TreeFeature.class)
public class MixinTreeFeatureNearCityBorder {

    @Unique
    private static final int LC2H_BOP_CAPTURE_RADIUS_BLOCKS = 48;

    @Unique
    private boolean lc2h$isBiomesOPlentyTree() {
        return ((Object) this).getClass().getName().startsWith("biomesoplenty.common.worldgen.feature.tree.");
    }

    @Inject(method = "place", at = @At("HEAD"), cancellable = true)
    private void lc2h$deferTreesNearCityBorder(
        FeaturePlaceContext<TreeConfiguration> context,
        CallbackInfoReturnable<Boolean> cir
    ) {
        DeferredTreeCaptureContext.clear();
        if (DeferredTreeQueue.isReplaying()) {
            return;
        }
        boolean biomesOPlentyTree = lc2h$isBiomesOPlentyTree();
        if (biomesOPlentyTree) {
            TreeCompatTracker.markHookObserved("biomesoplenty.tree_feature.place");
        }
        WorldGenLevel level = context.level();
        if (level == null) {
            return;
        }

        IDimensionInfo dimInfo;
        try {
            dimInfo = Registration.LOSTCITY_FEATURE.get().getDimensionInfo(level);
        } catch (Throwable ignored) {
            return;
        }
        if (dimInfo == null) {
            return;
        }

        ResourceKey<Level> dim = dimInfo.getType();
        if (dim == null) {
            return;
        }

        BlockPos origin = context.origin();
        if (origin == null || context.config() == null) {
            return;
        }
        TreeCapturePolicy.Decision decision = TreeCapturePolicy.decideAt(
            level.getLevel(), origin, biomesOPlentyTree ? LC2H_BOP_CAPTURE_RADIUS_BLOCKS : 0);
        if (decision == TreeCapturePolicy.Decision.REJECT) {
            cir.setReturnValue(false);
            return;
        }
        if (decision == TreeCapturePolicy.Decision.CAPTURE) {
            if (DeferredTreeQueue.isDeferredReplayEnabled()) {
                DeferredTreeCaptureContext.begin(origin, dim, level.getSeed(),
                    biomesOPlentyTree
                        ? DeferredTreeCaptureContext.CaptureSource.BOP_TREE
                        : DeferredTreeCaptureContext.CaptureSource.VANILLA_TREE);
            } else {
                cir.setReturnValue(false);
            }
            return;
        }

        // PASS_THROUGH intentionally leaves vanilla generation untouched.
    }

    @Inject(method = "place", at = @At("RETURN"), cancellable = true)
    private void lc2h$finalizeDeferredTreeCapture(
        FeaturePlaceContext<TreeConfiguration> context,
        CallbackInfoReturnable<Boolean> cir
    ) {
        DeferredTreeCaptureContext.CapturedTree captured = DeferredTreeCaptureContext.finish();
        if (captured == null) {
            return;
        }
        if (!DeferredTreeQueue.isDeferredReplayEnabled()) {
            cir.setReturnValue(false);
            return;
        }
        if (!cir.getReturnValue() || captured.blocks().isEmpty()) {
            return;
        }
        TreeCompatTracker.recordCapture(captured.source(), captured.blocks().size());
        DeferredTreeQueue.enqueue(
            captured.dim(),
            DeferredTreeQueue.PendingTree.captured(captured.origin(), captured.blocks(), captured.dim(), captured.seed(), captured.source())
        );
        cir.setReturnValue(true);
    }
}
