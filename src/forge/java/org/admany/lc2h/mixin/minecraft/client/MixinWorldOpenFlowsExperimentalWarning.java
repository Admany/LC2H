package org.admany.lc2h.mixin.minecraft.client;

import com.mojang.serialization.Lifecycle;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldOpenFlows;
import org.admany.lc2h.config.ConfigManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(WorldOpenFlows.class)
public class MixinWorldOpenFlowsExperimentalWarning {
    @Inject(method = "m_269260_", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private static void lc2h$skipExperimentalConfirm(Minecraft minecraft,
                                                     CreateWorldScreen screen,
                                                     Lifecycle lifecycle,
                                                     Runnable onConfirm,
                                                     boolean skipWarnings,
                                                     CallbackInfo ci) {
        if (!ConfigManager.HIDE_EXPERIMENTAL_WARNING) {
            return;
        }
        if (!skipWarnings && lifecycle == Lifecycle.stable()) {
            return;
        }
        if (lifecycle == Lifecycle.experimental()) {
            onConfirm.run();
            ci.cancel();
        }
    }
}
