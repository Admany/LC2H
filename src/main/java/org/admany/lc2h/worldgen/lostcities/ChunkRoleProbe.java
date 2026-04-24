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
    private static final Probe EMPTY_PROBE = new Probe(false, false, 0, false, false, false);

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

    public record RoleGrid(
        ResourceKey<Level> dim,
        int centerX,
        int centerZ,
        int radius,
        Probe[] probes
    ) {
        public Probe get(int chunkX, int chunkZ) {
            int diameter = radius * 2 + 1;
            int localX = chunkX - centerX + radius;
            int localZ = chunkZ - centerZ + radius;
            if (localX < 0 || localZ < 0 || localX >= diameter || localZ >= diameter) {
                return EMPTY_PROBE;
            }
            Probe probe = probes[localZ * diameter + localX];
            return probe == null ? EMPTY_PROBE : probe;
        }

        public boolean isCity(int chunkX, int chunkZ) {
            return get(chunkX, chunkZ).isCity();
        }

        public boolean isUnsafe(int chunkX, int chunkZ) {
            return get(chunkX, chunkZ).isUnsafe();
        }
    }

    private record Entry(Probe probe, LostChunkCharacteristics characteristics, long timestampMs, boolean routeKnown) {
    }

    public static Probe get(IDimensionInfo dimInfo, ResourceKey<Level> dim, int chunkX, int chunkZ) {
        if (dimInfo == null || dim == null) {
            return EMPTY_PROBE;
        }
        long now = System.currentTimeMillis();
        ChunkCoord coord = new ChunkCoord(dim, chunkX, chunkZ);
        Entry cached = CACHE.get(coord);
        if (cached != null && isFresh(cached, now)) {
            return cached.probe();
        }

        Entry snapshot = fromSnapshot(coord, now);
        if (snapshot != null) {
            CACHE.put(coord, snapshot);
            maybePrune(now);
            return snapshot.probe();
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

        Entry snapshot = fromSnapshot(coord, now);
        if (snapshot != null) {
            CACHE.put(coord, snapshot);
            maybePrune(now);
            return snapshot.characteristics();
        }

        Entry computed = compute(dimInfo, coord, now);
        CACHE.put(coord, computed);
        maybePrune(now);
        return computed.characteristics();
    }

    public static LostChunkCharacteristics peekCharacteristics(ChunkCoord coord) {
        if (coord == null) {
            return null;
        }
        Entry cached = CACHE.get(coord);
        long now = System.currentTimeMillis();
        if (cached != null && isFresh(cached, now)) {
            return cached.characteristics();
        }
        BuildingInfoSnapshotStore.Snapshot snapshot = BuildingInfoSnapshotStore.get(coord);
        return snapshot != null ? snapshot.characteristics() : null;
    }

    public static boolean isCity(IDimensionInfo dimInfo, ResourceKey<Level> dim, int chunkX, int chunkZ) {
        return get(dimInfo, dim, chunkX, chunkZ).isCity();
    }

    public static RoleGrid getGrid(IDimensionInfo dimInfo,
                                   ResourceKey<Level> dim,
                                   int centerX,
                                   int centerZ,
                                   int radius) {
        int clampedRadius = Math.max(0, Math.min(16, radius));
        int diameter = clampedRadius * 2 + 1;
        Probe[] probes = new Probe[diameter * diameter];
        int index = 0;
        for (int dz = -clampedRadius; dz <= clampedRadius; dz++) {
            for (int dx = -clampedRadius; dx <= clampedRadius; dx++) {
                probes[index++] = get(dimInfo, dim, centerX + dx, centerZ + dz);
            }
        }
        return new RoleGrid(dim, centerX, centerZ, clampedRadius, probes);
    }

    public static boolean isUnsafe(IDimensionInfo dimInfo, ResourceKey<Level> dim, int chunkX, int chunkZ) {
        return getRouteAware(dimInfo, dim, chunkX, chunkZ).isUnsafe();
    }

    public static boolean hasHighway(IDimensionInfo dimInfo, ResourceKey<Level> dim, int chunkX, int chunkZ) {
        return getRouteAware(dimInfo, dim, chunkX, chunkZ).hasHighway();
    }

    public static boolean hasRailway(IDimensionInfo dimInfo, ResourceKey<Level> dim, int chunkX, int chunkZ) {
        return getRouteAware(dimInfo, dim, chunkX, chunkZ).hasRailway();
    }

    public static void invalidate(ChunkCoord coord) {
        if (coord != null) {
            CACHE.remove(coord);
            BuildingInfoSnapshotStore.invalidate(coord);
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
        BuildingInfoSnapshotStore.invalidateArea(topLeft, areaSize);
    }

    public static void clear() {
        CACHE.clear();
        BuildingInfoSnapshotStore.clear();
    }

    public static void rememberCharacteristics(ChunkCoord coord, LostChunkCharacteristics characteristics) {
        if (coord == null || characteristics == null) {
            return;
        }
        BuildingInfoSnapshotStore.Snapshot snapshot = BuildingInfoSnapshotStore.remember(coord, characteristics);
        if (snapshot != null) {
            CACHE.put(coord, entryFromSnapshot(snapshot));
        }
    }

    private static Entry fromSnapshot(ChunkCoord coord, long now) {
        BuildingInfoSnapshotStore.Snapshot snapshot = BuildingInfoSnapshotStore.get(coord);
        if (snapshot == null) {
            return null;
        }
        return entryFromSnapshot(snapshot);
    }

    private static Entry entryFromSnapshot(BuildingInfoSnapshotStore.Snapshot snapshot) {
        Probe probe = new Probe(
            snapshot.isCity(),
            snapshot.couldHaveBuilding(),
            snapshot.cityLevel(),
            false,
            false,
            snapshot.buildingTypeKnown()
        );
        return new Entry(probe, snapshot.characteristics(), snapshot.timestampMs(), false);
    }

    private static Probe getRouteAware(IDimensionInfo dimInfo, ResourceKey<Level> dim, int chunkX, int chunkZ) {
        if (dimInfo == null || dim == null) {
            return EMPTY_PROBE;
        }
        long now = System.currentTimeMillis();
        ChunkCoord coord = new ChunkCoord(dim, chunkX, chunkZ);
        Entry cached = CACHE.get(coord);
        if (cached != null && isFresh(cached, now) && cached.routeKnown()) {
            return cached.probe();
        }

        Entry computed = compute(dimInfo, coord, now);
        CACHE.put(coord, computed);
        maybePrune(now);
        return computed.probe();
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
        return new Entry(probe, characteristics, now, true);
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
