package org.admany.lc2h.mixin.lostcities.damage;

import mcjty.lostcities.worldgen.IDimensionInfo;
import mcjty.lostcities.worldgen.lost.DamageArea;
import mcjty.lostcities.worldgen.lost.Explosion;
import mcjty.lostcities.worldgen.lost.cityassets.CompiledPalette;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import org.admany.lc2h.config.ConfigManager;
import org.admany.lc2h.dev.diagnostics.CriticalMixinHookValidator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Collections;
import java.util.List;

@Mixin(value = DamageArea.class, remap = false)
public abstract class MixinDamageAreaDisable {

    @Inject(method = "hasExplosions()Z", at = @At("HEAD"), cancellable = true)
    private void lc2h$disableExplosionsWhenOff(CallbackInfoReturnable<Boolean> cir) {
        observe();
        if (!ConfigManager.ENABLE_EXPLOSION_DEBRIS) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "hasExplosions(I)Z", at = @At("HEAD"), cancellable = true)
    private void lc2h$disableSectionExplosionsWhenOff(int sectionY, CallbackInfoReturnable<Boolean> cir) {
        observe();
        if (!ConfigManager.ENABLE_EXPLOSION_DEBRIS) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "isCompletelyDestroyed(I)Z", at = @At("HEAD"), cancellable = true)
    private void lc2h$disableCompleteDestructionWhenOff(int y, CallbackInfoReturnable<Boolean> cir) {
        observe();
        if (!ConfigManager.ENABLE_EXPLOSION_DEBRIS) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "getDamage(III)F", at = @At("HEAD"), cancellable = true)
    private void lc2h$disableDamageWhenOff(int x, int y, int z, CallbackInfoReturnable<Float> cir) {
        observe();
        if (!ConfigManager.ENABLE_EXPLOSION_DEBRIS) {
            cir.setReturnValue(0.0f);
        }
    }

    @Inject(method = "getDamageFactor()F", at = @At("HEAD"), cancellable = true)
    private void lc2h$disableDamageFactorWhenOff(CallbackInfoReturnable<Float> cir) {
        observe();
        if (!ConfigManager.ENABLE_EXPLOSION_DEBRIS) {
            cir.setReturnValue(0.0f);
        }
    }

    @Inject(method = "getExplosions()Ljava/util/List;", at = @At("HEAD"), cancellable = true)
    private void lc2h$disableExplosionsListWhenOff(CallbackInfoReturnable<List<Explosion>> cir) {
        observe();
        if (!ConfigManager.ENABLE_EXPLOSION_DEBRIS) {
            cir.setReturnValue(Collections.emptyList());
        }
    }

    @Inject(
        method = "damageBlock(Lnet/minecraft/world/level/block/state/BlockState;Lmcjty/lostcities/worldgen/IDimensionInfo;Lnet/minecraft/util/RandomSource;IFLmcjty/lostcities/worldgen/lost/cityassets/CompiledPalette;Lnet/minecraft/world/level/block/state/BlockState;)Lnet/minecraft/world/level/block/state/BlockState;",
        at = @At("HEAD"),
        cancellable = true
    )
    private void lc2h$disableDamageBlockWhenOff(BlockState state,
                                                IDimensionInfo provider,
                                                RandomSource random,
                                                int y,
                                                float damage,
                                                CompiledPalette palette,
                                                BlockState liquidState,
                                                CallbackInfoReturnable<BlockState> cir) {
        observe();
        if (!ConfigManager.ENABLE_EXPLOSION_DEBRIS) {
            cir.setReturnValue(state);
        }
    }

    @Unique
    private static void observe() {
        CriticalMixinHookValidator.markObserved(CriticalMixinHookValidator.DAMAGE_AREA_DISABLE);
    }
}
