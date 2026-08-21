package org.admany.lc2h.util;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackedCoordinateKeyTest {

    @Test
    void squareWindowsKeepExactIdentityAndHealthyLongHashes() {
        Set<Long> keys = new HashSet<>();
        Set<Integer> hashes = new HashSet<>();
        int side = 129;

        for (int z = -64; z <= 64; z++) {
            for (int x = -64; x <= 64; x++) {
                long key = PackedCoordinateKey.of(x, z);
                keys.add(key);
                hashes.add(Long.hashCode(key));
            }
        }

        int cells = side * side;
        assertEquals(cells, keys.size());
        assertTrue(hashes.size() > cells * 0.99,
            "packed coordinate hashes must not collapse square-window diagonals");
    }
}
