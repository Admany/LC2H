package org.admany.lc2h.mixin.minecraft.worldgen;

import mcjty.lostcities.setup.Registration;
import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.IDimensionInfo;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.admany.lc2h.config.ConfigManager;
import org.admany.lc2h.worldgen.lostcities.ChunkRoleProbe;

@Mixin(StructureStart.class)
public class MixinStructureStartCityUndergroundGuard {
    @Unique
    private Boolean lc2h$intersectsCity;

    @Inject(method = "placeInChunk", at = @At("HEAD"), cancellable = true)
    private void lc2h$skipStructuresNearCityGround(WorldGenLevel level,
                                                   StructureManager structureManager,
                                                   ChunkGenerator generator,
                                                   RandomSource random,
                                                   BoundingBox box,
                                                   ChunkPos chunkPos,
                                                   CallbackInfo ci) {
        if (!ConfigManager.REJECT_STRUCTURES_IN_CITY_CHUNKS || level == null || chunkPos == null) {
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

        if (lc2h$intersectsCity == null) {
            StructureStart self = (StructureStart) (Object) this;
            BoundingBox structureBox;
            try {
                structureBox = self.getBoundingBox();
            } catch (Throwable ignored) {
                structureBox = box;
            }
            lc2h$intersectsCity = intersectsCity(dimInfo, structureBox == null ? box : structureBox);
        }
        if (Boolean.TRUE.equals(lc2h$intersectsCity)) {
            ci.cancel();
        }
    }

    @Unique
    private static boolean intersectsCity(IDimensionInfo dimInfo, BoundingBox box) {
        if (dimInfo == null || dimInfo.getType() == null || box == null) {
            return false;
        }
        int buffer = ConfigManager.CITY_STRUCTURE_REJECTION_BUFFER_CHUNKS;
        int minChunkX = (box.minX() >> 4) - buffer;
        int maxChunkX = (box.maxX() >> 4) + buffer;
        int minChunkZ = (box.minZ() >> 4) - buffer;
        int maxChunkZ = (box.maxZ() >> 4) + buffer;
        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                if (ChunkRoleProbe.isCity(dimInfo, dimInfo.getType(), cx, cz)) {
                    return true;
                }
            }
        }
        return false;
    }
}
