package org.admany.lc2h.worldgen.highway;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JavaRandomFirstDrawTest {
    private static final long MULTIPLIER = 0x5DEECE66DL;
    private static final long ADDEND = 0xBL;
    private static final long MASK = (1L << 48) - 1L;

    @Test
    void firstDoubleMatchesJavaRandomExactly() {
        for (int x = -2048; x <= 2048; x += 17) {
            for (int z = -2048; z <= 2048; z += 31) {
                long seed = (long) z * 797003437L + (long) x * 295075153L;
                assertEquals(new Random(seed).nextDouble(), firstDouble(seed));
            }
        }
    }

    @Test
    void firstIntMatchesJavaRandomExactlyForRadiusRanges() {
        for (int bound = 1; bound <= 512; bound++) {
            for (int x = -1024; x <= 1024; x += 29) {
                long seed = (long) (x * 3) * 100001653L + (long) x * 295075153L;
                assertEquals(new Random(seed).nextInt(bound), firstInt(seed, bound));
            }
        }
    }

    private static double firstDouble(long externalSeed) {
        long seed = (externalSeed ^ MULTIPLIER) & MASK;
        seed = (seed * MULTIPLIER + ADDEND) & MASK;
        long high = seed >>> 22;
        seed = (seed * MULTIPLIER + ADDEND) & MASK;
        long low = seed >>> 21;
        return ((high << 27) + low) * 0x1.0p-53;
    }

    private static int firstInt(long externalSeed, int bound) {
        long seed = (externalSeed ^ MULTIPLIER) & MASK;
        seed = (seed * MULTIPLIER + ADDEND) & MASK;
        int bits = (int) (seed >>> 17);
        int mask = bound - 1;
        if ((bound & mask) == 0) {
            return (int) ((bound * (long) bits) >> 31);
        }
        int value = bits % bound;
        while (bits - value + mask < 0) {
            seed = (seed * MULTIPLIER + ADDEND) & MASK;
            bits = (int) (seed >>> 17);
            value = bits % bound;
        }
        return value;
    }
}
