package org.admany.lc2h.worldgen.lostcities;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class DeferredTreeCaptureContext {

    private static final ThreadLocal<CaptureSession> ACTIVE = new ThreadLocal<>();

    private DeferredTreeCaptureContext() {
    }

    public record CapturedTree(
        BlockPos origin,
        ResourceKey<Level> dim,
        List<DeferredTreeQueue.CapturedBlock> blocks
    ) {
    }

    private static final class CaptureSession {
        private final BlockPos origin;
        private final ResourceKey<Level> dim;
        private final int baseY;
        private final Map<Long, DeferredTreeQueue.CapturedBlock> blocks = new LinkedHashMap<>();

        private CaptureSession(BlockPos origin, ResourceKey<Level> dim) {
            this.origin = origin;
            this.dim = dim;
            this.baseY = origin.getY();
        }
    }

    public static void begin(BlockPos origin, ResourceKey<Level> dim) {
        if (origin == null || dim == null) {
            return;
        }
        ACTIVE.set(new CaptureSession(origin.immutable(), dim));
    }

    public static boolean isCapturing() {
        return ACTIVE.get() != null;
    }

    public static void capture(BlockPos pos, BlockState state) {
        CaptureSession session = ACTIVE.get();
        if (session == null || pos == null || state == null) {
            return;
        }
        session.blocks.put(pos.asLong(), new DeferredTreeQueue.CapturedBlock(pos.immutable(), state));
    }

    public static BlockState getCapturedState(BlockPos pos) {
        CaptureSession session = ACTIVE.get();
        if (session == null || pos == null) {
            return null;
        }
        DeferredTreeQueue.CapturedBlock captured = session.blocks.get(pos.asLong());
        return captured == null ? null : captured.state();
    }

    public static BlockState getVirtualState(BlockPos pos, BlockState actual) {
        CaptureSession session = ACTIVE.get();
        if (session == null || pos == null) {
            return actual;
        }

        DeferredTreeQueue.CapturedBlock captured = session.blocks.get(pos.asLong());
        if (captured != null) {
            return captured.state();
        }

        if (pos.getY() >= session.baseY) {
            return Blocks.AIR.defaultBlockState();
        }

        return actual;
    }

    public static CapturedTree finish() {
        CaptureSession session = ACTIVE.get();
        ACTIVE.remove();
        if (session == null) {
            return null;
        }
        return new CapturedTree(session.origin, session.dim, new ArrayList<>(session.blocks.values()));
    }

    public static void clear() {
        ACTIVE.remove();
    }
}
