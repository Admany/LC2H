package org.admany.lc2h.worldgen.apply;

import mcjty.lostcities.varia.ChunkCoord;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChainBlock;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.GrowingPlantBodyBlock;
import net.minecraft.world.level.block.GrowingPlantHeadBlock;
import net.minecraft.world.level.block.HangingRootsBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.FluidState;
import org.admany.lc2h.util.chunk.ChunkPostProcessor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class ShadowMutationFinalizer {

    private static final int MAX_PLAN_RETRIES = Math.max(1, Integer.getInteger("lc2h.shadowApply.max_plan_retries", 6));
    private static final long MAX_PLAN_AGE_MS = Math.max(5_000L, Long.getLong("lc2h.shadowApply.max_plan_age_ms", 120_000L));
    /* Neighbor propagation can resolve adjacent chunks through ServerLevel.
     * Keep the replay path local by default.  Set this property only when a
     * pack explicitly needs vanilla neighbor notifications during replay. */
    private static final boolean ENABLE_NEIGHBOR_UPDATES = Boolean.parseBoolean(
        System.getProperty("lc2h.shadowApply.neighborUpdates", "false"));

    static boolean neighborUpdatesEnabled() {
        return ENABLE_NEIGHBOR_UPDATES;
    }

    private ShadowMutationFinalizer() {
    }

    record Result(int applied,
                  int skipped,
                  int retriedEntries,
                  int gravityTicks,
                  int fluidTicks,
                  int attachmentChecks,
                  int gravityOps,
                  int fluidOps,
                  int attachmentOps,
                  ChunkShadowMutationPlan retryPlan) {
        static Result empty() {
            return new Result(0, 0, 0, 0, 0, 0, 0, 0, 0, null);
        }
    }

    static Result apply(ServerLevel level, ChunkShadowMutationPlan plan) {
        return apply(level, plan, null);
    }

    static Result apply(ServerLevel level, ChunkShadowMutationPlan plan, ShadowMutationTransactionView transactionView) {
        if (level == null || plan == null || plan.chunk() == null || plan.size() == 0) {
            return Result.empty();
        }

        List<IndexedEntry> ordered = classify(plan);
        if (ordered.isEmpty()) {
            return Result.empty();
        }

        List<ChunkShadowMutationPlan.Entry> retryEntries = null;
        int applied = 0;
        int skipped = 0;
        int retriedEntries = 0;
        int gravityTicks = 0;
        int fluidTicks = 0;
        int attachmentChecks = 0;
        int gravityOps = 0;
        int fluidOps = 0;
        int attachmentOps = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        LevelReader supportReader = transactionView == null
            ? ShadowMutationTransactionView.loadedOnlyReader(level)
            : transactionView.reader();

        for (IndexedEntry indexed : ordered) {
            ChunkShadowMutationPlan.Entry entry = indexed.entry();
            if (entry == null || entry.state() == null) {
                skipped++;
                continue;
            }
            ChunkShadowMutationPlan.unpack(plan.chunk(), entry.packedPos(), cursor);
            BlockPos immutablePos = cursor.immutable();
            BlockState previousState = getVisibleOrLoadedState(level, immutablePos, transactionView);
            String previousStateString = stableState(previousState);

            if (indexed.stage().needsSupportCheck()) {
                attachmentChecks++;
                if (!canPlaceNow(supportReader, immutablePos, entry.state())) {
                    if (shouldRetry(level, cursor, indexed.stage(), plan, transactionView)) {
                        if (retryEntries == null) {
                            retryEntries = new ArrayList<>();
                        }
                        retryEntries.add(entry);
                        retriedEntries++;
                        ShadowMutationTraceRegistry.recordRetried(plan, indexed.index(), immutablePos, entry,
                            indexed.stage().name().toLowerCase(), "support_wait");
                    } else {
                        skipped++;
                        ShadowMutationTraceRegistry.recordRejected(plan, indexed.index(), immutablePos, entry,
                            indexed.stage().name().toLowerCase(), "support_rejected", previousStateString);
                    }
                    continue;
                }
            }

            ApplyOutcome outcome = applyState(level, immutablePos, entry, transactionView);
            if (!outcome.applied()) {
                skipped++;
                ShadowMutationTraceRegistry.recordRejected(plan, indexed.index(), immutablePos, entry,
                    indexed.stage().name().toLowerCase(), outcome.reason(), previousStateString);
                continue;
            }
            if (transactionView != null) {
                transactionView.recordApplied(immutablePos, outcome.observedState());
            }
            applied++;
            if (indexed.stage() == Stage.GRAVITY) {
                gravityOps++;
            } else if (indexed.stage() == Stage.FLUID) {
                fluidOps++;
            } else if (indexed.stage() == Stage.ATTACHMENT) {
                attachmentOps++;
            }

            if (indexed.stage() == Stage.FLUID) {
                fluidTicks += scheduleFluidTick(level, cursor, entry.state());
            } else if (indexed.stage() == Stage.GRAVITY) {
                gravityTicks += scheduleGravityTick(level, cursor, entry.state());
            } else if (needsFluidReschedule(entry.state())) {
                fluidTicks += scheduleFluidTick(level, cursor, entry.state());
            }
            ShadowMutationTraceRegistry.recordApplied(
                plan,
                indexed.index(),
                immutablePos,
                entry,
                indexed.stage().name().toLowerCase(),
                previousStateString,
                stableState(outcome.observedState()),
                indexed.stage() == Stage.FLUID || needsFluidReschedule(entry.state()),
                indexed.stage() == Stage.GRAVITY,
                indexed.stage().needsSupportCheck(),
                !entry.state().equals(outcome.observedState())
            );
        }

        if (retryEntries == null || retryEntries.isEmpty()) {
            return new Result(applied, skipped, 0, gravityTicks, fluidTicks, attachmentChecks, gravityOps, fluidOps, attachmentOps, null);
        }

        ChunkShadowMutationPlan retryPlan = buildRetryPlan(plan, retryEntries);
        return new Result(applied, skipped, retriedEntries, gravityTicks, fluidTicks, attachmentChecks, gravityOps, fluidOps, attachmentOps, retryPlan);
    }

    private static List<IndexedEntry> classify(ChunkShadowMutationPlan plan) {
        ChunkShadowMutationPlan.Entry[] entries = plan.entries();
        if (entries == null || entries.length == 0) {
            return List.of();
        }
        if (plan.kind() == ChunkShadowMutationPlan.MutationKind.TREE_CAPTURE && entries.length > 1) {
            // Captured trees arrive as small plans. Stage buckets keep their
            // order without sorting every slice.
            @SuppressWarnings("unchecked")
            List<IndexedEntry>[] buckets = new List[Stage.values().length];
            for (int i = 0; i < entries.length; i++) {
                ChunkShadowMutationPlan.Entry entry = entries[i];
                if (entry == null || entry.state() == null) {
                    continue;
                }
                Stage stage = classify(entry.state());
                List<IndexedEntry> bucket = buckets[stage.order()];
                if (bucket == null) {
                    bucket = new ArrayList<>();
                    buckets[stage.order()] = bucket;
                }
                bucket.add(new IndexedEntry(i, stage, entry));
            }
            List<IndexedEntry> ordered = new ArrayList<>(entries.length);
            for (List<IndexedEntry> bucket : buckets) {
                if (bucket != null) {
                    ordered.addAll(bucket);
                }
            }
            return ordered;
        }
        List<IndexedEntry> ordered = new ArrayList<>(entries.length);
        for (int i = 0; i < entries.length; i++) {
            ChunkShadowMutationPlan.Entry entry = entries[i];
            if (entry == null || entry.state() == null) {
                continue;
            }
            ordered.add(new IndexedEntry(i, classify(entry.state()), entry));
        }
        ordered.sort(Comparator
            .comparingInt((IndexedEntry indexed) -> indexed.stage().order())
            .thenComparingInt(IndexedEntry::index));
        return ordered;
    }

    private static Stage classify(BlockState state) {
        if (state == null || state.isAir()) {
            return Stage.REMOVAL;
        }
        Block block = state.getBlock();
        FluidState fluidState = state.getFluidState();
        if (block instanceof FallingBlock) {
            return Stage.GRAVITY;
        }
        if (isAttachmentSensitive(block)) {
            return Stage.ATTACHMENT;
        }
        if (block instanceof LiquidBlock || (fluidState != null && !fluidState.isEmpty())) {
            return Stage.FLUID;
        }
        if (state.is(BlockTags.LOGS) || state.canOcclude()) {
            return Stage.CORE;
        }
        return Stage.STRUCTURE;
    }

    private static boolean isAttachmentSensitive(Block block) {
        return block instanceof VineBlock
            || block instanceof HangingRootsBlock
            || block instanceof ChainBlock
            || block instanceof GrowingPlantBodyBlock
            || block instanceof GrowingPlantHeadBlock;
    }

    private static boolean canPlaceNow(LevelReader reader,
                                       BlockPos pos,
                                       BlockState state) {
        try {
            return state.canSurvive(reader, pos);
        } catch (Throwable ignored) {
            return true;
        }
    }

    private static boolean shouldRetry(ServerLevel level,
                                       BlockPos pos,
                                       Stage stage,
                                       ChunkShadowMutationPlan plan,
                                       ShadowMutationTransactionView transactionView) {
        if (plan.retryCount() >= MAX_PLAN_RETRIES) {
            return false;
        }
        if (plan.isExpired(System.currentTimeMillis())) {
            return false;
        }
        if (stage == Stage.REMOVAL || stage == Stage.CORE || stage == Stage.STRUCTURE) {
            return false;
        }
        for (Direction direction : relevantSupportDirections(stage)) {
            BlockPos neighbor = pos.relative(direction);
            if (transactionView != null && transactionView.hasPlannedSupport(neighbor)) {
                continue;
            }
            int neighborChunkX = neighbor.getX() >> 4;
            int neighborChunkZ = neighbor.getZ() >> 4;
            if (neighborChunkX == plan.chunk().chunkX() && neighborChunkZ == plan.chunk().chunkZ()) {
                continue;
            }
            if (!level.hasChunk(neighborChunkX, neighborChunkZ)) {
                return true;
            }
        }
        return false;
    }

    private static Direction[] relevantSupportDirections(Stage stage) {
        return switch (stage) {
            case ATTACHMENT -> new Direction[]{Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};
            case FLUID -> new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};
            default -> Direction.values();
        };
    }

    private static ApplyOutcome applyState(ServerLevel level,
                                           BlockPos pos,
                                           ChunkShadowMutationPlan.Entry entry,
                                           ShadowMutationTransactionView transactionView) {
        try {
            if (level == null || pos == null || entry == null || entry.state() == null) {
                return ApplyOutcome.rejected("invalid");
            }
            LevelChunk chunk = getLoadedChunk(level, pos);
            if (chunk == null) {
                return ApplyOutcome.rejected("chunk_unloaded");
            }
            int flags = normalizeApplyFlags(entry.flags(), entry.state(), entry.markTreePlacement());
            // Remove a stale block entity before replacing its block state.
            clearExistingBlockEntity(level, chunk, pos);
            boolean changed = level.setBlock(pos, entry.state(), flags);
            if (!entry.state().hasBlockEntity()) {
                // Remove directly from the pinned chunk to avoid neighbour
                // lookups during the server tick.
                chunk.removeBlockEntity(pos);
            }
            BlockState actualState = getLoadedChunk(level, pos) == null ? null : getLoadedChunk(level, pos).getBlockState(pos);
            if (actualState == null) {
                return ApplyOutcome.rejected(changed ? "missing_post_state" : "set_failed");
            }
            LevelReader supportReader = transactionView == null
                ? ShadowMutationTransactionView.loadedOnlyReader(level)
                : transactionView.reader();
            if (!entry.state().equals(actualState)) {
                if (isAttachmentSensitive(entry.state().getBlock()) && canSurviveInWorld(supportReader, pos, entry.state())) {
                    level.setBlock(pos, entry.state(), normalizeAttachmentFlags(flags));
                    LevelChunk refreshed = getLoadedChunk(level, pos);
                    actualState = refreshed == null ? actualState : refreshed.getBlockState(pos);
                }
            }
            if (!entry.state().equals(actualState)) {
                return ApplyOutcome.rejected("post_apply_mismatch", actualState);
            }
            if (entry.state().hasBlockEntity() && chunk.getBlockEntity(pos) == null && entry.state().getBlock() instanceof EntityBlock entityBlock) {
                BlockEntity created = entityBlock.newBlockEntity(pos, entry.state());
                if (created != null) {
                    chunk.setBlockEntity(created);
                    actualState = chunk.getBlockState(pos);
                }
            }
            if (entry.markTreePlacement()) {
                ChunkPostProcessor.markTreePlacement(level, pos, entry.state());
            }
            return ApplyOutcome.applied(actualState);
        } catch (Throwable ignored) {
            return ApplyOutcome.rejected("exception");
        }
    }

    private static void clearExistingBlockEntity(ServerLevel level, LevelChunk chunk, BlockPos pos) {
        if (level == null || chunk == null || pos == null || chunk.getBlockEntity(pos) == null) {
            return;
        }
        try {
            // The chunk is known to be loaded.  Keep this cleanup local and
            // non-blocking rather than asking ServerLevel to resolve neighbours.
            chunk.removeBlockEntity(pos);
        } catch (Throwable ignored) {
        }
    }

    private static boolean canSurviveInWorld(LevelReader reader,
                                             BlockPos pos,
                                             BlockState state) {
        try {
            return state.canSurvive(reader, pos);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static BlockState getVisibleOrLoadedState(ServerLevel level,
                                                      BlockPos pos,
                                                      ShadowMutationTransactionView transactionView) {
        if (transactionView != null) {
            return transactionView.getVisibleOrActual(pos);
        }
        LevelChunk chunk = getLoadedChunk(level, pos);
        return chunk == null ? null : chunk.getBlockState(pos);
    }

    private static LevelChunk getLoadedChunk(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null) {
            return null;
        }
        return level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
    }

    private static int normalizeAttachmentFlags(int flags) {
        // The destination is loaded already; skip neighbour propagation to
        // avoid resolving another chunk from the server thread.
        return (flags | Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE)
            & ~(Block.UPDATE_NEIGHBORS | Block.UPDATE_INVISIBLE);
    }

    private static int normalizeApplyFlags(int flags, BlockState state, boolean capturedTreePlacement) {
        if (capturedTreePlacement) {
            // Deferred trees are replayed only after their destination chunks
            // are already loaded.  Calling UPDATE_NEIGHBORS here makes a large
            // BOP tree synchronously walk Minecraft's neighbour updater, which
            // can request chunks and stall the integrated server for seconds.
            // Support-sensitive decorations are validated above and get the
            // known-shape flag. Logs and leaves need neither a redstone cascade nor
            // a cross-chunk update while being restored.
            return (flags | Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE)
                & ~(Block.UPDATE_NEIGHBORS | Block.UPDATE_INVISIBLE);
        }
        int normalized = flags | Block.UPDATE_CLIENTS;
        if (ENABLE_NEIGHBOR_UPDATES) {
            normalized |= Block.UPDATE_NEIGHBORS;
        } else {
            normalized &= ~Block.UPDATE_NEIGHBORS;
        }
        if (state != null && (state.getBlock() instanceof LiquidBlock || !state.getFluidState().isEmpty())) {
            normalized |= Block.UPDATE_KNOWN_SHAPE;
        }
        if (state != null && (state.is(BlockTags.LOGS) || state.is(BlockTags.LEAVES) || isAttachmentSensitive(state.getBlock()))) {
            normalized |= Block.UPDATE_KNOWN_SHAPE;
        }
        return normalized & ~Block.UPDATE_INVISIBLE;
    }

    private static String stableState(BlockState state) {
        return state == null ? "<null>" : state.toString();
    }

    private static int scheduleGravityTick(ServerLevel level, BlockPos pos, BlockState state) {
        if (state == null || !(state.getBlock() instanceof FallingBlock falling)) {
            return 0;
        }
        try {
            level.scheduleTick(pos, falling, 2);
            return 1;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static boolean needsFluidReschedule(BlockState state) {
        FluidState fluidState = state == null ? null : state.getFluidState();
        return fluidState != null && !fluidState.isEmpty();
    }

    private static int scheduleFluidTick(ServerLevel level, BlockPos pos, BlockState state) {
        FluidState fluidState = state == null ? null : state.getFluidState();
        if (fluidState == null || fluidState.isEmpty()) {
            return 0;
        }
        try {
            level.scheduleTick(pos, fluidState.getType(), fluidState.getType().getTickDelay(level));
            return 1;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static ChunkShadowMutationPlan buildRetryPlan(ChunkShadowMutationPlan plan,
                                                          List<ChunkShadowMutationPlan.Entry> entries) {
        if (plan == null || plan.chunk() == null || entries == null || entries.isEmpty()) {
            return null;
        }
        long createdAtMs = plan.createdAtMs() > 0L ? plan.createdAtMs() : System.currentTimeMillis();
        long expiresAtMs = plan.expiresAtMs() > 0L ? plan.expiresAtMs() : (createdAtMs + MAX_PLAN_AGE_MS);
        if (System.currentTimeMillis() >= expiresAtMs) {
            return null;
        }
        ChunkShadowMutationPlan.Builder builder = ChunkShadowMutationPlan.builder(plan.chunk(), plan.scope())
            .transaction(plan.transactionId(), createdAtMs, plan.kind())
            .retryState(plan.retryCount() + 1, expiresAtMs);
        for (ChunkShadowMutationPlan.Entry entry : entries) {
            if (entry != null && entry.state() != null) {
                BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
                ChunkShadowMutationPlan.unpack(plan.chunk(), entry.packedPos(), pos);
                builder.add(pos, entry.state(), entry.flags(), entry.markTreePlacement());
            }
        }
        return builder.build();
    }

    private enum Stage {
        REMOVAL(0, false),
        CORE(1, false),
        STRUCTURE(2, false),
        ATTACHMENT(3, true),
        FLUID(4, true),
        GRAVITY(5, true);

        private final int order;
        private final boolean needsSupportCheck;

        Stage(int order, boolean needsSupportCheck) {
            this.order = order;
            this.needsSupportCheck = needsSupportCheck;
        }

        int order() {
            return order;
        }

        boolean needsSupportCheck() {
            return needsSupportCheck;
        }
    }

    private record IndexedEntry(int index, Stage stage, ChunkShadowMutationPlan.Entry entry) {
    }

    private record ApplyOutcome(boolean applied, BlockState observedState, String reason) {
        private static ApplyOutcome applied(BlockState observedState) {
            return new ApplyOutcome(true, observedState, null);
        }

        private static ApplyOutcome rejected(String reason) {
            return new ApplyOutcome(false, null, reason);
        }

        private static ApplyOutcome rejected(String reason, BlockState observedState) {
            return new ApplyOutcome(false, observedState, reason);
        }
    }
}
