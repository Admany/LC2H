package org.admany.lc2h.dev.debug;

import org.admany.lc2h.worldgen.apply.ShadowBlockMutationApplier;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShadowMutationRuntimeHarnessTest {

    @Test
    void gravitySettlementAcceptsSingleCycleCompletion() {
        ArrayList<String> details = new ArrayList<>();
        boolean success = ShadowMutationRuntimeHarness.evaluateGravitySettlement(
            0,
            new ShadowBlockMutationApplier.RuntimeSnapshot(0, 0, 0, 0, 0L, 0L, 0L, 0L, 0L, 3L, 1L),
            0,
            details
        );

        assertTrue(success);
        assertTrue(details.stream().anyMatch(line -> line.contains("single eligible drain cycle")));
    }

    @Test
    void gravitySettlementFailsWhenResidualWorkRemains() {
        ArrayList<String> details = new ArrayList<>();
        boolean success = ShadowMutationRuntimeHarness.evaluateGravitySettlement(
            1,
            new ShadowBlockMutationApplier.RuntimeSnapshot(1, 0, 0, 0, 0L, 0L, 0L, 0L, 0L, 3L, 1L),
            0,
            details
        );

        assertFalse(success);
        assertTrue(details.stream().anyMatch(line -> line.contains("shadow queue still pending")));
    }
}
