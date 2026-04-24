package org.admany.lc2h.mixin.lostcities.damage;

import mcjty.lostcities.worldgen.lost.DamageArea;
import mcjty.lostcities.worldgen.IDimensionInfo;
import mcjty.lostcities.worldgen.lost.cityassets.CompiledPalette;
import mcjty.lostcities.worldgen.lost.Explosion;
import net.minecraft.world.level.block.state.BlockState;
import org.admany.lc2h.config.ConfigManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.Collections;
import java.util.List;

@Mixin(value = DamageArea.class, remap = false)
public abstract class MixinDamageAreaDisable {

    @Inject(method = "hasExplosions", at = @At("HEAD"), cancellable = true, remap = false)
    private void lc2h$disableExplosionsWhenOff(CallbackInfoReturnable<Boolean> cir) {
        if (!ConfigManager.ENABLE_EXPLOSION_DEBRIS) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "getDamage", at = @At("HEAD"), cancellable = true, remap = false)
    private void lc2h$disableDamageWhenOff(int x, int y, int z, CallbackInfoReturnable<Float> cir) {
        if (!ConfigManager.ENABLE_EXPLOSION_DEBRIS) {
            cir.setReturnValue(0.0f);
        }
    }

    @Inject(method = "getDamageFactor", at = @At("HEAD"), cancellable = true, remap = false)
    private void lc2h$disableDamageFactorWhenOff(CallbackInfoReturnable<Float> cir) {
        if (!ConfigManager.ENABLE_EXPLOSION_DEBRIS) {
            cir.setReturnValue(0.0f);
        }
    }

    @Inject(method = "getExplosions", at = @At("HEAD"), cancellable = true, remap = false)
    private void lc2h$disableExplosionsListWhenOff(CallbackInfoReturnable<List<Explosion>> cir) {
        if (!ConfigManager.ENABLE_EXPLOSION_DEBRIS) {
            cir.setReturnValue(Collections.emptyList());
        }
    }

    @Inject(method = "damageBlock", at = @At("HEAD"), cancellable = true, remap = false)
    private void lc2h$disableDamageBlockWhenOff(BlockState state,
                                                IDimensionInfo provider,
                                                int y,
                                                float damage,
                                                CompiledPalette palette,
                                                BlockState liquidState,
                                                CallbackInfoReturnable<BlockState> cir) {
        if (!ConfigManager.ENABLE_EXPLOSION_DEBRIS) {
            cir.setReturnValue(state);
        }
    }
}
