package org.admany.lc2h.mixin.lostcities.terrain;

import mcjty.lostcities.worldgen.LostCityTerrainFeature;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.SaplingBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.admany.lc2h.worldgen.lostcities.LostCityTodoTreeGuard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = LostCityTerrainFeature.class, remap = false)
public class MixinLostCityTerrainFeatureTodoTreeSafety {

    @Inject(
        method = "lambda$handleTodo$10",
        at = @At("HEAD"),
        cancellable = true,
        require = 0,
        expect = 0,
        remap = false
    )
    private static void lc2h$guardLostCityTodo(BlockPos pos,
                                               BlockState state,
                                               SaplingBlock sapling,
                                               RandomSource random,
                                               ServerLevel level,
                                               CallbackInfo ci) {
        LostCityTodoTreeGuard.handleLostCityTodo(pos, state, sapling, random, level);
        ci.cancel();
    }
}
