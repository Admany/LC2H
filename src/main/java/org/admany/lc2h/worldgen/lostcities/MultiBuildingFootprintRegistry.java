package org.admany.lc2h.worldgen.lostcities;

import mcjty.lostcities.api.LostChunkCharacteristics;
import mcjty.lostcities.api.MultiPos;
import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.IDimensionInfo;
import mcjty.lostcities.worldgen.lost.MultiChunk;
import mcjty.lostcities.worldgen.lost.cityassets.AssetRegistries;
import mcjty.lostcities.worldgen.lost.cityassets.MultiBuilding;
import org.admany.lc2h.mixin.accessor.lostcities.MultiChunkAccessor;
import org.admany.lc2h.worldgen.async.snapshot.MultiChunkSnapshot;

import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Publishes immutable multibuilding cell ownership before BuildingInfo can
 * reuse an older single-building characteristic for the same chunk.
 */
public final class MultiBuildingFootprintRegistry {
    private static final ConcurrentHashMap<IDimensionInfo, ProviderState> PROVIDERS = new ConcurrentHashMap<>();
    private static final LongAdder AREAS_PUBLISHED = new LongAdder();
    private static final LongAdder CELLS_PUBLISHED = new LongAdder();
    private static final LongAdder STALE_CHARACTERISTICS = new LongAdder();
    private static final LongAdder CLAIMS_ENFORCED = new LongAdder();
    private static final LongAdder ASSET_MISSES = new LongAdder();

    private MultiBuildingFootprintRegistry() {
    }

    public static void register(IDimensionInfo provider, ChunkCoord multiCoord, MultiChunk multiChunk) {
        if (provider == null || multiChunk == null) {
            return;
        }
        MultiChunkAccessor accessor = (MultiChunkAccessor) multiChunk;
        ChunkCoord topLeft = accessor.lc2h$getTopLeft();
        int areaSize = accessor.lc2h$getAreaSize();
        if (topLeft == null || areaSize <= 0) {
            return;
        }

        ProviderState state = PROVIDERS.computeIfAbsent(provider, ignored -> new ProviderState());
        int published = 0;
        synchronized (state.publishLock) {
            removeArea(state, topLeft, areaSize);
            for (int dx = 0; dx < areaSize; dx++) {
                for (int dz = 0; dz < areaSize; dz++) {
                    ChunkCoord coord = new ChunkCoord(topLeft.dimension(), topLeft.chunkX() + dx, topLeft.chunkZ() + dz);
                    MultiChunkSnapshot.MultiChunkCell cell = MultiChunkSnapshot.describeCell(multiChunk, coord);
                    if (cell == null || cell.name() == null || cell.name().isBlank()) {
                        continue;
                    }
                    MultiBuilding building = AssetRegistries.MULTI_BUILDINGS.get(provider.getWorld(), cell.name());
                    if (building == null) {
                        ASSET_MISSES.increment();
                        continue;
                    }
                    ChunkCoord buildingTopLeft = new ChunkCoord(
                        coord.dimension(),
                        coord.chunkX() - cell.offsetX(),
                        coord.chunkZ() - cell.offsetZ());
                    state.claims.put(coord, new Claim(
                        building.getName(),
                        buildingTopLeft,
                        cell.offsetX(),
                        cell.offsetZ(),
                        building.getDimX(),
                        building.getDimZ()));
                    published++;
                }
            }
            state.revision++;
        }
        AREAS_PUBLISHED.increment();
        CELLS_PUBLISHED.add(published);
    }

    public static boolean matches(IDimensionInfo provider, ChunkCoord coord, LostChunkCharacteristics characteristics) {
        Claim claim = claim(provider, coord);
        return claim == null || claim.matches(characteristics);
    }

    public static boolean owns(IDimensionInfo provider, ChunkCoord coord) {
        return claim(provider, coord) != null;
    }

    public static boolean enforce(IDimensionInfo provider, ChunkCoord coord, LostChunkCharacteristics characteristics) {
        if (provider == null || coord == null || characteristics == null) {
            return false;
        }
        Claim claim = claim(provider, coord);
        if (claim == null || claim.matches(characteristics)) {
            return false;
        }
        STALE_CHARACTERISTICS.increment();
        MultiBuilding building = AssetRegistries.MULTI_BUILDINGS.get(provider.getWorld(), claim.name());
        if (building == null) {
            ASSET_MISSES.increment();
            return false;
        }
        characteristics.isCity = true;
        characteristics.multiBuilding = building;
        characteristics.multiBuildingId = building.getId();
        characteristics.multiPos = new MultiPos(
            claim.offsetX(), claim.offsetZ(), claim.width(), claim.height());
        CLAIMS_ENFORCED.increment();
        return true;
    }

    public static void invalidateArea(IDimensionInfo provider, ChunkCoord topLeft, int areaSize) {
        if (provider == null || topLeft == null || areaSize <= 0) {
            return;
        }
        ProviderState state = PROVIDERS.get(provider);
        if (state == null) {
            return;
        }
        synchronized (state.publishLock) {
            removeArea(state, topLeft, areaSize);
            state.revision++;
        }
    }

    public static void clearAll() {
        PROVIDERS.clear();
    }

    public static String diagnostics() {
        long liveCells = 0L;
        for (ProviderState state : PROVIDERS.values()) {
            liveCells += state.claims.size();
        }
        return String.format(Locale.ROOT,
            "providers=%d liveCells=%d areas=%d publishedCells=%d stale=%d enforced=%d assetMiss=%d",
            PROVIDERS.size(), liveCells, AREAS_PUBLISHED.sum(), CELLS_PUBLISHED.sum(),
            STALE_CHARACTERISTICS.sum(), CLAIMS_ENFORCED.sum(), ASSET_MISSES.sum());
    }

    static Claim claim(IDimensionInfo provider, ChunkCoord coord) {
        if (provider == null || coord == null) {
            return null;
        }
        ProviderState state = PROVIDERS.get(provider);
        return state == null ? null : state.claims.get(coord);
    }

    private static void removeArea(ProviderState state, ChunkCoord topLeft, int areaSize) {
        for (int dx = 0; dx < areaSize; dx++) {
            for (int dz = 0; dz < areaSize; dz++) {
                state.claims.remove(new ChunkCoord(
                    topLeft.dimension(), topLeft.chunkX() + dx, topLeft.chunkZ() + dz));
            }
        }
    }

    private static final class ProviderState {
        private final Object publishLock = new Object();
        private final ConcurrentHashMap<ChunkCoord, Claim> claims = new ConcurrentHashMap<>();
        private long revision;
    }

    public record Claim(String name,
                        ChunkCoord topLeft,
                        int offsetX,
                        int offsetZ,
                        int width,
                        int height) {
        public boolean matches(LostChunkCharacteristics characteristics) {
            if (characteristics == null || characteristics.multiPos == null || !characteristics.multiPos.isMulti()) {
                return false;
            }
            MultiPos pos = characteristics.multiPos;
            if (pos.x() != offsetX || pos.z() != offsetZ || pos.w() != width || pos.h() != height) {
                return false;
            }
            if (characteristics.multiBuilding == null) {
                return false;
            }
            return name.equals(characteristics.multiBuilding.getName());
        }
    }
}
