package org.admany.lc2h.worldgen.lostcities;

import mcjty.lostcities.varia.ChunkCoord;
import org.admany.lc2h.util.cache.CacheTtl;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class LostCityFeatureGuards {

    public static final long PLACE_GUARD_MS = Long.getLong("lc2h.lostcities.placeGuardMs", 60_000L);
    public static final long PLACE_CACHE_TTL_MS = Long.getLong(
        "lc2h.lostcities.placeCacheTtlMs",
        Math.max(PLACE_GUARD_MS * 2, 5L * 60L * 1000L)
    );
    private static final long CLEANUP_INTERVAL_MS = Math.max(10_000L,
        Long.getLong("lc2h.lostcities.placeCacheCleanupMs", 30_000L));
    private static final int CLEANUP_SCAN = Math.max(128,
        Integer.getInteger("lc2h.lostcities.placeCacheCleanupScan", 512));
    private static final AtomicLong LAST_CLEANUP_MS = new AtomicLong(0L);
    public static final boolean TRACE_PLACE = Boolean.parseBoolean(System.getProperty("lc2h.lostcities.tracePlace", "true"));

    public static final ConcurrentHashMap<ChunkCoord, Long> LAST_SUCCESSFUL_PLACE_MS = new ConcurrentHashMap<>();
    public static final ConcurrentHashMap<ChunkCoord, Long> IN_FLIGHT_PLACE_MS = new ConcurrentHashMap<>();
    public static final ConcurrentHashMap<ChunkCoord, Long> PLACED_CHUNKS = new ConcurrentHashMap<>();

    private LostCityFeatureGuards() {
    }

    public static boolean isPlacedRecently(ChunkCoord coord, long nowMs) {
        pruneExpired(nowMs);
        return CacheTtl.isFresh(PLACED_CHUNKS, coord, PLACE_CACHE_TTL_MS, nowMs);
    }

    public static Long getLastSuccess(ChunkCoord coord, long nowMs) {
        pruneExpired(nowMs);
        Long last = LAST_SUCCESSFUL_PLACE_MS.get(coord);
        if (last == null) {
            return null;
        }
        if ((nowMs - last) > PLACE_CACHE_TTL_MS) {
            LAST_SUCCESSFUL_PLACE_MS.remove(coord, last);
            return null;
        }
        return last;
    }

    public static void markPlaced(ChunkCoord coord, long nowMs) {
        if (coord == null) {
            return;
        }
        PLACED_CHUNKS.put(coord, nowMs);
        LAST_SUCCESSFUL_PLACE_MS.put(coord, nowMs);
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
        CacheTtl.pruneExpired(IN_FLIGHT_PLACE_MS, PLACE_GUARD_MS * 2, CLEANUP_SCAN, nowMs);
        CacheTtl.pruneExpired(LAST_SUCCESSFUL_PLACE_MS, PLACE_CACHE_TTL_MS, CLEANUP_SCAN, nowMs);
        CacheTtl.pruneExpired(PLACED_CHUNKS, PLACE_CACHE_TTL_MS, CLEANUP_SCAN, nowMs);
    }

    public static void reset() {
        LAST_SUCCESSFUL_PLACE_MS.clear();
        IN_FLIGHT_PLACE_MS.clear();
        PLACED_CHUNKS.clear();
        LAST_CLEANUP_MS.set(0L);
    }
}
