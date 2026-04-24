package org.admany.lc2h.mixin.lostcities.highway;

import mcjty.lostcities.worldgen.gen.Highways;
import org.admany.lc2h.config.ConfigManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

@Mixin(value = Highways.class, remap = false)
public abstract class MixinHighwaySupportDepth {

    @ModifyConstant(method = "generateHighwayPart", constant = @Constant(intValue = 40), require = 0)
    private static int lc2h$extendHighwaySupportDepth(int original) {
        int configured = Math.max(40, Math.min(384, ConfigManager.HIGHWAY_SUPPORT_MAX_DEPTH));
        return Math.max(original, configured);
    }
}
