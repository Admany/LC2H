package org.admany.lc2h.mixin.mods.dh;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.core.dataObjects.render.bufferBuilding.ColumnRenderBufferBuilder", remap = false)
public abstract class MixinDhRenderDataPointClamp {

    @Redirect(
        method = {"addRenderDataPointToBuilder", "addRenderDataPoint"},
        at = @At(
            value = "INVOKE",
            target = "Lcom/seibel/distanthorizons/core/util/RenderDataPointUtil;getYMin(J)S"
        ),
        require = 0
    )
    private static short lc2h$correctSignedYMin(long packed) {
        int yMin = (int) ((packed >> 8) & 4095L);
        int yMax = (int) ((packed >> 20) & 4095L);
        if (yMin > yMax && yMin >= 2048) {
            yMin -= 4096;
        }
        return (short) yMin;
    }
}
