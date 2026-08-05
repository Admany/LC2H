package org.admany.lc2h.worldgen.scope;

import net.minecraft.world.level.Level;
import org.admany.lc2h.testutil.TestResourceKeys;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldGenScopeTest {
    private static final net.minecraft.resources.ResourceKey<Level> TEST_DIMENSION =
        TestResourceKeys.testDimension("lc2h:test");

    @Test
    void bridgeDiskKeyIncludesActiveLifecycleAndSchema() {
        String first = WorldGenScope.bridgeDiskKey("raw-key");
        WorldGenScope.endServer();
        String second = WorldGenScope.bridgeDiskKey("raw-key");

        assertTrue(first.contains("schema=" + WorldGenScope.CACHE_SCHEMA_VERSION));
        assertTrue(second.contains("schema=" + WorldGenScope.CACHE_SCHEMA_VERSION));
        assertNotEquals(first, second);
    }

    @Test
    void explicitLifecycleDimensionKeyUsesProvidedLifecycle() {
        WorldGenScope.DimensionKey key = WorldGenScope.dimension(TEST_DIMENSION, 99L, 1234L);

        assertTrue(key.shortText().contains("life=99"));
        assertTrue(key.shortText().contains("seed=1234"));
        assertTrue(key.shortText().contains("dim=lc2h_test"));
    }
}
