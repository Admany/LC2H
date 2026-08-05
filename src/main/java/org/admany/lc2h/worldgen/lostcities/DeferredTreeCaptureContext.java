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
import java.util.concurrent.atomic.AtomicInteger;

public final class DeferredTreeCaptureContext {

    private static final ThreadLocal<CaptureSession> ACTIVE = new ThreadLocal<>();
    /**
     * Global fast gate for the Level/WorldGenRegion read hooks. Those hooks run
     * for virtually every worldgen block query, while capture sessions are
     * rare and normally disabled entirely. Avoid touching ThreadLocal storage
     * unless at least one thread is actually capturing a tree.
     */
    private static final AtomicInteger ACTIVE_SESSIONS = new AtomicInteger();

    private DeferredTreeCaptureContext() {
    }

    public record CapturedTree(
        BlockPos origin,
        ResourceKey<Level> dim,
        long seed,
        CaptureSource source,
        List<DeferredTreeQueue.CapturedBlock> blocks
    ) {
    }

    public enum CaptureSource {
        VANILLA_TREE,
        BOP_TREE,
        TREEPLACER,
        MIDGARD_TREEPLACER,
        MIDGARD_STRUCTURE
    }

    private static final class CaptureSession {
        private final BlockPos origin;
        private final ResourceKey<Level> dim;
        private final long seed;
        private final CaptureSource source;
        private final int baseY;
        private final Map<Long, DeferredTreeQueue.CapturedBlock> blocks = new LinkedHashMap<>();

        private CaptureSession(BlockPos origin, ResourceKey<Level> dim, long seed, CaptureSource source) {
            this.origin = origin;
            this.dim = dim;
            this.seed = seed;
            this.source = source == null ? CaptureSource.VANILLA_TREE : source;
            this.baseY = origin.getY();
        }
    }

    public static void begin(BlockPos origin, ResourceKey<Level> dim) {
        begin(origin, dim, 0L, CaptureSource.VANILLA_TREE);
    }

    public static void begin(BlockPos origin, ResourceKey<Level> dim, long seed) {
        begin(origin, dim, seed, CaptureSource.VANILLA_TREE);
    }

    public static void begin(BlockPos origin, ResourceKey<Level> dim, long seed, CaptureSource source) {
        if (origin == null || dim == null) {
            return;
        }
        boolean replacing = ACTIVE.get() != null;
        ACTIVE.set(new CaptureSession(origin.immutable(), dim, seed, source));
        if (!replacing) {
            ACTIVE_SESSIONS.incrementAndGet();
        }
    }

    public static boolean isCapturing() {
        return ACTIVE_SESSIONS.get() != 0 && ACTIVE.get() != null;
    }

    public static CaptureSource currentSource() {
        CaptureSession session = ACTIVE.get();
        return session == null ? null : session.source;
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
        return actual;
    }

    public static CapturedTree finish() {
        CaptureSession session = ACTIVE.get();
        if (session == null) {
            return null;
        }
        ACTIVE.remove();
        ACTIVE_SESSIONS.decrementAndGet();
        return new CapturedTree(session.origin, session.dim, session.seed, session.source, new ArrayList<>(session.blocks.values()));
    }

    public static void clear() {
        if (ACTIVE.get() != null) {
            ACTIVE.remove();
            ACTIVE_SESSIONS.decrementAndGet();
        }
    }

    /**
     * A capture is only valid for the TreeFeature/compat invocation that created
     * it.  Vanilla biome decoration invokes each placed feature as a separate
     * top-level call, so an active session at the start of the next call can
     * only be a capture whose owner did not reach its normal RETURN hook.
     *
     * @return {@code true} when a stale session was discarded
     */
    public static boolean discardStalePlacedFeatureCapture() {
        if (ACTIVE.get() == null) {
            return false;
        }
        ACTIVE.remove();
        ACTIVE_SESSIONS.decrementAndGet();
        return true;
    }
}
