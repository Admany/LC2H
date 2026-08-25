package org.admany.lc2h.worldgen;

import org.admany.lc2h.worldgen.terrain.CityDensityShiftField;

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

    @Test
    void smoothInterpolationNeverOvershootsAControlEnvelope() {
        double max = 0.0D;
        double min = 96.0D;
        for (int i = 0; i <= 1000; i++) {
            double t = i / 1000.0D;
            double value = CityDensityShiftField.monotoneCubic(0.0D, 96.0D, 0.0D, 0.0D, t);
            max = Math.max(max, value);
            min = Math.min(min, value);
        }
        assertTrue(max <= 96.0D + 1.0E-9D, "the interpolant must not create a taller ridge than its control");
        assertTrue(min >= -1.0E-9D, "the interpolant must not create a negative offset");
    }

    @Test
    void smoothInterpolationPreservesMonotoneTransitions() {
        double previous = Double.NEGATIVE_INFINITY;
        for (int i = 0; i <= 1000; i++) {
            double value = CityDensityShiftField.monotoneCubic(0.0D, 12.0D, 64.0D, 80.0D, i / 1000.0D);
            assertTrue(value + 1.0E-9D >= previous, "a monotone control sequence must stay monotone");
            previous = value;
        }
    }

    @Test
    void surfaceEnvelopeDoesNotLowerAFlatNativeRidge() {
        assertEquals(0.0D,
            CityDensityShiftField.capToNativeSurfaceEnvelope(30.0D, 102, 102), 1.0E-9D);
        assertEquals(0.0D,
            CityDensityShiftField.capToNativeSurfaceEnvelope(30.0D, 98, 102), 1.0E-9D);
    }

    @Test
    void surfaceEnvelopeCapsAnIsolatedPeakToItsMeasuredExcess() {
        assertEquals(6.0D,
            CityDensityShiftField.capToNativeSurfaceEnvelope(30.0D, 102, 96), 1.0E-9D);
        assertEquals(2.0D,
            CityDensityShiftField.capToNativeSurfaceEnvelope(2.0D, 102, 96), 1.0E-9D);
    }
}
