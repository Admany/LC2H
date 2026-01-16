package org.admany.lc2h.mixin.minecraft.safety;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Suppresses the noisy "Hanging entity at invalid position" error log by
 * handling invalid hanging entities early and silently discarding them.
 */
@Mixin(HangingEntity.class)
public abstract class MixinHangingEntityInvalidPos extends Entity {

    protected MixinHangingEntityInvalidPos(EntityType<?> entityType, Level level) {
        super(entityType, level);
    }

    @Shadow
    public abstract boolean survives();

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true, require = 0)
    private void lc2h$skipInvalidHangingEntityLog(CallbackInfo ci) {
        handleInvalidHangingEntity(ci);
    }

    @Inject(method = "m_8119_", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private void lc2h$skipInvalidHangingEntityLogSrg(CallbackInfo ci) {
        handleInvalidHangingEntity(ci);
    }

    private void handleInvalidHangingEntity(CallbackInfo ci) {
        Level level = this.level();
        if (level != null && !level.isClientSide && !this.survives()) {
            // Vanilla would log an error and then discard; we keep the discard
            // to clean up bad entities but suppress the error log.
            this.discard();
            ci.cancel();
        }
    }
}
