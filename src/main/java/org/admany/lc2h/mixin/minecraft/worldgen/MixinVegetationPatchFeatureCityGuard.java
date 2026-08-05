package org.admany.lc2h.mixin.minecraft.worldgen;

import mcjty.lostcities.setup.Registration;
import mcjty.lostcities.worldgen.IDimensionInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.VegetationPatchFeature;
import net.minecraft.world.level.levelgen.feature.configurations.VegetationPatchConfiguration;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.admany.lc2h.worldgen.lostcities.ChunkRoleProbe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.function.Predicate;

@Mixin(VegetationPatchFeature.class)
public class MixinVegetationPatchFeatureCityGuard {
    private static final boolean LC2H_SKIP_MOSS_VEGETATION_PATCH_WRITES_IN_CITY_CHUNKS =
        Boolean.parseBoolean(System.getProperty("lc2h.skipMossVegetationPatchWritesInCityChunks", "false"));

    @Redirect(
        method = "placeGround",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/WorldGenLevel;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z"
        )
    )
    private boolean lc2h$skipMossGroundWriteInCityChunk(WorldGenLevel level,
                                                        BlockPos pos,
                                                        BlockState state,
                                                        int flags,
                                                        WorldGenLevel world,
                                                        VegetationPatchConfiguration config,
                                                        Predicate<BlockState> replaceable,
                                                        net.minecraft.util.RandomSource random,
                                                        BlockPos.MutableBlockPos mutablePos,
                                                        int depth) {
        if (LC2H_SKIP_MOSS_VEGETATION_PATCH_WRITES_IN_CITY_CHUNKS
            && state != null
            && state.is(Blocks.MOSS_BLOCK)
            && lc2h$isCityChunk(level, pos)) {
            return false;
        }
        return level.setBlock(pos, state, flags);
    }

    private static boolean lc2h$isCityChunk(WorldGenLevel level, BlockPos pos) {
        if (level == null || pos == null) {
            return false;
        }
        IDimensionInfo dimInfo;
        try {
            dimInfo = Registration.LOSTCITY_FEATURE.get().getDimensionInfo(level);
        } catch (Throwable ignored) {
            return false;
        }
        if (dimInfo == null) {
            return false;
        }
        ResourceKey<Level> dim = dimInfo.getType();
        if (dim == null && level.getLevel() != null) {
            dim = level.getLevel().dimension();
        }
        if (dim == null) {
            return false;
        }
        return ChunkRoleProbe.isCity(dimInfo, dim, pos.getX() >> 4, pos.getZ() >> 4);
    }
}
