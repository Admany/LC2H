package org.admany.lc2h.mixin.lostcities.debris;

import mcjty.lostcities.worldgen.ChunkDriver;
import mcjty.lostcities.worldgen.LostCityTerrainFeature;
import mcjty.lostcities.worldgen.lost.BuildingInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.admany.lc2h.config.ConfigManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(value = LostCityTerrainFeature.class, remap = false)
public abstract class MixinLostCityRubble {

    @Redirect(method = "generateRuins", at = @At(value = "INVOKE", target = "Lmcjty/lostcities/worldgen/ChunkDriver;add(Lnet/minecraft/world/level/block/state/BlockState;)Lmcjty/lostcities/worldgen/ChunkDriver;"))
    private ChunkDriver lc2h_guardRubblePlacement(ChunkDriver driver, BlockState state, BuildingInfo info) {
        if (!ConfigManager.ENABLE_EXPLOSION_DEBRIS) {
            return driver;
        }
        BlockState below = driver.getBlockDown();
        if (!hasSolidSupport(below)) {
            return driver;
        }
        return driver.add(state);
    }

    private static boolean hasSolidSupport(BlockState below) {
        if (below == null || below.isAir()) {
            return false;
        }
        if (below.getFluidState() != null && !below.getFluidState().isEmpty()) {
            return false;
        }
        if (below.isAir()) return false;
        if (below.is(BlockTags.LEAVES)) return false;
        if (below.is(BlockTags.LOGS)) return false;
        if (below.is(BlockTags.FLOWERS)) return false;
        if (below.is(BlockTags.SAPLINGS)) return false;
        if (!below.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)) {
            return false;
        }
        return !below.canBeReplaced();
    }
}
