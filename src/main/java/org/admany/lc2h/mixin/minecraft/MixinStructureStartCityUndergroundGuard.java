package org.admany.lc2h.mixin.minecraft;

import mcjty.lostcities.setup.Registration;
import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.IDimensionInfo;
import mcjty.lostcities.worldgen.lost.BuildingInfo;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(StructureStart.class)
public class MixinStructureStartCityUndergroundGuard {

    private static final int CITY_UNDERGROUND_BUFFER = Math.max(0,
        Integer.getInteger("lc2h.cityUndergroundStructureBuffer", 20));

    @Inject(method = "placeInChunk", at = @At("HEAD"), cancellable = true)
    private void lc2h$skipStructuresNearCityGround(WorldGenLevel level,
                                                   StructureManager structureManager,
                                                   ChunkGenerator generator,
                                                   RandomSource random,
                                                   BoundingBox box,
                                                   ChunkPos chunkPos,
                                                   CallbackInfo ci) {
        if (CITY_UNDERGROUND_BUFFER <= 0 || level == null || box == null || chunkPos == null) {
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

        ChunkCoord coord = new ChunkCoord(dimInfo.getType(), chunkPos.x, chunkPos.z);
        if (!BuildingInfo.isCity(coord, dimInfo)) {
            return;
        }

        int ground;
        try {
            BuildingInfo info = BuildingInfo.getBuildingInfo(coord, dimInfo);
            if (info == null) {
                return;
            }
            ground = info.getCityGroundLevel();
        } catch (Throwable ignored) {
            return;
        }

        int cutoffY = ground - CITY_UNDERGROUND_BUFFER;
        if (box.maxY() >= cutoffY) {
            ci.cancel();
        }
    }
}
