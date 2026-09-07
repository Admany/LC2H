package org.admany.lc2h.worldgen.lostcities;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.feature.TreeFeature;
import net.minecraft.world.level.levelgen.feature.configurations.TreeConfiguration;
import org.admany.lc2h.worldgen.scope.WorldGenScope;
import org.admany.lc2h.worldgen.seams.SeamOwnershipJournal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class DeferredTreeQueue {

    private static final Map<WorldGenScope.DimensionKey, DimensionQueue> QUEUES = new ConcurrentHashMap<>();
    private static final Map<WorldGenScope.DimensionKey, ConcurrentLinkedQueue<PendingTree>> READY_QUEUES = new ConcurrentHashMap<>();
    private static final Set<WorldGenScope.DimensionKey> DIRTY_DIMENSIONS = ConcurrentHashMap.newKeySet();
    private static final Set<WorldGenScope.DimensionKey> LOADED_FROM_DISK = ConcurrentHashMap.newKeySet();
    private static final ThreadLocal<Boolean> REPLAYING = ThreadLocal.withInitial(() -> false);
    private static final int MAX_CHECKS_PER_DRAIN = 64;
    private static final int MAX_CHECKS_PER_SWEEP = Math.max(8,
        Integer.getInteger("lc2h.treeReplay.maxChecksPerSweep", 128));
    private static final int MAX_REQUEUE_ATTEMPTS = Math.max(1,
        Integer.getInteger("lc2h.treeReplay.maxRequeueAttempts", 8));
    private static final long MAX_PENDING_AGE_MS = Math.max(5_000L,
        Long.getLong("lc2h.treeReplay.maxPendingAgeMs", 120_000L));
    private static final AtomicLong ENQUEUED = new AtomicLong();
    private static final AtomicLong REPLACED = new AtomicLong();
    private static final AtomicLong READY_ENQUEUED = new AtomicLong();
    private static final AtomicLong DRAIN_CHECKS = new AtomicLong();
    private static final AtomicLong DRAIN_READY = new AtomicLong();
    private static final AtomicLong READY_POLLED = new AtomicLong();
    private static final AtomicLong REQUEUED = new AtomicLong();
    private static final AtomicLong SWEEP_CHECKS = new AtomicLong();
    private static final AtomicLong SWEEP_READY = new AtomicLong();
    private static final AtomicLong STALE_SCOPE_DROPS = new AtomicLong();
    private static final AtomicLong EXPIRED_DROPS = new AtomicLong();
    private static final AtomicLong RETRY_LIMIT_DROPS = new AtomicLong();
    private static final AtomicLong LEGACY_FALLBACK_READY = new AtomicLong();

    private DeferredTreeQueue() {
    }

    private static final class DimensionQueue {
        private final ConcurrentHashMap<Long, List<PendingTree>> buckets = new ConcurrentHashMap<>();
        private final AtomicInteger size = new AtomicInteger();
    }

    public record CapturedBlock(
        BlockPos pos,
        BlockState state
    ) {
    }

    public enum ReplayMode {
        CAPTURED_MUTATION,
        LEGACY_REPLAY
    }

    public record PendingTree(
        BlockPos pos,
        List<CapturedBlock> blocks,
        TreeConfiguration config,
        TreeFeature feature,
        ResourceKey<Level> dim,
        long[] touchedChunks,
        long lifecycleId,
        long seed,
        DeferredTreeCaptureContext.CaptureSource source,
        long createdAtMs,
        long transactionId,
        ReplayMode mode,
        int retryCount,
        long expiresAtMs
    ) {
        public static PendingTree captured(BlockPos pos, List<CapturedBlock> blocks, ResourceKey<Level> dim) {
            return captured(pos, blocks, dim, 0L);
        }

        public static PendingTree captured(BlockPos pos, List<CapturedBlock> blocks, ResourceKey<Level> dim, long seed) {
            List<CapturedBlock> immutable = blocks == null ? Collections.emptyList() : List.copyOf(blocks);
            long now = System.currentTimeMillis();
            DeferredTreeCaptureContext.CaptureSource source = DeferredTreeCaptureContext.CaptureSource.VANILLA_TREE;
            long transactionId = TreeCompatTracker.deterministicTransactionId(dim, seed, pos, source);
            return new PendingTree(pos, immutable, null, null, dim, computeTouchedChunks(immutable),
                WorldGenScope.activeLifecycleId(), seed, source, now, transactionId,
                ReplayMode.CAPTURED_MUTATION, 0, now + MAX_PENDING_AGE_MS);
        }

        public static PendingTree captured(BlockPos pos,
                                           List<CapturedBlock> blocks,
                                           ResourceKey<Level> dim,
                                           long seed,
                                           DeferredTreeCaptureContext.CaptureSource source) {
            List<CapturedBlock> immutable = blocks == null ? Collections.emptyList() : List.copyOf(blocks);
            DeferredTreeCaptureContext.CaptureSource effective = source == null
                ? DeferredTreeCaptureContext.CaptureSource.VANILLA_TREE
                : source;
            long now = System.currentTimeMillis();
            long transactionId = TreeCompatTracker.deterministicTransactionId(dim, seed, pos, effective);
            return new PendingTree(pos, immutable, null, null, dim, computeTouchedChunks(immutable),
                WorldGenScope.activeLifecycleId(), seed, effective, now, transactionId,
                ReplayMode.CAPTURED_MUTATION, 0, now + MAX_PENDING_AGE_MS);
        }

        public static PendingTree replay(BlockPos pos, TreeConfiguration config, TreeFeature feature, ResourceKey<Level> dim) {
            return replay(pos, config, feature, dim, 0L);
        }

        public static PendingTree replay(BlockPos pos, TreeConfiguration config, TreeFeature feature, ResourceKey<Level> dim, long seed) {
            long now = System.currentTimeMillis();
            DeferredTreeCaptureContext.CaptureSource source = DeferredTreeCaptureContext.CaptureSource.VANILLA_TREE;
            long transactionId = TreeCompatTracker.deterministicTransactionId(dim, seed, pos, source);
            return new PendingTree(pos, Collections.emptyList(), config, feature, dim, new long[0],
                WorldGenScope.activeLifecycleId(), seed, source, now, transactionId,
                ReplayMode.LEGACY_REPLAY, 0, now + MAX_PENDING_AGE_MS);
        }

        public boolean hasCapturedBlocks() {
            return blocks != null && !blocks.isEmpty();
        }

        public boolean isExpired(long now) {
            return expiresAtMs > 0L && now >= expiresAtMs;
        }

        public boolean allowLegacyReplay() {
            return mode == ReplayMode.LEGACY_REPLAY;
        }

        public PendingTree withRetry() {
            return new PendingTree(pos, blocks, config, feature, dim, touchedChunks, lifecycleId, seed, source,
                createdAtMs, transactionId, mode, retryCount + 1, expiresAtMs);
        }

        public PendingTree capturedSubset(List<CapturedBlock> subset) {
            List<CapturedBlock> immutable = subset == null ? Collections.emptyList() : List.copyOf(subset);
            return new PendingTree(pos, immutable, null, null, dim, computeTouchedChunks(immutable),
                lifecycleId, seed, source, createdAtMs,
                TreeCompatTracker.deterministicTransactionId(dim, seed, pos, source),
                ReplayMode.CAPTURED_MUTATION, retryCount, expiresAtMs);
        }

    }

    public static void enqueue(ResourceKey<Level> dim, PendingTree tree) {
        if (dim == null || tree == null || tree.pos() == null) {
            return;
        }
        if (tree.isExpired(System.currentTimeMillis())) {
            DeferredTreeChunkRetainer.release(tree);
            EXPIRED_DROPS.incrementAndGet();
            return;
        }
        if (!isDeferredReplayEnabled()) {
            return;
        }
        if (!tree.hasCapturedBlocks() && (tree.config() == null || tree.feature() == null)) {
            return;
        }
        WorldGenScope.DimensionKey scope = scope(tree);
        DimensionQueue queue = QUEUES.computeIfAbsent(scope, ignored -> new DimensionQueue());
        List<PendingTree> bucket = queue.buckets.computeIfAbsent(anchorChunkKey(tree), ignored -> Collections.synchronizedList(new ArrayList<>()));
        synchronized (bucket) {
            int replacementIndex = -1;
            PendingTree replacementTarget = null;
            for (int i = 0; i < bucket.size(); i++) {
                PendingTree existing = bucket.get(i);
                if (!rootsConflict(existing, tree)) {
                    continue;
                }
                replacementIndex = i;
                replacementTarget = existing;
                break;
            }
            if (replacementIndex >= 0) {
                PendingTree preferred = preferTree(replacementTarget, tree);
                if (preferred == replacementTarget) {
                    return;
                }
                bucket.set(replacementIndex, preferred);
                DeferredTreeChunkRetainer.release(replacementTarget);
                DeferredTreeChunkRetainer.retain(preferred);
                DIRTY_DIMENSIONS.add(scope);
                REPLACED.incrementAndGet();
                return;
            }
            bucket.add(tree);
            DeferredTreeChunkRetainer.retain(tree);
            queue.size.incrementAndGet();
            DIRTY_DIMENSIONS.add(scope);
            ENQUEUED.incrementAndGet();
        }
    }

    public static void requeue(PendingTree tree) {
        if (tree == null) {
            return;
        }
        if (tree.retryCount() >= MAX_REQUEUE_ATTEMPTS) {
            DeferredTreeChunkRetainer.release(tree);
            RETRY_LIMIT_DROPS.incrementAndGet();
            return;
        }
        PendingTree retried = tree.withRetry();
        if (retried.isExpired(System.currentTimeMillis())) {
            DeferredTreeChunkRetainer.release(tree);
            EXPIRED_DROPS.incrementAndGet();
            return;
        }
        REQUEUED.incrementAndGet();
        enqueue(retried.dim(), retried);
    }

    /**
     * Requeue a tree after it has been removed from a ready queue and release
     * the current retention exactly once. Requeue releases expired entries.
     */
    public static void requeueAndRelease(PendingTree tree) {
        if (tree == null) {
            return;
        }
        boolean requeueOwnsRelease = tree.retryCount() < MAX_REQUEUE_ATTEMPTS
            && !tree.isExpired(System.currentTimeMillis());
        requeue(tree);
        if (requeueOwnsRelease) {
            DeferredTreeChunkRetainer.release(tree);
        }
    }

    public static List<PendingTree> drainReady(ResourceKey<Level> dim, ServerLevel level, int loadedChunkX, int loadedChunkZ) {
        if (dim == null || level == null) {
            return Collections.emptyList();
        }
        WorldGenScope.DimensionKey scope = WorldGenScope.dimension(level);
        DimensionQueue queue = QUEUES.get(scope);
        if (queue == null || queue.size.get() <= 0) {
            return Collections.emptyList();
        }

        List<PendingTree> ready = new ArrayList<>();
        int checksRemaining = MAX_CHECKS_PER_DRAIN;
        for (long bucketKey : neighborhoodKeys(loadedChunkX, loadedChunkZ)) {
            if (checksRemaining <= 0) {
                break;
            }
            List<PendingTree> bucket = queue.buckets.get(bucketKey);
            if (bucket == null || bucket.isEmpty()) {
                continue;
            }
            synchronized (bucket) {
                int index = 0;
                while (index < bucket.size() && checksRemaining > 0) {
                    PendingTree tree = bucket.get(index);
                    if (tree == null) {
                        bucket.remove(index);
                        queue.size.decrementAndGet();
                        DIRTY_DIMENSIONS.add(scope);
                        continue;
                    }
                    if (tree.isExpired(System.currentTimeMillis())) {
                        DeferredTreeChunkRetainer.release(tree);
                        bucket.remove(index);
                        queue.size.decrementAndGet();
                        DIRTY_DIMENSIONS.add(scope);
                        EXPIRED_DROPS.incrementAndGet();
                        continue;
                    }
                    checksRemaining--;
                    DRAIN_CHECKS.incrementAndGet();
                    if (isReady(tree, level)) {
                        ready.add(tree);
                        bucket.remove(index);
                        queue.size.decrementAndGet();
                        DIRTY_DIMENSIONS.add(scope);
                        DRAIN_READY.incrementAndGet();
                        continue;
                    }
                    index++;
                }
                if (bucket.isEmpty()) {
                    queue.buckets.remove(bucketKey, bucket);
                }
            }
        }

        if (!ready.isEmpty()) {
            DIRTY_DIMENSIONS.add(scope);
        }
        return ready;
    }

    public static int promoteReadyLoaded(ServerLevel level, int maxPromotions) {
        return promoteReadyLoaded(level, maxPromotions, null, MAX_CHECKS_PER_SWEEP);
    }

    public static int promoteReadyLoaded(ServerLevel level,
                                         int maxPromotions,
                                         Set<Long> interestChunks,
                                         int maxChecks) {
        if (level == null || maxPromotions <= 0) {
            return 0;
        }
        WorldGenScope.DimensionKey scope = WorldGenScope.dimension(level);
        DimensionQueue queue = QUEUES.get(scope);
        if (queue == null || queue.size.get() <= 0) {
            return 0;
        }

        List<PendingTree> ready = new ArrayList<>(Math.min(maxPromotions, 16));
        int checksRemaining = Math.max(1, maxChecks);
        for (Map.Entry<Long, List<PendingTree>> entry : queue.buckets.entrySet()) {
            if (checksRemaining <= 0 || ready.size() >= maxPromotions) {
                break;
            }
            List<PendingTree> bucket = entry.getValue();
            if (bucket == null || bucket.isEmpty()) {
                continue;
            }
            synchronized (bucket) {
                int index = 0;
                while (index < bucket.size() && checksRemaining > 0 && ready.size() < maxPromotions) {
                    PendingTree tree = bucket.get(index);
                    if (tree == null) {
                        bucket.remove(index);
                        queue.size.decrementAndGet();
                        DIRTY_DIMENSIONS.add(scope);
                        continue;
                    }
                    if (!intersectsInterest(tree, interestChunks)) {
                        index++;
                        continue;
                    }
                    if (tree.isExpired(System.currentTimeMillis())) {
                        DeferredTreeChunkRetainer.release(tree);
                        bucket.remove(index);
                        queue.size.decrementAndGet();
                        DIRTY_DIMENSIONS.add(scope);
                        EXPIRED_DROPS.incrementAndGet();
                        continue;
                    }
                    checksRemaining--;
                    SWEEP_CHECKS.incrementAndGet();
                    if (isReady(tree, level)) {
                        ready.add(tree);
                        bucket.remove(index);
                        queue.size.decrementAndGet();
                        DIRTY_DIMENSIONS.add(scope);
                        SWEEP_READY.incrementAndGet();
                        continue;
                    }
                    index++;
                }
                if (bucket.isEmpty()) {
                    queue.buckets.remove(entry.getKey(), bucket);
                }
            }
        }
        if (ready.isEmpty()) {
            return 0;
        }
        enqueueReady(level.dimension(), ready);
        return ready.size();
    }

    private static boolean isReady(PendingTree tree, ServerLevel level) {
        if (tree == null || level == null || tree.pos() == null) {
            return false;
        }
        if (tree.hasCapturedBlocks()) {
            return areCapturedChunksLoaded(tree.touchedChunks(), level);
        }
        return allNeighboursAtFull(tree.pos(), level);
    }

    private static boolean allNeighboursAtFull(BlockPos pos, ServerLevel level) {
        int cx = pos.getX() >> 4;
        int cz = pos.getZ() >> 4;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx + dx, cz + dz);
                if (chunk == null) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean areCapturedChunksLoaded(long[] touchedChunks, ServerLevel level) {
        if (touchedChunks == null || touchedChunks.length == 0 || level == null) {
            return false;
        }
        var dimension = level.dimension().location();
        for (long chunkKey : touchedChunks) {
            int cx = unpackChunkX(chunkKey);
            int cz = unpackChunkZ(chunkKey);
            LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
            if (chunk == null) {
                return false;
            }
            SeamOwnershipJournal.flushLoadedChunk(level, cx, cz);
            if (!SeamOwnershipJournal.isChunkReadyForTreeReplay(dimension, cx, cz)) {
                return false;
            }
        }
        return true;
    }

    private static long[] computeTouchedChunks(List<CapturedBlock> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            return new long[0];
        }
        Set<Long> seenChunks = new HashSet<>();
        for (CapturedBlock block : blocks) {
            if (block == null || block.pos() == null) {
                continue;
            }
            seenChunks.add(packChunk(block.pos().getX() >> 4, block.pos().getZ() >> 4));
        }
        if (seenChunks.isEmpty()) {
            return new long[0];
        }
        long[] out = new long[seenChunks.size()];
        int i = 0;
        for (Long chunk : seenChunks) {
            if (chunk != null) {
                out[i++] = chunk;
            }
        }
        if (i == out.length) {
            return out;
        }
        long[] trimmed = new long[i];
        System.arraycopy(out, 0, trimmed, 0, i);
        return trimmed;
    }

    private static long anchorChunkKey(PendingTree tree) {
        if (tree == null || tree.pos() == null) {
            return 0L;
        }
        return packChunk(tree.pos().getX() >> 4, tree.pos().getZ() >> 4);
    }

    private static long[] neighborhoodKeys(int chunkX, int chunkZ) {
        long[] out = new long[9];
        int cursor = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                out[cursor++] = packChunk(chunkX + dx, chunkZ + dz);
            }
        }
        return out;
    }

    private static long packChunk(int cx, int cz) {
        return ChunkPos.asLong(cx, cz);
    }

    private static int unpackChunkX(long packed) {
        return ChunkPos.getX(packed);
    }

    private static int unpackChunkZ(long packed) {
        return ChunkPos.getZ(packed);
    }

    public static void clearDimension(ResourceKey<Level> dim) {
        if (dim != null) {
            clearDimension(WorldGenScope.dimension(dim));
        }
    }

    public static void clearDimension(ServerLevel level) {
        if (level != null) {
            WorldGenScope.DimensionKey scope = WorldGenScope.dimension(level);
            DeferredTreeChunkRetainer.clearDimension(level, scope);
            clearDimension(scope);
        }
    }

    private static void clearDimension(WorldGenScope.DimensionKey scope) {
        if (scope != null) {
            QUEUES.remove(scope);
            READY_QUEUES.remove(scope);
            DIRTY_DIMENSIONS.remove(scope);
            LOADED_FROM_DISK.remove(scope);
        }
    }

    public static void clearAll() {
        DeferredTreeChunkRetainer.clearAll();
        QUEUES.clear();
        READY_QUEUES.clear();
        DIRTY_DIMENSIONS.clear();
        LOADED_FROM_DISK.clear();
        REPLAYING.remove();
    }

    public static int pendingCount(ResourceKey<Level> dim) {
        if (dim == null) {
            return 0;
        }
        int total = 0;
        String id = WorldGenScope.dimensionId(dim);
        for (Map.Entry<WorldGenScope.DimensionKey, DimensionQueue> entry : QUEUES.entrySet()) {
            if (entry.getKey() != null && id.equals(entry.getKey().dimension()) && entry.getValue() != null) {
                total += Math.max(0, entry.getValue().size.get());
            }
        }
        return total;
    }

    public static int pendingCount(ServerLevel level) {
        DimensionQueue queue = level == null ? null : QUEUES.get(WorldGenScope.dimension(level));
        return queue == null ? 0 : Math.max(0, queue.size.get());
    }

    public static int readyCount(ServerLevel level) {
        if (level == null) {
            return 0;
        }
        ConcurrentLinkedQueue<PendingTree> readyQueue = READY_QUEUES.get(WorldGenScope.dimension(level));
        return readyQueue == null ? 0 : readyQueue.size();
    }

    public static int pendingCount(ServerLevel level, Set<Long> interestChunks) {
        if (level == null) {
            return 0;
        }
        WorldGenScope.DimensionKey scope = WorldGenScope.dimension(level);
        DimensionQueue queue = QUEUES.get(scope);
        if (queue == null || queue.size.get() <= 0) {
            return 0;
        }
        int total = 0;
        for (List<PendingTree> bucket : queue.buckets.values()) {
            if (bucket == null || bucket.isEmpty()) {
                continue;
            }
            synchronized (bucket) {
                for (PendingTree tree : bucket) {
                    if (tree == null || tree.touchedChunks() == null || tree.touchedChunks().length == 0) {
                        continue;
                    }
                    if (intersectsInterest(tree, interestChunks)) {
                        total++;
                    }
                }
            }
        }
        return total;
    }

    public static int readyCount(ServerLevel level, Set<Long> interestChunks) {
        if (level == null) {
            return 0;
        }
        ConcurrentLinkedQueue<PendingTree> readyQueue = READY_QUEUES.get(WorldGenScope.dimension(level));
        if (readyQueue == null || readyQueue.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (PendingTree tree : readyQueue) {
            if (tree == null || tree.touchedChunks() == null || tree.touchedChunks().length == 0) {
                continue;
            }
            if (intersectsInterest(tree, interestChunks)) {
                total++;
            }
        }
        return total;
    }

    public static int pendingCountAll() {
        int total = 0;
        for (DimensionQueue queue : QUEUES.values()) {
            if (queue != null) {
                total += Math.max(0, queue.size.get());
            }
        }
        return total;
    }

    public static int readyCountAll() {
        int total = 0;
        for (ConcurrentLinkedQueue<PendingTree> queue : READY_QUEUES.values()) {
            if (queue != null) {
                total += queue.size();
            }
        }
        return total;
    }

    public static List<String> pendingDetails(ServerLevel level, int limit) {
        if (level == null || limit <= 0) {
            return Collections.emptyList();
        }
        return pendingDetails(level, limit, null);
    }

    public static List<String> pendingDetails(ServerLevel level, int limit, Set<Long> interestChunks) {
        if (level == null || limit <= 0) {
            return Collections.emptyList();
        }
        WorldGenScope.DimensionKey scope = WorldGenScope.dimension(level);
        DimensionQueue queue = QUEUES.get(scope);
        if (queue == null || queue.size.get() <= 0) {
            return Collections.emptyList();
        }

        ArrayList<String> out = new ArrayList<>(Math.min(limit, 8));
        long now = System.currentTimeMillis();
        var dimension = level.dimension().location();
        outer:
        for (List<PendingTree> bucket : queue.buckets.values()) {
            if (bucket == null || bucket.isEmpty()) {
                continue;
            }
            synchronized (bucket) {
                for (PendingTree tree : bucket) {
                    if (tree == null || tree.pos() == null) {
                        continue;
                    }
                    if (!intersectsInterest(tree, interestChunks)) {
                        continue;
                    }
                    int touched = tree.touchedChunks() == null ? 0 : tree.touchedChunks().length;
                    int loaded = 0;
                    int seamReady = 0;
                    ArrayList<String> missing = new ArrayList<>();
                    if (touched > 0) {
                        for (long chunkKey : tree.touchedChunks()) {
                            int cx = unpackChunkX(chunkKey);
                            int cz = unpackChunkZ(chunkKey);
                            LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                            boolean chunkLoaded = chunk != null;
                            if (chunkLoaded) {
                                SeamOwnershipJournal.flushLoadedChunk(level, cx, cz);
                            }
                            boolean chunkReady = chunkLoaded && SeamOwnershipJournal.isChunkReadyForTreeReplay(dimension, cx, cz);
                            boolean chunkTicketed = DeferredTreeChunkRetainer.holdsChunk(tree, chunkKey);
                            if (chunkLoaded) {
                                loaded++;
                            }
                            if (chunkReady) {
                                seamReady++;
                            } else if (missing.size() < 4) {
                                missing.add(cx + "," + cz + ":" + (chunkLoaded ? "seam-wait" : "unloaded")
                                    + ":ticketed=" + chunkTicketed);
                            }
                        }
                    }
                    out.add("tree pos=" + tree.pos().getX() + "," + tree.pos().getY() + "," + tree.pos().getZ()
                        + " source=" + tree.source()
                        + " mode=" + tree.mode()
                        + " tx=" + tree.transactionId()
                        + " retry=" + tree.retryCount()
                        + " ageMs=" + Math.max(0L, now - tree.createdAtMs())
                        + " touched=" + touched
                        + " ticketed=" + DeferredTreeChunkRetainer.ticketedChunkCount(tree)
                        + " loaded=" + loaded
                        + " seamReady=" + seamReady
                        + " missing=" + missing);
                    if (out.size() >= limit) {
                        break outer;
                    }
                }
            }
        }
        return out;
    }

    public static List<String> readyDetails(ServerLevel level, int limit, Set<Long> interestChunks) {
        if (level == null || limit <= 0) {
            return Collections.emptyList();
        }
        ConcurrentLinkedQueue<PendingTree> readyQueue = READY_QUEUES.get(WorldGenScope.dimension(level));
        if (readyQueue == null || readyQueue.isEmpty()) {
            return Collections.emptyList();
        }

        ArrayList<String> out = new ArrayList<>(Math.min(limit, 8));
        long now = System.currentTimeMillis();
        var dimension = level.dimension().location();
        for (PendingTree tree : readyQueue) {
            if (tree == null || tree.pos() == null || !intersectsInterest(tree, interestChunks)) {
                continue;
            }
            int touched = tree.touchedChunks() == null ? 0 : tree.touchedChunks().length;
            int loaded = 0;
            int seamReady = 0;
            ArrayList<String> missing = new ArrayList<>();
            if (touched > 0) {
                for (long chunkKey : tree.touchedChunks()) {
                    int cx = unpackChunkX(chunkKey);
                    int cz = unpackChunkZ(chunkKey);
                    LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                    boolean chunkLoaded = chunk != null;
                    if (chunkLoaded) {
                        SeamOwnershipJournal.flushLoadedChunk(level, cx, cz);
                    }
                    boolean chunkReady = chunkLoaded && SeamOwnershipJournal.isChunkReadyForTreeReplay(dimension, cx, cz);
                    boolean chunkTicketed = DeferredTreeChunkRetainer.holdsChunk(tree, chunkKey);
                    if (chunkLoaded) {
                        loaded++;
                    }
                    if (chunkReady) {
                        seamReady++;
                    } else if (missing.size() < 4) {
                        missing.add(cx + "," + cz + ":" + (chunkLoaded ? "seam-wait" : "unloaded")
                            + ":ticketed=" + chunkTicketed);
                    }
                }
            }
            out.add("tree pos=" + tree.pos().getX() + "," + tree.pos().getY() + "," + tree.pos().getZ()
                + " source=" + tree.source()
                + " mode=" + tree.mode()
                + " tx=" + tree.transactionId()
                + " retry=" + tree.retryCount()
                + " ageMs=" + Math.max(0L, now - tree.createdAtMs())
                + " touched=" + touched
                + " ticketed=" + DeferredTreeChunkRetainer.ticketedChunkCount(tree)
                + " loaded=" + loaded
                + " seamReady=" + seamReady
                + " residue=" + classifyResidue(level, tree)
                + " missing=" + missing);
            if (out.size() >= limit) {
                break;
            }
        }
        return out;
    }

    public static List<ChunkPos> pendingUnloadedTouchedChunks(ServerLevel level,
                                                              int limit,
                                                              Set<Long> exclude,
                                                              Set<Long> interestChunks) {
        if (level == null || limit <= 0) {
            return Collections.emptyList();
        }
        WorldGenScope.DimensionKey scope = WorldGenScope.dimension(level);
        DimensionQueue queue = QUEUES.get(scope);
        if (queue == null || queue.size.get() <= 0) {
            return Collections.emptyList();
        }

        LinkedHashSet<Long> chunkKeys = new LinkedHashSet<>();
        outer:
        for (List<PendingTree> bucket : queue.buckets.values()) {
            if (bucket == null || bucket.isEmpty()) {
                continue;
            }
            synchronized (bucket) {
                for (PendingTree tree : bucket) {
                    if (tree == null || tree.touchedChunks() == null || tree.touchedChunks().length == 0) {
                        continue;
                    }
                    if (!intersectsInterest(tree, interestChunks)) {
                        continue;
                    }
                    for (long chunkKey : tree.touchedChunks()) {
                        if (exclude != null && exclude.contains(chunkKey)) {
                            continue;
                        }
                        int cx = unpackChunkX(chunkKey);
                        int cz = unpackChunkZ(chunkKey);
                        LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                        if (chunk != null) {
                            continue;
                        }
                        if (chunkKeys.add(chunkKey) && chunkKeys.size() >= limit) {
                            break outer;
                        }
                    }
                }
            }
        }
        if (chunkKeys.isEmpty()) {
            return Collections.emptyList();
        }
        ArrayList<ChunkPos> out = new ArrayList<>(chunkKeys.size());
        for (Long chunkKey : chunkKeys) {
            if (chunkKey != null) {
                out.add(new ChunkPos(chunkKey));
            }
        }
        return out;
    }

    private static boolean intersectsInterest(long[] touchedChunks, Set<Long> interestChunks) {
        if (touchedChunks == null || touchedChunks.length == 0) {
            return false;
        }
        if (interestChunks == null || interestChunks.isEmpty()) {
            return true;
        }
        for (long chunkKey : touchedChunks) {
            if (interestChunks.contains(chunkKey)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isReplaying() {
        return REPLAYING.get();
    }

    public static boolean isDeferredReplayEnabled() {
        // Captured trees must remain replayable after crossing an LC boundary.
        return true;
    }

    public static void runReplay(Runnable runnable) {
        if (runnable == null) {
            return;
        }
        boolean previous = REPLAYING.get();
        REPLAYING.set(true);
        try {
            runnable.run();
        } finally {
            REPLAYING.set(previous);
        }
    }

    public static void loadFromDisk(ServerLevel level) {
        if (level == null) {
            return;
        }
        ResourceKey<Level> dim = level.dimension();
        WorldGenScope.DimensionKey scope = WorldGenScope.dimension(level);
        if (!LOADED_FROM_DISK.add(scope)) {
            return;
        }
        if (!isDeferredReplayEnabled()) {
            DIRTY_DIMENSIONS.add(scope);
            return;
        }
        DeferredTreeSavedData data = DeferredTreeSavedData.getOrCreate(level);
        for (PendingTree tree : data.toPendingTrees(level)) {
            enqueue(dim, tree);
        }
    }

    public static void flushToDisk(ServerLevel level) {
        if (level == null) {
            return;
        }
        ResourceKey<Level> dim = level.dimension();
        WorldGenScope.DimensionKey scope = WorldGenScope.dimension(level);
        if (!DIRTY_DIMENSIONS.remove(scope)) {
            return;
        }
        DeferredTreeSavedData data = DeferredTreeSavedData.getOrCreate(level);
        data.replaceFromPendingTrees(snapshot(scope));
        data.setDirty();
    }

    private static List<PendingTree> snapshot(WorldGenScope.DimensionKey scope) {
        List<PendingTree> out = new ArrayList<>();

        DimensionQueue queue = QUEUES.get(scope);
        if (queue != null && queue.size.get() > 0) {
            for (List<PendingTree> bucket : queue.buckets.values()) {
                if (bucket == null || bucket.isEmpty()) {
                    continue;
                }
                synchronized (bucket) {
                    out.addAll(bucket);
                }
            }
        }

        ConcurrentLinkedQueue<PendingTree> readyQueue = READY_QUEUES.get(scope);
        if (readyQueue != null && !readyQueue.isEmpty()) {
            out.addAll(readyQueue);
        }

        return out.isEmpty() ? Collections.emptyList() : out;
    }

    public static void enqueueReady(ResourceKey<Level> dim, List<PendingTree> trees) {
        if (dim == null || trees == null || trees.isEmpty()) {
            return;
        }
        for (PendingTree tree : trees) {
            if (tree != null) {
                WorldGenScope.DimensionKey scope = scope(tree);
                ConcurrentLinkedQueue<PendingTree> readyQueue = READY_QUEUES.computeIfAbsent(scope, ignored -> new ConcurrentLinkedQueue<>());
                dedupeReadyQueue(readyQueue, tree);
            }
        }
    }

    public static List<PendingTree> pollReady(ResourceKey<Level> dim, int maxCount) {
        if (dim == null || maxCount <= 0) {
            return Collections.emptyList();
        }
        ConcurrentLinkedQueue<PendingTree> readyQueue = READY_QUEUES.get(WorldGenScope.dimension(dim));
        if (readyQueue == null || readyQueue.isEmpty()) {
            return Collections.emptyList();
        }

        List<PendingTree> out = new ArrayList<>(Math.min(maxCount, 8));
        while (out.size() < maxCount) {
            PendingTree tree = readyQueue.poll();
            if (tree == null) {
                break;
            }
            if (tree.isExpired(System.currentTimeMillis())) {
                DeferredTreeChunkRetainer.release(tree);
                EXPIRED_DROPS.incrementAndGet();
                continue;
            }
            out.add(tree);
            DIRTY_DIMENSIONS.add(scope(tree));
            READY_POLLED.incrementAndGet();
        }
        return out;
    }

    public static List<PendingTree> pollReady(ServerLevel level, int maxCount) {
        if (level == null || maxCount <= 0) {
            return Collections.emptyList();
        }
        WorldGenScope.DimensionKey scope = WorldGenScope.dimension(level);
        ConcurrentLinkedQueue<PendingTree> readyQueue = READY_QUEUES.get(scope);
        if (readyQueue == null || readyQueue.isEmpty()) {
            return Collections.emptyList();
        }

        List<PendingTree> out = new ArrayList<>(Math.min(maxCount, 8));
        while (out.size() < maxCount) {
            PendingTree tree = readyQueue.poll();
            if (tree == null) {
                break;
            }
            if (tree.isExpired(System.currentTimeMillis())) {
                DeferredTreeChunkRetainer.release(tree);
                EXPIRED_DROPS.incrementAndGet();
                continue;
            }
            if (!WorldGenScope.matches(level, scope(tree))) {
                DeferredTreeChunkRetainer.release(tree);
                STALE_SCOPE_DROPS.incrementAndGet();
                continue;
            }
            if (tree.allowLegacyReplay()) {
                LEGACY_FALLBACK_READY.incrementAndGet();
            }
            out.add(tree);
            DIRTY_DIMENSIONS.add(scope);
            READY_POLLED.incrementAndGet();
        }
        return out;
    }

    public static List<PendingTree> pollReady(ServerLevel level,
                                              int maxCount,
                                              Set<Long> interestChunks,
                                              int maxScans) {
        if (level == null || maxCount <= 0) {
            return Collections.emptyList();
        }
        if (interestChunks == null || interestChunks.isEmpty()) {
            return pollReady(level, maxCount);
        }
        WorldGenScope.DimensionKey scope = WorldGenScope.dimension(level);
        ConcurrentLinkedQueue<PendingTree> readyQueue = READY_QUEUES.get(scope);
        if (readyQueue == null || readyQueue.isEmpty()) {
            return Collections.emptyList();
        }

        int scanBudget = Math.max(maxCount, maxScans);
        List<PendingTree> matched = new ArrayList<>(Math.min(maxCount, 8));
        List<PendingTree> deferred = new ArrayList<>(Math.min(scanBudget, 64));
        while (matched.size() < maxCount && scanBudget-- > 0) {
            PendingTree tree = readyQueue.poll();
            if (tree == null) {
                break;
            }
            if (tree.isExpired(System.currentTimeMillis())) {
                DeferredTreeChunkRetainer.release(tree);
                EXPIRED_DROPS.incrementAndGet();
                continue;
            }
            if (!WorldGenScope.matches(level, scope(tree))) {
                DeferredTreeChunkRetainer.release(tree);
                STALE_SCOPE_DROPS.incrementAndGet();
                continue;
            }
            if (!intersectsInterest(tree, interestChunks)) {
                deferred.add(tree);
                continue;
            }
            if (tree.allowLegacyReplay()) {
                LEGACY_FALLBACK_READY.incrementAndGet();
            }
            matched.add(tree);
            DIRTY_DIMENSIONS.add(scope);
            READY_POLLED.incrementAndGet();
        }
        for (PendingTree tree : deferred) {
            if (tree != null) {
                readyQueue.offer(tree);
            }
        }
        return matched;
    }

    private static void dedupeReadyQueue(ConcurrentLinkedQueue<PendingTree> readyQueue, PendingTree incoming) {
        if (readyQueue == null || incoming == null) {
            return;
        }
        PendingTree replacementTarget = null;
        for (PendingTree existing : readyQueue) {
            if (rootsConflict(existing, incoming)) {
                replacementTarget = existing;
                break;
            }
        }
        if (replacementTarget == null) {
            readyQueue.offer(incoming);
            READY_ENQUEUED.incrementAndGet();
            return;
        }
        PendingTree preferred = preferTree(replacementTarget, incoming);
        if (preferred == replacementTarget) {
            return;
        }
        readyQueue.remove(replacementTarget);
        DeferredTreeChunkRetainer.release(replacementTarget);
        DeferredTreeChunkRetainer.retain(preferred);
        readyQueue.offer(preferred);
        REPLACED.incrementAndGet();
    }

    static WorldGenScope.DimensionKey treeScope(PendingTree tree) {
        if (tree == null) {
            return WorldGenScope.dimension((ResourceKey<Level>) null);
        }
        return WorldGenScope.dimension(tree.dim(), tree.lifecycleId(), tree.seed());
    }

    private static WorldGenScope.DimensionKey scope(PendingTree tree) {
        return treeScope(tree);
    }

    public static String diagnostics() {
        return "scopes=" + QUEUES.size()
            + ", readyScopes=" + READY_QUEUES.size()
            + ", dirty=" + DIRTY_DIMENSIONS.size()
            + ", loaded=" + LOADED_FROM_DISK.size()
            + ", enqueued=" + ENQUEUED.get()
            + ", replaced=" + REPLACED.get()
            + ", readyEnqueued=" + READY_ENQUEUED.get()
            + ", drainChecks=" + DRAIN_CHECKS.get()
            + ", drainReady=" + DRAIN_READY.get()
            + ", readyPolled=" + READY_POLLED.get()
            + ", requeued=" + REQUEUED.get()
            + ", sweepChecks=" + SWEEP_CHECKS.get()
            + ", sweepReady=" + SWEEP_READY.get()
            + ", staleDrops=" + STALE_SCOPE_DROPS.get()
            + ", expiredDrops=" + EXPIRED_DROPS.get()
            + ", retryLimitDrops=" + RETRY_LIMIT_DROPS.get()
            + ", legacyFallbackReady=" + LEGACY_FALLBACK_READY.get()
            + ", tickets[" + DeferredTreeChunkRetainer.diagnostics() + "]"
            + ", oldestAgeMs=" + oldestAgeMs();
    }

    public static String diagnostics(ServerLevel level) {
        if (level == null) {
            return diagnostics();
        }
        return "pending=" + pendingCount(level)
            + ", ready=" + readyCount(level)
            + ", residue[" + residueDiagnostics(level, null) + "]"
            + ", oldestAgeMs=" + oldestAgeMs()
            + ", tickets[" + DeferredTreeChunkRetainer.scopedDiagnostics(level) + "]";
    }

    public static String residueDiagnostics(ServerLevel level, Set<Long> interestChunks) {
        if (level == null) {
            return "unavailable";
        }
        long now = System.currentTimeMillis();
        int waitingUnloaded = 0;
        int waitingSeam = 0;
        int waitingTicket = 0;
        int retrying = 0;
        int unresolved = 0;
        WorldGenScope.DimensionKey scope = WorldGenScope.dimension(level);
        DimensionQueue queue = QUEUES.get(scope);
        if (queue != null) {
            for (List<PendingTree> bucket : queue.buckets.values()) {
                if (bucket == null || bucket.isEmpty()) {
                    continue;
                }
                synchronized (bucket) {
                    for (PendingTree tree : bucket) {
                        if (tree == null || !intersectsInterest(tree, interestChunks)) {
                            continue;
                        }
                        if (tree.isExpired(now)) {
                            unresolved++;
                            continue;
                        }
                        if (tree.retryCount() > 0) {
                            retrying++;
                        }
                        TreeResidue residue = classifyResidue(level, tree);
                        switch (residue) {
                            case WAITING_UNLOADED_CHUNK -> waitingUnloaded++;
                            case WAITING_SEAM_READY -> waitingSeam++;
                            case WAITING_TICKET -> waitingTicket++;
                            default -> unresolved++;
                        }
                    }
                }
            }
        }
        int readyPendingApply = countReadyInterest(level, interestChunks);
        return "readyPendingApply=" + readyPendingApply
            + ", waitingUnloaded=" + waitingUnloaded
            + ", waitingSeam=" + waitingSeam
            + ", waitingTicket=" + waitingTicket
            + ", retrying=" + retrying
            + ", unresolved=" + unresolved;
    }

    private static long oldestAgeMs() {
        long oldest = Long.MAX_VALUE;
        for (DimensionQueue queue : QUEUES.values()) {
            if (queue == null) {
                continue;
            }
            for (List<PendingTree> bucket : queue.buckets.values()) {
                if (bucket == null || bucket.isEmpty()) {
                    continue;
                }
                synchronized (bucket) {
                    for (PendingTree tree : bucket) {
                        if (tree != null && tree.createdAtMs() > 0L) {
                            oldest = Math.min(oldest, tree.createdAtMs());
                        }
                    }
                }
            }
        }
        for (ConcurrentLinkedQueue<PendingTree> readyQueue : READY_QUEUES.values()) {
            if (readyQueue == null || readyQueue.isEmpty()) {
                continue;
            }
            for (PendingTree tree : readyQueue) {
                if (tree != null && tree.createdAtMs() > 0L) {
                    oldest = Math.min(oldest, tree.createdAtMs());
                }
            }
        }
        return oldest == Long.MAX_VALUE ? 0L : Math.max(0L, System.currentTimeMillis() - oldest);
    }

    private static boolean rootsConflict(PendingTree left, PendingTree right) {
        if (left == null || right == null || left.pos() == null || right.pos() == null) {
            return false;
        }
        if (!java.util.Objects.equals(left.dim(), right.dim())) {
            return false;
        }
        int dx = Math.abs(left.pos().getX() - right.pos().getX());
        int dz = Math.abs(left.pos().getZ() - right.pos().getZ());
        int dy = Math.abs(left.pos().getY() - right.pos().getY());
        return dx <= 2 && dz <= 2 && dy <= 4;
    }

    private static PendingTree preferTree(PendingTree existing, PendingTree incoming) {
        if (existing == null) {
            return incoming;
        }
        if (incoming == null) {
            return existing;
        }
        if (incoming.hasCapturedBlocks() != existing.hasCapturedBlocks()) {
            return incoming.hasCapturedBlocks() ? incoming : existing;
        }
        int existingWeight = treeWeight(existing);
        int incomingWeight = treeWeight(incoming);
        return incomingWeight > existingWeight ? incoming : existing;
    }

    private static int treeWeight(PendingTree tree) {
        if (tree == null) {
            return 0;
        }
        if (tree.hasCapturedBlocks()) {
            return tree.blocks() == null ? 0 : tree.blocks().size();
        }
        return 1;
    }

    private static int countReadyInterest(ServerLevel level, Set<Long> interestChunks) {
        ConcurrentLinkedQueue<PendingTree> readyQueue = READY_QUEUES.get(WorldGenScope.dimension(level));
        if (readyQueue == null || readyQueue.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (PendingTree tree : readyQueue) {
            if (tree != null && intersectsInterest(tree, interestChunks)) {
                count++;
            }
        }
        return count;
    }

    private static boolean intersectsInterest(PendingTree tree, Set<Long> interestChunks) {
        if (tree == null) {
            return false;
        }
        if (intersectsInterest(tree.touchedChunks(), interestChunks)) {
            return true;
        }
        if (interestChunks == null || interestChunks.isEmpty()) {
            return true;
        }
        BlockPos root = tree.pos();
        return root != null && interestChunks.contains(new ChunkPos(root).toLong());
    }

    private static TreeResidue classifyResidue(ServerLevel level, PendingTree tree) {
        if (tree == null || level == null) {
            return TreeResidue.UNRESOLVED_BUG;
        }
        long[] touched = tree.touchedChunks();
        if (touched == null || touched.length == 0) {
            return TreeResidue.UNRESOLVED_BUG;
        }
        boolean missingTicket = false;
        for (long chunkKey : touched) {
            int cx = unpackChunkX(chunkKey);
            int cz = unpackChunkZ(chunkKey);
            LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
            if (chunk == null) {
                return TreeResidue.WAITING_UNLOADED_CHUNK;
            }
            SeamOwnershipJournal.flushLoadedChunk(level, cx, cz);
            if (!DeferredTreeChunkRetainer.holdsChunk(tree, chunkKey)) {
                missingTicket = true;
            }
            if (!SeamOwnershipJournal.isChunkReadyForTreeReplay(level.dimension().location(), cx, cz)) {
                return TreeResidue.WAITING_SEAM_READY;
            }
        }
        if (missingTicket) {
            return TreeResidue.WAITING_TICKET;
        }
        return TreeResidue.UNRESOLVED_BUG;
    }

    private enum TreeResidue {
        WAITING_UNLOADED_CHUNK,
        WAITING_SEAM_READY,
        WAITING_TICKET,
        UNRESOLVED_BUG
    }
}
