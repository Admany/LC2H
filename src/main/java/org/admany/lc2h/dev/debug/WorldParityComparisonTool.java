package org.admany.lc2h.dev.debug;

import java.nio.file.Path;

/**
 * Offline entry point for comparing two already-captured parity exports with
 * the exact classifier used by the packaged world-parity runner.
 */
public final class WorldParityComparisonTool {
    private WorldParityComparisonTool() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            throw new IllegalArgumentException("Expected: <baseline-export> <test-export> <output-compare>");
        }
        WorldParityHarness.ComparisonSummary summary = WorldParityHarness.compareExports(
            Path.of(args[0]),
            Path.of(args[1]),
            Path.of(args[2])
        );
        System.out.println(summary.summary());
    }
}
