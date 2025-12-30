package org.admany.lc2h.worldgen.lostcities;

import mcjty.lostcities.varia.ChunkCoord;

import java.util.concurrent.ConcurrentHashMap;

public final class LostCityTerrainFeatureGuards {

    public static final long GENERATE_GUARD_MS = Long.getLong("lc2h.lostcities.generateGuardMs", 60_000L);
    public static final ConcurrentHashMap<ChunkCoord, Long> LAST_SUCCESSFUL_GENERATE_MS = new ConcurrentHashMap<>();
    public static final ConcurrentHashMap<ChunkCoord, Long> IN_FLIGHT_GENERATE_MS = new ConcurrentHashMap<>();
    public static final ConcurrentHashMap<ChunkCoord, Boolean> GENERATED_CHUNKS = new ConcurrentHashMap<>();
    public static final boolean TRACE_GENERATE = Boolean.parseBoolean(System.getProperty("lc2h.lostcities.traceGenerate", "true"));

    private LostCityTerrainFeatureGuards() {
    }

    public static void reset() {
        LAST_SUCCESSFUL_GENERATE_MS.clear();
        IN_FLIGHT_GENERATE_MS.clear();
        GENERATED_CHUNKS.clear();
    }
}

