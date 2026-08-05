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
    private static final LongAdder CAPTURED_BOUNDARY = new LongAdder();
    private static final LongAdder TOTAL_TIME_NS = new LongAdder();

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

    /**
     * Resolve the one decision that can be made safely before a feature runs:
     * a tree rooted in an LC-owned chunk must not generate.  A tree rooted in a
     * normal chunk is left to its owning feature.  We deliberately do not
     * capture a broad "halo" here: at this point the feature has not disclosed
     * its final footprint, and replaying every nearby tree caused normal
     * vegetation to be deferred, cut, or stranded during city generation.
     */
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
        if (LostCityTreeSafety.isUnsafeChunk(unsafeLookup, originChunkX, originChunkZ)) {
            REJECTED_UNSAFE_ROOT.increment();
            return Decision.REJECT;
        }

        // Capture only roots that are actually next to an LC-owned chunk. The
        // capture is replayed after terrain/structure work, so a boundary tree
        // cannot be half-overwritten. Do not use a speculative BOP-size halo:
        // it deferred normal forest generation and made every city expensive.
        if (isOnUnsafeBoundary(unsafeLookup, x, z)) {
            CAPTURED_BOUNDARY.increment();
            return Decision.CAPTURE;
        }

        // `minimumCaptureRadiusBlocks` remains in the API for compat callers,
        // but a feature's maximum possible canopy is not a safe reason to
        // capture its root before the real footprint is known.
        return pass();
    }

    public static String diagnostics() {
        long checks = CHECKS.sum();
        double averageUs = checks == 0L ? 0.0D : (TOTAL_TIME_NS.sum() / 1_000.0D) / checks;
        return "deferredReplay=boundary-only checks=" + CHECKS.sum()
            + " pass=" + PASSED.sum()
            + " rejectUnsafe=" + REJECTED_UNSAFE_ROOT.sum()
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

}
