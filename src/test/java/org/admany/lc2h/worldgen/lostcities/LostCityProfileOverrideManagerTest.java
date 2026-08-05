package org.admany.lc2h.worldgen.lostcities;

import net.minecraft.world.level.Level;
import org.admany.lc2h.testutil.TestResourceKeys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LostCityProfileOverrideManagerTest {
    private static final net.minecraft.resources.ResourceKey<Level> TEST_DIMENSION =
        TestResourceKeys.testDimension("lc2h:test");

    @AfterEach
    void tearDown() {
        LostCityProfileOverrideManager.clearAllOverrides();
    }

    @Test
    void storesOutsideOverrideIndependentlyFromPrimaryProfile() {
        LostCityProfileOverrideManager.setOverride(TEST_DIMENSION, "primary");
        LostCityProfileOverrideManager.setOutsideOverride(TEST_DIMENSION, "outside");

        assertEquals("primary", LostCityProfileOverrideManager.overrideName(TEST_DIMENSION).orElseThrow());
        assertEquals("outside", LostCityProfileOverrideManager.outsideOverrideName(TEST_DIMENSION).orElseThrow());
    }

    @Test
    void clearsBothPrimaryAndOutsideOverrides() {
        LostCityProfileOverrideManager.setOverride(TEST_DIMENSION, "primary");
        LostCityProfileOverrideManager.setOutsideOverride(TEST_DIMENSION, "outside");

        LostCityProfileOverrideManager.clearAllOverrides();

        assertTrue(LostCityProfileOverrideManager.overrideName(TEST_DIMENSION).isEmpty());
        assertTrue(LostCityProfileOverrideManager.outsideOverrideName(TEST_DIMENSION).isEmpty());
    }
}
