package org.admany.lc2h.worldgen.lostcities;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.admany.lc2h.LC2H;
import org.admany.lc2h.util.server.ServerRescheduler;
import org.admany.lc2h.worldgen.scope.WorldGenScope;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

final class DeferredTreeChunkRetainer {
    private static final TicketType<ChunkPos> LC2H_TREE_TICKET =
        TicketType.create("lc2h_deferred_tree", Comparator.comparingLong(ChunkPos::toLong));

    private static final ConcurrentHashMap<WorldGenScope.DimensionKey, ConcurrentHashMap<Long, Integer>> CHUNK_REFS =
        new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<TreeKey, Set<Long>> TREE_CHUNKS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<WorldGenScope.DimensionKey, Set<Long>> APPLIED_CHUNKS =
        new ConcurrentHashMap<>();
    private static final Set<WorldGenScope.DimensionKey> RECONCILE_PENDING = ConcurrentHashMap.newKeySet();
    private static final AtomicLong TICKET_ACQUIRED = new AtomicLong();
    private static final AtomicLong TICKET_RELEASED = new AtomicLong();
    private static final AtomicLong TICKET_ACQUIRE_FAILURES = new AtomicLong();
    private static final AtomicLong TICKET_RELEASE_FAILURES = new AtomicLong();
    private static final AtomicLong TREE_RETAINS = new AtomicLong();
    private static final AtomicLong TREE_RELEASES = new AtomicLong();
    private static final AtomicLong RECONCILE_RUNS = new AtomicLong();

    private DeferredTreeChunkRetainer() {
    }

    private record TreeKey(WorldGenScope.DimensionKey scope, long transactionId, long rootPos) {
    }

    static void retain(DeferredTreeQueue.PendingTree tree) {
        if (tree == null || tree.pos() == null || tree.touchedChunks() == null || tree.touchedChunks().length == 0) {
            return;
        }
        WorldGenScope.DimensionKey scope = DeferredTreeQueue.treeScope(tree);
        TreeKey treeKey = new TreeKey(scope, tree.transactionId(), tree.pos().asLong());
        Set<Long> retained = TREE_CHUNKS.computeIfAbsent(treeKey, ignored -> ConcurrentHashMap.newKeySet());
        ConcurrentHashMap<Long, Integer> refs = CHUNK_REFS.computeIfAbsent(scope, ignored -> new ConcurrentHashMap<>());
        boolean touchedAny = false;
        for (long packedChunk : tree.touchedChunks()) {
            if (!retained.add(packedChunk)) {
                continue;
            }
            touchedAny = true;
            refs.merge(packedChunk, 1, Integer::sum);
        }
        if (touchedAny) {
            TREE_RETAINS.incrementAndGet();
            scheduleReconcile(scope);
        }
        if (retained.isEmpty()) {
            TREE_CHUNKS.remove(treeKey, retained);
        }
        if (refs.isEmpty()) {
            CHUNK_REFS.remove(scope, refs);
        }
    }

    static void release(DeferredTreeQueue.PendingTree tree) {
        if (tree == null || tree.pos() == null) {
            return;
        }
        WorldGenScope.DimensionKey scope = DeferredTreeQueue.treeScope(tree);
        TreeKey treeKey = new TreeKey(scope, tree.transactionId(), tree.pos().asLong());
        Set<Long> retained = TREE_CHUNKS.remove(treeKey);
        if (retained == null || retained.isEmpty()) {
            return;
        }
        ConcurrentHashMap<Long, Integer> refs = CHUNK_REFS.get(scope);
        for (Long packedChunk : new ArrayList<>(retained)) {
            if (packedChunk == null || refs == null) {
                continue;
            }
            Integer remaining = refs.compute(packedChunk, (ignored, current) -> {
                if (current == null || current <= 1) {
                    return null;
                }
                return current - 1;
            });
            if (remaining != null) {
                continue;
            }
        }
        if (refs != null && refs.isEmpty()) {
            CHUNK_REFS.remove(scope, refs);
        }
        TREE_RELEASES.incrementAndGet();
        scheduleReconcile(scope);
    }

    static void clearDimension(ServerLevel level, WorldGenScope.DimensionKey scope) {
        if (scope == null) {
            return;
        }
        ConcurrentHashMap<Long, Integer> refs = CHUNK_REFS.remove(scope);
        ArrayList<TreeKey> treeKeys = new ArrayList<>();
        for (TreeKey key : TREE_CHUNKS.keySet()) {
            if (scope.equals(key.scope())) {
                treeKeys.add(key);
            }
        }
        for (TreeKey key : treeKeys) {
            TREE_CHUNKS.remove(key);
        }
        if (refs != null) {
            refs.clear();
        }
        scheduleReconcile(scope);
        if (level == null) {
            return;
        }
    }

    static void clearAll() {
        MinecraftServer server = ServerRescheduler.getServer();
        for (Map.Entry<WorldGenScope.DimensionKey, ConcurrentHashMap<Long, Integer>> entry : CHUNK_REFS.entrySet()) {
            ServerLevel level = findLevel(server, entry.getKey());
            clearDimension(level, entry.getKey());
        }
        CHUNK_REFS.clear();
        TREE_CHUNKS.clear();
        APPLIED_CHUNKS.clear();
        RECONCILE_PENDING.clear();
    }

    static int ticketedChunkCount(DeferredTreeQueue.PendingTree tree) {
        if (tree == null || tree.pos() == null) {
            return 0;
        }
        WorldGenScope.DimensionKey scope = DeferredTreeQueue.treeScope(tree);
        TreeKey treeKey = new TreeKey(scope, tree.transactionId(), tree.pos().asLong());
        Set<Long> retained = TREE_CHUNKS.get(treeKey);
        return retained == null ? 0 : retained.size();
    }

    static boolean holdsChunk(DeferredTreeQueue.PendingTree tree, long packedChunk) {
        if (tree == null || tree.pos() == null) {
            return false;
        }
        WorldGenScope.DimensionKey scope = DeferredTreeQueue.treeScope(tree);
        TreeKey treeKey = new TreeKey(scope, tree.transactionId(), tree.pos().asLong());
        Set<Long> retained = TREE_CHUNKS.get(treeKey);
        return retained != null && retained.contains(packedChunk);
    }

    static long heldChunkCount(ServerLevel level) {
        if (level == null) {
            return 0L;
        }
        ConcurrentHashMap<Long, Integer> refs = CHUNK_REFS.get(WorldGenScope.dimension(level));
        return refs == null ? 0L : refs.size();
    }

    static int heldTreeCount(ServerLevel level) {
        if (level == null) {
            return 0;
        }
        WorldGenScope.DimensionKey scope = WorldGenScope.dimension(level);
        int count = 0;
        for (TreeKey key : TREE_CHUNKS.keySet()) {
            if (key != null && scope.equals(key.scope())) {
                count++;
            }
        }
        return count;
    }

    static String scopedDiagnostics(ServerLevel level) {
        if (level == null) {
            return "ticketTrees=0, heldChunks=0, appliedChunks=0";
        }
        Set<Long> applied = APPLIED_CHUNKS.get(WorldGenScope.dimension(level));
        return "ticketTrees=" + heldTreeCount(level)
            + ", heldChunks=" + heldChunkCount(level)
            + ", appliedChunks=" + (applied == null ? 0 : applied.size());
    }

    static String diagnostics() {
        int scopeCount = CHUNK_REFS.size();
        int treeCount = TREE_CHUNKS.size();
        long heldChunks = 0L;
        for (ConcurrentHashMap<Long, Integer> refs : CHUNK_REFS.values()) {
            if (refs != null) {
                heldChunks += refs.size();
            }
        }
        long appliedChunks = 0L;
        for (Set<Long> applied : APPLIED_CHUNKS.values()) {
            if (applied != null) {
                appliedChunks += applied.size();
            }
        }
        return "ticketScopes=" + scopeCount
            + ", ticketTrees=" + treeCount
            + ", heldChunks=" + heldChunks
            + ", appliedChunks=" + appliedChunks
            + ", acquired=" + TICKET_ACQUIRED.get()
            + ", released=" + TICKET_RELEASED.get()
            + ", acquireFail=" + TICKET_ACQUIRE_FAILURES.get()
            + ", releaseFail=" + TICKET_RELEASE_FAILURES.get()
            + ", retains=" + TREE_RETAINS.get()
            + ", releases=" + TREE_RELEASES.get()
            + ", reconcileRuns=" + RECONCILE_RUNS.get()
            + ", reconcilePending=" + RECONCILE_PENDING.size();
    }

    private static void scheduleReconcile(WorldGenScope.DimensionKey scope) {
        if (scope == null || !RECONCILE_PENDING.add(scope)) {
            return;
        }
        ServerRescheduler.runOnServer(() -> {
            try {
                reconcileScope(scope);
            } finally {
                RECONCILE_PENDING.remove(scope);
            }
        });
    }

    private static void reconcileScope(WorldGenScope.DimensionKey scope) {
        if (scope == null) {
            return;
        }
        RECONCILE_RUNS.incrementAndGet();
        ServerLevel level = findLevel(scope);
        ConcurrentHashMap<Long, Integer> desired = CHUNK_REFS.get(scope);
        Set<Long> applied = APPLIED_CHUNKS.computeIfAbsent(scope, ignored -> ConcurrentHashMap.newKeySet());

        if (level != null) {
            ArrayList<Long> toAdd = new ArrayList<>();
            if (desired != null) {
                for (Map.Entry<Long, Integer> entry : desired.entrySet()) {
                    Long packedChunk = entry.getKey();
                    Integer refs = entry.getValue();
                    if (packedChunk == null || refs == null || refs <= 0 || applied.contains(packedChunk)) {
                        continue;
                    }
                    toAdd.add(packedChunk);
                }
            }
            for (Long packedChunk : toAdd) {
                ChunkPos pos = new ChunkPos(packedChunk);
                try {
                    level.getChunkSource().addRegionTicket(LC2H_TREE_TICKET, pos, 2, pos, true);
                    applied.add(packedChunk);
                    TICKET_ACQUIRED.incrementAndGet();
                } catch (Throwable t) {
                    TICKET_ACQUIRE_FAILURES.incrementAndGet();
                    LC2H.LOGGER.debug("[LC2H] Failed to retain deferred-tree chunk ticket for chunk={}: {}", pos, t.toString());
                }
            }
        }

        ArrayList<Long> toRemove = new ArrayList<>();
        for (Long packedChunk : applied) {
            if (packedChunk == null) {
                continue;
            }
            int desiredRefs = desired == null ? 0 : Math.max(0, desired.getOrDefault(packedChunk, 0));
            if (desiredRefs <= 0 || level == null) {
                toRemove.add(packedChunk);
            }
        }
        if (level != null) {
            for (Long packedChunk : toRemove) {
                ChunkPos pos = new ChunkPos(packedChunk);
                try {
                    level.getChunkSource().removeRegionTicket(LC2H_TREE_TICKET, pos, 2, pos, true);
                    TICKET_RELEASED.incrementAndGet();
                } catch (Throwable t) {
                    TICKET_RELEASE_FAILURES.incrementAndGet();
                    LC2H.LOGGER.debug("[LC2H] Failed to release deferred-tree chunk ticket for chunk={}: {}", pos, t.toString());
                } finally {
                    applied.remove(packedChunk);
                }
            }
        } else {
            applied.clear();
        }

        if ((desired == null || desired.isEmpty()) && applied.isEmpty()) {
            APPLIED_CHUNKS.remove(scope, applied);
        }
    }

    private static ServerLevel findLevel(WorldGenScope.DimensionKey scope) {
        return findLevel(ServerRescheduler.getServer(), scope);
    }

    private static ServerLevel findLevel(MinecraftServer server, WorldGenScope.DimensionKey scope) {
        if (server == null || scope == null) {
            return null;
        }
        for (ServerLevel level : server.getAllLevels()) {
            if (level != null && WorldGenScope.matches(level, scope)) {
                return level;
            }
        }
        return null;
    }
}
