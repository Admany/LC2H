package org.admany.lc2h.worldgen.kernel;

import mcjty.lostcities.varia.ChunkCoord;

public record LostCityStageResult(
    LostCityKernelStage stage,
    ChunkCoord coord,
    boolean completed,
    Object payload,
    LostCityKernelSignature signature
) {
    public static LostCityStageResult completed(LostCityKernelStage stage,
                                                ChunkCoord coord,
                                                Object payload,
                                                LostCityKernelSignature signature) {
        return new LostCityStageResult(stage, coord, true, payload, signature);
    }

    public <T> T payloadAs(Class<T> type) {
        if (type == null || payload == null || !type.isInstance(payload)) {
            return null;
        }
        return type.cast(payload);
    }
}
