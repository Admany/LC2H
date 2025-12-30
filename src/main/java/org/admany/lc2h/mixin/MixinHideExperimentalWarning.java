package org.admany.lc2h.mixin;

import net.minecraft.client.gui.screens.worldselection.WorldOpenFlows;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import org.admany.lc2h.logging.config.ConfigManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@OnlyIn(Dist.CLIENT)
@Mixin(value = WorldOpenFlows.class, priority = 1001)
public class MixinHideExperimentalWarning {

    @ModifyVariable(
            method = "doLoadLevel",
            at = @At("HEAD"),
            argsOnly = true,
            ordinal = 1,
            require = 0
    )
    private boolean lc2h$skipExperimentalPrompt(boolean askConfirmation) {
        return ConfigManager.HIDE_EXPERIMENTAL_WARNING ? false : askConfirmation;
    }

    @ModifyVariable(
            method = "doLoadLevel",
            at = @At("HEAD"),
            argsOnly = true,
            ordinal = 2,
            require = 0
    )
    private boolean lc2h$markWarningConfirmed(boolean confirmed) {
        return ConfigManager.HIDE_EXPERIMENTAL_WARNING ? true : confirmed;
    }
}
