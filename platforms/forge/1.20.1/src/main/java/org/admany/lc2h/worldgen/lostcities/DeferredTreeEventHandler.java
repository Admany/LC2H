package org.admany.lc2h.worldgen.lostcities;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.TreeConfiguration;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.admany.lc2h.LC2H;
import org.admany.lc2h.util.chunk.ChunkPostProcessor;
import org.admany.lc2h.worldgen.apply.ChunkShadowMutationPlan;
import org.admany.lc2h.worldgen.apply.ShadowBlockMutationApplier;
import mcjty.lostcities.setup.Registration;
import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.IDimensionInfo;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;

@Mod.EventBusSubscriber(modid = LC2H.MODID)
public final class DeferredTreeEventHandler {

    private static final int TREE_CAPTURE_SET_FLAGS = Block.UPDATE_NEIGHBORS | Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
    private static final int MAX_READY_ENQUEUES_PER_CHUNK_LOAD = 8;
    private static final int MAX_READY_SWEEP_PROMOTIONS_PER_TICK = Math.max(8,
        Integer.getInteger("lc2h.treeReplay.maxReadySweepPromotionsPerTick", 32));
    // Keep replay work bounded per tick.
    private static final int MAX_REPLAYS_PER_SERVER_TICK = Math.max(1,
        Integer.getInteger("lc2h.treeReplay.maxReplaysPerTick", 6));
    private static final int MAX_REPLAY_BLOCKS_PER_SERVER_TICK = Math.max(128,
        Integer.getInteger("lc2h.treeReplay.maxBlocksPerTick", 4096));
    // Split large captured trees into small server-thread batches.
    private static final int MAX_CAPTURED_TREE_PLAN_BLOCKS = Math.max(32,
        Integer.getInteger("lc2h.treeReplay.maxPlanBlocks", 384));
    private static final int MAX_CAPTURED_TREE_BLOCKS = Math.max(4096,
        Integer.getInteger("lc2h.treeReplay.maxCapturedTreeBlocks", 16_384));
    private static final int MAX_CAPTURED_TREE_HEIGHT = Math.max(96,
        Integer.getInteger("lc2h.treeReplay.maxCapturedTreeHeight", 192));
    private static final boolean ENABLE_LEGACY_TREE_REPLAY_FALLBACK =
        Boolean.parseBoolean(System.getProperty("lc2h.treeReplay.legacyFallback", "false"));
    private static final AtomicInteger CHUNK_LOAD_REPLAY_SUPPRESSIONS = new AtomicInteger(0);
    private static final AtomicInteger SERVER_TICK_REPLAY_SUPPRESSIONS = new AtomicInteger(0);
    private static final AtomicLong CAPTURED_TREES_APPLIED = new AtomicLong();
    private static final AtomicLong CAPTURED_TREES_DROPPED_SHAPE = new AtomicLong();
    private static final AtomicLong CAPTURED_TREES_DROPPED_ROOT = new AtomicLong();
    private static final AtomicLong CAPTURED_TREES_DROPPED_OVERLAP = new AtomicLong();
    private static final AtomicLong CAPTURED_BLOCKS_QUEUED = new AtomicLong();
    private static final AtomicLong LEGACY_REPLAY_EXECUTED = new AtomicLong();
    private static final AtomicLong LEGACY_REPLAY_DISABLED_DROPS = new AtomicLong();

    private DeferredTreeEventHandler() {
    }

    public static ReplaySuppression suppressChunkLoadReplay() {
        CHUNK_LOAD_REPLAY_SUPPRESSIONS.incrementAndGet();
        return ReplaySuppression.chunkLoadOnly();
    }

    public static ReplaySuppression holdReplaySuppression() {
        CHUNK_LOAD_REPLAY_SUPPRESSIONS.incrementAndGet();
        SERVER_TICK_REPLAY_SUPPRESSIONS.incrementAndGet();
        return ReplaySuppression.fullReplay();
    }

    public static int forceReplayReadyForDebug(MinecraftServer server, int maxReplays, int maxBlocks) {
        if (!DeferredTreeQueue.isDeferredReplayEnabled() || server == null) {
            return 0;
        }
        if (SERVER_TICK_REPLAY_SUPPRESSIONS.get() > 0) {
            return 0;
        }
        return drainReadyTrees(server.getAllLevels(), Math.max(1, maxReplays), Math.max(128, maxBlocks), null);
    }

    public static int forceReplayReadyForDebug(MinecraftServer server,
                                               int maxReplays,
                                               int maxBlocks,
                                               Set<Long> interestChunks) {
        if (!DeferredTreeQueue.isDeferredReplayEnabled() || server == null) {
            return 0;
        }
        if (SERVER_TICK_REPLAY_SUPPRESSIONS.get() > 0) {
            return 0;
        }
        return drainReadyTrees(server.getAllLevels(),
            Math.max(1, maxReplays),
            Math.max(128, maxBlocks),
            interestChunks == null || interestChunks.isEmpty() ? null : Set.copyOf(interestChunks));
    }

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!DeferredTreeQueue.isDeferredReplayEnabled()) {
            return;
        }
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        if (event.getChunk() == null) {
            return;
        }
        if (CHUNK_LOAD_REPLAY_SUPPRESSIONS.get() > 0) {
            return;
        }

        var dim = level.dimension();
        DeferredTreeQueue.loadFromDisk(level);
        if (DeferredTreeQueue.pendingCount(level) == 0) {
            return;
        }

        List<DeferredTreeQueue.PendingTree> ready = DeferredTreeQueue.drainReady(
            dim,
            level,
            event.getChunk().getPos().x,
            event.getChunk().getPos().z
        );
        if (ready.isEmpty()) {
            return;
        }

        if (ready.size() > MAX_READY_ENQUEUES_PER_CHUNK_LOAD) {
            DeferredTreeQueue.enqueueReady(dim, ready.subList(0, MAX_READY_ENQUEUES_PER_CHUNK_LOAD));
            for (int i = MAX_READY_ENQUEUES_PER_CHUNK_LOAD; i < ready.size(); i++) {
                DeferredTreeQueue.enqueue(dim, ready.get(i));
            }
            return;
        }

        DeferredTreeQueue.enqueueReady(dim, ready);
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (!DeferredTreeQueue.isDeferredReplayEnabled()) {
            return;
        }
        if (event.phase != TickEvent.Phase.END || event.getServer() == null) {
            return;
        }
        if (SERVER_TICK_REPLAY_SUPPRESSIONS.get() > 0) {
            return;
        }
        drainReadyTrees(event.getServer().getAllLevels(), MAX_REPLAYS_PER_SERVER_TICK, MAX_REPLAY_BLOCKS_PER_SERVER_TICK, null);
    }

    private static int drainReadyTrees(Iterable<ServerLevel> levels,
                                       int maxReplays,
                                       int maxReplayBlocks,
                                       Set<Long> interestChunks) {
        int replayed = 0;
        int queuedBlocks = 0;
        for (ServerLevel level : levels) {
            if (level == null) {
                continue;
            }
            if (replayed >= maxReplays || queuedBlocks >= maxReplayBlocks) {
                return replayed;
            }
            ChunkGenerator generator = level.getChunkSource().getGenerator();
            if (generator == null) {
                continue;
            }

            DeferredTreeQueue.promoteReadyLoaded(level, MAX_READY_SWEEP_PROMOTIONS_PER_TICK);

            List<DeferredTreeQueue.PendingTree> ready = interestChunks == null
                ? DeferredTreeQueue.pollReady(level, maxReplays - replayed)
                : DeferredTreeQueue.pollReady(level,
                    maxReplays - replayed,
                    interestChunks,
                    Math.max(MAX_READY_SWEEP_PROMOTIONS_PER_TICK, interestChunks.size() * 8));
            if (ready.isEmpty()) {
                continue;
            }

            for (DeferredTreeQueue.PendingTree pending : ready) {
                int queued = replayTree(level, generator, pending);
                replayed++;
                queuedBlocks += Math.max(0, queued);
                if (replayed >= maxReplays || queuedBlocks >= maxReplayBlocks) {
                    return replayed;
                }
            }
        }
        return replayed;
    }

    private static int replayTree(ServerLevel level, ChunkGenerator generator, DeferredTreeQueue.PendingTree pending) {
        if (level == null || generator == null || pending == null) {
            return 0;
        }
        if (pending.pos() == null) {
            DeferredTreeChunkRetainer.release(pending);
            return 0;
        }
        if (!level.isLoaded(pending.pos())) {
            // Retry later if this position unloaded between readiness check and replay.
            DeferredTreeQueue.requeueAndRelease(pending);
            return 0;
        }

        if (pending.hasCapturedBlocks()) {
            return applyCapturedTree(level, pending);
        }

        if (!pending.allowLegacyReplay()) {
            return 0;
        }

        if (!ENABLE_LEGACY_TREE_REPLAY_FALLBACK) {
            LEGACY_REPLAY_DISABLED_DROPS.incrementAndGet();
            TreeCompatTracker.recordFallback(TreeCompatTracker.FallbackReason.MISSING_RUNTIME_DEPENDENCY);
            return 0;
        }

        if (pending.config() == null || pending.feature() == null) {
            return 0;
        }

        try {
            RandomSource random = RandomSource.create();
            random.setSeed(level.getSeed() ^ pending.pos().asLong());

            FeaturePlaceContext<TreeConfiguration> replayContext = new FeaturePlaceContext<>(
                Optional.empty(),
                level,
                generator,
                random,
                pending.pos(),
                pending.config()
            );

            DeferredTreeQueue.runReplay(() -> {
                try {
                    LEGACY_REPLAY_EXECUTED.incrementAndGet();
                    pending.feature().place(replayContext);
                } catch (Throwable t) {
                    LC2H.LOGGER.debug("[LC2H] Deferred tree replay failed at {}: {}", pending.pos(), t.toString());
                }
            });
        } catch (Throwable t) {
            LC2H.LOGGER.debug("[LC2H] Deferred tree replay setup failed at {}: {}", pending.pos(), t.toString());
        }
        DeferredTreeChunkRetainer.release(pending);
        return 0;
    }

    private static int applyCapturedTree(ServerLevel level, DeferredTreeQueue.PendingTree pending) {
        CapturedTreeReplayPlan replayPlan = CapturedTreeReplayPlan.from(pending);
        if (!replayPlan.validShape()) {
            DeferredTreeChunkRetainer.release(pending);
            CAPTURED_TREES_DROPPED_SHAPE.incrementAndGet();
            TreeCompatTracker.recordFallback(TreeCompatTracker.FallbackReason.INVALID_CAPTURE);
            return 0;
        }
        if (!hasStableTreeRoot(level, pending, replayPlan)) {
            DeferredTreeChunkRetainer.release(pending);
            CAPTURED_TREES_DROPPED_ROOT.incrementAndGet();
            TreeCompatTracker.recordFallback(TreeCompatTracker.FallbackReason.ROOT_REJECTED);
            return 0;
        }
        if (!hasLoadedConflictWindow(level, pending, replayPlan)) {
            // Wait until every touched chunk is loaded before replaying a tree.
            DeferredTreeQueue.requeueAndRelease(pending);
            TreeCompatTracker.recordFallback(TreeCompatTracker.FallbackReason.UNLOADED_DESTINATION);
            return 0;
        }
        if (hasConflictingTreeAtTarget(level, pending, replayPlan)
            || hasProtectedCityConflict(level, pending)) {
            DeferredTreeChunkRetainer.release(pending);
            CAPTURED_TREES_DROPPED_OVERLAP.incrementAndGet();
            TreeCompatTracker.recordFallback(TreeCompatTracker.FallbackReason.OVERLAP_REJECTED);
            return 0;
        }
        // The applier budgets each destination chunk, so a replay can span ticks.
        LinkedHashMap<ChunkCoord, ChunkShadowMutationPlan.Builder> plans = new LinkedHashMap<>();
        int queued = 0;
        for (DeferredTreeQueue.CapturedBlock block : pending.blocks()) {
            if (block == null || block.pos() == null || block.state() == null) {
                continue;
            }
            if (shouldApplyCapturedBlock(level, block.pos(), block.state(), replayPlan)) {
                ChunkCoord chunk = new ChunkCoord(level.dimension(), block.pos().getX() >> 4, block.pos().getZ() >> 4);
                plans.computeIfAbsent(chunk, key -> ChunkShadowMutationPlan.builder(level, key))
                    .transaction(pending.transactionId(), pending.createdAtMs(), ChunkShadowMutationPlan.MutationKind.TREE_CAPTURE)
                    .add(block.pos(), block.state(), TREE_CAPTURE_SET_FLAGS, true);
                queued++;
            }
        }

        if (queued == 0) {
            DeferredTreeChunkRetainer.release(pending);
            return 0;
        }
        CAPTURED_TREES_APPLIED.incrementAndGet();
        CAPTURED_BLOCKS_QUEUED.addAndGet(queued);
        TreeCompatTracker.recordApplied(pending.source());
        for (ChunkShadowMutationPlan.Builder builder : plans.values()) {
            try {
                // Queue bounded slices so one large tree cannot monopolize a tick.
                ChunkShadowMutationPlan plan = builder.build().withoutTransaction();
                for (int offset = 0; offset < plan.size(); offset += MAX_CAPTURED_TREE_PLAN_BLOCKS) {
                    ShadowBlockMutationApplier.enqueueDeferred(plan.slice(offset, MAX_CAPTURED_TREE_PLAN_BLOCKS));
                }
            } catch (Throwable t) {
                LC2H.LOGGER.debug("[LC2H] Deferred captured tree block queue failed: {}", t.toString());
            }
        }
        DeferredTreeChunkRetainer.release(pending);
        return queued;
    }

    private static BlockState getLoadedState(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null) {
            return Blocks.AIR.defaultBlockState();
        }
        LevelChunk chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        return chunk == null ? Blocks.AIR.defaultBlockState() : chunk.getBlockState(pos);
    }

    private static boolean hasLoadedConflictWindow(ServerLevel level,
                                                   DeferredTreeQueue.PendingTree pending,
                                                   CapturedTreeReplayPlan replayPlan) {
        if (level == null || pending == null || pending.pos() == null || replayPlan == null) {
            return false;
        }
        // Recheck the small touched-chunk set before walking every captured block.
        long[] touchedChunks = pending.touchedChunks();
        if (touchedChunks == null || touchedChunks.length == 0) {
            return false;
        }
        for (long chunkKey : touchedChunks) {
            if (level.getChunkSource().getChunkNow(ChunkPos.getX(chunkKey), ChunkPos.getZ(chunkKey)) == null) {
                return false;
            }
        }
        return true;
    }

    private static boolean shouldApplyCapturedBlock(ServerLevel level,
                                                    BlockPos pos,
                                                    BlockState planned,
                                                    CapturedTreeReplayPlan replayPlan) {
        if (level == null || pos == null || planned == null) {
            return false;
        }

        boolean plannedLog = isTreeLogLike(planned);
        boolean plannedLeaves = isTreeLeafLike(planned);
        // Captured logs and leaves are already part of the feature. Only other
        // blocks need a proximity check to a captured log.
        if (!plannedLog && !plannedLeaves && !replayPlan.nearLog(pos, 6, 4)) {
            return false;
        }

        BlockState existing = getLoadedState(level, pos);
        if (existing.equals(planned)) {
            return false;
        }
        if (existing.isAir() || existing.canBeReplaced()) {
            return true;
        }

        boolean existingTreeish = isTreeBlockLike(existing);
        if (existingTreeish) {
            if (plannedLog) {
                return isTreeLeafLike(existing);
            }
            if (plannedLeaves) {
                return isTreeLeafLike(existing);
            }
            return false;
        }

        // Preserve ordinary terrain in the source chunk. Only protect blocks
        // owned by a Lost Cities structure.
        return !isLostCityOwnedChunk(level, pos);
    }

    /** Match common wood and foliage names for mods without vanilla tags. */
    private static boolean isTreeLogLike(BlockState state) {
        if (state == null) {
            return false;
        }
        if (state.is(BlockTags.LOGS)) {
            return true;
        }
        String path = registryPath(state);
        return path.contains("log")
            || path.contains("trunk")
            || path.contains("stem")
            || path.contains("branch")
            || path.contains("bark")
            || path.endsWith("_wood")
            || path.equals("wood");
    }

    private static boolean isTreeLeafLike(BlockState state) {
        if (state == null) {
            return false;
        }
        if (state.is(BlockTags.LEAVES)) {
            return true;
        }
        String path = registryPath(state);
        return path.contains("leaves")
            || path.contains("leaf")
            || path.contains("foliage")
            || path.contains("needles")
            || path.contains("frond");
    }

    private static boolean isTreeBlockLike(BlockState state) {
        return isTreeLogLike(state) || isTreeLeafLike(state);
    }

    private static String registryPath(BlockState state) {
        try {
            ResourceLocation key = ForgeRegistries.BLOCKS.getKey(state.getBlock());
            return key == null ? "" : key.getPath().toLowerCase(java.util.Locale.ROOT);
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static boolean hasProtectedCityConflict(ServerLevel level,
                                                     DeferredTreeQueue.PendingTree pending) {
        if (level == null || pending == null || pending.blocks() == null) {
            return false;
        }
        for (DeferredTreeQueue.CapturedBlock block : pending.blocks()) {
            if (block == null || block.pos() == null || block.state() == null
                || !isTreeLogLike(block.state())) {
                continue;
            }
            BlockState existing = getLoadedState(level, block.pos());
            if (existing == null || existing.equals(block.state())
                || existing.isAir() || existing.canBeReplaced() || isTreeBlockLike(existing)) {
                continue;
            }
            if (isLostCityOwnedChunk(level, block.pos())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isLostCityOwnedChunk(ServerLevel level, BlockPos pos) {
        try {
            IDimensionInfo dimInfo = Registration.LOSTCITY_FEATURE.get().getDimensionInfo(level);
            if (dimInfo == null) {
                return true;
            }
            ResourceKey<Level> dim = dimInfo.getType();
            if (dim == null) {
                return true;
            }
            return LostCityTreeSafety.isUnsafeChunk(dimInfo, dim, pos.getX() >> 4, pos.getZ() >> 4);
        } catch (Throwable ignored) {
            return true;
        }
    }

    private static boolean hasStableTreeRoot(ServerLevel level,
                                             DeferredTreeQueue.PendingTree pending,
                                             CapturedTreeReplayPlan replayPlan) {
        if (level == null || pending == null || pending.pos() == null) {
            return false;
        }
        BlockPos rootPos = pending.pos();
        if (!level.isLoaded(rootPos) || !level.isLoaded(rootPos.below())) {
            return false;
        }
        if (!replayPlan.rootLogPresent()) {
            return false;
        }
        BlockState below = getLoadedState(level, rootPos.below());
        if (below == null || below.isAir() || below.canBeReplaced()) {
            return false;
        }
        if (isTreeBlockLike(below)) {
            return false;
        }
        return below.is(BlockTags.DIRT) || below.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
    }

    public static String capturedTreeDiagnostics() {
        return "enabled=" + DeferredTreeQueue.isDeferredReplayEnabled()
            + ", authoritativeShadow=true"
            + ", pending=" + DeferredTreeQueue.pendingCountAll()
            + ", ready=" + DeferredTreeQueue.readyCountAll()
            + ", applied=" + CAPTURED_TREES_APPLIED.get()
            + ", droppedShape=" + CAPTURED_TREES_DROPPED_SHAPE.get()
            + ", droppedRoot=" + CAPTURED_TREES_DROPPED_ROOT.get()
            + ", droppedOverlap=" + CAPTURED_TREES_DROPPED_OVERLAP.get()
            + ", blocksQueued=" + CAPTURED_BLOCKS_QUEUED.get()
            + ", legacyFallbackEnabled=" + ENABLE_LEGACY_TREE_REPLAY_FALLBACK
            + ", legacyReplayExecuted=" + LEGACY_REPLAY_EXECUTED.get()
            + ", legacyReplayDisabledDrops=" + LEGACY_REPLAY_DISABLED_DROPS.get()
            + ", suppressions[chunkLoad=" + CHUNK_LOAD_REPLAY_SUPPRESSIONS.get()
            + ", serverTick=" + SERVER_TICK_REPLAY_SUPPRESSIONS.get() + "]"
            + ", todoGuard[" + LostCityTodoTreeGuard.diagnostics() + "]"
            + ", queue[" + DeferredTreeQueue.diagnostics() + "]"
            + ", compat=" + TreeCompatTracker.compactSummary();
    }

    public static String capturedTreeDiagnostics(ServerLevel level) {
        if (level == null) {
            return capturedTreeDiagnostics();
        }
        return "enabled=" + DeferredTreeQueue.isDeferredReplayEnabled()
            + ", authoritativeShadow=true"
            + ", pending=" + DeferredTreeQueue.pendingCount(level)
            + ", ready=" + DeferredTreeQueue.readyCount(level)
            + ", applied=" + CAPTURED_TREES_APPLIED.get()
            + ", droppedShape=" + CAPTURED_TREES_DROPPED_SHAPE.get()
            + ", droppedRoot=" + CAPTURED_TREES_DROPPED_ROOT.get()
            + ", droppedOverlap=" + CAPTURED_TREES_DROPPED_OVERLAP.get()
            + ", blocksQueued=" + CAPTURED_BLOCKS_QUEUED.get()
            + ", legacyFallbackEnabled=" + ENABLE_LEGACY_TREE_REPLAY_FALLBACK
            + ", legacyReplayExecuted=" + LEGACY_REPLAY_EXECUTED.get()
            + ", legacyReplayDisabledDrops=" + LEGACY_REPLAY_DISABLED_DROPS.get()
            + ", suppressions[chunkLoad=" + CHUNK_LOAD_REPLAY_SUPPRESSIONS.get()
            + ", serverTick=" + SERVER_TICK_REPLAY_SUPPRESSIONS.get() + "]"
            + ", todoGuard[" + LostCityTodoTreeGuard.diagnostics() + "]"
            + ", queue[" + DeferredTreeQueue.diagnostics(level) + "]"
            + ", compat=" + TreeCompatTracker.compactSummary();
    }

    public static final class ReplaySuppression implements AutoCloseable {
        private final boolean releaseChunkLoad;
        private final boolean releaseServerTick;
        private boolean closed;

        private ReplaySuppression(boolean releaseChunkLoad, boolean releaseServerTick) {
            this.releaseChunkLoad = releaseChunkLoad;
            this.releaseServerTick = releaseServerTick;
        }

        private static ReplaySuppression chunkLoadOnly() {
            return new ReplaySuppression(true, false);
        }

        private static ReplaySuppression fullReplay() {
            return new ReplaySuppression(true, true);
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (releaseChunkLoad) {
                CHUNK_LOAD_REPLAY_SUPPRESSIONS.updateAndGet(current -> Math.max(0, current - 1));
            }
            if (releaseServerTick) {
                SERVER_TICK_REPLAY_SUPPRESSIONS.updateAndGet(current -> Math.max(0, current - 1));
            }
        }
    }

    private static boolean hasConflictingTreeAtTarget(ServerLevel level,
                                                      DeferredTreeQueue.PendingTree pending,
                                                      CapturedTreeReplayPlan replayPlan) {
        if (level == null || pending == null || pending.pos() == null || replayPlan == null) {
            return false;
        }
        for (DeferredTreeQueue.CapturedBlock block : pending.blocks()) {
            if (block == null || block.pos() == null || block.state() == null) {
                continue;
            }
            if (!isTreeLogLike(block.state())) {
                continue;
            }
            BlockState existing = getLoadedState(level, block.pos());
            if (existing != null
                && isTreeLogLike(existing)
                && !replayPlan.containsLog(block.pos())) {
                return true;
            }
        }
        // A second tree's TRUNK sharing our footprint is a real conflict.
        // Neighboring *foliage* is not: canopies overlap constantly in a
        // dense forest, and rejecting on that dropped whole legitimate trees
        // (visible as gaps/stumps near city borders). Restrict the check to
        // foreign logs intruding into this tree's own trunk column.
        BlockPos root = pending.pos();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = root.getX() - 1; x <= root.getX() + 1; x++) {
            for (int z = root.getZ() - 1; z <= root.getZ() + 1; z++) {
                for (int y = root.getY(); y <= root.getY() + 6; y++) {
                    cursor.set(x, y, z);
                    if (replayPlan.containsAny(cursor)) {
                        continue;
                    }
                    BlockState existing = getLoadedState(level, cursor);
                    if (existing != null && isTreeLogLike(existing)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static final class CapturedTreeReplayPlan {
        private final BlockPos origin;
        private final Set<Long> logs;
        private final Set<Long> captured;
        private final int blockCount;
        private final int logCount;
        private final int minLogY;
        private final int maxY;
        private final boolean rootLogPresent;

        private CapturedTreeReplayPlan(BlockPos origin,
                                       Set<Long> logs,
                                       Set<Long> captured,
                                       int blockCount,
                                       int logCount,
                                       int minLogY,
                                       int maxY,
                                       boolean rootLogPresent) {
            this.origin = origin;
            this.logs = logs;
            this.captured = captured;
            this.blockCount = blockCount;
            this.logCount = logCount;
            this.minLogY = minLogY;
            this.maxY = maxY;
            this.rootLogPresent = rootLogPresent;
        }

        private static CapturedTreeReplayPlan from(DeferredTreeQueue.PendingTree pending) {
            BlockPos origin = pending == null ? null : pending.pos();
            List<DeferredTreeQueue.CapturedBlock> blocks = pending == null ? null : pending.blocks();
            if (origin == null || blocks == null || blocks.isEmpty()) {
                return new CapturedTreeReplayPlan(origin, Set.of(), Set.of(), 0, 0, Integer.MAX_VALUE, Integer.MIN_VALUE, false);
            }

            Set<Long> logs = new HashSet<>();
            Set<Long> captured = new HashSet<>(blocks.size());
            int logCount = 0;
            int minLogY = Integer.MAX_VALUE;
            int maxY = Integer.MIN_VALUE;
            for (DeferredTreeQueue.CapturedBlock block : blocks) {
                if (block == null || block.pos() == null || block.state() == null) {
                    continue;
                }
                captured.add(block.pos().asLong());
                maxY = Math.max(maxY, block.pos().getY());
                if (isTreeLogLike(block.state())) {
                    logs.add(block.pos().asLong());
                    logCount++;
                    minLogY = Math.min(minLogY, block.pos().getY());
                }
            }

            boolean rootLog = logs.contains(origin.asLong()) || logs.contains(origin.above().asLong());
            if (!rootLog) {
                for (long packed : logs) {
                    BlockPos logPos = BlockPos.of(packed);
                    if (Math.abs(logPos.getX() - origin.getX()) <= 1
                        && Math.abs(logPos.getZ() - origin.getZ()) <= 1
                        && logPos.getY() >= origin.getY()
                        && logPos.getY() <= origin.getY() + 1) {
                        rootLog = true;
                        break;
                    }
                }
            }

            return new CapturedTreeReplayPlan(origin, logs, captured, blocks.size(), logCount, minLogY, maxY, rootLog);
        }

        private boolean validShape() {
            if (origin == null || blockCount <= 0 || blockCount > MAX_CAPTURED_TREE_BLOCKS) {
                return false;
            }
            if (logCount <= 0 || !rootLogPresent) {
                return false;
            }
            if (minLogY < origin.getY() || minLogY > origin.getY() + 2) {
                return false;
            }
            return maxY >= minLogY && (maxY - minLogY) <= MAX_CAPTURED_TREE_HEIGHT;
        }

        private boolean rootLogPresent() {
            return rootLogPresent;
        }

        private boolean containsLog(BlockPos pos) {
            return pos != null && logs.contains(pos.asLong());
        }

        private boolean containsAny(BlockPos pos) {
            return pos != null && captured.contains(pos.asLong());
        }

        private boolean attachedLog(BlockPos pos) {
            if (pos == null || !logs.contains(pos.asLong())) {
                return false;
            }
            if (pos.equals(origin) || pos.equals(origin.above())) {
                return true;
            }
            return nearLog(pos, 1, 1);
        }

        private boolean attachedLeaf(BlockPos pos) {
            return nearLog(pos, 6, 4);
        }

        private boolean nearLog(BlockPos pos, int horizontalRadius, int verticalRadius) {
            if (pos == null || logs.isEmpty()) {
                return false;
            }
            BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
            int minY = Math.max(origin.getY(), pos.getY() - verticalRadius);
            int maxY = pos.getY() + verticalRadius;
            for (int x = pos.getX() - horizontalRadius; x <= pos.getX() + horizontalRadius; x++) {
                for (int z = pos.getZ() - horizontalRadius; z <= pos.getZ() + horizontalRadius; z++) {
                    for (int y = minY; y <= maxY; y++) {
                        cursor.set(x, y, z);
                        long packed = cursor.asLong();
                        if (packed != pos.asLong() && logs.contains(packed)) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            org.admany.lc2h.worldgen.gpu.TerrainCorrectionGpuPipeline.clearRegions();
            DeferredTreeQueue.flushToDisk(level);
            DeferredTreeQueue.clearDimension(level);
            LostCityTodoTreeGuard.clearDimension(level);
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        if (event == null || event.getServer() == null) {
            return;
        }
        for (ServerLevel level : event.getServer().getAllLevels()) {
            if (level != null) {
                DeferredTreeQueue.flushToDisk(level);
            }
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        org.admany.lc2h.worldgen.gpu.TerrainCorrectionGpuPipeline.clearRegions();
        DeferredTreeQueue.clearAll();
        LostCityTodoTreeGuard.clearAll();
    }
}
