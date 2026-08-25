package org.admany.lc2h.worldgen.lostcities;

import mcjty.lostcities.config.StreetGenerationMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LostCitiesStreetModePolicyTest {
    @AfterEach
    void restoreDefault() {
        LostCitiesStreetModePolicy.setConfiguredMode("LEGACY");
    }

    @Test
    void appliesPlannerChoiceImmediatelyWithoutReinitializingTheDimension() {
        LostCitiesStreetModePolicy.setConfiguredMode("LEGACY");
        assertEquals(StreetGenerationMode.LEGACY,
            LostCitiesStreetModePolicy.resolve(StreetGenerationMode.HIERARCHICAL_GRID_V1));

        LostCitiesStreetModePolicy.setConfiguredMode("HIERARCHICAL_GRID_V1");
        assertEquals(StreetGenerationMode.HIERARCHICAL_GRID_V1,
            LostCitiesStreetModePolicy.resolve(StreetGenerationMode.LEGACY));
    }

    @Test
    void invalidConfigFallsBackToLegacy() {
        LostCitiesStreetModePolicy.setConfiguredMode("not-a-planner");

        assertEquals("LEGACY", LostCitiesStreetModePolicy.normalizeValue("not-a-planner"));
        assertEquals(StreetGenerationMode.LEGACY,
            LostCitiesStreetModePolicy.resolve(StreetGenerationMode.HIERARCHICAL_GRID_V1));
    }
}
