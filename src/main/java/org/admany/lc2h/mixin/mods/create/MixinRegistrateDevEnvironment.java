package org.admany.lc2h.mixin.mods.create;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Keeps Create's bundled Registrate from treating the ForgeGradle MCP runtime
 * as a strict Registrate development environment. That mode turns unused
 * registration callbacks into fatal errors and makes Create unreliable in
 * LC2H's runtime validation lanes. Normal production runtimes already report
 * false here, so this only changes the development environment.
 */
@Pseudo
@Mixin(targets = "com.tterrag.registrate.AbstractRegistrate", remap = false)
public abstract class MixinRegistrateDevEnvironment {
    @Inject(method = "isDevEnvironment", at = @At("HEAD"), cancellable = true, remap = false)
    private static void lc2h$disableRegistrateStrictDevMode(CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(false);
    }
}
