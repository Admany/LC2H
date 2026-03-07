package org.admany.lc2h.worldgen.lostcities;

import mcjty.lostcities.setup.Registration;
import mcjty.lostcities.worldgen.IDimensionInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.WorldGenLevel;

/**
 * Shared tree-safety checks for Lost Cities worldgen.
 *
 * We consider city, highway, and railway chunks as unsafe for cross-chunk tree growth.
 * Trees near chunk edges that would cross into an unsafe neighbor are vetoed up front.
 */
public final class LostCityTreeSafety {

    private LostCityTreeSafety() {
    }

    public static boolean shouldBlockTreeAt(WorldGenLevel level, BlockPos pos, int buffer) {
        if (level == null || pos == null) {
            return false;
        }
        IDimensionInfo dimInfo;
        try {
            dimInfo = Registration.LOSTCITY_FEATURE.get().getDimensionInfo(level);
        } catch (Throwable ignored) {
            return false;
        }
        if (dimInfo == null) {
            return false;
        }
        ResourceKey<Level> dim = dimInfo.getType();
        if (dim == null) {
            return false;
        }
        return shouldBlockTreeAt(dimInfo, dim, pos.getX(), pos.getZ(), buffer);
    }

    public static boolean shouldBlockTreeAt(IDimensionInfo dimInfo, ResourceKey<Level> dim, int worldX, int worldZ, int buffer) {
        if (dimInfo == null || dim == null) {
            return false;
        }

        int edgeBuffer = Math.max(1, Math.min(15, buffer));
        int localX = worldX & 15;
        int localZ = worldZ & 15;
        int cx = worldX >> 4;
        int cz = worldZ >> 4;

        boolean west = localX < edgeBuffer;
        boolean east = localX >= 16 - edgeBuffer;
        boolean north = localZ < edgeBuffer;
        boolean south = localZ >= 16 - edgeBuffer;
        if (!west && !east && !north && !south) {
            return false;
        }

        ChunkUnsafeLookup unsafeLookup = new ChunkUnsafeLookup(dimInfo, dim);

        if (west && unsafeLookup.isUnsafe(cx - 1, cz)) {
            return true;
        }
        if (east && unsafeLookup.isUnsafe(cx + 1, cz)) {
            return true;
        }
        if (north && unsafeLookup.isUnsafe(cx, cz - 1)) {
            return true;
        }
        if (south && unsafeLookup.isUnsafe(cx, cz + 1)) {
            return true;
        }

        if (west && north && unsafeLookup.isUnsafe(cx - 1, cz - 1)) {
            return true;
        }
        if (west && south && unsafeLookup.isUnsafe(cx - 1, cz + 1)) {
            return true;
        }
        if (east && north && unsafeLookup.isUnsafe(cx + 1, cz - 1)) {
            return true;
        }
        if (east && south && unsafeLookup.isUnsafe(cx + 1, cz + 1)) {
            return true;
        }

        return false;
    }

    public static boolean hasUnsafeNeighborChunk(IDimensionInfo dimInfo, ResourceKey<Level> dim, int chunkX, int chunkZ) {
        if (dimInfo == null || dim == null) {
            return false;
        }
        ChunkUnsafeLookup unsafeLookup = new ChunkUnsafeLookup(dimInfo, dim);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                if (unsafeLookup.isUnsafe(chunkX + dx, chunkZ + dz)) {
                    return true;
                }
            }
        }
        return false;
    }

    public static boolean isUnsafeChunk(IDimensionInfo dimInfo, ResourceKey<Level> dim, int chunkX, int chunkZ) {
        if (dimInfo == null || dim == null) {
            return false;
        }
        return new ChunkUnsafeLookup(dimInfo, dim).isUnsafe(chunkX, chunkZ);
    }

    public static boolean isNearUnsafeChunk(IDimensionInfo dimInfo, ResourceKey<Level> dim, int worldX, int worldZ, int radiusBlocks) {
        if (dimInfo == null || dim == null || radiusBlocks <= 0) {
            return false;
        }

        int maxDistSq = radiusBlocks * radiusBlocks;
        int originChunkX = worldX >> 4;
        int originChunkZ = worldZ >> 4;
        int radiusChunks = ((radiusBlocks + 15) / 16) + 1;
        ChunkUnsafeLookup unsafeLookup = new ChunkUnsafeLookup(dimInfo, dim);

        for (int dcx = -radiusChunks; dcx <= radiusChunks; dcx++) {
            int cx = originChunkX + dcx;
            for (int dcz = -radiusChunks; dcz <= radiusChunks; dcz++) {
                int cz = originChunkZ + dcz;
                if (!unsafeLookup.isUnsafe(cx, cz)) {
                    continue;
                }

                int minX = cx << 4;
                int maxX = minX + 15;
                int minZ = cz << 4;
                int maxZ = minZ + 15;

                int dx = 0;
                if (worldX < minX) {
                    dx = minX - worldX;
                } else if (worldX > maxX) {
                    dx = worldX - maxX;
                }

                int dz = 0;
                if (worldZ < minZ) {
                    dz = minZ - worldZ;
                } else if (worldZ > maxZ) {
                    dz = worldZ - maxZ;
                }

                int distSq = dx * dx + dz * dz;
                if (distSq <= maxDistSq) {
                    return true;
                }
            }
        }
        return false;
    }

    public static boolean isNearUnsafeTransition(IDimensionInfo dimInfo, ResourceKey<Level> dim, int worldX, int worldZ, int radiusBlocks) {
        if (dimInfo == null || dim == null || radiusBlocks <= 0) {
            return false;
        }

        int maxDistSq = radiusBlocks * radiusBlocks;
        int originChunkX = worldX >> 4;
        int originChunkZ = worldZ >> 4;
        int radiusChunks = ((radiusBlocks + 15) / 16) + 1;
        ChunkUnsafeLookup unsafeLookup = new ChunkUnsafeLookup(dimInfo, dim);
        boolean originUnsafe = unsafeLookup.isUnsafe(originChunkX, originChunkZ);

        for (int dcx = -radiusChunks; dcx <= radiusChunks; dcx++) {
            int cx = originChunkX + dcx;
            for (int dcz = -radiusChunks; dcz <= radiusChunks; dcz++) {
                int cz = originChunkZ + dcz;
                boolean candidateUnsafe = unsafeLookup.isUnsafe(cx, cz);
                if (candidateUnsafe == originUnsafe) {
                    continue;
                }

                int minX = cx << 4;
                int maxX = minX + 15;
                int minZ = cz << 4;
                int maxZ = minZ + 15;

                int dx = 0;
                if (worldX < minX) {
                    dx = minX - worldX;
                } else if (worldX > maxX) {
                    dx = worldX - maxX;
                }

                int dz = 0;
                if (worldZ < minZ) {
                    dz = minZ - worldZ;
                } else if (worldZ > maxZ) {
                    dz = worldZ - maxZ;
                }

                int distSq = dx * dx + dz * dz;
                if (distSq <= maxDistSq) {
                    return true;
                }
            }
        }

        return false;
    }
}
