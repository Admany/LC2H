package org.admany.lc2h.mixin.minecraft.client;

import net.minecraft.world.level.levelgen.WorldDimensions;
import org.admany.lc2h.config.ConfigManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(WorldDimensions.class)
public class MixinWorldDimensionsExperimentalWarning {
    @Inject(method = "m_246739_", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private void lc2h$forceNonExperimental(CallbackInfoReturnable<Boolean> cir) {
        if (ConfigManager.HIDE_EXPERIMENTAL_WARNING) {
            cir.setReturnValue(false);
        }
    }
}
