package org.admany.lc2h.mixin.minecraft.server;

import net.minecraft.world.entity.ai.village.poi.PoiManager;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(PoiManager.class)
public class MixinPoiManagerSuppressError {

    @Redirect(
            method = "*",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/slf4j/Logger;error(Ljava/lang/String;Ljava/lang/Object;)V",
                    remap = false
            ),
            require = 0,
            expect = 0
    )
    private void lc2h$downgradePoiErrors(Logger logger, String message, Object arg) {
        logger.debug(message, arg);
    }

    @Redirect(
            method = "*",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/slf4j/Logger;error(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;)V",
                    remap = false
            ),
            require = 0,
            expect = 0
    )
    private void lc2h$downgradePoiErrorsTwo(Logger logger, String message, Object arg1, Object arg2) {
        logger.debug(message, arg1, arg2);
    }
}
