package org.admany.lc2h.worldgen.kernel;

import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.lost.MultiChunk;

import java.util.Objects;

public record LostCityMultiChunkPlan(
    ChunkCoord multiCoord,
    int areaSize,
    MultiChunk multiChunk,
    LostCityKernelSignature signature
) {
    public LostCityMultiChunkPlan {
        Objects.requireNonNull(multiCoord, "multiCoord");
        Objects.requireNonNull(multiChunk, "multiChunk");
        Objects.requireNonNull(signature, "signature");
    }
}
