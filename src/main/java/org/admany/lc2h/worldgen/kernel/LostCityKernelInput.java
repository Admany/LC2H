package org.admany.lc2h.worldgen.kernel;

import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.IDimensionInfo;

import java.util.Objects;

public record LostCityKernelInput(
    IDimensionInfo provider,
    ChunkCoord coord,
    int areaSize,
    LostCityKernelSignature signature
) {
    public LostCityKernelInput {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(coord, "coord");
        Objects.requireNonNull(signature, "signature");
        if (areaSize <= 0) {
            throw new IllegalArgumentException("areaSize must be positive");
        }
    }

    public String localityKey() {
        return signature.dimensionId() + ":" + coord.chunkX() + ":" + coord.chunkZ();
    }
}
