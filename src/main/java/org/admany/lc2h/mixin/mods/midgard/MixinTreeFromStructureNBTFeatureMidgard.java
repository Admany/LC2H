package org.admany.lc2h.mixin.mods.midgard;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.admany.lc2h.worldgen.lostcities.MidgardTreeCaptureHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;

@Pseudo
@Mixin(targets = "dev.corgitaco.ohthetreesyoullgrow.world.level.levelgen.feature.TreeFromStructureNBTFeature", remap = false)
public class MixinTreeFromStructureNBTFeatureMidgard {

    @Unique
    private static final ThreadLocal<Boolean> LC2H_CAPTURE_STARTED = ThreadLocal.withInitial(() -> false);

    @Inject(
        method = "m_142674_",
        at = @At("HEAD"),
        cancellable = true,
        require = 0
    )
    private void lc2h$beginMidgardStructureCapture(FeaturePlaceContext<?> context,
                                                   CallbackInfoReturnable<Boolean> cir) {
        org.admany.lc2h.worldgen.lostcities.TreeCapturePolicy.Decision decision =
            MidgardTreeCaptureHooks.decisionForFeaturePlacement(context);
        if (decision == org.admany.lc2h.worldgen.lostcities.TreeCapturePolicy.Decision.REJECT) {
            LC2H_CAPTURE_STARTED.set(false);
            cir.setReturnValue(false);
            return;
        }
        LC2H_CAPTURE_STARTED.set(MidgardTreeCaptureHooks.beginFeaturePlacement(context, "midgard.structure.place", decision));
    }

    @Inject(
        method = "m_142674_",
        at = @At("RETURN"),
        require = 0
    )
    private void lc2h$finishMidgardStructureCapture(FeaturePlaceContext<?> context,
                                                    CallbackInfoReturnable<Boolean> cir) {
        boolean started = LC2H_CAPTURE_STARTED.get();
        LC2H_CAPTURE_STARTED.remove();
        MidgardTreeCaptureHooks.finishFeaturePlacement(started, cir.getReturnValue());
    }

    @Inject(method = "placeKnownBlockPositions", at = @At("HEAD"), require = 0)
    private static void lc2h$observeMidgardStructureTrunkPostPass(Map<BlockPos, BlockState> trunkPositions,
                                                                  WorldGenLevel level,
                                                                  CallbackInfo ci) {
        MidgardTreeCaptureHooks.observeStructurePostPass(trunkPositions, level);
    }
}
