package org.admany.lc2h.worldgen.apply;

import mcjty.lostcities.varia.ChunkCoord;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.admany.lc2h.worldgen.scope.WorldGenScope;

import java.util.ArrayList;
import java.util.List;

public final class ChunkShadowMutationPlan {

    private static final long DEFAULT_PLAN_AGE_MS = Math.max(5_000L,
        Long.getLong("lc2h.shadowApply.default_plan_age_ms", 120_000L));

    public enum MutationKind {
        TREE_CAPTURE,
        TREE_FALLBACK,
        POST_PROCESS,
        GENERIC
    }

    public record Entry(int packedPos, BlockState state, int flags, boolean markTreePlacement) {
    }

    private final ChunkCoord chunk;
    private final WorldGenScope.DimensionKey scope;
    private final long transactionId;
    private final long createdAtMs;
    private final MutationKind kind;
    private final int retryCount;
    private final long expiresAtMs;
    private final Entry[] entries;

    private ChunkShadowMutationPlan(ChunkCoord chunk,
                                    WorldGenScope.DimensionKey scope,
                                    long transactionId,
                                    long createdAtMs,
                                    MutationKind kind,
                                    int retryCount,
                                    long expiresAtMs,
                                    Entry[] entries) {
        this.chunk = chunk;
        this.scope = scope;
        this.transactionId = transactionId;
        this.createdAtMs = createdAtMs;
        this.kind = kind == null ? MutationKind.GENERIC : kind;
        this.retryCount = retryCount;
        this.expiresAtMs = expiresAtMs;
        this.entries = entries;
    }

    public ChunkCoord chunk() {
        return chunk;
    }

    public WorldGenScope.DimensionKey scope() {
        return scope;
    }

    public Entry[] entries() {
        return entries;
    }

    public long transactionId() {
        return transactionId;
    }

    public long createdAtMs() {
        return createdAtMs;
    }

    public MutationKind kind() {
        return kind;
    }

    public int retryCount() {
        return retryCount;
    }

    public long expiresAtMs() {
        return expiresAtMs;
    }

    public int size() {
        return entries.length;
    }

    public boolean isExpired(long now) {
        return expiresAtMs > 0L && now >= expiresAtMs;
    }

    /**
     * Captured tree replays are deliberately applied as independently bounded
     * chunk plans.  Their source transaction is useful for tracing, but it
     * must not turn an entire cross-chunk tree into one main-thread write
     * burst.
     */
    public ChunkShadowMutationPlan withoutTransaction() {
        if (transactionId == 0L) {
            return this;
        }
        return new ChunkShadowMutationPlan(
            chunk, scope, 0L, createdAtMs, kind, retryCount, expiresAtMs, entries
        );
    }

    /**
     * Return a bounded immutable slice while retaining the original plan
     * metadata. This is used only at safe replay boundaries. Normal Lost
     * Cities mutation transactions remain intact.
     */
    public ChunkShadowMutationPlan slice(int offset, int maximumEntries) {
        if (offset < 0 || maximumEntries <= 0 || offset >= entries.length) {
            throw new IllegalArgumentException("Invalid shadow mutation slice");
        }
        int end = Math.min(entries.length, offset + maximumEntries);
        if (offset == 0 && end == entries.length) {
            return this;
        }
        Entry[] sliced = java.util.Arrays.copyOfRange(entries, offset, end);
        return new ChunkShadowMutationPlan(
            chunk, scope, transactionId, createdAtMs, kind, retryCount, expiresAtMs, sliced
        );
    }

    public static Builder builder(ChunkCoord chunk) {
        return new Builder(chunk, WorldGenScope.dimension(chunk == null ? null : chunk.dimension()));
    }

    public static Builder builder(ServerLevel level, ChunkCoord chunk) {
        return new Builder(chunk, WorldGenScope.dimension(level));
    }

    public static Builder builder(ChunkCoord chunk, WorldGenScope.DimensionKey scope) {
        return new Builder(chunk, scope);
    }

    public static int pack(BlockPos pos) {
        int localX = pos.getX() & 15;
        int localZ = pos.getZ() & 15;
        int y = pos.getY() + 1024;
        return (y << 8) | (localX << 4) | localZ;
    }

    public static void unpack(ChunkCoord chunk, int packedPos, BlockPos.MutableBlockPos out) {
        int localZ = packedPos & 15;
        int localX = (packedPos >>> 4) & 15;
        int y = (packedPos >>> 8) - 1024;
        out.set((chunk.chunkX() << 4) + localX, y, (chunk.chunkZ() << 4) + localZ);
    }

    public static final class Builder {
        private final ChunkCoord chunk;
        private final WorldGenScope.DimensionKey scope;
        private final List<Entry> entries = new ArrayList<>();
        private long transactionId;
        private long createdAtMs;
        private MutationKind kind = MutationKind.GENERIC;
        private int retryCount;
        private long expiresAtMs;

        private Builder(ChunkCoord chunk, WorldGenScope.DimensionKey scope) {
            this.chunk = chunk;
            this.scope = scope;
        }

        public Builder add(BlockPos pos, BlockState state, int flags, boolean markTreePlacement) {
            if (pos == null || state == null) {
                return this;
            }
            entries.add(new Entry(pack(pos), state, flags, markTreePlacement));
            return this;
        }

        public Builder transaction(long transactionId, long createdAtMs, MutationKind kind) {
            this.transactionId = transactionId;
            this.createdAtMs = createdAtMs;
            if (kind != null) {
                this.kind = kind;
            }
            return this;
        }

        public Builder retryState(int retryCount, long expiresAtMs) {
            this.retryCount = Math.max(0, retryCount);
            this.expiresAtMs = expiresAtMs;
            return this;
        }

        public ChunkShadowMutationPlan build() {
            long effectiveCreatedAtMs = createdAtMs > 0L ? createdAtMs : System.currentTimeMillis();
            long effectiveExpiresAtMs = expiresAtMs > 0L ? expiresAtMs : (effectiveCreatedAtMs + DEFAULT_PLAN_AGE_MS);
            return new ChunkShadowMutationPlan(
                chunk,
                scope,
                transactionId,
                effectiveCreatedAtMs,
                kind,
                retryCount,
                effectiveExpiresAtMs,
                entries.toArray(Entry[]::new)
            );
        }
    }
}
