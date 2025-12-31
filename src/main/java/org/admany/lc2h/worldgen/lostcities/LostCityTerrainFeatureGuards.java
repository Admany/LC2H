package org.admany.lc2h.worldgen.lostcities;

import mcjty.lostcities.varia.ChunkCoord;
import org.admany.lc2h.util.cache.CacheTtl;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class LostCityTerrainFeatureGuards {

    public static final long GENERATE_GUARD_MS = Long.getLong("lc2h.lostcities.generateGuardMs", 60_000L);
    public static final long GENERATE_CACHE_TTL_MS = Long.getLong(
        "lc2h.lostcities.generateCacheTtlMs",
        Math.max(GENERATE_GUARD_MS * 2, 5L * 60L * 1000L)
    );
    private static final long CLEANUP_INTERVAL_MS = Math.max(10_000L,
        Long.getLong("lc2h.lostcities.generateCacheCleanupMs", 30_000L));
    private static final int CLEANUP_SCAN = Math.max(128,
        Integer.getInteger("lc2h.lostcities.generateCacheCleanupScan", 512));
    private static final AtomicLong LAST_CLEANUP_MS = new AtomicLong(0L);
    public static final ConcurrentHashMap<ChunkCoord, Long> LAST_SUCCESSFUL_GENERATE_MS = new ConcurrentHashMap<>();
    public static final ConcurrentHashMap<ChunkCoord, Long> IN_FLIGHT_GENERATE_MS = new ConcurrentHashMap<>();
    public static final ConcurrentHashMap<ChunkCoord, Long> GENERATED_CHUNKS = new ConcurrentHashMap<>();
    public static final boolean TRACE_GENERATE = Boolean.parseBoolean(System.getProperty("lc2h.lostcities.traceGenerate", "true"));

    private LostCityTerrainFeatureGuards() {
    }

    public static boolean isGeneratedRecently(ChunkCoord coord, long nowMs) {
        pruneExpired(nowMs);
        return CacheTtl.isFresh(GENERATED_CHUNKS, coord, GENERATE_CACHE_TTL_MS, nowMs);
    }

    public static Long getLastSuccess(ChunkCoord coord, long nowMs) {
        pruneExpired(nowMs);
        Long last = LAST_SUCCESSFUL_GENERATE_MS.get(coord);
        if (last == null) {
            return null;
        }
        if ((nowMs - last) > GENERATE_CACHE_TTL_MS) {
            LAST_SUCCESSFUL_GENERATE_MS.remove(coord, last);
            return null;
        }
        return last;
    }

    public static void markGenerated(ChunkCoord coord, long nowMs) {
        if (coord == null) {
            return;
        }
        GENERATED_CHUNKS.put(coord, nowMs);
        LAST_SUCCESSFUL_GENERATE_MS.put(coord, nowMs);
        pruneExpired(nowMs);
    }

    public static void pruneExpired(long nowMs) {
        long last = LAST_CLEANUP_MS.get();
        if ((nowMs - last) < CLEANUP_INTERVAL_MS) {
            return;
        }
        if (!LAST_CLEANUP_MS.compareAndSet(last, nowMs)) {
            return;
        }
        CacheTtl.pruneExpired(IN_FLIGHT_GENERATE_MS, GENERATE_GUARD_MS * 2, CLEANUP_SCAN, nowMs);
        CacheTtl.pruneExpired(LAST_SUCCESSFUL_GENERATE_MS, GENERATE_CACHE_TTL_MS, CLEANUP_SCAN, nowMs);
        CacheTtl.pruneExpired(GENERATED_CHUNKS, GENERATE_CACHE_TTL_MS, CLEANUP_SCAN, nowMs);
    }

    public static void reset() {
        LAST_SUCCESSFUL_GENERATE_MS.clear();
        IN_FLIGHT_GENERATE_MS.clear();
        GENERATED_CHUNKS.clear();
        LAST_CLEANUP_MS.set(0L);
    }
}
