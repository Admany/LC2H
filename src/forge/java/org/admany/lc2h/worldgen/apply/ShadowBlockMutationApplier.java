package org.admany.lc2h.worldgen.apply;

import mcjty.lostcities.varia.ChunkCoord;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.TicketType;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.admany.lc2h.LC2H;
import org.admany.lc2h.concurrency.async.AsyncManager;
import org.admany.lc2h.concurrency.async.Priority;
import org.admany.lc2h.util.server.ServerRescheduler;
import org.admany.lc2h.util.server.ServerTickLoad;
import org.admany.lc2h.worldgen.scope.WorldGenScope;

import java.util.Comparator;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.Locale;

public final class ShadowBlockMutationApplier {

    private static final int MAX_MUTATIONS_PER_TICK = Math.max(64, Integer.getInteger("lc2h.shadowApply.max_mutations_per_tick", 768));
    private static final int MAX_CHUNKS_PER_TICK = Math.max(1, Integer.getInteger("lc2h.shadowApply.max_chunks_per_tick", 8));
    private static final TicketType<ChunkPos> LC2H_SHADOW_TICKET =
        TicketType.create("lc2h_shadow_apply", Comparator.comparingLong(ChunkPos::toLong));
    private static final PriorityBlockingQueue<ChunkMutationBatch> QUEUE =
        new PriorityBlockingQueue<>(128, Comparator.comparingInt(ChunkMutationBatch::remaining).reversed());
    private static final ConcurrentHashMap<ScopedChunk, ChunkMutationBatch> PENDING = new ConcurrentHashMap<>();
    private static final PriorityBlockingQueue<TransactionMutationBatch> TRANSACTION_QUEUE =
        new PriorityBlockingQueue<>(64, Comparator.comparingInt(TransactionMutationBatch::remaining).reversed());
    private static final ConcurrentHashMap<ScopedTransaction, TransactionMutationBatch> TRANSACTION_PENDING = new ConcurrentHashMap<>();
    private static final AtomicBoolean DRAIN_SCHEDULED = new AtomicBoolean(false);
    private static final AtomicBoolean DEFERRED_DRAIN_SCHEDULED = new AtomicBoolean(false);
    private static final AtomicBoolean STALE_SCOPE_LOGGED = new AtomicBoolean(false);
    private static final AtomicLong ENQUEUED_PLANS = new AtomicLong();
    private static final AtomicLong ENQUEUED_MUTATIONS = new AtomicLong();
    private static final AtomicLong ENQUEUED_TRANSACTIONS = new AtomicLong();
    private static final AtomicLong APPLIED_MUTATIONS = new AtomicLong();
    private static final AtomicLong SKIPPED_MUTATIONS = new AtomicLong();
    private static final AtomicLong STALE_SCOPE_DROPS = new AtomicLong();
    private static final AtomicLong MISSING_LEVEL_RETRIES = new AtomicLong();
    private static final AtomicLong MISSING_CHUNK_RETRIES = new AtomicLong();
    private static final AtomicLong APPLY_FAILURES = new AtomicLong();
    private static final AtomicLong EXPIRED_PLAN_DROPS = new AtomicLong();
    private static final AtomicLong RETRIED_PLANS = new AtomicLong();
    private static final AtomicLong RETRIED_ENTRIES = new AtomicLong();
    private static final AtomicLong GRAVITY_TICKS = new AtomicLong();
    private static final AtomicLong FLUID_TICKS = new AtomicLong();
    private static final AtomicLong ATTACHMENT_CHECKS = new AtomicLong();
    private static final AtomicLong TRANSACTION_BATCHES_APPLIED = new AtomicLong();
    private static final AtomicLong COMPLETED_TRANSACTIONS = new AtomicLong();
    private static final AtomicLong FAILED_TRANSACTIONS = new AtomicLong();
    private static final AtomicLong EXPIRED_TRANSACTIONS = new AtomicLong();
    private static final AtomicLong TOTAL_TRANSACTION_MUTATIONS = new AtomicLong();
    private static final AtomicLong MAX_TRANSACTION_MUTATIONS = new AtomicLong();
    private static final AtomicLong TOTAL_TRANSACTION_DEST_CHUNKS = new AtomicLong();
    private static final AtomicLong MAX_TRANSACTION_DEST_CHUNKS = new AtomicLong();
    private static final AtomicLong TOTAL_FINALIZATION_NS = new AtomicLong();
    private static final AtomicLong MAX_FINALIZATION_NS = new AtomicLong();
    private static final AtomicLong TOTAL_APPLY_NS = new AtomicLong();
    private static final AtomicLong MAX_APPLY_NS = new AtomicLong();
    private static final AtomicLong TOTAL_APPLY_CYCLES = new AtomicLong();
    private static final AtomicLong MAX_APPLY_CYCLES = new AtomicLong();
    private static final AtomicLong GRAVITY_OPERATIONS = new AtomicLong();
    private static final AtomicLong FLUID_OPERATIONS = new AtomicLong();
    private static final AtomicLong ATTACHMENT_OPERATIONS = new AtomicLong();
    private static final AtomicLong TICKET_ACQUIRED = new AtomicLong();
    private static final AtomicLong TICKET_RELEASED = new AtomicLong();
    private static final AtomicLong TICKET_ACQUIRE_FAILURES = new AtomicLong();
    private static final AtomicLong TICKET_RELEASE_FAILURES = new AtomicLong();
    private static final int DIAGNOSTIC_PENDING_TX_LIMIT = 3;
    private static final int DIAGNOSTIC_PENDING_CHUNK_LIMIT = 8;

    private ShadowBlockMutationApplier() {
    }

    private record ScopedChunk(WorldGenScope.DimensionKey scope, ChunkCoord chunk) {
        private static ScopedChunk of(ChunkShadowMutationPlan plan) {
            if (plan == null || plan.chunk() == null || plan.chunk().dimension() == null) {
                return null;
            }
            WorldGenScope.DimensionKey scope = plan.scope() == null
                ? WorldGenScope.dimension(plan.chunk().dimension())
                : plan.scope();
            return new ScopedChunk(scope, plan.chunk());
        }
    }

    private record ScopedTransaction(WorldGenScope.DimensionKey scope, long transactionId) {
    }

    private static final class ChunkMutationBatch {
        private final ScopedChunk key;
        private final java.util.concurrent.ConcurrentLinkedQueue<ChunkShadowMutationPlan> plans = new java.util.concurrent.ConcurrentLinkedQueue<>();
        private final AtomicInteger remaining = new AtomicInteger();
        private final AtomicBoolean queued = new AtomicBoolean();
        /** Pin the destination while a captured tree batch is being applied. */
        private final AtomicBoolean retainsChunkTicket = new AtomicBoolean(false);
        private final AtomicBoolean chunkTicketHeld = new AtomicBoolean(false);
        private ChunkShadowMutationPlan activePlan;
        private int activeIndex;

        private ChunkMutationBatch(ScopedChunk key) {
            this.key = key;
        }

        private int remaining() {
            return remaining.get();
        }

        private boolean hasWork() {
            return remaining() > 0 && (activePlan != null || !plans.isEmpty());
        }
    }

    private static final class TransactionMutationBatch {
        private final ScopedTransaction key;
        private final ConcurrentHashMap<ScopedChunk, ConcurrentLinkedQueue<ChunkShadowMutationPlan>> plansByChunk = new ConcurrentHashMap<>();
        private final AtomicInteger remaining = new AtomicInteger();
        private final AtomicBoolean queued = new AtomicBoolean();
        private final AtomicBoolean ticketsHeld = new AtomicBoolean(false);
        private final Set<Long> ticketedChunks = ConcurrentHashMap.newKeySet();
        private final AtomicInteger ticketAcquireFailures = new AtomicInteger();
        private volatile long firstTicketAcquireAtMs;
        private volatile long lastTicketAcquireAtMs;
        private volatile long lastTicketReleaseAtMs;

        private TransactionMutationBatch(ScopedTransaction key) {
            this.key = key;
        }

        private void add(ChunkShadowMutationPlan plan) {
            ScopedChunk chunkKey = ScopedChunk.of(plan);
            if (chunkKey == null) {
                return;
            }
            plansByChunk.computeIfAbsent(chunkKey, ignored -> new ConcurrentLinkedQueue<>()).offer(plan);
            remaining.addAndGet(plan.size());
        }

        private int remaining() {
            return remaining.get();
        }

        private int chunkCount() {
            return plansByChunk.size();
        }

        private boolean hasWork() {
            return remaining() > 0 && !plansByChunk.isEmpty();
        }

        private boolean allChunksLoaded(ServerLevel level) {
            for (ScopedChunk chunkKey : plansByChunk.keySet()) {
                ChunkCoord chunk = chunkKey == null ? null : chunkKey.chunk();
                if (chunk == null || level.getChunkSource().getChunkNow(chunk.chunkX(), chunk.chunkZ()) == null) {
                    return false;
                }
            }
            return true;
        }

        private List<ChunkShadowMutationPlan> drainPlansDeterministic() {
            List<ChunkShadowMutationPlan> drained = new ArrayList<>();
            for (Map.Entry<ScopedChunk, ConcurrentLinkedQueue<ChunkShadowMutationPlan>> entry : sortedEntries()) {
                ConcurrentLinkedQueue<ChunkShadowMutationPlan> queue = entry.getValue();
                if (queue == null) {
                    continue;
                }
                for (ChunkShadowMutationPlan plan; (plan = queue.poll()) != null; ) {
                    drained.add(plan);
                }
            }
            plansByChunk.clear();
            remaining.set(0);
            drained.sort(Comparator
                .comparingInt((ChunkShadowMutationPlan plan) -> plan.chunk().chunkX())
                .thenComparingInt(plan -> plan.chunk().chunkZ())
                .thenComparingLong(ChunkShadowMutationPlan::createdAtMs));
            return drained;
        }

        private Collection<ChunkShadowMutationPlan> snapshotPlans() {
            List<ChunkShadowMutationPlan> snapshot = new ArrayList<>();
            for (Map.Entry<ScopedChunk, ConcurrentLinkedQueue<ChunkShadowMutationPlan>> entry : sortedEntries()) {
                ConcurrentLinkedQueue<ChunkShadowMutationPlan> queue = entry.getValue();
                if (queue != null) {
                    snapshot.addAll(queue);
                }
            }
            snapshot.sort(Comparator
                .comparingInt((ChunkShadowMutationPlan plan) -> plan.chunk().chunkX())
                .thenComparingInt(plan -> plan.chunk().chunkZ())
                .thenComparingLong(ChunkShadowMutationPlan::createdAtMs));
            return snapshot;
        }

        private List<Map.Entry<ScopedChunk, ConcurrentLinkedQueue<ChunkShadowMutationPlan>>> sortedEntries() {
            List<Map.Entry<ScopedChunk, ConcurrentLinkedQueue<ChunkShadowMutationPlan>>> entries =
                new ArrayList<>(plansByChunk.entrySet());
            entries.sort(Comparator.comparingInt((Map.Entry<ScopedChunk, ConcurrentLinkedQueue<ChunkShadowMutationPlan>> entry) ->
                    entry.getKey().chunk().chunkX())
                .thenComparingInt(entry -> entry.getKey().chunk().chunkZ()));
            return entries;
        }
    }

    public static void enqueue(ChunkShadowMutationPlan plan) {
        enqueueInternal(plan, false);
    }

    public static void enqueueDeferred(ChunkShadowMutationPlan plan) {
        enqueueInternal(plan, true);
    }

    private static void enqueueInternal(ChunkShadowMutationPlan plan, boolean deferDrain) {
        if (plan == null || plan.chunk() == null || plan.size() == 0) {
            return;
        }
        if (plan.isExpired(System.currentTimeMillis())) {
            EXPIRED_PLAN_DROPS.incrementAndGet();
            if (plan.transactionId() > 0L) {
                EXPIRED_TRANSACTIONS.incrementAndGet();
            }
            return;
        }
        ScopedChunk key = ScopedChunk.of(plan);
        if (key == null || key.scope() == null) {
            return;
        }
        ShadowMutationTraceRegistry.recordPlanQueued(plan);
        if (plan.transactionId() > 0L) {
            ScopedTransaction txKey = new ScopedTransaction(key.scope(), plan.transactionId());
            TransactionMutationBatch txBatch = TRANSACTION_PENDING.computeIfAbsent(txKey, ignored -> {
                ENQUEUED_TRANSACTIONS.incrementAndGet();
                return new TransactionMutationBatch(txKey);
            });
            txBatch.add(plan);
            ENQUEUED_PLANS.incrementAndGet();
            ENQUEUED_MUTATIONS.addAndGet(plan.size());
            offerTransactionBatch(txBatch);
            if (deferDrain) {
                scheduleDrainDeferred();
            } else {
                scheduleDrain();
            }
            return;
        }
        ChunkMutationBatch batch = PENDING.computeIfAbsent(key, ChunkMutationBatch::new);
        if (plan.kind() == ChunkShadowMutationPlan.MutationKind.TREE_CAPTURE
            || plan.kind() == ChunkShadowMutationPlan.MutationKind.TREE_FALLBACK) {
            batch.retainsChunkTicket.set(true);
        }
        batch.plans.offer(plan);
        batch.remaining.addAndGet(plan.size());
        ENQUEUED_PLANS.incrementAndGet();
        ENQUEUED_MUTATIONS.addAndGet(plan.size());
        offerChunkBatch(batch);
        if (deferDrain) {
            scheduleDrainDeferred();
        } else {
            scheduleDrain();
        }
    }

    private static void scheduleDrain() {
        MinecraftServer server = ServerRescheduler.getServer();
        if (server == null) {
            return;
        }
        if (!DRAIN_SCHEDULED.compareAndSet(false, true)) {
            return;
        }
        try {
            ServerRescheduler.runOnServer(() -> {
                DRAIN_SCHEDULED.set(false);
                drain();
            });
        } catch (Throwable t) {
            DRAIN_SCHEDULED.set(false);
            LC2H.LOGGER.debug("[LC2H] Failed to schedule shadow drain: {}", t.toString());
        }
    }

    private static void scheduleDrainDeferred() {
        MinecraftServer server = ServerRescheduler.getServer();
        if (server == null) {
            return;
        }
        if (!DEFERRED_DRAIN_SCHEDULED.compareAndSet(false, true)) {
            return;
        }
        try {
            AsyncManager.runLater("shadow-apply-drain-deferred", () -> {
                DEFERRED_DRAIN_SCHEDULED.set(false);
                scheduleDrain();
            }, 1L, Priority.LOW);
        } catch (Throwable t) {
            DEFERRED_DRAIN_SCHEDULED.set(false);
            LC2H.LOGGER.debug("[LC2H] Failed to schedule deferred shadow drain: {}", t.toString());
        }
    }

    /** Keep one queue token for all slices targeting a chunk. */
    private static void offerChunkBatch(ChunkMutationBatch batch) {
        if (batch != null && batch.queued.compareAndSet(false, true)) {
            QUEUE.offer(batch);
        }
    }

    private static void offerTransactionBatch(TransactionMutationBatch batch) {
        if (batch != null && batch.queued.compareAndSet(false, true)) {
            TRANSACTION_QUEUE.offer(batch);
        }
    }

    private static void drain() {
        DRAIN_SCHEDULED.set(false);
        int remainingMutations = MAX_MUTATIONS_PER_TICK;
        if (ServerTickLoad.getElapsedMsInCurrentTick() >= 12.0D) {
            // Allow two small tree slices when the tick still has headroom.
            remainingMutations = Math.min(remainingMutations, 192);
        }
        int processedChunks = 0;
        boolean hasPending = false;

        while (!TRANSACTION_QUEUE.isEmpty() && processedChunks < MAX_CHUNKS_PER_TICK) {
            TransactionMutationBatch txBatch = TRANSACTION_QUEUE.poll();
            if (txBatch == null || txBatch.key == null || txBatch.key.scope() == null) {
                continue;
            }
            txBatch.queued.set(false);
            var server = ServerRescheduler.getServer();
            ServerLevel level = null;
            if (server != null) {
                for (ServerLevel candidate : server.getAllLevels()) {
                    if (candidate != null && WorldGenScope.matches(candidate, txBatch.key.scope())) {
                        level = candidate;
                        break;
                    }
                }
            }
            if (level == null) {
                MISSING_LEVEL_RETRIES.incrementAndGet();
                hasPending = true;
                offerTransactionBatch(txBatch);
                break;
            }
            if (!WorldGenScope.matches(level, txBatch.key.scope())) {
                logStaleScopeOnce(level, txBatch.key.scope(), null, txBatch.remaining(), true);
                releaseTransactionTickets(level, txBatch);
                STALE_SCOPE_DROPS.addAndGet(Math.max(0, txBatch.remaining()));
                TRANSACTION_PENDING.remove(txBatch.key, txBatch);
                continue;
            }
            retainTransactionTickets(level, txBatch);
            if (!txBatch.allChunksLoaded(level)) {
                MISSING_CHUNK_RETRIES.incrementAndGet();
                hasPending = true;
                offerTransactionBatch(txBatch);
                continue;
            }

            List<ChunkShadowMutationPlan> plans = txBatch.drainPlansDeterministic();
            if (plans.isEmpty()) {
                releaseTransactionTickets(level, txBatch);
                TRANSACTION_PENDING.remove(txBatch.key, txBatch);
                continue;
            }

            ShadowMutationTransactionView transactionView = ShadowMutationTransactionView.of(level, plans);
            long txStartedNs = System.nanoTime();
            int txMutations = 0;
            int txApplied = 0;
            int txSkipped = 0;
            int txRetriedEntries = 0;
            List<ChunkShadowMutationPlan> retryPlans = new ArrayList<>();
            for (ChunkShadowMutationPlan plan : plans) {
                long planStartedNs = System.nanoTime();
                ShadowMutationFinalizer.Result result = ShadowMutationFinalizer.apply(level, plan, transactionView);
                long applyNs = System.nanoTime() - planStartedNs;
                TOTAL_APPLY_NS.addAndGet(applyNs);
                updateMax(MAX_APPLY_NS, applyNs);
                APPLIED_MUTATIONS.addAndGet(result.applied());
                SKIPPED_MUTATIONS.addAndGet(result.skipped());
                RETRIED_ENTRIES.addAndGet(result.retriedEntries());
                GRAVITY_TICKS.addAndGet(result.gravityTicks());
                FLUID_TICKS.addAndGet(result.fluidTicks());
                ATTACHMENT_CHECKS.addAndGet(result.attachmentChecks());
                GRAVITY_OPERATIONS.addAndGet(result.gravityOps());
                FLUID_OPERATIONS.addAndGet(result.fluidOps());
                ATTACHMENT_OPERATIONS.addAndGet(result.attachmentOps());
                txMutations += plan.size();
                txApplied += result.applied();
                txSkipped += result.skipped();
                txRetriedEntries += result.retriedEntries();
                if (result.retryPlan() != null) {
                    RETRIED_PLANS.incrementAndGet();
                    retryPlans.add(result.retryPlan());
                }
                if (result.applied() == 0 && result.skipped() == 0 && result.retriedEntries() == 0 && plan.size() > 0) {
                    APPLY_FAILURES.incrementAndGet();
                    LC2H.LOGGER.debug("[LC2H] Shadow mutation transaction produced no work for tx={} chunk={}", plan.transactionId(), plan.chunk());
                }
            }
            long txNs = System.nanoTime() - txStartedNs;
            TRANSACTION_BATCHES_APPLIED.incrementAndGet();
            COMPLETED_TRANSACTIONS.incrementAndGet();
            TOTAL_TRANSACTION_MUTATIONS.addAndGet(txMutations);
            updateMax(MAX_TRANSACTION_MUTATIONS, txMutations);
            TOTAL_TRANSACTION_DEST_CHUNKS.addAndGet(txBatch.chunkCount());
            updateMax(MAX_TRANSACTION_DEST_CHUNKS, txBatch.chunkCount());
            TOTAL_FINALIZATION_NS.addAndGet(txNs);
            updateMax(MAX_FINALIZATION_NS, txNs);
            TOTAL_APPLY_CYCLES.incrementAndGet();
            updateMax(MAX_APPLY_CYCLES, 1L);
            if (txApplied == 0 && txSkipped == 0 && txRetriedEntries == 0 && txMutations > 0) {
                FAILED_TRANSACTIONS.incrementAndGet();
            }
            releaseTransactionTickets(level, txBatch);
            TRANSACTION_PENDING.remove(txBatch.key, txBatch);
            for (ChunkShadowMutationPlan retryPlan : retryPlans) {
                scheduleRetry(retryPlan);
            }
            remainingMutations -= Math.max(1, txMutations);
            processedChunks += Math.max(1, txBatch.chunkCount());
            if (remainingMutations <= 0) {
                hasPending = hasPending || !TRANSACTION_QUEUE.isEmpty();
                break;
            }
        }

        while (remainingMutations > 0 && processedChunks < MAX_CHUNKS_PER_TICK) {
            ChunkMutationBatch batch = QUEUE.poll();
            if (batch == null) {
                break;
            }
            batch.queued.set(false);
            ScopedChunk key = batch.key;
            ChunkCoord chunk = key == null ? null : key.chunk();
            if (chunk == null || chunk.dimension() == null) {
                continue;
            }

            var server = ServerRescheduler.getServer();
            ServerLevel level = server == null ? null : server.getLevel(chunk.dimension());
            if (level == null) {
                MISSING_LEVEL_RETRIES.incrementAndGet();
                hasPending = true;
                offerChunkBatch(batch);
                break;
            }
            if (!WorldGenScope.matches(level, key.scope())) {
                logStaleScopeOnce(level, key.scope(), chunk, batch.remaining(), false);
                STALE_SCOPE_DROPS.addAndGet(Math.max(0, batch.remaining()));
                releaseChunkTicket(level, batch);
                PENDING.remove(key, batch);
                continue;
            }
            retainChunkTicket(level, batch);
            LevelChunk levelChunk = level.getChunkSource().getChunkNow(chunk.chunkX(), chunk.chunkZ());
            if (levelChunk == null) {
                MISSING_CHUNK_RETRIES.incrementAndGet();
                hasPending = true;
                offerChunkBatch(batch);
                break;
            }

            processedChunks++;
            while (remainingMutations > 0) {
                ChunkShadowMutationPlan plan = batch.activePlan;
                if (plan == null) {
                    plan = batch.plans.poll();
                    batch.activePlan = plan;
                    batch.activeIndex = 0;
                }
                if (plan == null) {
                    break;
                }
                batch.activeIndex = plan.size();
                long planStartedNs = System.nanoTime();
                ShadowMutationFinalizer.Result result = ShadowMutationFinalizer.apply(level, plan);
                long applyNs = System.nanoTime() - planStartedNs;
                TOTAL_APPLY_NS.addAndGet(applyNs);
                updateMax(MAX_APPLY_NS, applyNs);
                APPLIED_MUTATIONS.addAndGet(result.applied());
                SKIPPED_MUTATIONS.addAndGet(result.skipped());
                RETRIED_ENTRIES.addAndGet(result.retriedEntries());
                GRAVITY_TICKS.addAndGet(result.gravityTicks());
                FLUID_TICKS.addAndGet(result.fluidTicks());
                ATTACHMENT_CHECKS.addAndGet(result.attachmentChecks());
                GRAVITY_OPERATIONS.addAndGet(result.gravityOps());
                FLUID_OPERATIONS.addAndGet(result.fluidOps());
                ATTACHMENT_OPERATIONS.addAndGet(result.attachmentOps());
                TOTAL_APPLY_CYCLES.incrementAndGet();
                updateMax(MAX_APPLY_CYCLES, 1L);
                if (result.retryPlan() != null) {
                    RETRIED_PLANS.incrementAndGet();
                    scheduleRetry(result.retryPlan());
                }
                if (result.applied() == 0 && result.skipped() == 0 && result.retriedEntries() == 0 && plan.size() > 0) {
                    APPLY_FAILURES.incrementAndGet();
                    LC2H.LOGGER.debug("[LC2H] Shadow mutation apply produced no work for tx={} chunk={}", plan.transactionId(), chunk);
                }
                batch.remaining.addAndGet(-plan.size());
                remainingMutations -= Math.max(1, plan.size());
                batch.activePlan = null;
                batch.activeIndex = 0;
                if (remainingMutations <= 0 || ServerTickLoad.shouldPauseNonCritical(level.getServer())) {
                    break;
                }
            }

            if (batch.hasWork()) {
                hasPending = true;
                offerChunkBatch(batch);
            } else {
                releaseChunkTicket(level, batch);
                PENDING.remove(key, batch);
            }
        }

        if (hasPending || !QUEUE.isEmpty() || !TRANSACTION_QUEUE.isEmpty()) {
            AsyncManager.runLater("shadow-apply-drain", ShadowBlockMutationApplier::scheduleDrain, 1L, Priority.LOW);
        }
    }

    public static void clearAll() {
        releaseAllTransactionTickets();
        var server = ServerRescheduler.getServer();
        for (Map.Entry<ScopedChunk, ChunkMutationBatch> entry : PENDING.entrySet()) {
            ScopedChunk key = entry.getKey();
            ChunkMutationBatch batch = entry.getValue();
            releaseChunkTicket(findLevel(server, key == null ? null : key.scope()), batch);
        }
        QUEUE.clear();
        PENDING.clear();
        TRANSACTION_QUEUE.clear();
        TRANSACTION_PENDING.clear();
        DRAIN_SCHEDULED.set(false);
        DEFERRED_DRAIN_SCHEDULED.set(false);
    }

    public static boolean hasPendingWork() {
        return !QUEUE.isEmpty() || !TRANSACTION_QUEUE.isEmpty() || !PENDING.isEmpty() || !TRANSACTION_PENDING.isEmpty();
    }

    public static boolean hasPendingWork(ServerLevel level, Set<Long> interestChunks) {
        return pendingChunkCount(level, interestChunks) > 0
            || pendingTransactionCount(level, interestChunks) > 0
            || heldTicketCount(level, interestChunks) > 0L;
    }

    public static RuntimeSnapshot runtimeSnapshot() {
        return new RuntimeSnapshot(
            PENDING.size(),
            TRANSACTION_PENDING.size(),
            QUEUE.size(),
            TRANSACTION_QUEUE.size(),
            heldTicketCount(),
            FAILED_TRANSACTIONS.get(),
            APPLY_FAILURES.get(),
            RETRIED_PLANS.get(),
            RETRIED_ENTRIES.get(),
            APPLIED_MUTATIONS.get(),
            COMPLETED_TRANSACTIONS.get()
        );
    }

    public static int getPendingTransactionCount() {
        return TRANSACTION_PENDING.size();
    }

    public static int getPendingTransactionCount(ServerLevel level, Set<Long> interestChunks) {
        return pendingTransactionCount(level, interestChunks);
    }

    public static long getHeldTicketCount() {
        return heldTicketCount();
    }

    public static long getHeldTicketCount(ServerLevel level, Set<Long> interestChunks) {
        return heldTicketCount(level, interestChunks);
    }

    public static long getTicketAcquiredCount() {
        return TICKET_ACQUIRED.get();
    }

    public static long getTicketReleasedCount() {
        return TICKET_RELEASED.get();
    }

    public static List<String> pendingTransactionDetails() {
        if (TRANSACTION_PENDING.isEmpty()) {
            return List.of();
        }
        var server = ServerRescheduler.getServer();
        List<String> details = new ArrayList<>();
        for (Map.Entry<ScopedTransaction, TransactionMutationBatch> entry : sortedPendingTransactions()) {
            ScopedTransaction txKey = entry.getKey();
            TransactionMutationBatch batch = entry.getValue();
            ServerLevel level = findLevel(server, txKey == null ? null : txKey.scope());
            ArrayList<String> destinationChunks = new ArrayList<>();
            ArrayList<String> loadedChunks = new ArrayList<>();
            ArrayList<String> missingChunks = new ArrayList<>();
            ArrayList<String> ticketedChunks = new ArrayList<>();
            long oldestCreatedAt = Long.MAX_VALUE;
            for (ChunkShadowMutationPlan plan : batch.snapshotPlans()) {
                if (plan != null && plan.createdAtMs() > 0L) {
                    oldestCreatedAt = Math.min(oldestCreatedAt, plan.createdAtMs());
                }
            }
            for (Map.Entry<ScopedChunk, ConcurrentLinkedQueue<ChunkShadowMutationPlan>> chunkEntry : batch.sortedEntries()) {
                ScopedChunk scopedChunk = chunkEntry.getKey();
                ChunkCoord chunk = scopedChunk == null ? null : scopedChunk.chunk();
                if (chunk == null) {
                    continue;
                }
                String coord = chunk.chunkX() + "," + chunk.chunkZ();
                destinationChunks.add(coord);
                if (level != null && level.getChunkSource().getChunkNow(chunk.chunkX(), chunk.chunkZ()) != null) {
                    loadedChunks.add(coord);
                } else {
                    missingChunks.add(coord);
                }
                if (batch.ticketedChunks.contains(new ChunkPos(chunk.chunkX(), chunk.chunkZ()).toLong())) {
                    ticketedChunks.add(coord);
                }
            }
            long ageMs = oldestCreatedAt == Long.MAX_VALUE ? 0L : Math.max(0L, System.currentTimeMillis() - oldestCreatedAt);
            details.add("tx=" + (txKey == null ? "unknown" : txKey.transactionId())
                + " scope=" + (txKey == null ? "unknown" : txKey.scope())
                + " destinations=" + destinationChunks.size() + destinationChunks
                + " loaded=" + loadedChunks.size() + loadedChunks
                + " missing=" + missingChunks.size() + missingChunks
                + " ticketed=" + ticketedChunks.size() + ticketedChunks
                + " ready=" + missingChunks.isEmpty()
                + " ticketsHeld=" + batch.ticketsHeld.get()
                + " acquireFail=" + batch.ticketAcquireFailures.get()
                + " firstTicketAt=" + batch.firstTicketAcquireAtMs
                + " lastTicketAt=" + batch.lastTicketAcquireAtMs
                + " lastReleaseAt=" + batch.lastTicketReleaseAtMs
                + " remaining=" + batch.remaining()
                + " ageMs=" + ageMs);
        }
        return details;
    }

    public static int forceDrainForDebug(int maxCycles) {
        int limit = Math.max(1, maxCycles);
        int cycles = 0;
        while (cycles < limit && hasPendingWork()) {
            drain();
            cycles++;
        }
        return cycles;
    }

    public record RuntimeSnapshot(int pendingChunks,
                                  int pendingTransactions,
                                  int queuedBatches,
                                  int queuedTransactionBatches,
                                  long heldTickets,
                                  long failedTransactions,
                                  long applyFailures,
                                  long retriedPlans,
                                  long retriedEntries,
                                  long appliedMutations,
                                  long completedTransactions) {
        public boolean hasPendingWork() {
            return pendingChunks > 0
                || pendingTransactions > 0
                || queuedBatches > 0
                || queuedTransactionBatches > 0
                || heldTickets > 0;
        }
    }

    private static void scheduleRetry(ChunkShadowMutationPlan plan) {
        if (plan == null || plan.size() == 0) {
            return;
        }
        if (plan.isExpired(System.currentTimeMillis())) {
            EXPIRED_PLAN_DROPS.incrementAndGet();
            if (plan.transactionId() > 0L) {
                EXPIRED_TRANSACTIONS.incrementAndGet();
            }
            return;
        }
        AsyncManager.runLater("shadow-apply-retry", () -> enqueue(plan), 1L, Priority.LOW);
    }

    private static void retainChunkTicket(ServerLevel level, ChunkMutationBatch batch) {
        if (level == null || batch == null || !batch.retainsChunkTicket.get()
            || batch.chunkTicketHeld.get() || batch.key == null || batch.key.chunk() == null) {
            return;
        }
        ChunkCoord chunk = batch.key.chunk();
        ChunkPos pos = new ChunkPos(chunk.chunkX(), chunk.chunkZ());
        try {
            level.getChunkSource().addRegionTicket(LC2H_SHADOW_TICKET, pos, 2, pos);
            if (batch.chunkTicketHeld.compareAndSet(false, true)) {
                TICKET_ACQUIRED.incrementAndGet();
            } else {
                level.getChunkSource().removeRegionTicket(LC2H_SHADOW_TICKET, pos, 2, pos);
            }
        } catch (Throwable t) {
            LC2H.LOGGER.debug("[LC2H] Failed to retain tree shadow chunk ticket for {}: {}", pos, t.toString());
        }
    }

    private static void releaseChunkTicket(ServerLevel level, ChunkMutationBatch batch) {
        if (level == null || batch == null || !batch.chunkTicketHeld.compareAndSet(true, false)
            || batch.key == null || batch.key.chunk() == null) {
            return;
        }
        ChunkCoord chunk = batch.key.chunk();
        ChunkPos pos = new ChunkPos(chunk.chunkX(), chunk.chunkZ());
        try {
            level.getChunkSource().removeRegionTicket(LC2H_SHADOW_TICKET, pos, 2, pos);
            TICKET_RELEASED.incrementAndGet();
        } catch (Throwable t) {
            TICKET_RELEASE_FAILURES.incrementAndGet();
            LC2H.LOGGER.debug("[LC2H] Failed to release tree shadow chunk ticket for {}: {}", pos, t.toString());
        }
    }

    public static String diagnostics() {
        long oldestCreatedAt = Long.MAX_VALUE;
        for (ChunkMutationBatch batch : PENDING.values()) {
            if (batch == null) {
                continue;
            }
            ChunkShadowMutationPlan active = batch.activePlan;
            if (active != null && active.createdAtMs() > 0L) {
                oldestCreatedAt = Math.min(oldestCreatedAt, active.createdAtMs());
            }
            for (ChunkShadowMutationPlan plan : batch.plans) {
                if (plan != null && plan.createdAtMs() > 0L) {
                    oldestCreatedAt = Math.min(oldestCreatedAt, plan.createdAtMs());
                }
            }
        }
        for (TransactionMutationBatch batch : TRANSACTION_PENDING.values()) {
            if (batch == null) {
                continue;
            }
            for (ChunkShadowMutationPlan plan : batch.snapshotPlans()) {
                if (plan != null && plan.createdAtMs() > 0L) {
                    oldestCreatedAt = Math.min(oldestCreatedAt, plan.createdAtMs());
                }
            }
        }
        long oldestAgeMs = oldestCreatedAt == Long.MAX_VALUE ? 0L : Math.max(0L, System.currentTimeMillis() - oldestCreatedAt);
        long completedTransactions = COMPLETED_TRANSACTIONS.get();
        long totalTransactionMutations = TOTAL_TRANSACTION_MUTATIONS.get();
        long totalDestinationChunks = TOTAL_TRANSACTION_DEST_CHUNKS.get();
        long totalApplyCycles = TOTAL_APPLY_CYCLES.get();
        double avgTransactionSize = completedTransactions == 0L ? 0.0D : totalTransactionMutations / (double) completedTransactions;
        double avgDestinationChunks = completedTransactions == 0L ? 0.0D : totalDestinationChunks / (double) completedTransactions;
        double avgFinalizationMs = completedTransactions == 0L ? 0.0D : (TOTAL_FINALIZATION_NS.get() / 1_000_000.0D) / completedTransactions;
        double avgApplyMs = totalApplyCycles == 0L ? 0.0D : (TOTAL_APPLY_NS.get() / 1_000_000.0D) / totalApplyCycles;
        long heldTickets = heldTicketCount();
        return "pendingChunks=" + PENDING.size()
            + ", pendingTransactions=" + TRANSACTION_PENDING.size()
            + ", queuedBatches=" + QUEUE.size()
            + ", queuedTransactionBatches=" + TRANSACTION_QUEUE.size()
            + ", enqueuedTransactions=" + ENQUEUED_TRANSACTIONS.get()
            + ", completedTransactions=" + completedTransactions
            + ", failedTransactions=" + FAILED_TRANSACTIONS.get()
            + ", expiredTransactions=" + EXPIRED_TRANSACTIONS.get()
            + ", enqueuedPlans=" + ENQUEUED_PLANS.get()
            + ", enqueuedMutations=" + ENQUEUED_MUTATIONS.get()
            + ", appliedTransactions=" + TRANSACTION_BATCHES_APPLIED.get()
            + ", applied=" + APPLIED_MUTATIONS.get()
            + ", skipped=" + SKIPPED_MUTATIONS.get()
            + ", failures=" + APPLY_FAILURES.get()
            + ", expiredDrops=" + EXPIRED_PLAN_DROPS.get()
            + ", retriedPlans=" + RETRIED_PLANS.get()
            + ", retriedEntries=" + RETRIED_ENTRIES.get()
            + ", avgTransactionSize=" + String.format(java.util.Locale.ROOT, "%.2f", avgTransactionSize)
            + ", maxTransactionSize=" + MAX_TRANSACTION_MUTATIONS.get()
            + ", avgDestChunks=" + String.format(java.util.Locale.ROOT, "%.2f", avgDestinationChunks)
            + ", maxDestChunks=" + MAX_TRANSACTION_DEST_CHUNKS.get()
            + ", avgFinalizationMs=" + String.format(java.util.Locale.ROOT, "%.3f", avgFinalizationMs)
            + ", maxFinalizationMs=" + String.format(java.util.Locale.ROOT, "%.3f", MAX_FINALIZATION_NS.get() / 1_000_000.0D)
            + ", avgApplyMs=" + String.format(java.util.Locale.ROOT, "%.3f", avgApplyMs)
            + ", maxApplyMs=" + String.format(java.util.Locale.ROOT, "%.3f", MAX_APPLY_NS.get() / 1_000_000.0D)
            + ", maxApplyCycles=" + MAX_APPLY_CYCLES.get()
            + ", gravityTicks=" + GRAVITY_TICKS.get()
            + ", fluidTicks=" + FLUID_TICKS.get()
            + ", attachmentChecks=" + ATTACHMENT_CHECKS.get()
            + ", gravityOps=" + GRAVITY_OPERATIONS.get()
            + ", fluidOps=" + FLUID_OPERATIONS.get()
            + ", attachmentOps=" + ATTACHMENT_OPERATIONS.get()
            + ", neighborUpdates=" + ShadowMutationFinalizer.neighborUpdatesEnabled()
            + ", tickets[acquired=" + TICKET_ACQUIRED.get()
            + ", released=" + TICKET_RELEASED.get()
            + ", acquireFail=" + TICKET_ACQUIRE_FAILURES.get()
            + ", releaseFail=" + TICKET_RELEASE_FAILURES.get()
            + ", held=" + heldTickets + "]"
            + ", staleDrops=" + STALE_SCOPE_DROPS.get()
            + ", missingLevelRetries=" + MISSING_LEVEL_RETRIES.get()
            + ", missingChunkRetries=" + MISSING_CHUNK_RETRIES.get()
            + ", oldestAgeMs=" + oldestAgeMs
            + ", pendingTx=" + pendingTransactionSummary();
    }

    private static String pendingTransactionSummary() {
        if (TRANSACTION_PENDING.isEmpty()) {
            return "[]";
        }
        var server = ServerRescheduler.getServer();
        List<String> parts = new ArrayList<>();
        int shown = 0;
        for (Map.Entry<ScopedTransaction, TransactionMutationBatch> entry : sortedPendingTransactions()) {
            if (shown++ >= DIAGNOSTIC_PENDING_TX_LIMIT) {
                parts.add("...+" + Math.max(0, TRANSACTION_PENDING.size() - DIAGNOSTIC_PENDING_TX_LIMIT));
                break;
            }
            ScopedTransaction txKey = entry.getKey();
            TransactionMutationBatch batch = entry.getValue();
            ServerLevel level = findLevel(server, txKey == null ? null : txKey.scope());
            List<String> missingChunks = new ArrayList<>();
            int chunkCount = 0;
            int loadedChunks = 0;
            long oldestCreatedAt = Long.MAX_VALUE;
            for (ChunkShadowMutationPlan plan : batch.snapshotPlans()) {
                if (plan == null) {
                    continue;
                }
                oldestCreatedAt = plan.createdAtMs() > 0L ? Math.min(oldestCreatedAt, plan.createdAtMs()) : oldestCreatedAt;
            }
            for (Map.Entry<ScopedChunk, ConcurrentLinkedQueue<ChunkShadowMutationPlan>> chunkEntry : batch.sortedEntries()) {
                ScopedChunk scopedChunk = chunkEntry.getKey();
                ChunkCoord chunk = scopedChunk == null ? null : scopedChunk.chunk();
                if (chunk == null) {
                    continue;
                }
                chunkCount++;
                if (level == null || level.getChunkSource().getChunkNow(chunk.chunkX(), chunk.chunkZ()) == null) {
                    if (missingChunks.size() < DIAGNOSTIC_PENDING_CHUNK_LIMIT) {
                        missingChunks.add(chunk.chunkX() + "," + chunk.chunkZ());
                    }
                } else {
                    loadedChunks++;
                }
            }
            long ageMs = oldestCreatedAt == Long.MAX_VALUE ? 0L : Math.max(0L, System.currentTimeMillis() - oldestCreatedAt);
            parts.add("tx=" + (txKey == null ? "unknown" : txKey.transactionId())
                + "/scope=" + (txKey == null ? "unknown" : txKey.scope())
                + "/dest=" + chunkCount
                + "/ticketed=" + batch.ticketedChunks.size()
                + "/loaded=" + loadedChunks
                + "/ready=" + (loadedChunks == chunkCount)
                + "/ticketsHeld=" + batch.ticketsHeld.get()
                + "/acquireFail=" + batch.ticketAcquireFailures.get()
                + "/firstTicketAt=" + batch.firstTicketAcquireAtMs
                + "/lastTicketAt=" + batch.lastTicketAcquireAtMs
                + "/lastReleaseAt=" + batch.lastTicketReleaseAtMs
                + "/missing=" + missingChunks
                + "/ageMs=" + ageMs
                + "/remaining=" + batch.remaining());
        }
        return "[" + String.join("; ", parts) + "]";
    }

    private static List<Map.Entry<ScopedTransaction, TransactionMutationBatch>> sortedPendingTransactions() {
        List<Map.Entry<ScopedTransaction, TransactionMutationBatch>> entries = new ArrayList<>(TRANSACTION_PENDING.entrySet());
        entries.sort(Comparator
            .comparingLong((Map.Entry<ScopedTransaction, TransactionMutationBatch> entry) ->
                entry.getKey() == null ? Long.MAX_VALUE : entry.getKey().transactionId())
            .thenComparing(entry -> String.valueOf(entry.getKey() == null ? null : entry.getKey().scope())));
        return entries;
    }

    private static ServerLevel findLevel(net.minecraft.server.MinecraftServer server, WorldGenScope.DimensionKey scope) {
        if (server == null || scope == null) {
            return null;
        }
        for (ServerLevel candidate : server.getAllLevels()) {
            if (candidate != null && WorldGenScope.matches(candidate, scope)) {
                return candidate;
            }
        }
        return null;
    }

    private static long heldTicketCount() {
        long total = 0L;
        for (TransactionMutationBatch batch : TRANSACTION_PENDING.values()) {
            if (batch != null) {
                total += batch.ticketedChunks.size();
            }
        }
        return total;
    }

    public static int getPendingChunkCount(ServerLevel level, Set<Long> interestChunks) {
        return pendingChunkCount(level, interestChunks);
    }

    private static int pendingChunkCount(ServerLevel level, Set<Long> interestChunks) {
        if (level == null || interestChunks == null || interestChunks.isEmpty()) {
            return PENDING.size();
        }
        int total = 0;
        for (Map.Entry<ScopedChunk, ChunkMutationBatch> entry : PENDING.entrySet()) {
            ScopedChunk scopedChunk = entry.getKey();
            if (!matchesInterest(level, interestChunks, scopedChunk == null ? null : scopedChunk.chunk())) {
                continue;
            }
            ChunkMutationBatch batch = entry.getValue();
            if (batch != null && batch.hasWork()) {
                total++;
            }
        }
        return total;
    }

    private static int pendingTransactionCount(ServerLevel level, Set<Long> interestChunks) {
        if (level == null || interestChunks == null || interestChunks.isEmpty()) {
            return TRANSACTION_PENDING.size();
        }
        int total = 0;
        for (TransactionMutationBatch batch : TRANSACTION_PENDING.values()) {
            if (batch == null || !batch.hasWork() || !intersectsInterest(level, batch, interestChunks)) {
                continue;
            }
            total++;
        }
        return total;
    }

    private static long heldTicketCount(ServerLevel level, Set<Long> interestChunks) {
        if (level == null || interestChunks == null || interestChunks.isEmpty()) {
            return heldTicketCount();
        }
        long total = 0L;
        for (TransactionMutationBatch batch : TRANSACTION_PENDING.values()) {
            if (batch == null || batch.ticketedChunks.isEmpty() || !intersectsInterest(level, batch, interestChunks)) {
                continue;
            }
            for (Long packedChunk : batch.ticketedChunks) {
                if (packedChunk != null && interestChunks.contains(packedChunk)) {
                    total++;
                }
            }
        }
        return total;
    }

    private static boolean intersectsInterest(ServerLevel level, TransactionMutationBatch batch, Set<Long> interestChunks) {
        if (level == null || batch == null || interestChunks == null || interestChunks.isEmpty()) {
            return true;
        }
        for (Map.Entry<ScopedChunk, ConcurrentLinkedQueue<ChunkShadowMutationPlan>> chunkEntry : batch.sortedEntries()) {
            ScopedChunk scopedChunk = chunkEntry.getKey();
            if (matchesInterest(level, interestChunks, scopedChunk == null ? null : scopedChunk.chunk())) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesInterest(ServerLevel level, Set<Long> interestChunks, ChunkCoord chunk) {
        if (level == null || chunk == null || interestChunks == null || interestChunks.isEmpty()) {
            return true;
        }
        if (!WorldGenScope.matches(level, WorldGenScope.dimension(chunk.dimension()))) {
            return false;
        }
        return interestChunks.contains(new ChunkPos(chunk.chunkX(), chunk.chunkZ()).toLong());
    }

    private static void retainTransactionTickets(ServerLevel level, TransactionMutationBatch batch) {
        if (level == null || batch == null) {
            return;
        }
        boolean retainedAny = false;
        for (Map.Entry<ScopedChunk, ConcurrentLinkedQueue<ChunkShadowMutationPlan>> chunkEntry : batch.sortedEntries()) {
            ScopedChunk scopedChunk = chunkEntry.getKey();
            ChunkCoord chunk = scopedChunk == null ? null : scopedChunk.chunk();
            if (chunk == null) {
                continue;
            }
            ChunkPos pos = new ChunkPos(chunk.chunkX(), chunk.chunkZ());
            if (!batch.ticketedChunks.add(pos.toLong())) {
                continue;
            }
            try {
                level.getChunkSource().addRegionTicket(LC2H_SHADOW_TICKET, pos, 2, pos);
                retainedAny = true;
                long now = System.currentTimeMillis();
                if (batch.firstTicketAcquireAtMs == 0L) {
                    batch.firstTicketAcquireAtMs = now;
                }
                batch.lastTicketAcquireAtMs = now;
                TICKET_ACQUIRED.incrementAndGet();
            } catch (Throwable t) {
                batch.ticketedChunks.remove(pos.toLong());
                batch.ticketAcquireFailures.incrementAndGet();
                TICKET_ACQUIRE_FAILURES.incrementAndGet();
                LC2H.LOGGER.debug("[LC2H] Failed to retain shadow chunk ticket for tx={} chunk={}: {}", batch.key == null ? "unknown" : batch.key.transactionId(), pos, t.toString());
            }
        }
        if (retainedAny || !batch.ticketedChunks.isEmpty()) {
            batch.ticketsHeld.set(true);
        }
    }

    private static void releaseTransactionTickets(ServerLevel level, TransactionMutationBatch batch) {
        if (level == null || batch == null || batch.ticketedChunks.isEmpty()) {
            return;
        }
        for (Long packedChunk : new ArrayList<>(batch.ticketedChunks)) {
            ChunkPos pos = new ChunkPos(packedChunk);
            try {
                level.getChunkSource().removeRegionTicket(LC2H_SHADOW_TICKET, pos, 2, pos);
                TICKET_RELEASED.incrementAndGet();
            } catch (Throwable t) {
                TICKET_RELEASE_FAILURES.incrementAndGet();
                LC2H.LOGGER.debug("[LC2H] Failed to release shadow chunk ticket for tx={} chunk={}: {}", batch.key == null ? "unknown" : batch.key.transactionId(), pos, t.toString());
            }
        }
        batch.ticketedChunks.clear();
        batch.ticketsHeld.set(false);
        batch.lastTicketReleaseAtMs = System.currentTimeMillis();
    }

    private static void releaseAllTransactionTickets() {
        var server = ServerRescheduler.getServer();
        if (server == null) {
            return;
        }
        for (Map.Entry<ScopedTransaction, TransactionMutationBatch> entry : TRANSACTION_PENDING.entrySet()) {
            ScopedTransaction txKey = entry.getKey();
            TransactionMutationBatch batch = entry.getValue();
            releaseTransactionTickets(findLevel(server, txKey == null ? null : txKey.scope()), batch);
        }
    }

    private static void updateMax(AtomicLong target, long value) {
        long current;
        do {
            current = target.get();
            if (value <= current) {
                return;
            }
        } while (!target.compareAndSet(current, value));
    }

    private static void logStaleScopeOnce(ServerLevel level,
                                          WorldGenScope.DimensionKey expected,
                                          ChunkCoord chunk,
                                          int remaining,
                                          boolean transactionBatch) {
        if (!STALE_SCOPE_LOGGED.compareAndSet(false, true)) {
            return;
        }
        WorldGenScope.DimensionKey actual = level == null ? null : WorldGenScope.dimension(level);
        LC2H.LOGGER.warn(
            "[LC2H] Shadow stale-scope drop: batchType={} chunk={} remaining={} expectedScope={} actualScope={}",
            transactionBatch ? "transaction" : "chunk",
            chunk == null ? "unknown" : (chunk.chunkX() + "," + chunk.chunkZ() + "@" + chunk.dimension()),
            remaining,
            expected == null ? "null" : expected.shortText(),
            actual == null ? "null" : actual.shortText()
        );
    }
}
