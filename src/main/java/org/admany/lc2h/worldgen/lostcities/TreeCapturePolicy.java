package org.admany.lc2h.worldgen.lostcities;

import mcjty.lostcities.setup.Registration;
import mcjty.lostcities.worldgen.IDimensionInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.admany.lc2h.config.ConfigManager;

import java.util.concurrent.atomic.LongAdder;

public final class TreeCapturePolicy {

    private static final LongAdder CHECKS = new LongAdder();
    private static final LongAdder PASSED = new LongAdder();
    private static final LongAdder REJECTED_UNSAFE_ROOT = new LongAdder();
    private static final LongAdder REJECTED_UNDERGROUND_RAIL_ROOT = new LongAdder();
    private static final LongAdder CAPTURED_BOUNDARY = new LongAdder();
    private static final LongAdder TOTAL_TIME_NS = new LongAdder();
    /* The pre-feature check only protects trees that reach an LC seam. */
    private static final int DEFAULT_TREE_FOOTPRINT_RADIUS_BLOCKS = Math.max(4,
        Math.min(16, Integer.getInteger("lc2h.treeSafety.defaultFootprintBlocks", 8)));

    public enum Decision {
        PASS_THROUGH,
        CAPTURE,
        REJECT
    }

    private TreeCapturePolicy() {
    }

    public static boolean shouldCaptureAt(ServerLevel level, BlockPos origin) {
        return decideAt(level, origin) == Decision.CAPTURE;
    }

    public static boolean shouldRejectAt(ServerLevel level, BlockPos origin) {
        return decideAt(level, origin) == Decision.REJECT;
    }

    public static Decision decideAt(ServerLevel level, BlockPos origin) {
        return decideAt(level, origin, 0);
    }

    /** Decide whether a tree needs seam capture before its feature runs. */
    public static Decision decideAt(ServerLevel level, BlockPos origin, int minimumCaptureRadiusBlocks) {
        long startedNs = System.nanoTime();
        try {
            return decideAtInternal(level, origin, minimumCaptureRadiusBlocks);
        } finally {
            TOTAL_TIME_NS.add(System.nanoTime() - startedNs);
        }
    }

    private static Decision decideAtInternal(ServerLevel level, BlockPos origin, int minimumCaptureRadiusBlocks) {
        CHECKS.increment();
        if (level == null || origin == null) {
            return pass();
        }
        IDimensionInfo dimInfo;
        try {
            dimInfo = Registration.LOSTCITY_FEATURE.get().getDimensionInfo(level);
        } catch (Throwable ignored) {
            return pass();
        }
        if (dimInfo == null) {
            return pass();
        }
        ResourceKey<Level> dim = dimInfo.getType();
        if (dim == null) {
            return pass();
        }

        int x = origin.getX();
        int z = origin.getZ();
        int originChunkX = x >> 4;
        int originChunkZ = z >> 4;
        ChunkUnsafeLookup unsafeLookup = new ChunkUnsafeLookup(dimInfo, dim);
        ChunkRoleProbe.Probe originProbe = ChunkRoleProbe.getTreeSafetyProbe(
            dimInfo, dim, originChunkX, originChunkZ);
        if (!ChunkRoleProbe.hasTreeSafetyProbe(dimInfo, dim, originChunkX, originChunkZ)) {
            CAPTURED_BOUNDARY.increment();
            return Decision.CAPTURE;
        }
        if (originProbe.isUnsafe()) {
            if (originProbe.hasRailway() && !originProbe.hasSurfaceRailway()
                && !originProbe.isCity() && !originProbe.hasHighway()) {
                REJECTED_UNDERGROUND_RAIL_ROOT.increment();
            }
            REJECTED_UNSAFE_ROOT.increment();
            return Decision.REJECT;
        }

        // Capture only when the estimated footprint reaches an LC-owned chunk.
        int footprintRadius = minimumCaptureRadiusBlocks > 0
            ? Math.min(64, minimumCaptureRadiusBlocks)
            : DEFAULT_TREE_FOOTPRINT_RADIUS_BLOCKS;
        footprintRadius = scaledRadius(footprintRadius);
        if (isWithinUnsafeRadius(unsafeLookup, x, z, footprintRadius)) {
            CAPTURED_BOUNDARY.increment();
            return Decision.CAPTURE;
        }

        return pass();
    }

    public static String diagnostics() {
        long checks = CHECKS.sum();
        double averageUs = checks == 0L ? 0.0D : (TOTAL_TIME_NS.sum() / 1_000.0D) / checks;
        return "deferredReplay=bounded-footprint checks=" + CHECKS.sum()
            + " pass=" + PASSED.sum()
            + " rejectUnsafe=" + REJECTED_UNSAFE_ROOT.sum()
            + " rejectUndergroundRail=" + REJECTED_UNDERGROUND_RAIL_ROOT.sum()
            + " captureBoundary=" + CAPTURED_BOUNDARY.sum()
            + " avgUs=" + String.format(java.util.Locale.ROOT, "%.3f", averageUs);
    }

    private static Decision pass() {
        PASSED.increment();
        return Decision.PASS_THROUGH;
    }

    private static boolean isOnUnsafeBoundary(ChunkUnsafeLookup unsafeLookup, int worldX, int worldZ) {
        int buffer = Math.max(1, Math.min(4, ConfigManager.CITY_BLEND_TREE_SEAM_BUFFER));
        int localX = worldX & 15;
        int localZ = worldZ & 15;
        int chunkX = worldX >> 4;
        int chunkZ = worldZ >> 4;

        boolean west = localX < buffer;
        boolean east = localX >= 16 - buffer;
        boolean north = localZ < buffer;
        boolean south = localZ >= 16 - buffer;
        if ((!west || !unsafeLookup.isUnsafe(chunkX - 1, chunkZ))
            && (!east || !unsafeLookup.isUnsafe(chunkX + 1, chunkZ))
            && (!north || !unsafeLookup.isUnsafe(chunkX, chunkZ - 1))
            && (!south || !unsafeLookup.isUnsafe(chunkX, chunkZ + 1))) {
            return (west && north && unsafeLookup.isUnsafe(chunkX - 1, chunkZ - 1))
                || (west && south && unsafeLookup.isUnsafe(chunkX - 1, chunkZ + 1))
                || (east && north && unsafeLookup.isUnsafe(chunkX + 1, chunkZ - 1))
                || (east && south && unsafeLookup.isUnsafe(chunkX + 1, chunkZ + 1));
        }
        return true;
    }

    private static boolean isWithinUnsafeRadius(ChunkUnsafeLookup unsafeLookup,
                                                int worldX,
                                                int worldZ,
                                                int radiusBlocks) {
        if (radiusBlocks <= 0) {
            return isOnUnsafeBoundary(unsafeLookup, worldX, worldZ);
        }
        int minChunkX = Math.floorDiv(worldX - radiusBlocks, 16);
        int maxChunkX = Math.floorDiv(worldX + radiusBlocks, 16);
        int minChunkZ = Math.floorDiv(worldZ - radiusBlocks, 16);
        int maxChunkZ = Math.floorDiv(worldZ + radiusBlocks, 16);
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                if (unsafeLookup.isUnsafe(chunkX, chunkZ)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Apply the live giant-tree seam multiplier to a real/estimated feature footprint. */
    public static int scaledRadius(int radiusBlocks) {
        double multiplier = Math.max(0.5D, Math.min(3.0D, ConfigManager.TREE_SEAM_RADIUS_MULTIPLIER));
        return Math.max(4, Math.min(64, (int) Math.ceil(Math.max(1, radiusBlocks) * multiplier)));
    }

}
