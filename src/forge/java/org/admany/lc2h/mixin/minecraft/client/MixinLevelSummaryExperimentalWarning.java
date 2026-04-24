package org.admany.lc2h.mixin.minecraft.client;

import net.minecraft.world.level.storage.LevelSummary;
import org.admany.lc2h.config.ConfigManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = LevelSummary.class, priority = 1001)
public class MixinLevelSummaryExperimentalWarning {
    @Inject(method = "isLifecycleExperimental", at = @At("RETURN"), cancellable = true, require = 0, remap = false)
    private void lc2h$hideExperimentalWarning(CallbackInfoReturnable<Boolean> cir) {
        if (ConfigManager.HIDE_EXPERIMENTAL_WARNING) {
            cir.setReturnValue(false);
        }
    }
}
