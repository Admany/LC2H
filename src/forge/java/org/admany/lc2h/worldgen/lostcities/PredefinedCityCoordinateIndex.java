package org.admany.lc2h.worldgen.lostcities;

import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.lost.cityassets.PredefinedCity;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.Map;

/**
 * Immutable primitive-coordinate view of Lost Cities' predefined-city map.
 *
 * <p>Most packs define no predefined cities. Keeping the empty case as two
 * zero-length arrays makes every city-factor candidate check allocation-free.
 * Packs that do define them usually have only a handful, where a compact
 * linear scan is cheaper than boxing a long for a hash-map lookup.</p>
 */
public final class PredefinedCityCoordinateIndex {

    private static final PredefinedCityCoordinateIndex EMPTY =
        new PredefinedCityCoordinateIndex(new long[0], new PredefinedCity[0]);

    private final long[] keys;
    private final PredefinedCity[] values;

    private PredefinedCityCoordinateIndex(long[] keys, PredefinedCity[] values) {
        this.keys = keys;
        this.values = values;
    }

    public static PredefinedCityCoordinateIndex create(Map<ChunkCoord, PredefinedCity> source,
                                                        ResourceKey<Level> dimension) {
        if (source == null || source.isEmpty() || dimension == null) {
            return EMPTY;
        }
        int count = 0;
        for (Map.Entry<ChunkCoord, PredefinedCity> entry : source.entrySet()) {
            ChunkCoord coord = entry.getKey();
            if (coord != null && dimension.equals(coord.dimension())) {
                count++;
            }
        }
        if (count == 0) {
            return EMPTY;
        }
        long[] keys = new long[count];
        PredefinedCity[] values = new PredefinedCity[count];
        int cursor = 0;
        for (Map.Entry<ChunkCoord, PredefinedCity> entry : source.entrySet()) {
            ChunkCoord coord = entry.getKey();
            if (coord != null && dimension.equals(coord.dimension())) {
                keys[cursor] = pack(coord.chunkX(), coord.chunkZ());
                values[cursor] = entry.getValue();
                cursor++;
            }
        }
        return new PredefinedCityCoordinateIndex(keys, values);
    }

    public PredefinedCity get(int chunkX, int chunkZ) {
        long key = pack(chunkX, chunkZ);
        for (int i = 0; i < keys.length; i++) {
            if (keys[i] == key) {
                return values[i];
            }
        }
        return null;
    }

    private static long pack(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) ^ (chunkZ & 0xffffffffL);
    }
}
