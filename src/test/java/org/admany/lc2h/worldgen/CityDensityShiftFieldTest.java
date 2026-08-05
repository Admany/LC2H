package org.admany.lc2h.worldgen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CityDensityShiftFieldTest {

    @Test
    void chunkCentreUsesItsExactControlValue() {
        double shift = CityDensityShiftField.sample(8, 8,
            (chunkX, chunkZ) -> chunkX == 0 && chunkZ == 0 ? 24.0D : 0.0D);

        assertEquals(24.0D, shift, 1.0E-9D);
    }

    @Test
    void transitionIsContinuousAcrossChunkBorder() {
        CityDensityShiftField.ControlSampler controls =
            (chunkX, chunkZ) -> chunkX <= 0 ? 24.0D : 0.0D;

        double before = CityDensityShiftField.sample(15, 8, controls);
        double border = CityDensityShiftField.sample(16, 8, controls);
        double after = CityDensityShiftField.sample(17, 8, controls);

        assertTrue(before >= border);
        assertTrue(border >= after);
        assertTrue(Math.abs(before - after) < 6.5D,
            "a one-block step at the chunk border must stay gradual");
    }

    @Test
    void negativeCoordinatesUseTheSameWorldSpaceLattice() {
        CityDensityShiftField.ControlSampler controls =
            (chunkX, chunkZ) -> chunkX == -1 && chunkZ == -1 ? 18.0D : 0.0D;

        assertEquals(18.0D, CityDensityShiftField.sample(-8, -8, controls), 1.0E-9D);
        assertTrue(CityDensityShiftField.sample(-1, -8, controls) > 0.0D);
    }
}
