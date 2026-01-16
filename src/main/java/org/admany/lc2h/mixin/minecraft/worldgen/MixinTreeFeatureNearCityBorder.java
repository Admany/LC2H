package org.admany.lc2h.mixin.minecraft.worldgen;

import mcjty.lostcities.setup.Registration;
import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.IDimensionInfo;
import mcjty.lostcities.worldgen.lost.BuildingInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.TreeFeature;
import net.minecraft.world.level.levelgen.feature.configurations.TreeConfiguration;
import org.admany.lc2h.config.ConfigManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(TreeFeature.class)
public class MixinTreeFeatureNearCityBorder {

    @Inject(method = "place", at = @At("HEAD"), cancellable = true)
    private void lc2h$preventTreesNearCityBorder(FeaturePlaceContext<TreeConfiguration> context, CallbackInfoReturnable<Boolean> cir) {
        if (!ConfigManager.CITY_BLEND_ENABLED || !ConfigManager.CITY_BLEND_CLEAR_TREES) {
            return;
        }

        WorldGenLevel level = context.level();
        if (level == null) {
            return;
        }

        IDimensionInfo dimInfo;
        try {
            dimInfo = Registration.LOSTCITY_FEATURE.get().getDimensionInfo(level);
        } catch (Throwable ignored) {
            return;
        }
        if (dimInfo == null) {
            return;
        }

        BlockPos origin = context.origin();
        int x = origin.getX();
        int z = origin.getZ();

        ResourceKey<Level> dim = dimInfo.getType();
        int originChunkX = x >> 4;
        int originChunkZ = z >> 4;

        if (BuildingInfo.isCity(new ChunkCoord(dim, originChunkX, originChunkZ), dimInfo)) {
            return;
        }

        int maxDistance = Math.max(8, ConfigManager.CITY_BLEND_WIDTH);
        if (isNearCityChunk(dimInfo, dim, x, z, originChunkX, originChunkZ, maxDistance)) {
            cir.setReturnValue(false);
        }
    }

    private static boolean isNearCityChunk(IDimensionInfo dimInfo, ResourceKey<Level> dim, int worldX, int worldZ, int originChunkX, int originChunkZ, int maxDistanceBlocks) {
        int maxDistSq = maxDistanceBlocks * maxDistanceBlocks;
        int radiusChunks = ((maxDistanceBlocks + 15) / 16) + 1;

        for (int dcx = -radiusChunks; dcx <= radiusChunks; dcx++) {
            int cx = originChunkX + dcx;
            for (int dcz = -radiusChunks; dcz <= radiusChunks; dcz++) {
                int cz = originChunkZ + dcz;
                if (!BuildingInfo.isCity(new ChunkCoord(dim, cx, cz), dimInfo)) {
                    continue;
                }

                int minX = cx << 4;
                int maxX = minX + 15;
                int minZ = cz << 4;
                int maxZ = minZ + 15;

                int dx = 0;
                if (worldX < minX) dx = minX - worldX;
                else if (worldX > maxX) dx = worldX - maxX;

                int dz = 0;
                if (worldZ < minZ) dz = minZ - worldZ;
                else if (worldZ > maxZ) dz = worldZ - maxZ;

                int distSq = dx * dx + dz * dz;
                if (distSq <= maxDistSq) {
                    return true;
                }
            }
        }
        return false;
    }
}
