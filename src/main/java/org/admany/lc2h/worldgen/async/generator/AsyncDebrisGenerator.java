package org.admany.lc2h.worldgen.async.generator;

import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.IDimensionInfo;
import org.admany.lc2h.LC2H;
import org.admany.lc2h.worldgen.async.warmup.AsyncChunkWarmup;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public final class AsyncDebrisGenerator {

    private static final ConcurrentHashMap<ChunkCoord, Boolean> COMPUTATION_CACHE = new ConcurrentHashMap<>();

    public static final ConcurrentHashMap<ChunkCoord, float[]> GPU_DATA_CACHE = new ConcurrentHashMap<>();

    private AsyncDebrisGenerator() {
    }

    public static void preSchedule(IDimensionInfo provider, ChunkCoord coord) {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(coord, "coord");

        if (COMPUTATION_CACHE.putIfAbsent(coord, Boolean.TRUE) != null) {
            return;
        }

        boolean debugLogging = AsyncChunkWarmup.isWarmupDebugLoggingEnabled();

        if (GPU_DATA_CACHE.containsKey(coord)) {
            if (debugLogging) {
                LC2H.LOGGER.debug("Used GPU data for debris generation in {}", coord);
            }
            return;
        }

        if (debugLogging) {
            LC2H.LOGGER.debug("Starting preSchedule for {}", coord);
        }
        long startTime = System.nanoTime();

        try {
            computeDebrisPlacements(coord, provider);
            long endTime = System.nanoTime();
            if (debugLogging) {
                LC2H.LOGGER.debug("Finished preSchedule for {} in {} ms", coord, (endTime - startTime) / 1_000_000);
            }
        } catch (Throwable t) {
            LC2H.LOGGER.error("Synchronous debris generation failed for {}: {}", coord, t.getMessage());
            throw t;
        }
    }

    private static void computeDebrisPlacements(ChunkCoord coord, IDimensionInfo provider) {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                if (shouldPlaceDebris(coord, x, z, provider)) {
                    String debrisType = determineDebrisType(coord, x, z, provider);
                    int debrisHeight = determineDebrisHeight(coord, x, z, provider);
                    cacheDebris(coord, x, z, debrisType, debrisHeight);
                }
            }
        }

        computeAddonDebris(coord, provider);
    }

    private static void computeAddonDebris(ChunkCoord coord, IDimensionInfo provider) {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                String addonDebris = determineAddonDebris(coord, x, z, provider);
                if (addonDebris != null) {
                    int height = determineAddonDebrisHeight(coord, x, z, provider);
                    cacheAddonDebris(coord, x, z, addonDebris, height);
                }
            }
        }
    }

    private static boolean shouldPlaceDebris(ChunkCoord coord, int x, int z, IDimensionInfo provider) {
        return (x + z) % 32 == 0;
    }

    private static String determineDebrisType(ChunkCoord coord, int x, int z, IDimensionInfo provider) {
        return "rubble";
    }

    private static int determineDebrisHeight(ChunkCoord coord, int x, int z, IDimensionInfo provider) {
        return 64;
    }

    private static String determineAddonDebris(ChunkCoord coord, int x, int z, IDimensionInfo provider) {
        return null;
    }

    private static int determineAddonDebrisHeight(ChunkCoord coord, int x, int z, IDimensionInfo provider) {
        return 64;
    }

    private static void cacheDebris(ChunkCoord coord, int x, int z, String type, int height) {
    }

    private static void cacheAddonDebris(ChunkCoord coord, int x, int z, String type, int height) {
    }
}
