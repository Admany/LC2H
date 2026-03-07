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
import org.admany.lc2h.config.ConfigManager;
import org.admany.lc2h.worldgen.lostcities.DeferredTreeQueue;
import org.admany.lc2h.worldgen.lostcities.LostCityTreeSafety;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(TreeFeature.class)
public class MixinTreeFeatureNearCityBorder {

    @Inject(method = "place", at = @At("HEAD"), cancellable = true)
    private void lc2h$deferTreesNearCityBorder(
        FeaturePlaceContext<TreeConfiguration> context,
        CallbackInfoReturnable<Boolean> cir
    ) {
        DeferredTreeCaptureContext.clear();
        if (DeferredTreeQueue.isReplaying()) {
            return;
        }
        if (!ConfigManager.CITY_BLEND_TREE_SEAM_FIX
            && (!ConfigManager.CITY_BLEND_ENABLED || !ConfigManager.CITY_BLEND_CLEAR_TREES)) {
            return;
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
        int x = origin.getX();
        int z = origin.getZ();
        int originChunkX = x >> 4;
        int originChunkZ = z >> 4;

        if (ConfigManager.CITY_BLEND_TREE_SEAM_FIX) {
            if (LostCityTreeSafety.isUnsafeChunk(dimInfo, dim, originChunkX, originChunkZ)) {
                DeferredTreeCaptureContext.begin(origin, dim);
                return;
            }
            int seamRadius = getTreeSeamRadiusBlocks();
            if (LostCityTreeSafety.isNearUnsafeTransition(dimInfo, dim, x, z, seamRadius)) {
                DeferredTreeCaptureContext.begin(origin, dim);
                return;
            }
        }

        if (!ConfigManager.CITY_BLEND_ENABLED || !ConfigManager.CITY_BLEND_CLEAR_TREES) {
            return;
        }

        if (LostCityTreeSafety.isUnsafeChunk(dimInfo, dim, originChunkX, originChunkZ)) {
            return;
        }

        int maxDistance = Math.max(8, ConfigManager.CITY_BLEND_WIDTH);
        if (LostCityTreeSafety.isNearUnsafeChunk(dimInfo, dim, x, z, maxDistance)) {
            cir.setReturnValue(false);
        }
    }

    private static int getTreeSeamRadiusBlocks() {
        int buffer = Math.max(1, ConfigManager.CITY_BLEND_TREE_SEAM_BUFFER);
        float multiplier = Math.max(0.5f, ConfigManager.TREE_SEAM_RADIUS_MULTIPLIER);
        int radius = Math.round((8.0f + buffer) * multiplier);
        return Math.max(buffer, Math.min(48, radius));
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
        if (!cir.getReturnValue() || captured.blocks().isEmpty()) {
            return;
        }
        DeferredTreeQueue.enqueue(
            captured.dim(),
            DeferredTreeQueue.PendingTree.captured(captured.origin(), captured.blocks(), captured.dim())
        );
        cir.setReturnValue(true);
    }
}
