package org.admany.lc2h.worldgen.lostcities;

import mcjty.lostcities.api.LostChunkCharacteristics;
import mcjty.lostcities.config.LostCityProfile;
import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.IDimensionInfo;
import mcjty.lostcities.worldgen.lost.BuildingInfo;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class ChunkRoleProbe {

    private static final long TTL_MS = Math.max(5_000L,
        Long.getLong("lc2h.chunkRoleProbe.ttlMs", TimeUnit.MINUTES.toMillis(10)));
    private static final int PRUNE_EVERY = Math.max(128,
        Integer.getInteger("lc2h.chunkRoleProbe.pruneEvery", 512));

    private static final ConcurrentHashMap<ChunkCoord, Entry> CACHE = new ConcurrentHashMap<>();
    private static final AtomicInteger OP_COUNTER = new AtomicInteger(0);

    private ChunkRoleProbe() {
    }

    public record Probe(
        boolean isCity,
        boolean couldHaveBuilding,
        int cityLevel,
        boolean hasHighway,
        boolean hasRailway,
        boolean buildingTypeKnown
    ) {
        public boolean isUnsafe() {
            return isCity || hasHighway || hasRailway;
        }
    }

    private record Entry(Probe probe, LostChunkCharacteristics characteristics, long timestampMs) {
    }

    public static Probe get(IDimensionInfo dimInfo, ResourceKey<Level> dim, int chunkX, int chunkZ) {
        if (dimInfo == null || dim == null) {
            return new Probe(false, false, 0, false, false, false);
        }
        long now = System.currentTimeMillis();
        ChunkCoord coord = new ChunkCoord(dim, chunkX, chunkZ);
        Entry cached = CACHE.get(coord);
        if (cached != null && isFresh(cached, now)) {
            return cached.probe();
        }

        Entry computed = compute(dimInfo, coord, now);
        CACHE.put(coord, computed);
        maybePrune(now);
        return computed.probe();
    }

    public static LostChunkCharacteristics getCharacteristics(IDimensionInfo dimInfo,
                                                              ResourceKey<Level> dim,
                                                              int chunkX,
                                                              int chunkZ) {
        if (dimInfo == null || dim == null) {
            return null;
        }
        long now = System.currentTimeMillis();
        ChunkCoord coord = new ChunkCoord(dim, chunkX, chunkZ);
        Entry cached = CACHE.get(coord);
        if (cached != null && isFresh(cached, now)) {
            return cached.characteristics();
        }

        Entry computed = compute(dimInfo, coord, now);
        CACHE.put(coord, computed);
        maybePrune(now);
        return computed.characteristics();
    }

    public static boolean isCity(IDimensionInfo dimInfo, ResourceKey<Level> dim, int chunkX, int chunkZ) {
        return get(dimInfo, dim, chunkX, chunkZ).isCity();
    }

    public static boolean isUnsafe(IDimensionInfo dimInfo, ResourceKey<Level> dim, int chunkX, int chunkZ) {
        return get(dimInfo, dim, chunkX, chunkZ).isUnsafe();
    }

    public static boolean hasHighway(IDimensionInfo dimInfo, ResourceKey<Level> dim, int chunkX, int chunkZ) {
        return get(dimInfo, dim, chunkX, chunkZ).hasHighway();
    }

    public static boolean hasRailway(IDimensionInfo dimInfo, ResourceKey<Level> dim, int chunkX, int chunkZ) {
        return get(dimInfo, dim, chunkX, chunkZ).hasRailway();
    }

    public static void invalidate(ChunkCoord coord) {
        if (coord != null) {
            CACHE.remove(coord);
        }
    }

    public static void invalidateArea(ChunkCoord topLeft, int areaSize) {
        if (topLeft == null || areaSize <= 0) {
            return;
        }
        for (int dx = 0; dx < areaSize; dx++) {
            for (int dz = 0; dz < areaSize; dz++) {
                CACHE.remove(new ChunkCoord(topLeft.dimension(), topLeft.chunkX() + dx, topLeft.chunkZ() + dz));
            }
        }
    }

    public static void clear() {
        CACHE.clear();
    }

    private static Entry compute(IDimensionInfo dimInfo, ChunkCoord coord, long now) {
        LostCityProfile profile = null;
        try {
            profile = dimInfo.getProfile();
        } catch (Exception ignored) {
        }

        LostChunkCharacteristics characteristics = null;
        try {
            characteristics = BuildingInfo.getChunkCharacteristics(coord, dimInfo);
        } catch (Exception ignored) {
        }

        boolean isCity = false;
        boolean couldHaveBuilding = false;
        int cityLevel = 0;
        boolean buildingTypeKnown = false;
        if (characteristics != null) {
            isCity = characteristics.isCity;
            couldHaveBuilding = characteristics.couldHaveBuilding;
            cityLevel = characteristics.cityLevel;
            buildingTypeKnown = characteristics.buildingType != null || characteristics.buildingTypeId != null;
        } else {
            try {
                isCity = BuildingInfo.isCityRaw(coord, dimInfo, profile);
                if (isCity) {
                    cityLevel = BuildingInfo.getCityLevel(coord, dimInfo);
                }
            } catch (Exception ignored) {
            }
        }

        boolean hasHighway = false;
        boolean hasRailway = false;
        try {
            if (profile != null) {
                hasHighway = BuildingInfo.hasHighway(coord, dimInfo, profile);
                hasRailway = BuildingInfo.hasRailway(coord, dimInfo, profile);
            }
        } catch (Exception ignored) {
        }

        Probe probe = new Probe(isCity, couldHaveBuilding, cityLevel, hasHighway, hasRailway, buildingTypeKnown);
        return new Entry(probe, characteristics, now);
    }

    private static boolean isFresh(Entry entry, long now) {
        return entry != null && (now - entry.timestampMs()) <= TTL_MS;
    }

    private static void maybePrune(long now) {
        int local = OP_COUNTER.incrementAndGet();
        if (local < PRUNE_EVERY) {
            return;
        }
        OP_COUNTER.set(0);
        for (Map.Entry<ChunkCoord, Entry> entry : CACHE.entrySet()) {
            Entry value = entry.getValue();
            if (value != null && !isFresh(value, now)) {
                CACHE.remove(entry.getKey(), value);
            }
        }
    }
}
