package org.admany.lc2h.mixin.lostcities.gui;

import mcjty.lostcities.gui.GuiLCConfig;
import net.minecraft.client.gui.GuiGraphics;
import org.admany.lc2h.worldgen.lostcities.LostCitiesGuiPreviewGuard;
import org.admany.lc2h.data.cache.BuildingInfoCacheRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Bounds the uncached railway/highway work performed by the upstream profile preview. */
@Mixin(value = GuiLCConfig.class, remap = false)
public abstract class MixinGuiLCConfigPreviewBudget {

    /* GuiLCConfig's inherited Screen override is m_88315_ in the shipped
       Lost Cities 7.5.x production jar. */
    @Inject(method = "m_88315_", at = @At("HEAD"))
    private void lc2h$beginPreviewFrame(GuiGraphics graphics,
                                        int mouseX,
                                        int mouseY,
                                        float partialTick,
                                        CallbackInfo ci) {
        LostCitiesGuiPreviewGuard.beginFrame();
    }

    @Inject(method = "m_88315_", at = @At("RETURN"))
    private void lc2h$endPreviewFrame(GuiGraphics graphics,
                                      int mouseX,
                                      int mouseY,
                                      float partialTick,
                                      CallbackInfo ci) {
        LostCitiesGuiPreviewGuard.endFrame();
    }

    @Inject(method = "refreshPreview", at = @At("HEAD"))
    private void lc2h$clearPreviewBudget(CallbackInfo ci) {
        LostCitiesGuiPreviewGuard.refreshPreview();
    }

    @Redirect(
        method = "refreshPreview",
        at = @At(
            value = "INVOKE",
            target = "Lmcjty/lostcities/worldgen/lost/BuildingInfo;cleanCache()V",
            remap = false
        )
    )
    private void lc2h$clearPreviewBuildingInfoOnly() {
        BuildingInfoCacheRegistry.clearPreview();
    }

    @Redirect(
        method = "refreshPreview",
        at = @At(
            value = "INVOKE",
            target = "Lmcjty/lostcities/worldgen/lost/City;cleanCache()V",
            remap = false
        )
    )
    private void lc2h$keepRuntimeCityCaches() {
    }
}
