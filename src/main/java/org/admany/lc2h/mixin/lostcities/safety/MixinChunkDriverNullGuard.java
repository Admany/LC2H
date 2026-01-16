package org.admany.lc2h.mixin.lostcities.safety;

import mcjty.lostcities.worldgen.ChunkDriver;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = ChunkDriver.class, remap = false)
public class MixinChunkDriverNullGuard {

    @Inject(method = "correct", at = @At("HEAD"), cancellable = true)
    private void lc2h$nullGuard(BlockState state, CallbackInfoReturnable<BlockState> cir) {
        if (state == null) {
            cir.setReturnValue(null);
        }
    }
}
