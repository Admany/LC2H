package org.admany.lc2h.worldgen.async.generator;

import org.admany.lc2h.LC2H;
import org.admany.lc2h.async.AsyncManager;
import org.admany.lc2h.async.Priority;
import org.admany.lc2h.data.cache.FeatureCache;
import org.admany.lc2h.frustum.ChunkPriorityManager;
import org.admany.lc2h.worldgen.async.warmup.AsyncChunkWarmup;

import java.lang.reflect.Field;

import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.IDimensionInfo;

public class AsyncBuildingGenerator {

    public static void generateBuildingAsync(Object candidate) {
        int chunkX = Integer.MIN_VALUE;
        int chunkZ = Integer.MIN_VALUE;

        Object buildingInfo = resolveBuildingInfo(candidate);
        if (buildingInfo == null) {
            return;
        }

        ChunkCoord coord = extractCoord(buildingInfo);
        if (coord != null) {
            chunkX = coord.chunkX();
            chunkZ = coord.chunkZ();
        }

        if (chunkX == Integer.MIN_VALUE || chunkZ == Integer.MIN_VALUE) {
            return;
        }

        String cacheKey = "building_" + chunkX + "," + chunkZ;
        Object cached = FeatureCache.get(cacheKey, true);
        if (cached != null) {
            LC2H.LOGGER.debug("Using cached building for " + cacheKey);
            return;
        }

        IDimensionInfo provider = extractProvider(buildingInfo);
        if (provider != null && coord != null) {
            AsyncChunkWarmup.preSchedule(provider, coord);
        }

        Priority priority = coord != null ? ChunkPriorityManager.getPriorityForChunk(coord) : Priority.LOW;
        final int finalChunkX = chunkX;
        final int finalChunkZ = chunkZ;

        AsyncManager.submitTask("building_gen", () -> {
            try {
                LC2H.LOGGER.debug("Starting async building generation for " + finalChunkX + "," + finalChunkZ);
                long start = System.nanoTime();
                for (int i = 0; i < 10000; i++) {
                    Math.sin(i * 0.01);
                    Math.cos(i * 0.01);
                }
                long end = System.nanoTime();
                LC2H.LOGGER.debug("Completed async building generation in " + (end - start) / 1_000_000 + "ms");
            } catch (Exception e) {
                LC2H.LOGGER.error("Error in async building generation: " + e.getMessage(), e);
            }
        }, buildingInfo, priority).thenAccept(result -> {
            try {
                FeatureCache.put(cacheKey, result, true);
                AsyncManager.syncToMain(() -> LC2H.LOGGER.debug("Building generation completed for " + cacheKey));
            } catch (Exception e) {
                LC2H.LOGGER.error("Error applying building result: " + e.getMessage(), e);
            }
        });
    }

    private static Object resolveBuildingInfo(Object candidate) {
        if (candidate == null) {
            return null;
        }

        try {
            Class<?> buildingClass = Class.forName("mcjty.lostcities.worldgen.lost.BuildingInfo");
            return buildingClass.isInstance(candidate) ? candidate : null;
        } catch (ClassNotFoundException e) {
            LC2H.LOGGER.error("Lost Cities BuildingInfo class unavailable", e);
            return null;
        }
    }

    private static ChunkCoord extractCoord(Object buildingInfo) {
        try {
            Field coordField = buildingInfo.getClass().getField("coord");
            coordField.setAccessible(true);
            Object value = coordField.get(buildingInfo);
            if (value instanceof ChunkCoord coord) {
                return coord;
            }
            return null;
        } catch (ReflectiveOperationException e) {
            LC2H.LOGGER.debug("Unable to reflect coord field for building info", e);
            return null;
        }
    }

    private static IDimensionInfo extractProvider(Object buildingInfo) {
        try {
            Field providerField = buildingInfo.getClass().getField("provider");
            providerField.setAccessible(true);
            Object value = providerField.get(buildingInfo);
            if (value instanceof IDimensionInfo info) {
                return info;
            }
        } catch (ReflectiveOperationException e) {
            LC2H.LOGGER.debug("Unable to reflect provider field for building info", e);
        }
        return null;
    }
}
