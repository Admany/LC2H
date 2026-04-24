package org.admany.lc2h.worldgen.lostcities;

import mcjty.lostcities.api.LostChunkCharacteristics;
import mcjty.lostcities.varia.ChunkCoord;
import net.minecraft.resources.ResourceLocation;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class BuildingInfoSnapshotStore {
    private static final long TTL_MS = Math.max(5_000L,
        Long.getLong("lc2h.buildingInfoSnapshot.ttlMs", TimeUnit.MINUTES.toMillis(10)));
    private static final int PRUNE_EVERY = Math.max(128,
        Integer.getInteger("lc2h.buildingInfoSnapshot.pruneEvery", 512));

    private static final ConcurrentHashMap<ChunkCoord, Snapshot> SNAPSHOTS = new ConcurrentHashMap<>();
    private static final AtomicInteger OP_COUNTER = new AtomicInteger();

    private BuildingInfoSnapshotStore() {
    }

    public record Snapshot(
        boolean isCity,
        boolean couldHaveBuilding,
        int cityLevel,
        boolean buildingTypeKnown,
        ResourceLocation buildingTypeId,
        ResourceLocation multiBuildingId,
        boolean multiChunk,
        LostChunkCharacteristics characteristics,
        long timestampMs
    ) {
    }

    public static Snapshot get(ChunkCoord coord) {
        if (coord == null) {
            return null;
        }
        long now = System.currentTimeMillis();
        Snapshot snapshot = SNAPSHOTS.get(coord);
        if (snapshot == null || !isFresh(snapshot, now)) {
            if (snapshot != null) {
                SNAPSHOTS.remove(coord, snapshot);
            }
            return null;
        }
        maybePrune(now);
        return snapshot;
    }

    public static LostChunkCharacteristics getCharacteristics(ChunkCoord coord) {
        Snapshot snapshot = get(coord);
        return snapshot != null ? snapshot.characteristics() : null;
    }

    public static Snapshot remember(ChunkCoord coord, LostChunkCharacteristics characteristics) {
        if (coord == null || characteristics == null) {
            return null;
        }
        Snapshot snapshot = createSnapshot(characteristics, System.currentTimeMillis());
        SNAPSHOTS.put(coord, snapshot);
        maybePrune(snapshot.timestampMs());
        return snapshot;
    }

    public static void invalidate(ChunkCoord coord) {
        if (coord != null) {
            SNAPSHOTS.remove(coord);
        }
    }

    public static void invalidateArea(ChunkCoord topLeft, int areaSize) {
        if (topLeft == null || areaSize <= 0) {
            return;
        }
        for (int dx = 0; dx < areaSize; dx++) {
            for (int dz = 0; dz < areaSize; dz++) {
                SNAPSHOTS.remove(new ChunkCoord(topLeft.dimension(), topLeft.chunkX() + dx, topLeft.chunkZ() + dz));
            }
        }
    }

    public static void clear() {
        SNAPSHOTS.clear();
    }

    public static int size() {
        return SNAPSHOTS.size();
    }

    private static Snapshot createSnapshot(LostChunkCharacteristics characteristics, long now) {
        boolean buildingKnown = characteristics.buildingType != null || characteristics.buildingTypeId != null;
        boolean multiChunk = characteristics.multiPos != null && characteristics.multiPos.isMulti();
        return new Snapshot(
            characteristics.isCity,
            characteristics.couldHaveBuilding,
            characteristics.cityLevel,
            buildingKnown,
            characteristics.buildingTypeId,
            characteristics.multiBuildingId,
            multiChunk,
            characteristics,
            now
        );
    }

    private static boolean isFresh(Snapshot snapshot, long now) {
        return snapshot != null && (now - snapshot.timestampMs()) <= TTL_MS;
    }

    private static void maybePrune(long now) {
        int local = OP_COUNTER.incrementAndGet();
        if (local < PRUNE_EVERY) {
            return;
        }
        OP_COUNTER.set(0);
        for (Map.Entry<ChunkCoord, Snapshot> entry : SNAPSHOTS.entrySet()) {
            Snapshot snapshot = entry.getValue();
            if (snapshot != null && !isFresh(snapshot, now)) {
                SNAPSHOTS.remove(entry.getKey(), snapshot);
            }
        }
    }
}
