package org.admany.lc2h.worldgen.lostcities;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.feature.TreeFeature;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.configurations.TreeConfiguration;
import org.admany.lc2h.worldgen.seams.SeamOwnershipJournal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

public final class DeferredTreeQueue {

    private static final Map<ResourceKey<Level>, List<PendingTree>> QUEUES = new ConcurrentHashMap<>();
    private static final Map<ResourceKey<Level>, ConcurrentLinkedQueue<PendingTree>> READY_QUEUES = new ConcurrentHashMap<>();
    private static final Map<ResourceKey<Level>, AtomicInteger> DRAIN_CURSOR = new ConcurrentHashMap<>();
    private static final Set<ResourceKey<Level>> DIRTY_DIMENSIONS = ConcurrentHashMap.newKeySet();
    private static final Set<ResourceKey<Level>> LOADED_FROM_DISK = ConcurrentHashMap.newKeySet();
    private static final ThreadLocal<Boolean> REPLAYING = ThreadLocal.withInitial(() -> false);
    private static final int MAX_CHECKS_PER_DRAIN = 64;

    private DeferredTreeQueue() {
    }

    public record CapturedBlock(
        BlockPos pos,
        BlockState state
    ) {
    }

    public record PendingTree(
        BlockPos pos,
        List<CapturedBlock> blocks,
        TreeConfiguration config,
        TreeFeature feature,
        ResourceKey<Level> dim,
        long[] touchedChunks
    ) {
        public static PendingTree captured(BlockPos pos, List<CapturedBlock> blocks, ResourceKey<Level> dim) {
            List<CapturedBlock> immutable = blocks == null ? Collections.emptyList() : List.copyOf(blocks);
            return new PendingTree(pos, immutable, null, null, dim, computeTouchedChunks(immutable));
        }

        public static PendingTree replay(BlockPos pos, TreeConfiguration config, TreeFeature feature, ResourceKey<Level> dim) {
            return new PendingTree(pos, Collections.emptyList(), config, feature, dim, new long[0]);
        }

        public boolean hasCapturedBlocks() {
            return blocks != null && !blocks.isEmpty();
        }
    }

    public static void enqueue(ResourceKey<Level> dim, PendingTree tree) {
        if (dim == null || tree == null || tree.pos() == null) {
            return;
        }
        if (!tree.hasCapturedBlocks() && (tree.config() == null || tree.feature() == null)) {
            return;
        }
        QUEUES.computeIfAbsent(dim, k -> Collections.synchronizedList(new ArrayList<>()))
            .add(tree);
        DIRTY_DIMENSIONS.add(dim);
    }

    public static List<PendingTree> drainReady(ResourceKey<Level> dim, ServerLevel level) {
        if (dim == null || level == null) {
            return Collections.emptyList();
        }
        List<PendingTree> queue = QUEUES.get(dim);
        if (queue == null || queue.isEmpty()) {
            return Collections.emptyList();
        }

        List<PendingTree> ready = new ArrayList<>();
        AtomicInteger cursorRef = DRAIN_CURSOR.computeIfAbsent(dim, ignored -> new AtomicInteger(0));

        synchronized (queue) {
            int size = queue.size();
            if (size == 0) {
                cursorRef.set(0);
                return Collections.emptyList();
            }

            int cursor = Math.floorMod(cursorRef.get(), size);
            int checks = Math.min(size, MAX_CHECKS_PER_DRAIN);

            for (int i = 0; i < checks && !queue.isEmpty(); i++) {
                if (cursor >= queue.size()) {
                    cursor = 0;
                }
                PendingTree tree = queue.get(cursor);
                if (tree == null) {
                    queue.remove(cursor);
                    DIRTY_DIMENSIONS.add(dim);
                    continue;
                }
                if (isReady(tree, level)) {
                    ready.add(tree);
                    queue.remove(cursor);
                    DIRTY_DIMENSIONS.add(dim);
                    continue;
                }
                cursor++;
            }

            cursorRef.set(queue.isEmpty() ? 0 : Math.floorMod(cursor, queue.size()));
        }

        if (!ready.isEmpty()) {
            DIRTY_DIMENSIONS.add(dim);
        }

        return ready;
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
                // Must remain non-blocking here: this runs from chunk-load events on server thread.
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

    private static long packChunk(int cx, int cz) {
        return (((long) cx) << 32) ^ (cz & 0xffffffffL);
    }

    private static int unpackChunkX(long packed) {
        return (int) (packed >> 32);
    }

    private static int unpackChunkZ(long packed) {
        return (int) packed;
    }

    public static void clearDimension(ResourceKey<Level> dim) {
        if (dim != null) {
            QUEUES.remove(dim);
            READY_QUEUES.remove(dim);
            DRAIN_CURSOR.remove(dim);
            DIRTY_DIMENSIONS.remove(dim);
            LOADED_FROM_DISK.remove(dim);
        }
    }

    public static void clearAll() {
        QUEUES.clear();
        READY_QUEUES.clear();
        DRAIN_CURSOR.clear();
        DIRTY_DIMENSIONS.clear();
        LOADED_FROM_DISK.clear();
        REPLAYING.remove();
    }

    public static int pendingCount(ResourceKey<Level> dim) {
        List<PendingTree> queue = QUEUES.get(dim);
        return queue == null ? 0 : queue.size();
    }

    public static int pendingCountAll() {
        int total = 0;
        for (List<PendingTree> queue : QUEUES.values()) {
            if (queue == null) {
                continue;
            }
            synchronized (queue) {
                total += queue.size();
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

    public static boolean isReplaying() {
        return REPLAYING.get();
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
        if (!LOADED_FROM_DISK.add(dim)) {
            return;
        }
        DeferredTreeSavedData data = DeferredTreeSavedData.getOrCreate(level);
        List<PendingTree> restored = data.toPendingTrees(dim);
        if (restored.isEmpty()) {
            return;
        }
        QUEUES.computeIfAbsent(dim, k -> Collections.synchronizedList(new ArrayList<>()))
            .addAll(restored);
    }

    public static void flushToDisk(ServerLevel level) {
        if (level == null) {
            return;
        }
        ResourceKey<Level> dim = level.dimension();
        if (!DIRTY_DIMENSIONS.remove(dim)) {
            return;
        }
        DeferredTreeSavedData data = DeferredTreeSavedData.getOrCreate(level);
        data.replaceFromPendingTrees(snapshot(dim));
        data.setDirty();
    }

    private static List<PendingTree> snapshot(ResourceKey<Level> dim) {
        List<PendingTree> out = new ArrayList<>();

        List<PendingTree> queue = QUEUES.get(dim);
        if (queue != null && !queue.isEmpty()) {
            synchronized (queue) {
                out.addAll(queue);
            }
        }

        ConcurrentLinkedQueue<PendingTree> readyQueue = READY_QUEUES.get(dim);
        if (readyQueue != null && !readyQueue.isEmpty()) {
            out.addAll(readyQueue);
        }

        return out.isEmpty() ? Collections.emptyList() : out;
    }

    public static void enqueueReady(ResourceKey<Level> dim, List<PendingTree> trees) {
        if (dim == null || trees == null || trees.isEmpty()) {
            return;
        }
        ConcurrentLinkedQueue<PendingTree> readyQueue = READY_QUEUES.computeIfAbsent(dim, ignored -> new ConcurrentLinkedQueue<>());
        for (PendingTree tree : trees) {
            if (tree != null) {
                readyQueue.offer(tree);
            }
        }
    }

    public static List<PendingTree> pollReady(ResourceKey<Level> dim, int maxCount) {
        if (dim == null || maxCount <= 0) {
            return Collections.emptyList();
        }
        ConcurrentLinkedQueue<PendingTree> readyQueue = READY_QUEUES.get(dim);
        if (readyQueue == null || readyQueue.isEmpty()) {
            return Collections.emptyList();
        }

        List<PendingTree> out = new ArrayList<>(Math.min(maxCount, 8));
        while (out.size() < maxCount) {
            PendingTree tree = readyQueue.poll();
            if (tree == null) {
                break;
            }
            out.add(tree);
            DIRTY_DIMENSIONS.add(dim);
        }
        return out;
    }
}
