package org.admany.lc2h.dev.debug;

import java.util.List;

public final class WorldParityLegacyArtifacts {
    public static final String ACTIVE_MATRIX_ID = "generated_ab_v2";
    public static final String RETIRED_MATRIX_ID = "generated_ab_v1";

    private WorldParityLegacyArtifacts() {
    }

    public static boolean isLegacyMatrix(String matrixId) {
        return RETIRED_MATRIX_ID.equals(matrixId);
    }

    public static boolean isActiveMatrix(String matrixId) {
        return ACTIVE_MATRIX_ID.equals(matrixId);
    }

    public static List<String> summaryLines() {
        return List.of(
            "WorldParityLegacy: activeMatrix=" + ACTIVE_MATRIX_ID + " retiredMatrix=" + RETIRED_MATRIX_ID,
            "WorldParityLegacy: generated_ab_v1 exports are legacy/invalid for correctness conclusions and must not be reused as baselines",
            "WorldParityLegacy: only generated_ab_v2 exports with matching environment and chunk window are accepted by the corrected harness"
        );
    }
}
