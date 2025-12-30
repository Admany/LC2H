package org.admany.lc2h.worldgen.coord;

import mcjty.lostcities.varia.ChunkCoord;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

public record RegionCoord(int regionX, int regionZ, ResourceKey<Level> dimension) {

    public static RegionCoord fromChunk(ChunkCoord chunk) {
        return new RegionCoord(
            chunk.chunkX() >> 4,
            chunk.chunkZ() >> 4,
            chunk.dimension()
        );
    }

    public ChunkCoord getChunk(int localX, int localZ) {
        return new ChunkCoord(dimension, (regionX << 4) + localX, (regionZ << 4) + localZ);
    }

    public int getMinChunkX() { return regionX << 4; }
    public int getMaxChunkX() { return (regionX << 4) + 15; }
    public int getMinChunkZ() { return regionZ << 4; }
    public int getMaxChunkZ() { return (regionZ << 4) + 15; }

    @Override
    public String toString() {
        return "RegionCoord{" + regionX + "," + regionZ + "," + dimension.location() + "}";
    }
}