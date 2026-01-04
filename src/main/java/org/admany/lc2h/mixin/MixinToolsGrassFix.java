package org.admany.lc2h.mixin;

import mcjty.lostcities.varia.Tools;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Tools.class)
public class MixinToolsGrassFix {

    @Inject(method = "stringToState", at = @At("HEAD"), cancellable = true, remap = false)
    private static void fixGrassBlockName(String s, CallbackInfoReturnable<BlockState> cir) {
        if (s == null || s.isEmpty()) {
            return;
        }
        if ("tall_grass".equals(s) || "minecraft:tall_grass".equals(s) || "tallgrass".equals(s) || "minecraft:tallgrass".equals(s)) {
            cir.setReturnValue(Blocks.TALL_GRASS.defaultBlockState());
            return;
        }

        if ("fern".equals(s) || "minecraft:fern".equals(s)) {
            cir.setReturnValue(Blocks.FERN.defaultBlockState());
            return;
        }

        if ("large_fern".equals(s) || "minecraft:large_fern".equals(s)) {
            cir.setReturnValue(Blocks.LARGE_FERN.defaultBlockState());
            return;
        }

        if ("grass".equals(s) || "minecraft:grass".equals(s)) {
            cir.setReturnValue(Blocks.GRASS.defaultBlockState());
        }
    }
}
