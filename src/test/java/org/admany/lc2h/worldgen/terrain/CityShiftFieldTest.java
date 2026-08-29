package org.admany.lc2h.worldgen.terrain;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CityShiftFieldTest {

    @Test
    void flatCitySourceLowersAReceivingMountain() {
        int side = 5;
        int source = 2 * side + 2;
        int east = source + 1;
        float[] value = new float[side * side];
        float[] demand = new float[value.length];
        float[] distance = new float[value.length];
        boolean[] locked = new boolean[value.length];
        float[] step = new float[value.length];
        float[] natural = new float[value.length];
        Arrays.fill(value, Float.NEGATIVE_INFINITY);
        Arrays.fill(distance, Float.POSITIVE_INFINITY);
        Arrays.fill(step, 8.0F);
        Arrays.fill(natural, 71.0F);

        value[source] = 0.0F;
        demand[source] = 0.0F;
        distance[source] = 0.0F;
        locked[source] = true;
        natural[east] = 111.0F;

        CityShiftField.boundedGradientTransform(
            value, demand, distance, locked, step, natural, side, 36);

        assertEquals(32.0F, value[east], 1.0E-4F,
            "the receiver's 40-block rise must be lowered to the 8-block slope budget");
    }

    @Test
    void flatTerrainKeepsLegacyDemandFalloff() {
        int side = 5;
        int source = 2 * side + 2;
        int east = source + 1;
        float[] value = new float[side * side];
        float[] demand = new float[value.length];
        float[] distance = new float[value.length];
        boolean[] locked = new boolean[value.length];
        float[] step = new float[value.length];
        float[] natural = new float[value.length];
        Arrays.fill(value, Float.NEGATIVE_INFINITY);
        Arrays.fill(distance, Float.POSITIVE_INFINITY);
        Arrays.fill(step, 8.0F);
        Arrays.fill(natural, 90.0F);

        value[source] = 24.0F;
        demand[source] = 24.0F;
        distance[source] = 0.0F;
        locked[source] = true;

        CityShiftField.boundedGradientTransform(
            value, demand, distance, locked, step, natural, side, 36);

        assertEquals(16.0F, value[east], 1.0E-4F);
        assertTrue(value[source + 2] < value[east]);
    }
}
