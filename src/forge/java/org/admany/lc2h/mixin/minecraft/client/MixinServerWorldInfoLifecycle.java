package org.admany.lc2h.mixin.minecraft.client;

import com.mojang.serialization.Lifecycle;
import net.minecraft.world.level.storage.PrimaryLevelData;
import org.admany.lc2h.config.ConfigManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PrimaryLevelData.class)
public class MixinServerWorldInfoLifecycle {
    @Inject(method = "m_5754_", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private void lc2h$forceStableLifecycle(CallbackInfoReturnable<Lifecycle> cir) {
        if (ConfigManager.HIDE_EXPERIMENTAL_WARNING) {
            cir.setReturnValue(Lifecycle.stable());
        }
    }
}
