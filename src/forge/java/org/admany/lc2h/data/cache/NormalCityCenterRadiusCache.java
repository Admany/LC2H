package org.admany.lc2h.data.cache;

import mcjty.lostcities.config.LostCityProfile;
import mcjty.lostcities.worldgen.IDimensionInfo;
import org.admany.lc2h.util.PackedCoordinateKey;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;

/**
 * Compact lifecycle cache for the deterministic normal-city centre test.
 * Zero means unknown and one means a tested non-centre, while positive radii
 * are stored as their raw float bits. A tile keeps the overwhelmingly common
 * negative result cheap without creating one map entry per chunk.
 */
public final class NormalCityCenterRadiusCache {

    private static final int TILE_SIDE = 32;
    private static final int MAX_TILES = Math.max(256,
        Integer.getInteger("lc2h.city.normalCenterCacheTiles", 4_096));

    private static final ConcurrentHashMap<IDimensionInfo,
        ConcurrentHashMap<LostCityProfile, ConcurrentHashMap<Long, Tile>>> TILES =
        new ConcurrentHashMap<>();
    private static final ConcurrentLinkedQueue<TileToken> ORDER = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger TILE_COUNT = new AtomicInteger();

    private NormalCityCenterRadiusCache() {
    }

    /** Returns NaN when this coordinate has not been classified yet. */
    public static float get(IDimensionInfo provider,
                            LostCityProfile profile,
                            int chunkX,
                            int chunkZ) {
        ConcurrentHashMap<LostCityProfile, ConcurrentHashMap<Long, Tile>> byProfile =
            TILES.get(provider);
        if (byProfile == null) {
            return Float.NaN;
        }
        ConcurrentHashMap<Long, Tile> tiles = byProfile.get(profile);
        if (tiles == null) {
            return Float.NaN;
        }
        Tile tile = tiles.get(tileKey(chunkX, chunkZ));
        if (tile == null) {
            return Float.NaN;
        }
        int encoded = tile.values.get(index(chunkX, chunkZ));
        if (encoded == 0) {
            return Float.NaN;
        }
        return encoded == 1 ? 0.0F : Float.intBitsToFloat(encoded);
    }

    public static float publish(IDimensionInfo provider,
                                LostCityProfile profile,
                                int chunkX,
                                int chunkZ,
                                float radius) {
        ConcurrentHashMap<LostCityProfile, ConcurrentHashMap<Long, Tile>> byProfile =
            TILES.computeIfAbsent(provider, ignored -> new ConcurrentHashMap<>());
        ConcurrentHashMap<Long, Tile> tiles =
            byProfile.computeIfAbsent(profile, ignored -> new ConcurrentHashMap<>());
        long tileKey = tileKey(chunkX, chunkZ);
        Tile tile = tiles.get(tileKey);
        if (tile == null) {
            Tile created = new Tile();
            Tile previous = tiles.putIfAbsent(tileKey, created);
            tile = previous == null ? created : previous;
            if (previous == null) {
                ORDER.offer(new TileToken(provider, profile, tiles, tileKey, created));
                TILE_COUNT.incrementAndGet();
                trim();
            }
        }
        int index = index(chunkX, chunkZ);
        int encoded = radius <= 0.0F ? 1 : Float.floatToRawIntBits(radius);
        if (tile.values.compareAndSet(index, 0, encoded)) {
            return radius;
        }
        int published = tile.values.get(index);
        return published == 1 ? 0.0F : Float.intBitsToFloat(published);
    }

    public static void clear() {
        TILES.clear();
        ORDER.clear();
        TILE_COUNT.set(0);
    }

    private static long tileKey(int chunkX, int chunkZ) {
        int tileX = Math.floorDiv(chunkX, TILE_SIDE);
        int tileZ = Math.floorDiv(chunkZ, TILE_SIDE);
        /*
         * Long.hashCode folds both halves together. Raw packed x/z keys make
         * every diagonal land in the same ConcurrentHashMap bucket, which is
         * exactly why cold city planning ended up inside TreeBin scans. Mix
         * the packed coordinate with a bijection so identity stays exact but
         * the map gets a properly distributed hash :]
         */
        return PackedCoordinateKey.of(tileX, tileZ);
    }

    private static int index(int chunkX, int chunkZ) {
        return Math.floorMod(chunkZ, TILE_SIDE) * TILE_SIDE + Math.floorMod(chunkX, TILE_SIDE);
    }

    private static void trim() {
        while (TILE_COUNT.get() > MAX_TILES) {
            TileToken oldest = ORDER.poll();
            if (oldest == null) {
                return;
            }
            if (!oldest.tiles.remove(oldest.tileKey, oldest.tile)) {
                continue;
            }
            TILE_COUNT.decrementAndGet();
            if (oldest.tiles.isEmpty()) {
                ConcurrentHashMap<LostCityProfile, ConcurrentHashMap<Long, Tile>> byProfile =
                    TILES.get(oldest.provider);
                if (byProfile != null) {
                    byProfile.remove(oldest.profile, oldest.tiles);
                    if (byProfile.isEmpty()) {
                        TILES.remove(oldest.provider, byProfile);
                    }
                }
            }
        }
    }

    private static final class Tile {
        private final AtomicIntegerArray values = new AtomicIntegerArray(TILE_SIDE * TILE_SIDE);
    }

    private record TileToken(IDimensionInfo provider,
                             LostCityProfile profile,
                             ConcurrentHashMap<Long, Tile> tiles,
                             long tileKey,
                             Tile tile) {
    }
}
