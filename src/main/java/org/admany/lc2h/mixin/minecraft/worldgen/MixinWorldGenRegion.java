package org.admany.lc2h.mixin.minecraft.worldgen;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.block.state.BlockState;

import org.admany.lc2h.util.chunk.ChunkPostProcessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(WorldGenRegion.class)
public class MixinWorldGenRegion {

    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
            at = @At("RETURN"))
    private void onBlockSet(BlockPos pos, BlockState state, int flags, int recursionLeft, CallbackInfoReturnable<Boolean> cir) {
        try {
            if (cir.getReturnValue() && ChunkPostProcessor.isTracked(state.getBlock())) {
                WorldGenRegion region = (WorldGenRegion)(Object)this;
                ChunkPostProcessor.markForRemovalIfFloating(region, pos);
            }
        } catch (Throwable t) {
        }
    }
}
