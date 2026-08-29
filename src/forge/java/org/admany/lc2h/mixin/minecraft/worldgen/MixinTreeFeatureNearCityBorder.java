package org.admany.lc2h.mixin.minecraft.worldgen;

import mcjty.lostcities.setup.Registration;
import mcjty.lostcities.worldgen.IDimensionInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
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
        int captureRadius = biomesOPlentyTree
            ? LC2H_BOP_CAPTURE_RADIUS_BLOCKS
            : lc2h$estimateTreeFootprint(context);
        TreeCapturePolicy.Decision decision = TreeCapturePolicy.decideAt(
            level.getLevel(), origin, captureRadius);
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

    /** Estimate the actual vanilla foliage footprint without consuming the feature's RNG. */
    @Unique
    private int lc2h$estimateTreeFootprint(FeaturePlaceContext<TreeConfiguration> context) {
        TreeConfiguration config = context.config();
        if (config == null || config.trunkPlacer == null || config.foliagePlacer == null) {
            return 6;
        }
        BlockPos origin = context.origin();
        long seed = context.level().getSeed()
            ^ ((long) origin.getX() * 341873128712L)
            ^ ((long) origin.getY() * 132897987541L)
            ^ ((long) origin.getZ() * 42317861L);
        try {
            RandomSource heightRandom = RandomSource.create(seed);
            int treeHeight = config.trunkPlacer.getTreeHeight(heightRandom);
            int foliageHeight = config.foliagePlacer.foliageHeight(
                RandomSource.create(seed ^ 0x9E3779B97F4A7C15L), treeHeight, config);
            int radius = 2;
            int samples = Math.max(1, Math.min(96, foliageHeight + 2));
            for (int layer = 0; layer < samples; layer++) {
                radius = Math.max(radius, config.foliagePlacer.foliageRadius(
                    RandomSource.create(seed + layer * 0x632BE59BD9B4E019L), layer));
            }
            // Use the configured foliage radius. Add two blocks for branches
            // and hanging leaves; TreeCapturePolicy applies the user multiplier.
            return Math.max(4, Math.min(64, radius + 2));
        } catch (Throwable ignored) {
            return 6;
        }
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
