package org.admany.lc2h.worldgen.gpu;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CityCenterGpuCacheTest {
    private static final long DOUBLE_SCALE = 1L << 53;

    @AfterEach
    void clear() {
        CityCenterGpuCache.clearAll();
    }

    @Test
    void tiledCpuFactsMatchJavaRandomAcrossNegativeTileBoundaries() {
        int minX = -97;
        int minZ = 61;
        int side = 179;
        int minRadius = 43;
        int range = 311;
        double chance = 0.173D;
        long chanceLimit = (long) Math.ceil(chance * 0x1.0p53);
        CityCenterGpuCache.Request request = new CityCenterGpuCache.Request(
            new CityCenterGpuCache.Key("test-scope", "test-profile", -16, 64, 16),
            minX, minZ, side, minRadius, range, chanceLimit);

        CityCenterGpuCache.PreparedCenters tiled = CityCenterGpuCache.assembleCpuTiles(request);
        for (int x = 0; x < side; x++) {
            int chunkX = minX + x;
            for (int z = 0; z < side; z++) {
                int chunkZ = minZ + z;
                long centerSeed = (long) chunkZ * 797003437L + (long) chunkX * 295075153L;
                float expected = 0.0F;
                if (new Random(centerSeed).nextDouble() < chance) {
                    long radiusSeed = (long) chunkZ * 100001653L + (long) chunkX * 295075153L;
                    expected = minRadius + new Random(radiusSeed).nextInt(range);
                }
                assertEquals(expected, tiled.radiusAt(chunkX, chunkZ),
                    () -> "mismatch at " + chunkX + "," + chunkZ);
            }
        }
    }
}
