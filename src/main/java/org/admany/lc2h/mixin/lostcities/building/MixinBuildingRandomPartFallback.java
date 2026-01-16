package org.admany.lc2h.mixin.lostcities.building;

import mcjty.lostcities.worldgen.lost.cityassets.Building;
import mcjty.lostcities.worldgen.lost.cityassets.ConditionContext;
import org.apache.commons.lang3.tuple.Pair;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.Random;
import java.util.function.Predicate;

@Mixin(value = Building.class, remap = false)
public class MixinBuildingRandomPartFallback {

    @Shadow
    @Final
    private List<Pair<Predicate<ConditionContext>, String>> parts;

    @Inject(method = "getRandomPart", at = @At("RETURN"), cancellable = true)
    private void lc2h$fallbackRandomPart(Random random, ConditionContext info, CallbackInfoReturnable<String> cir) {
        if (cir.getReturnValue() != null) {
            return;
        }
        if (parts == null || parts.isEmpty() || random == null) {
            return;
        }

        int idx = random.nextInt(parts.size());
        String part = parts.get(idx).getRight();
        if (part != null) {
            cir.setReturnValue(part);
        }
    }
}
