package org.admany.lc2h.mixin.lostcities;

import mcjty.lostcities.worldgen.lost.BuildingInfo;
import mcjty.lostcities.worldgen.lost.cityassets.BuildingPart;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.admany.lc2h.logging.config.ConfigManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = BuildingPart.class, remap = false)
public abstract class MixinBuildingPartSliceCompat {

    @Shadow @Final private String[] slices;

    private int lc2h$sliceCount() {
        return slices != null ? slices.length : 0;
    }

    @Inject(method = "getPaletteChar", at = @At("HEAD"), cancellable = true)
    private void lc2h$getPaletteCharBounds(int x, int slice, int z, CallbackInfoReturnable<Character> cir) {
        if (!ConfigManager.ENABLE_LOSTCITIES_PART_SLICE_COMPAT) {
            return;
        }
        int count = lc2h$sliceCount();
        if (slice < 0 || slice >= count) {
            cir.setReturnValue(' ');
        }
    }

    @Inject(method = "getC", at = @At("HEAD"), cancellable = true)
    private void lc2h$getCBounds(int x, int slice, int z, CallbackInfoReturnable<Character> cir) {
        if (!ConfigManager.ENABLE_LOSTCITIES_PART_SLICE_COMPAT) {
            return;
        }
        int count = lc2h$sliceCount();
        if (slice < 0 || slice >= count) {
            cir.setReturnValue(' ');
        }
    }

    @Inject(method = "get", at = @At("HEAD"), cancellable = true)
    private void lc2h$getBounds(BuildingInfo info, int x, int slice, int z, CallbackInfoReturnable<BlockState> cir) {
        if (!ConfigManager.ENABLE_LOSTCITIES_PART_SLICE_COMPAT) {
            return;
        }
        int count = lc2h$sliceCount();
        if (slice < 0 || slice >= count) {
            BlockState air = null;
            if (info != null) {
                try {
                    air = info.getCompiledPalette().get(' ');
                } catch (Throwable ignored) {
                }
            }
            cir.setReturnValue(air != null ? air : Blocks.AIR.defaultBlockState());
        }
    }
}
