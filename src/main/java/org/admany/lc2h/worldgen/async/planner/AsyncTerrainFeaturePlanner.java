package org.admany.lc2h.worldgen.async.planner;

import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.IDimensionInfo;
import org.admany.lc2h.LC2H;
import org.admany.lc2h.worldgen.gpu.GPUMemoryManager;
import org.admany.lc2h.worldgen.async.warmup.AsyncChunkWarmup;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public final class AsyncTerrainFeaturePlanner {

    private static final ConcurrentHashMap<ChunkCoord, Boolean> COMPUTATION_CACHE = new ConcurrentHashMap<>();

    public static final ConcurrentHashMap<ChunkCoord, float[]> GPU_DATA_CACHE = new ConcurrentHashMap<>();

    private AsyncTerrainFeaturePlanner() {
    }

    public static void preSchedule(IDimensionInfo provider, ChunkCoord coord) {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(coord, "coord");

        if (COMPUTATION_CACHE.putIfAbsent(coord, Boolean.TRUE) != null) {
            return;
        }

        boolean debugLogging = AsyncChunkWarmup.isWarmupDebugLoggingEnabled();

        if (debugLogging) {
            LC2H.LOGGER.debug("Starting preSchedule for {}", coord);
        }
        long startTime = System.nanoTime();

        float[] gpuData = GPUMemoryManager.getGPUData(coord, GPU_DATA_CACHE);
        if (gpuData != null) {
            injectGPUData(coord, gpuData, provider);
            if (debugLogging) {
                LC2H.LOGGER.debug("Used GPU data for terrain features in {}", coord);
                long endTime = System.nanoTime();
                LC2H.LOGGER.debug("Finished preSchedule for {} in {} ms", coord, (endTime - startTime) / 1_000_000);
            }
            return;
        }

        PlannerBatchQueue.enqueue(provider, coord, PlannerTaskKind.TERRAIN_FEATURE,
            () -> runTerrainFeatureComputation(coord, provider, debugLogging, startTime));
    }

    private static void injectGPUData(ChunkCoord coord, float[] gpuData, IDimensionInfo provider) {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int blockIndex = x * 16 + z;
                int dataBaseIndex = blockIndex * 4;

                float gpuHeight = gpuData[dataBaseIndex];
                float gpuExcavation = gpuData[dataBaseIndex + 1];
                float gpuRuinType = gpuData[dataBaseIndex + 2];
                float gpuPaletteIndex = gpuData[dataBaseIndex + 3];

                int heightMod = (int) gpuHeight;
                cacheHeightModification(coord, x, z, 62 + heightMod);

                int excavationDepth = Math.max(0, (int) gpuExcavation);
                cacheExcavationData(coord, x, z, excavationDepth);

                String ruinType = gpuRuinType > 0.5f ? determineRuinType(coord, x, z, provider) : null;
                cacheRuinData(coord, x, z, ruinType);

                String materialPalette = determineMaterialPaletteFromGPU(gpuPaletteIndex);
                cacheMaterialPalette(coord, x, z, materialPalette);

                double noise = gpuHeight * 0.1;
                cacheNoiseValue(coord, x, z, noise);

                boolean connectedNorth = isConnectedGPU(coord, x, z, 0, -1, gpuData);
                boolean connectedSouth = isConnectedGPU(coord, x, z, 0, 1, gpuData);
                boolean connectedEast = isConnectedGPU(coord, x, z, 1, 0, gpuData);
                boolean connectedWest = isConnectedGPU(coord, x, z, -1, 0, gpuData);
                cacheConnectivityData(coord, x, z, connectedNorth, connectedSouth, connectedEast, connectedWest);
            }
        }
    }

    private static String determineMaterialPaletteFromGPU(float paletteIndex) {
        int index = (int) Math.abs(paletteIndex) % 4;
        switch (index) {
            case 0: return "stone_palette";
            case 1: return "brick_palette";
            case 2: return "wood_palette";
            case 3: return "mixed_palette";
            default: return "default_palette";
        }
    }

    private static boolean isConnectedGPU(ChunkCoord coord, int x, int z, int dx, int dz, float[] gpuData) {
        int nx = x + dx;
        int nz = z + dz;
        if (nx < 0 || nx >= 16 || nz < 0 || nz >= 16) {
            return false;
        }

        int currentIndex = (x * 16 + z) * 4;
        int neighborIndex = (nx * 16 + nz) * 4;

        if (currentIndex >= gpuData.length || neighborIndex >= gpuData.length) {
            return false;
        }

        float currentHeight = gpuData[currentIndex];
        float neighborHeight = gpuData[neighborIndex];

        return Math.abs(currentHeight - neighborHeight) < 2.0f;
    }

    private static void computeTerrainModifications(ChunkCoord coord, IDimensionInfo provider) {
        precomputeNoiseData(coord);

        precomputeHeightModifications(coord, provider);

        precomputeBuildingExcavations(coord, provider);

        precomputeRuinPlacements(coord, provider);

        precomputePaletteVariations(coord, provider);

        precomputeConnectivityData(coord, provider);

        warmTerrainCaches(coord, provider);
    }

    private static void precomputeNoiseData(ChunkCoord coord) {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                double baseNoise = Math.sin((coord.chunkX() * 16 + x) * 0.01) * Math.cos((coord.chunkZ() * 16 + z) * 0.01);
                double detailNoise = Math.sin((coord.chunkX() * 16 + x) * 0.05) * Math.cos((coord.chunkZ() * 16 + z) * 0.05) * 0.5;
                double finalNoise = baseNoise + detailNoise;
                cacheNoiseValue(coord, x, z, finalNoise);
            }
        }
    }

    private static void precomputeHeightModifications(ChunkCoord coord, IDimensionInfo provider) {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                boolean isStreet = isStreetPosition(coord, x, z, provider);
                boolean isBuildingBase = isBuildingBasePosition(coord, x, z, provider);

                int baseHeight = 62;
                int modifiedHeight = baseHeight;

                if (isStreet) {
                    modifiedHeight = baseHeight - 2;
                } else if (isBuildingBase) {
                    modifiedHeight = baseHeight + 1;
                }

                double noise = getCachedNoise(coord, x, z);
                modifiedHeight += (int)(noise * 3);

                cacheHeightModification(coord, x, z, modifiedHeight);
            }
        }
    }

    private static void precomputeBuildingExcavations(ChunkCoord coord, IDimensionInfo provider) {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                if (isBuildingPosition(coord, x, z, provider)) {
                    int excavationDepth = calculateExcavationDepth(coord, x, z, provider);
                    cacheExcavationData(coord, x, z, excavationDepth);
                }
            }
        }
    }

    private static void precomputeRuinPlacements(ChunkCoord coord, IDimensionInfo provider) {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                if (shouldPlaceRuin(coord, x, z, provider)) {
                    String ruinType = determineRuinType(coord, x, z, provider);
                    cacheRuinData(coord, x, z, ruinType);
                }
            }
        }
    }

    private static void precomputePaletteVariations(ChunkCoord coord, IDimensionInfo provider) {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                String materialPalette = determineMaterialPalette(coord, x, z, provider);
                cacheMaterialPalette(coord, x, z, materialPalette);
            }
        }
    }

    private static void precomputeConnectivityData(ChunkCoord coord, IDimensionInfo provider) {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                boolean connectedNorth = isConnected(coord, x, z, 0, -1, provider);
                boolean connectedSouth = isConnected(coord, x, z, 0, 1, provider);
                boolean connectedEast = isConnected(coord, x, z, 1, 0, provider);
                boolean connectedWest = isConnected(coord, x, z, -1, 0, provider);
                cacheConnectivityData(coord, x, z, connectedNorth, connectedSouth, connectedEast, connectedWest);
            }
        }
    }

    private static void warmTerrainCaches(ChunkCoord coord, IDimensionInfo provider) {
    }

    private static boolean isStreetPosition(ChunkCoord coord, int x, int z, IDimensionInfo provider) {
        return (x % 8 == 0) || (z % 8 == 0);
    }

    private static boolean isBuildingBasePosition(ChunkCoord coord, int x, int z, IDimensionInfo provider) {
        return !isStreetPosition(coord, x, z, provider) && (x % 4 == 1) && (z % 4 == 1);
    }

    private static boolean isBuildingPosition(ChunkCoord coord, int x, int z, IDimensionInfo provider) {
        return isBuildingBasePosition(coord, x, z, provider);
    }

    private static int calculateExcavationDepth(ChunkCoord coord, int x, int z, IDimensionInfo provider) {
        return 3;
    }

    private static boolean shouldPlaceRuin(ChunkCoord coord, int x, int z, IDimensionInfo provider) {
        return (x + z) % 16 == 0;
    }

    private static String determineRuinType(ChunkCoord coord, int x, int z, IDimensionInfo provider) {
        return "small_ruin";
    }

    private static String determineMaterialPalette(ChunkCoord coord, int x, int z, IDimensionInfo provider) {
        return "stone_palette";
    }

    private static boolean isConnected(ChunkCoord coord, int x, int z, int dx, int dz, IDimensionInfo provider) {
        int nx = x + dx;
        int nz = z + dz;
        if (nx < 0 || nx >= 16 || nz < 0 || nz >= 16) {
            return false;
        }
        return isStreetPosition(coord, x, z, provider) == isStreetPosition(coord, nx, nz, provider);
    }

    private static void cacheNoiseValue(ChunkCoord coord, int x, int z, double noise) {
    }

    private static double getCachedNoise(ChunkCoord coord, int x, int z) {
        return 0.0;
    }

    private static void cacheHeightModification(ChunkCoord coord, int x, int z, int height) {
    }

    private static void cacheExcavationData(ChunkCoord coord, int x, int z, int depth) {
    }

    private static void cacheRuinData(ChunkCoord coord, int x, int z, String type) {
    }

    private static void cacheMaterialPalette(ChunkCoord coord, int x, int z, String palette) {
    }

    private static void cacheConnectivityData(ChunkCoord coord, int x, int z, boolean n, boolean s, boolean e, boolean w) {
    }

    public static void flushPendingTerrainBatches() {
        PlannerBatchQueue.flushKind(PlannerTaskKind.TERRAIN_FEATURE);
    }

    public static void shutdown() {
        try {
            LC2H.LOGGER.info("AsyncTerrainFeaturePlanner: Shutting down");

            PlannerBatchQueue.flushKind(PlannerTaskKind.TERRAIN_FEATURE);

            COMPUTATION_CACHE.clear();
            GPU_DATA_CACHE.clear();

            LC2H.LOGGER.info("AsyncTerrainFeaturePlanner: Shutdown complete");
        } catch (Exception e) {
            LC2H.LOGGER.error("AsyncTerrainFeaturePlanner: Error during shutdown", e);
        }
    }

    private static void runTerrainFeatureComputation(ChunkCoord coord, IDimensionInfo provider, boolean debugLogging, long startTime) {
        try {
            float[] gpuData = GPUMemoryManager.getGPUData(coord, GPU_DATA_CACHE);
            if (gpuData != null) {
                injectGPUData(coord, gpuData, provider);
            } else {
                computeTerrainModifications(coord, provider);
            }

            if (debugLogging) {
                long endTime = System.nanoTime();
                LC2H.LOGGER.debug("Finished terrain feature compute for {} in {} ms", coord, (endTime - startTime) / 1_000_000);
            }
        } catch (Throwable t) {
            LC2H.LOGGER.error("Terrain feature computation failed for {}: {}", coord, t.getMessage());
        }
    }
}
