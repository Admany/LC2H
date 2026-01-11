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
    private static final int CITY_BUFFER_RADIUS = Math.max(0,
        Integer.getInteger("lc2h.cityStructureBufferRadius", 1));

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

        CityContext context = resolveCityContext(dimInfo, chunkPos.x, chunkPos.z);
        if (context == null) {
            return;
        }
        int ground = context.groundLevel();
        if (ground <= 0) {
            return;
        }

        int cutoffY = ground - CITY_UNDERGROUND_BUFFER;
        if (box.maxY() >= cutoffY) {
            ci.cancel();
        }
    }

    private record CityContext(int groundLevel, boolean city) {
    }

    private static CityContext resolveCityContext(IDimensionInfo dimInfo, int chunkX, int chunkZ) {
        if (dimInfo == null) {
            return null;
        }
        ChunkCoord origin = new ChunkCoord(dimInfo.getType(), chunkX, chunkZ);
        if (BuildingInfo.isCity(origin, dimInfo)) {
            Integer ground = getGround(dimInfo, origin);
            if (ground != null) {
                return new CityContext(ground, true);
            }
            return new CityContext(0, true);
        }
        if (CITY_BUFFER_RADIUS <= 0) {
            return null;
        }
        int bestGround = Integer.MIN_VALUE;
        boolean found = false;
        for (int dx = -CITY_BUFFER_RADIUS; dx <= CITY_BUFFER_RADIUS; dx++) {
            for (int dz = -CITY_BUFFER_RADIUS; dz <= CITY_BUFFER_RADIUS; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                ChunkCoord check = new ChunkCoord(dimInfo.getType(), chunkX + dx, chunkZ + dz);
                if (!BuildingInfo.isCity(check, dimInfo)) {
                    continue;
                }
                Integer ground = getGround(dimInfo, check);
                if (ground != null) {
                    found = true;
                    if (ground > bestGround) {
                        bestGround = ground;
                    }
                }
            }
        }
        if (!found) {
            return null;
        }
        return new CityContext(bestGround, false);
    }

    private static Integer getGround(IDimensionInfo dimInfo, ChunkCoord coord) {
        try {
            BuildingInfo info = BuildingInfo.getBuildingInfo(coord, dimInfo);
            if (info == null) {
                return null;
            }
            return info.getCityGroundLevel();
        } catch (Throwable ignored) {
            return null;
        }
    }
}
