package org.admany.lc2h.worldgen.lostcities;

import mcjty.lostcities.varia.ChunkCoord;

import java.util.concurrent.ConcurrentHashMap;

public final class LostCityFeatureGuards {

    public static final long PLACE_GUARD_MS = Long.getLong("lc2h.lostcities.placeGuardMs", 60_000L);
    public static final boolean TRACE_PLACE = Boolean.parseBoolean(System.getProperty("lc2h.lostcities.tracePlace", "true"));

    public static final ConcurrentHashMap<ChunkCoord, Long> LAST_SUCCESSFUL_PLACE_MS = new ConcurrentHashMap<>();
    public static final ConcurrentHashMap<ChunkCoord, Long> IN_FLIGHT_PLACE_MS = new ConcurrentHashMap<>();
    public static final ConcurrentHashMap<ChunkCoord, Boolean> PLACED_CHUNKS = new ConcurrentHashMap<>();

    private LostCityFeatureGuards() {
    }

    public static void reset() {
        LAST_SUCCESSFUL_PLACE_MS.clear();
        IN_FLIGHT_PLACE_MS.clear();
        PLACED_CHUNKS.clear();
    }
}

