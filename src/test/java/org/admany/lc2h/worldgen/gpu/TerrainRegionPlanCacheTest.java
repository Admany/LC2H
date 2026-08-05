package org.admany.lc2h.worldgen.gpu;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class TerrainRegionPlanCacheTest {
    @Test
    void alignsNegativeAndPositiveChunksToTheSameConfiguredRegionRule() {
        int side = Math.max(1, Integer.getInteger("lc2h.gpu.terrainRegions.side", 16));
        assertEquals(-side, TerrainRegionPlanCache.regionOriginForTest(-1));
        assertEquals(-side, TerrainRegionPlanCache.regionOriginForTest(-side));
        assertEquals(-(side * 2), TerrainRegionPlanCache.regionOriginForTest(-side - 1));
        assertEquals(0, TerrainRegionPlanCache.regionOriginForTest(0));
        assertEquals(0, TerrainRegionPlanCache.regionOriginForTest(side - 1));
        assertEquals(side, TerrainRegionPlanCache.regionOriginForTest(side));
    }

    @Test
    void doesNotAddUnprovenUpstreamCaptureWorkByDefault() {
        assertFalse(TerrainRegionPlanCache.hasUpstreamCapture());
    }
}
