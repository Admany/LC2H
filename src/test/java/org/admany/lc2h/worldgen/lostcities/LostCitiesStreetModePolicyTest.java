package org.admany.lc2h.worldgen.lostcities;

import mcjty.lostcities.config.StreetGenerationMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LostCitiesStreetModePolicyTest {
    @AfterEach
    void restoreDefault() {
        LostCitiesStreetModePolicy.setConfiguredMode("HIERARCHICAL_GRID_V1");
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
    void invalidConfigFallsBackToTheNewPlanner() {
        LostCitiesStreetModePolicy.setConfiguredMode("not-a-planner");

        assertEquals("HIERARCHICAL_GRID_V1", LostCitiesStreetModePolicy.normalizeValue("not-a-planner"));
        assertEquals(StreetGenerationMode.HIERARCHICAL_GRID_V1,
            LostCitiesStreetModePolicy.resolve(StreetGenerationMode.LEGACY));
    }

    @Test
    void chaosZPackProfilesAlwaysUseLegacy() {
        LostCitiesStreetModePolicy.setConfiguredMode("HIERARCHICAL_GRID_V1");

        assertEquals(StreetGenerationMode.LEGACY,
            LostCitiesStreetModePolicy.resolve(StreetGenerationMode.HIERARCHICAL_GRID_V1, "aaaaaaaaz15Flat"));
        assertEquals(StreetGenerationMode.LEGACY,
            LostCitiesStreetModePolicy.resolve(StreetGenerationMode.HIERARCHICAL_GRID_V1, "  AzzzChaosV4  "));
        assertTrue(LostCitiesStreetModePolicy.requiresLegacyMode("azzz_custom"));
        assertFalse(LostCitiesStreetModePolicy.requiresLegacyMode("chaos"));
        assertFalse(LostCitiesStreetModePolicy.requiresLegacyMode(null));
    }

    @Test
    void otherProfilesStillRespectTheButton() {
        LostCitiesStreetModePolicy.setConfiguredMode("LEGACY");
        assertEquals(StreetGenerationMode.LEGACY,
            LostCitiesStreetModePolicy.resolve(StreetGenerationMode.HIERARCHICAL_GRID_V1, "rarecities"));

        LostCitiesStreetModePolicy.setConfiguredMode("HIERARCHICAL_GRID_V1");
        assertEquals(StreetGenerationMode.HIERARCHICAL_GRID_V1,
            LostCitiesStreetModePolicy.resolve(StreetGenerationMode.LEGACY, "rarecities"));
    }
}
