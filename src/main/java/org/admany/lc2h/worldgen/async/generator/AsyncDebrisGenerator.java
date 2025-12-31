package org.admany.lc2h.worldgen.async.generator;

import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.IDimensionInfo;
import org.admany.lc2h.LC2H;
import org.admany.lc2h.util.cache.CacheTtl;
import org.admany.lc2h.worldgen.async.warmup.AsyncChunkWarmup;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class AsyncDebrisGenerator {

    private static final ConcurrentHashMap<ChunkCoord, Long> COMPUTATION_CACHE = new ConcurrentHashMap<>();
    private static final long COMPUTATION_CACHE_TTL_MS = Math.max(30_000L,
        Long.getLong("lc2h.debris.cacheTtlMs", TimeUnit.MINUTES.toMillis(20)));
    private static final int COMPUTATION_CACHE_PRUNE_SCAN = Math.max(128,
        Integer.getInteger("lc2h.debris.cachePruneScan", 512));
    private static final int COMPUTATION_CACHE_PRUNE_EVERY = Math.max(64,
        Integer.getInteger("lc2h.debris.cachePruneEvery", 128));
    private static final AtomicInteger COMPUTATION_CACHE_PRUNE_COUNTER = new AtomicInteger(0);

    public static final ConcurrentHashMap<ChunkCoord, float[]> GPU_DATA_CACHE = new ConcurrentHashMap<>();

    private AsyncDebrisGenerator() {
    }

    public static void preSchedule(IDimensionInfo provider, ChunkCoord coord) {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(coord, "coord");

        long now = System.currentTimeMillis();
        if (CacheTtl.markIfFresh(COMPUTATION_CACHE, coord, COMPUTATION_CACHE_TTL_MS, now)) {
            return;
        }
        maybePrune(now);

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

    public static void pruneExpiredEntries() {
        long now = System.currentTimeMillis();
        CacheTtl.pruneExpired(COMPUTATION_CACHE, COMPUTATION_CACHE_TTL_MS, COMPUTATION_CACHE_PRUNE_SCAN, now);
    }

    private static void maybePrune(long nowMs) {
        if (COMPUTATION_CACHE_TTL_MS <= 0L) {
            return;
        }
        int count = COMPUTATION_CACHE_PRUNE_COUNTER.incrementAndGet();
        if (count >= COMPUTATION_CACHE_PRUNE_EVERY) {
            COMPUTATION_CACHE_PRUNE_COUNTER.set(0);
            CacheTtl.pruneExpired(COMPUTATION_CACHE, COMPUTATION_CACHE_TTL_MS, COMPUTATION_CACHE_PRUNE_SCAN, nowMs);
        }
    }
}
