package org.admany.lc2h.data.cache;

import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.IDimensionInfo;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Owns the replacement BuildingInfo caches outside the transformed mixin class.
 * This gives multichunk publication a direct, reliable invalidation path.
 */
public final class BuildingInfoCacheRegistry {
    private static final ConcurrentMap<IDimensionInfo, BuildingInfoCacheScope> SCOPES = new ConcurrentHashMap<>();
    private static final BuildingInfoCacheScope FALLBACK_SCOPE = new BuildingInfoCacheScope();

    private BuildingInfoCacheRegistry() {
    }

    public static BuildingInfoCacheScope scope(IDimensionInfo provider) {
        return provider == null ? FALLBACK_SCOPE : SCOPES.computeIfAbsent(provider, ignored -> new BuildingInfoCacheScope());
    }

    public static boolean evictCityInfo(ChunkCoord coord) {
        return removeFromScopes(coord, CacheKind.CITY_INFO);
    }

    public static boolean evictBuildingInfo(ChunkCoord coord) {
        return removeFromScopes(coord, CacheKind.BUILDING_INFO);
    }

    public static boolean evictCityLevel(ChunkCoord coord) {
        return removeFromScopes(coord, CacheKind.CITY_LEVEL);
    }

    public static boolean evictCityRaw(ChunkCoord coord) {
        return removeFromScopes(coord, CacheKind.CITY_RAW);
    }

    public static boolean evictHighway(ChunkCoord coord) {
        return removeFromScopes(coord, CacheKind.HIGHWAY);
    }

    public static boolean evictMultiHeightStats(ChunkCoord coord) {
        return removeFromScopes(coord, CacheKind.MULTI_HEIGHT);
    }

    public static boolean evictMultiBoundary(Map.Entry<?, ?> boundary) {
        if (boundary == null) {
            return false;
        }
        boolean removed = FALLBACK_SCOPE.multiBoundary.remove(boundary) != null;
        for (BuildingInfoCacheScope scope : SCOPES.values()) {
            removed |= scope.multiBoundary.remove(boundary) != null;
        }
        return removed;
    }

    public static void invalidateArea(ChunkCoord topLeft, int areaSize, int borderRadius) {
        if (topLeft == null || areaSize <= 0) {
            return;
        }
        int border = Math.max(0, borderRadius);
        for (int dx = -border; dx < areaSize + border; dx++) {
            for (int dz = -border; dz < areaSize + border; dz++) {
                invalidate(new ChunkCoord(topLeft.dimension(), topLeft.chunkX() + dx, topLeft.chunkZ() + dz));
            }
        }
    }

    public static void invalidate(ChunkCoord coord) {
        if (coord == null) {
            return;
        }
        invalidateScope(FALLBACK_SCOPE, coord);
        for (BuildingInfoCacheScope scope : SCOPES.values()) {
            invalidateScope(scope, coord);
        }
    }

    public static void clear() {
        FALLBACK_SCOPE.clear();
        SCOPES.values().forEach(BuildingInfoCacheScope::clear);
        SCOPES.clear();
    }

    private static boolean removeFromScopes(ChunkCoord coord, CacheKind kind) {
        if (coord == null) {
            return false;
        }
        boolean removed = remove(FALLBACK_SCOPE, coord, kind);
        for (BuildingInfoCacheScope scope : SCOPES.values()) {
            removed |= remove(scope, coord, kind);
        }
        return removed;
    }

    private static boolean remove(BuildingInfoCacheScope scope, ChunkCoord coord, CacheKind kind) {
        return switch (kind) {
            case CITY_INFO -> scope.cityInfo.remove(coord) != null;
            case BUILDING_INFO -> scope.buildingInfo.remove(coord) != null;
            case CITY_LEVEL -> scope.cityLevel.remove(coord) != null;
            case CITY_RAW -> scope.cityRaw.remove(coord) != null;
            case HIGHWAY -> scope.highway.remove(coord) != null;
            case MULTI_HEIGHT -> scope.multiHeightStats.remove(coord) != null;
        };
    }

    private static void invalidateScope(BuildingInfoCacheScope scope, ChunkCoord coord) {
        scope.cityInfo.remove(coord);
        scope.nativeCharacteristics.remove(coord);
        scope.characteristicFlights.remove(coord);
        scope.buildingInfo.remove(coord);
        scope.buildingLocks.remove(coord);
        scope.cityLevel.remove(coord);
        scope.cityRaw.remove(coord);
        scope.highway.remove(coord);
        scope.multiHeightStats.remove(coord);
        scope.multiBoundary.keySet().removeIf(entry -> coord.equals(entry.getKey()) || coord.equals(entry.getValue()));
    }

    private enum CacheKind {
        CITY_INFO,
        BUILDING_INFO,
        CITY_LEVEL,
        CITY_RAW,
        HIGHWAY,
        MULTI_HEIGHT
    }
}
