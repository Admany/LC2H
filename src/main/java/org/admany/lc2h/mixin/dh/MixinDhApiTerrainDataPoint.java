package org.admany.lc2h.mixin.dh;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.api.objects.data.DhApiTerrainDataPoint", remap = false)
public abstract class MixinDhApiTerrainDataPoint {

    @ModifyArgs(
        method = "create",
        at = @At(
            value = "INVOKE",
            target = "Lcom/seibel/distanthorizons/api/objects/data/DhApiTerrainDataPoint;<init>(BIIIILcom/seibel/distanthorizons/api/interfaces/block/IDhApiBlockStateWrapper;Lcom/seibel/distanthorizons/api/interfaces/block/IDhApiBiomeWrapper;)V"
        )
    )
    private static void lc2h$clampTerrainY(Args args) {
        int bottom = clampY(args.get(3));
        int top = clampY(args.get(4));
        if (top < bottom) {
            int swap = top;
            top = bottom;
            bottom = swap;
        }
        args.set(3, bottom);
        args.set(4, top);
    }

    private static int clampY(Object value) {
        int y = value instanceof Number ? ((Number) value).intValue() : 0;
        if (y < 0) {
            return 0;
        }
        if (y > 4095) {
            return 4095;
        }
        return y;
    }
}
