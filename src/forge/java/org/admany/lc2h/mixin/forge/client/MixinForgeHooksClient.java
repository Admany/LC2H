package org.admany.lc2h.mixin.forge.client;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.admany.lc2h.config.ConfigManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@OnlyIn(Dist.CLIENT)
@Mixin(value = net.minecraftforge.client.ForgeHooksClient.class, remap = false)
public abstract class MixinForgeHooksClient {

    @Inject(method = "createWorldConfirmationScreen", at = @At("HEAD"), cancellable = true)
    private static void lc2h$skipExperimentalConfirmation(Runnable doConfirmedWorldLoad, CallbackInfo ci) {
        if (!ConfigManager.HIDE_EXPERIMENTAL_WARNING) {
            return;
        }
        if (doConfirmedWorldLoad != null) {
            doConfirmedWorldLoad.run();
        }
        ci.cancel();
    }
}
