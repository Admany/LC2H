package org.admany.lc2h.mixin.lostcities.worldgen;

import mcjty.lostcities.LostCities;
import mcjty.lostcities.worldgen.lost.MultiChunk;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(value = MultiChunk.class, remap = false)
public class MixinMultiChunkTimings {

    private static final boolean ENABLE_INFO_TIMINGS = Boolean.getBoolean("lc2h.lostcities.multichunk_timings");

    @Redirect(
            method = "calculateBuildings",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/apache/logging/log4j/Logger;info(Ljava/lang/String;[Ljava/lang/Object;)V"
            ),
            require = 0
    )
    private void lc2h$redirectMultiChunkTimingInfo(Logger logger, String message, Object[] params) {
        if (ENABLE_INFO_TIMINGS) {
            logger.info(message, params);
            return;
        }

        if (logger.isDebugEnabled() || LostCities.getLogger().isDebugEnabled()) {
            logger.debug(message, params);
        }
    }

    @Redirect(
            method = "calculateBuildings",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/apache/logging/log4j/Logger;debug(Ljava/lang/String;[Ljava/lang/Object;)V"
            ),
            require = 0
    )
    private void lc2h$redirectMultiChunkTimingDebug(Logger logger, String message, Object[] params) {
        logger.debug(message, params);
    }
}
