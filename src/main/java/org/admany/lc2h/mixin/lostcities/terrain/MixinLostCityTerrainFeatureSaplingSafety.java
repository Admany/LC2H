package org.admany.lc2h.mixin.lostcities.terrain;

import mcjty.lostcities.worldgen.LostCityTerrainFeature;
import mcjty.lostcities.worldgen.lost.BuildingInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.SaplingBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.admany.lc2h.config.ConfigManager;
import org.admany.lc2h.worldgen.lostcities.LostCityTreeSafety;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = LostCityTerrainFeature.class, remap = false)
public abstract class MixinLostCityTerrainFeatureSaplingSafety {

    @Shadow public BlockState air;

    @Inject(method = "handleTodo", at = @At("HEAD"), cancellable = true, remap = false)
    private void lc2h$vetoSaplingsThatWouldCrossUnsafeSeams(
        BuildingInfo info,
        int oy,
        WorldGenLevel world,
        int rx,
        int rz,
        int y,
        BlockState state,
        CallbackInfoReturnable<BlockState> cir
    ) {
        if (!ConfigManager.CITY_BLEND_TREE_SEAM_FIX) {
            return;
        }
        if (state == null || !(state.getBlock() instanceof SaplingBlock)) {
            return;
        }
        if (info == null || world == null) {
            return;
        }

        BlockPos pos;
        try {
            pos = info.getRelativePos(rx, oy + y, rz);
        } catch (Throwable ignored) {
            return;
        }
        if (pos == null) {
            return;
        }

        int buffer = Math.max(1, ConfigManager.CITY_BLEND_TREE_SEAM_BUFFER);
        if (LostCityTreeSafety.shouldBlockTreeAt(world, pos, buffer)) {
            cir.setReturnValue(air);
        }
    }
}
