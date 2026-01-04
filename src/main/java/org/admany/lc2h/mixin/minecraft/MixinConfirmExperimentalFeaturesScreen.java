package org.admany.lc2h.mixin.minecraft;

import it.unimi.dsi.fastutil.booleans.BooleanConsumer;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.ConfirmExperimentalFeaturesScreen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.admany.lc2h.logging.config.ConfigManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@OnlyIn(Dist.CLIENT)
@Mixin(ConfirmExperimentalFeaturesScreen.class)
public abstract class MixinConfirmExperimentalFeaturesScreen extends Screen {

    @Shadow @Final private BooleanConsumer callback;

    @Unique
    private boolean lc2h$autoConfirmed;

    protected MixinConfirmExperimentalFeaturesScreen(net.minecraft.network.chat.Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void lc2h$autoConfirm(CallbackInfo ci) {
        if (!ConfigManager.HIDE_EXPERIMENTAL_WARNING || lc2h$autoConfirmed) {
            return;
        }
        lc2h$autoConfirmed = true;
        callback.accept(true);
    }

    @Inject(method = "onClose", at = @At("HEAD"), cancellable = true)
    private void lc2h$forceAcceptOnClose(CallbackInfo ci) {
        if (!ConfigManager.HIDE_EXPERIMENTAL_WARNING) {
            return;
        }
        callback.accept(true);
        ci.cancel();
    }
}
