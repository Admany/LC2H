package org.admany.lc2h.worldgen;

import org.admany.lc2h.config.ConfigManager;

import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.lost.BuildingInfo;
import mcjty.lostcities.worldgen.ChunkHeightmap;
import net.minecraft.util.Mth;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.WorldGenLevel;

import java.util.Arrays;

public final class CityEdgeBlender {
    private CityEdgeBlender() {
    }

    private record Outside(int chunksToOutside, int distBlocks, int sampleWorldX, int sampleWorldZ) {
        boolean isPresent() {
            return chunksToOutside > 0;
        }
    }

    public static int blendedHeight(BuildingInfo info, ChunkHeightmap heightmap, int x, int z) {
        if (!ConfigManager.CITY_BLEND_ENABLED) {
            return legacyMinHeight(info, heightmap, x, z);
        }
        int width = Math.max(4, ConfigManager.CITY_BLEND_WIDTH);
        int maxDynamicWidth = Math.min(256, Math.max(width, width * 4));

        int maxScanChunks = Math.max(1, (maxDynamicWidth + 15) / 16);
        WorldGenLevel world = info.provider.getWorld();

        Outside west = findOutsideWest(info, world, maxScanChunks, x, z);
        Outside east = findOutsideEast(info, world, maxScanChunks, x, z);
        Outside north = findOutsideNorth(info, world, maxScanChunks, x, z);
        Outside south = findOutsideSouth(info, world, maxScanChunks, x, z);

        if (!(west.isPresent() || east.isPresent() || north.isPresent() || south.isPresent())) {
            return info.getCityGroundLevel();
        }

        int base = heightmap.getHeight();

        int[] samples = new int[8];
        int sampleCount = 0;

        if (west.isPresent()) {
            sampleCount = addSample(world, samples, sampleCount, west.sampleWorldX, west.sampleWorldZ);
            sampleCount = addSample(world, samples, sampleCount, west.sampleWorldX, west.sampleWorldZ - 1);
            sampleCount = addSample(world, samples, sampleCount, west.sampleWorldX, west.sampleWorldZ + 1);
        }
        if (east.isPresent()) {
            sampleCount = addSample(world, samples, sampleCount, east.sampleWorldX, east.sampleWorldZ);
            sampleCount = addSample(world, samples, sampleCount, east.sampleWorldX, east.sampleWorldZ - 1);
            sampleCount = addSample(world, samples, sampleCount, east.sampleWorldX, east.sampleWorldZ + 1);
        }
        if (north.isPresent()) {
            sampleCount = addSample(world, samples, sampleCount, north.sampleWorldX, north.sampleWorldZ);
        }
        if (south.isPresent()) {
            sampleCount = addSample(world, samples, sampleCount, south.sampleWorldX, south.sampleWorldZ);
        }

        double outside = estimateOutsideHeight(base, samples, sampleCount);

        int delta = (int) Math.round(Math.abs(info.getCityGroundLevel() - outside));
        int dynamicWidth = Math.min(maxDynamicWidth, Math.max(width, delta * 6));

        int seaLevel;
        if (world instanceof net.minecraft.world.level.Level level) {
            seaLevel = level.getSeaLevel();
        } else {
            @SuppressWarnings("deprecation")
            int deprecatedSea = world.getSeaLevel();
            seaLevel = deprecatedSea;
        }

        boolean shorelineTarget = outside <= (seaLevel + 1.0) && info.getCityGroundLevel() >= seaLevel + 3;
        if (shorelineTarget) {
            int shorelineWidth = (info.getCityGroundLevel() - seaLevel) * 8;
            dynamicWidth = Math.min(maxDynamicWidth, Math.max(dynamicWidth, shorelineWidth));
        }

        int dist = distanceToOutsideEdge(west, east, north, south);
        if (dist >= dynamicWidth) {
            return info.getCityGroundLevel();
        }

        double t = 1.0 - (dist / (double) dynamicWidth);
        double softness = ConfigManager.CITY_BLEND_SOFTNESS;
        double clamped = Mth.clamp(t, 0.0, 1.0);
        if (shorelineTarget) {
            t = 1.0 - Math.pow(1.0 - clamped, softness);
        } else {
            t = Math.pow(clamped, softness);
        }

        double blended = Mth.lerp(t, info.getCityGroundLevel(), outside);

        int variation = (x * 31 + z) % 3 - 1;
        blended += variation * 0.5;

        return (int) Math.round(blended);
    }

    private static int distanceToOutsideEdge(Outside west, Outside east, Outside north, Outside south) {
        int dist = Integer.MAX_VALUE;
        if (west.isPresent()) dist = Math.min(dist, west.distBlocks);
        if (east.isPresent()) dist = Math.min(dist, east.distBlocks);
        if (north.isPresent()) dist = Math.min(dist, north.distBlocks);
        if (south.isPresent()) dist = Math.min(dist, south.distBlocks);
        return dist == Integer.MAX_VALUE ? 15 : dist;
    }

    private static Outside findOutsideWest(BuildingInfo info, WorldGenLevel world, int maxScanChunks, int x, int z) {
        ChunkCoord c = info.coord;
        int baseX = c.chunkX() << 4;
        int baseZ = c.chunkZ() << 4;
        for (int d = 1; d <= maxScanChunks; d++) {
            ChunkCoord check = new ChunkCoord(c.dimension(), c.chunkX() - d, c.chunkZ());
            if (world != null && !world.hasChunk(check.chunkX(), check.chunkZ())) {
                break;
            }
            if (!BuildingInfo.isCity(check, info.provider)) {
                int distBlocks = x + ((d - 1) << 4);
                int sampleX = baseX - (d << 4) + 15;
                int sampleZ = baseZ + z;
                return new Outside(d, distBlocks, sampleX, sampleZ);
            }
        }
        return new Outside(0, Integer.MAX_VALUE, 0, 0);
    }

    private static Outside findOutsideEast(BuildingInfo info, WorldGenLevel world, int maxScanChunks, int x, int z) {
        ChunkCoord c = info.coord;
        int baseX = c.chunkX() << 4;
        int baseZ = c.chunkZ() << 4;
        for (int d = 1; d <= maxScanChunks; d++) {
            ChunkCoord check = new ChunkCoord(c.dimension(), c.chunkX() + d, c.chunkZ());
            if (world != null && !world.hasChunk(check.chunkX(), check.chunkZ())) {
                break;
            }
            if (!BuildingInfo.isCity(check, info.provider)) {
                int distBlocks = (15 - x) + ((d - 1) << 4);
                int sampleX = baseX + (d << 4);
                int sampleZ = baseZ + z;
                return new Outside(d, distBlocks, sampleX, sampleZ);
            }
        }
        return new Outside(0, Integer.MAX_VALUE, 0, 0);
    }

    private static Outside findOutsideNorth(BuildingInfo info, WorldGenLevel world, int maxScanChunks, int x, int z) {
        ChunkCoord c = info.coord;
        int baseX = c.chunkX() << 4;
        int baseZ = c.chunkZ() << 4;
        for (int d = 1; d <= maxScanChunks; d++) {
            ChunkCoord check = new ChunkCoord(c.dimension(), c.chunkX(), c.chunkZ() - d);
            if (world != null && !world.hasChunk(check.chunkX(), check.chunkZ())) {
                break;
            }
            if (!BuildingInfo.isCity(check, info.provider)) {
                int distBlocks = z + ((d - 1) << 4);
                int sampleX = baseX + x;
                int sampleZ = baseZ - (d << 4) + 15;
                return new Outside(d, distBlocks, sampleX, sampleZ);
            }
        }
        return new Outside(0, Integer.MAX_VALUE, 0, 0);
    }

    private static Outside findOutsideSouth(BuildingInfo info, WorldGenLevel world, int maxScanChunks, int x, int z) {
        ChunkCoord c = info.coord;
        int baseX = c.chunkX() << 4;
        int baseZ = c.chunkZ() << 4;
        for (int d = 1; d <= maxScanChunks; d++) {
            ChunkCoord check = new ChunkCoord(c.dimension(), c.chunkX(), c.chunkZ() + d);
            if (world != null && !world.hasChunk(check.chunkX(), check.chunkZ())) {
                break;
            }
            if (!BuildingInfo.isCity(check, info.provider)) {
                int distBlocks = (15 - z) + ((d - 1) << 4);
                int sampleX = baseX + x;
                int sampleZ = baseZ + (d << 4);
                return new Outside(d, distBlocks, sampleX, sampleZ);
            }
        }
        return new Outside(0, Integer.MAX_VALUE, 0, 0);
    }

    private static int legacyMinHeight(BuildingInfo info, ChunkHeightmap heightmap, int x, int z) {
        int height = heightmap.getHeight();
        var provider = info.provider;
        if (x == 0) {
            if (z == 0) return Math.min(height, provider.getHeightmap(info.coord.northWest()).getHeight());
            if (z == 15) return Math.min(height, provider.getHeightmap(info.coord.southWest()).getHeight());
            return Math.min(height, provider.getHeightmap(info.coord.west()).getHeight());
        } else if (x == 15) {
            if (z == 0) return Math.min(height, provider.getHeightmap(info.coord.northEast()).getHeight());
            if (z == 15) return Math.min(height, provider.getHeightmap(info.coord.southEast()).getHeight());
            return Math.min(height, provider.getHeightmap(info.coord.east()).getHeight());
        } else if (z == 0) {
            return Math.min(height, provider.getHeightmap(info.coord.north()).getHeight());
        } else if (z == 15) {
            return Math.min(height, provider.getHeightmap(info.coord.south()).getHeight());
        } else {
            return height;
        }
    }

    private static int sampleSurfaceHeight(WorldGenLevel world, int worldX, int worldZ) {
        if (world == null) {
            return Integer.MIN_VALUE;
        }
        int chunkX = worldX >> 4;
        int chunkZ = worldZ >> 4;
        if (!world.hasChunk(chunkX, chunkZ)) {
            return Integer.MIN_VALUE;
        }
        int surface = world.getHeight(Heightmap.Types.WORLD_SURFACE_WG, worldX, worldZ);
        if (surface <= world.getMinBuildHeight() + 1) {
            return surface;
        }
        BlockPos top = new BlockPos(worldX, surface - 1, worldZ);
        if (world.getFluidState(top).is(Fluids.WATER)) {
            int sea;
            if (world instanceof net.minecraft.world.level.Level level) {
                sea = level.getSeaLevel();
            } else {
                @SuppressWarnings("deprecation")
                int deprecatedSea = world.getSeaLevel();
                sea = deprecatedSea;
            }
            return sea;
        }
        return surface;
    }

    private static int addSample(WorldGenLevel world, int[] samples, int sampleCount, int worldX, int worldZ) {
        int s = sampleSurfaceHeight(world, worldX, worldZ);
        if (s != Integer.MIN_VALUE) {
            samples[sampleCount++] = s;
        }
        return sampleCount;
    }

    private static double estimateOutsideHeight(int base, int[] samples, int sampleCount) {
        if (sampleCount <= 0) {
            return base;
        }
        int[] effective = Arrays.copyOf(samples, sampleCount);
        Arrays.sort(effective);
        int median = effective[sampleCount / 2];
        int p25 = effective[sampleCount / 4];

        return (median * 0.85) + (p25 * 0.15);
    }
}
