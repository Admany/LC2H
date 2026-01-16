package org.admany.lc2h.mixin.lostcities.worldgen.feature;

import mcjty.lostcities.worldgen.LostCityTerrainFeature;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@SuppressWarnings("target")
@Mixin(value = LostCityTerrainFeature.class, remap = false)
public class MixinLostCityTerrainFeatureTallBlocks {

            @Redirect(
                method = "lambda$generatePart$6(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)V",
                at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/WorldGenLevel;m_7731_(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z"),
                remap = false,
                require = 0
            )
    private boolean fixTallBlockPlacement(WorldGenLevel world, BlockPos pos, BlockState state, int flags) {
        if (state.getBlock() instanceof DoublePlantBlock && state.getValue(DoublePlantBlock.HALF) == DoubleBlockHalf.LOWER) {
            BlockPos upperPos = pos.above();
            BlockState upperState = state.setValue(DoublePlantBlock.HALF, DoubleBlockHalf.UPPER);

            if (world.isEmptyBlock(upperPos) || world.getBlockState(upperPos).canBeReplaced()) {
                world.setBlock(upperPos, upperState, flags);
            }
        }

        return world.setBlock(pos, state, flags);
    }
}
