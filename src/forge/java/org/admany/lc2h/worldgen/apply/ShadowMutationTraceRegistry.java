package org.admany.lc2h.worldgen.apply;

import mcjty.lostcities.varia.ChunkCoord;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.admany.lc2h.LC2H;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;

@Mod.EventBusSubscriber(modid = LC2H.MODID)
public final class ShadowMutationTraceRegistry {

    private static final int MAX_RECORDS = Math.max(4_096,
        Integer.getInteger("lc2h.shadowTrace.maxRecords", 65_536));
    private static final ConcurrentHashMap<TraceKey, TraceRecord> BY_TRACE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<PositionKey, TraceRecord> BY_POSITION = new ConcurrentHashMap<>();
    private static final ConcurrentLinkedDeque<TraceKey> ORDER = new ConcurrentLinkedDeque<>();
    private static final AtomicLong CAPTURED = new AtomicLong();
    private static final AtomicLong FINALIZED = new AtomicLong();
    private static final AtomicLong APPLIED = new AtomicLong();
    private static final AtomicLong REJECTED = new AtomicLong();
    private static final AtomicLong RETRIED = new AtomicLong();
    private static final AtomicLong EVICTED = new AtomicLong();

    private ShadowMutationTraceRegistry() {
    }

    public static void recordPlanQueued(ChunkShadowMutationPlan plan) {
        if (plan == null || plan.chunk() == null || plan.entries() == null) {
            return;
        }
        ChunkCoord chunk = plan.chunk();
        ResourceLocation dimension = chunk.dimension() == null ? null : chunk.dimension().location();
        if (dimension == null) {
            return;
        }
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int index = 0; index < plan.entries().length; index++) {
            ChunkShadowMutationPlan.Entry entry = plan.entries()[index];
            if (entry == null || entry.state() == null) {
                continue;
            }
            ChunkShadowMutationPlan.unpack(chunk, entry.packedPos(), cursor);
            TraceRecord trace = trace(plan, index, cursor.immutable(), entry);
            synchronized (trace) {
                if (!trace.captured) {
                    trace.captured = true;
                    trace.capturedAt = System.currentTimeMillis();
                    CAPTURED.incrementAndGet();
                }
            }
        }
    }

    public static void recordRejected(ChunkShadowMutationPlan plan,
                                      int opIndex,
                                      BlockPos pos,
                                      ChunkShadowMutationPlan.Entry entry,
                                      String stage,
                                      String reason,
                                      String previousState) {
        TraceRecord trace = trace(plan, opIndex, pos, entry);
        synchronized (trace) {
            if (!trace.finalized) {
                trace.finalized = true;
                trace.finalizedAt = System.currentTimeMillis();
                FINALIZED.incrementAndGet();
            }
            if (!trace.rejected) {
                REJECTED.incrementAndGet();
            }
            trace.rejected = true;
            trace.lastStage = stage;
            trace.rejectReason = safe(reason);
            if (previousState != null) {
                trace.previousState = previousState;
            }
        }
    }

    public static void recordRetried(ChunkShadowMutationPlan plan,
                                     int opIndex,
                                     BlockPos pos,
                                     ChunkShadowMutationPlan.Entry entry,
                                     String stage,
                                     String reason) {
        TraceRecord trace = trace(plan, opIndex, pos, entry);
        synchronized (trace) {
            trace.lastStage = stage;
            trace.retryCount++;
            trace.retryReason = safe(reason);
            RETRIED.incrementAndGet();
        }
    }

    public static void recordApplied(ChunkShadowMutationPlan plan,
                                     int opIndex,
                                     BlockPos pos,
                                     ChunkShadowMutationPlan.Entry entry,
                                     String stage,
                                     String previousState,
                                     String observedState,
                                     boolean scheduledFluidTick,
                                     boolean scheduledGravityTick,
                                     boolean supportSensitive,
                                     boolean neighborAdjusted) {
        TraceRecord trace = trace(plan, opIndex, pos, entry);
        synchronized (trace) {
            long now = System.currentTimeMillis();
            if (!trace.finalized) {
                trace.finalized = true;
                trace.finalizedAt = now;
                FINALIZED.incrementAndGet();
            }
            if (!trace.applied) {
                trace.applied = true;
                trace.appliedAt = now;
                APPLIED.incrementAndGet();
            }
            trace.lastStage = stage;
            trace.previousState = previousState;
            trace.observedState = observedState;
            trace.scheduledFluidTick = trace.scheduledFluidTick || scheduledFluidTick;
            trace.scheduledGravityTick = trace.scheduledGravityTick || scheduledGravityTick;
            trace.supportSensitive = trace.supportSensitive || supportSensitive;
            trace.neighborAdjusted = trace.neighborAdjusted || neighborAdjusted;
            trace.rejected = false;
            trace.rejectReason = null;
        }
    }

    public static TraceSnapshot findLatest(ResourceLocation dimension, BlockPos pos) {
        if (dimension == null || pos == null) {
            return null;
        }
        TraceRecord trace = BY_POSITION.get(new PositionKey(dimension.toString(), pos.asLong()));
        if (trace == null) {
            return null;
        }
        synchronized (trace) {
            return trace.snapshot();
        }
    }

    public static List<String> summaryLines() {
        return List.of(
            "ShadowTrace: active=" + BY_TRACE.size()
                + " captured=" + CAPTURED.get()
                + " finalized=" + FINALIZED.get()
                + " applied=" + APPLIED.get()
                + " rejected=" + REJECTED.get()
                + " retried=" + RETRIED.get()
                + " evicted=" + EVICTED.get()
        );
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        clear();
    }

    public static void clear() {
        BY_TRACE.clear();
        BY_POSITION.clear();
        ORDER.clear();
        CAPTURED.set(0L);
        FINALIZED.set(0L);
        APPLIED.set(0L);
        REJECTED.set(0L);
        RETRIED.set(0L);
        EVICTED.set(0L);
    }

    private static TraceRecord trace(ChunkShadowMutationPlan plan,
                                     int opIndex,
                                     BlockPos pos,
                                     ChunkShadowMutationPlan.Entry entry) {
        ChunkCoord chunk = plan == null ? null : plan.chunk();
        ResourceLocation dimension = chunk == null || chunk.dimension() == null ? null : chunk.dimension().location();
        String dimensionId = dimension == null ? "unknown" : dimension.toString();
        TraceKey key = new TraceKey(
            dimensionId,
            plan == null ? 0L : plan.transactionId(),
            chunk == null ? Integer.MIN_VALUE : chunk.chunkX(),
            chunk == null ? Integer.MIN_VALUE : chunk.chunkZ(),
            pos == null ? Long.MIN_VALUE : pos.asLong(),
            opIndex
        );
        TraceRecord existing = BY_TRACE.get(key);
        if (existing != null) {
            return existing;
        }
        TraceRecord created = new TraceRecord(
            key,
            plan == null ? ChunkShadowMutationPlan.MutationKind.GENERIC : plan.kind(),
            entry == null ? 0 : entry.flags(),
            entry != null && entry.markTreePlacement(),
            entry == null || entry.state() == null ? "<null>" : entry.state().toString(),
            pos
        );
        TraceRecord raced = BY_TRACE.putIfAbsent(key, created);
        TraceRecord winner = raced == null ? created : raced;
        if (raced == null) {
            ORDER.addLast(key);
            if (pos != null) {
                BY_POSITION.put(new PositionKey(dimensionId, pos.asLong()), created);
            }
            evictIfNeeded();
        }
        return winner;
    }

    private static void evictIfNeeded() {
        while (BY_TRACE.size() > MAX_RECORDS) {
            TraceKey oldest = ORDER.pollFirst();
            if (oldest == null) {
                return;
            }
            TraceRecord removed = BY_TRACE.remove(oldest);
            if (removed == null) {
                continue;
            }
            if (removed.posLong != Long.MIN_VALUE) {
                BY_POSITION.remove(new PositionKey(oldest.dimension, removed.posLong), removed);
            }
            EVICTED.incrementAndGet();
        }
    }

    private static String safe(String value) {
        return value == null ? null : value.trim();
    }

    private record TraceKey(String dimension, long transactionId, int chunkX, int chunkZ, long posLong, int opIndex) {
    }

    private record PositionKey(String dimension, long posLong) {
    }

    private static final class TraceRecord {
        private final TraceKey key;
        private final ChunkShadowMutationPlan.MutationKind kind;
        private final int flags;
        private final boolean treePlacement;
        private final String intendedState;
        private final long posLong;
        private final int localX;
        private final int localZ;
        private boolean captured;
        private boolean finalized;
        private boolean applied;
        private boolean rejected;
        private int retryCount;
        private long capturedAt;
        private long finalizedAt;
        private long appliedAt;
        private String lastStage;
        private String rejectReason;
        private String retryReason;
        private String previousState;
        private String observedState;
        private boolean scheduledFluidTick;
        private boolean scheduledGravityTick;
        private boolean supportSensitive;
        private boolean neighborAdjusted;

        private TraceRecord(TraceKey key,
                            ChunkShadowMutationPlan.MutationKind kind,
                            int flags,
                            boolean treePlacement,
                            String intendedState,
                            BlockPos pos) {
            this.key = key;
            this.kind = kind == null ? ChunkShadowMutationPlan.MutationKind.GENERIC : kind;
            this.flags = flags;
            this.treePlacement = treePlacement;
            this.intendedState = intendedState;
            this.posLong = pos == null ? Long.MIN_VALUE : pos.asLong();
            this.localX = pos == null ? Integer.MIN_VALUE : (pos.getX() & 15);
            this.localZ = pos == null ? Integer.MIN_VALUE : (pos.getZ() & 15);
        }

        private TraceSnapshot snapshot() {
            long deferredTreeId = kind == ChunkShadowMutationPlan.MutationKind.TREE_CAPTURE ? key.transactionId : 0L;
            return new TraceSnapshot(
                key.dimension,
                key.transactionId,
                deferredTreeId,
                kind.name().toLowerCase(Locale.ROOT),
                key.opIndex,
                key.chunkX,
                key.chunkZ,
                localX,
                localZ,
                flags,
                treePlacement,
                captured,
                finalized,
                applied,
                rejected,
                retryCount,
                capturedAt,
                finalizedAt,
                appliedAt,
                lastStage,
                rejectReason,
                retryReason,
                previousState,
                intendedState,
                observedState,
                scheduledFluidTick,
                scheduledGravityTick,
                supportSensitive,
                neighborAdjusted
            );
        }
    }

    public record TraceSnapshot(String dimension,
                                long transactionId,
                                long deferredTreeId,
                                String mutationKind,
                                int operationIndex,
                                int destinationChunkX,
                                int destinationChunkZ,
                                int localX,
                                int localZ,
                                int flags,
                                boolean treePlacement,
                                boolean captured,
                                boolean finalized,
                                boolean applied,
                                boolean rejected,
                                int retryCount,
                                long capturedAt,
                                long finalizedAt,
                                long appliedAt,
                                String lastStage,
                                String rejectReason,
                                String retryReason,
                                String previousState,
                                String intendedState,
                                String observedState,
                                boolean scheduledFluidTick,
                                boolean scheduledGravityTick,
                                boolean supportSensitive,
                                boolean neighborAdjusted) {
        public String firstObservedTime() {
            long first = capturedAt > 0L ? capturedAt : (finalizedAt > 0L ? finalizedAt : appliedAt);
            return first <= 0L ? null : Instant.ofEpochMilli(first).toString();
        }
    }
}
