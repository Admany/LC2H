package org.admany.lc2h.worldgen.dag;

import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.IDimensionInfo;
import mcjty.lostcities.worldgen.lost.MultiChunk;
import org.admany.lc2h.LC2H;
import org.admany.lc2h.concurrency.async.AsyncManager;
import org.admany.lc2h.concurrency.async.Priority;
import org.admany.lc2h.worldgen.kernel.JavaScalarLostCityKernel;
import org.admany.lc2h.worldgen.kernel.LostCityCpuKernel;
import org.admany.lc2h.worldgen.kernel.LostCityKernelInput;
import org.admany.lc2h.worldgen.kernel.LostCityKernelSignature;
import org.admany.lc2h.worldgen.kernel.LostCityKernelStage;
import org.admany.lc2h.worldgen.kernel.LostCityMultiChunkPlan;
import org.admany.lc2h.worldgen.kernel.LostCityStageResult;
import org.admany.quantified.api.QuantifiedAPI;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

public final class LostCityDagScheduler {

    private static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("lc2h.kernel.enabled", "true"));
    private static final boolean MULTICHUNK_BATCH_DAG_ENABLED = Boolean.parseBoolean(System.getProperty("lc2h.kernel.multichunkBatchDag.enabled", "false"));
    private static final boolean MULTICHUNK_ROLE_WARMUP = Boolean.parseBoolean(System.getProperty("lc2h.kernel.multichunkRoleWarmup", "false"));
    private static final Duration NODE_TIMEOUT = Duration.ofSeconds(
        Math.max(5L, Long.getLong("lc2h.kernel.nodeTimeoutSeconds", 30L))
    );
    private static final int STAGE_BATCH_SIZE = Math.max(4,
        Integer.getInteger("lc2h.kernel.stageBatchSize", 32)
    );
    private static final int STAGE_BATCH_MAX_PENDING = Math.max(STAGE_BATCH_SIZE,
        Integer.getInteger("lc2h.kernel.stageBatchMaxPending", 2_048)
    );
    private static final long STAGE_BATCH_DELAY_MS = Math.max(1L,
        Long.getLong("lc2h.kernel.stageBatchDelayMs", 2L)
    );
    private static final int BATCH_MAX_PARALLELISM = Math.max(1,
        Integer.getInteger("lc2h.kernel.batchMaxParallelism", Math.max(2, Runtime.getRuntime().availableProcessors() / 2))
    );
    private static final LostCityCpuKernel KERNEL = JavaScalarLostCityKernel.INSTANCE;
    private static final AtomicLong GRAPH_RUN_SEQUENCE = new AtomicLong();
    private static final AtomicLong SINGLE_DAG_SUCCESS = new AtomicLong();
    private static final AtomicLong SINGLE_DAG_NULL_PLAN = new AtomicLong();
    private static final AtomicLong SINGLE_DAG_FALLBACK = new AtomicLong();
    private static final LongAdder DIRECT_COMPUTE_COUNT = new LongAdder();
    private static final LongAdder COMPUTE_SUBMIT_COUNT = new LongAdder();
    private static final LongAdder PARALLEL_BATCH_COUNT = new LongAdder();
    private static final LongAdder PARALLEL_BATCH_TASKS = new LongAdder();
    private static final LongAdder STAGE_BATCH_COUNT = new LongAdder();
    private static final LongAdder STAGE_BATCH_TASKS = new LongAdder();
    private static final LongAdder STAGE_BATCH_REJECTED = new LongAdder();
    private static final AtomicInteger STAGE_PENDING = new AtomicInteger();
    private static final ConcurrentHashMap<StageBatchKey, PendingStageBatch> STAGE_BATCHES = new ConcurrentHashMap<>();

    private LostCityDagScheduler() {
    }

    public static boolean isEnabled() {
        return ENABLED;
    }

    public static boolean isMultiChunkBatchDagEnabled() {
        return MULTICHUNK_BATCH_DAG_ENABLED;
    }

    public static CompletableFuture<MultiChunk> submitMultiChunk(IDimensionInfo provider, ChunkCoord multiCoord, int areaSize) {
        LostCityKernelInput input = input(provider, multiCoord, areaSize);
        COMPUTE_SUBMIT_COUNT.increment();
        long startNs = System.nanoTime();

        try {
            long runId = GRAPH_RUN_SEQUENCE.incrementAndGet();
            return QuantifiedAPI.<LostCityMultiChunkPlan>compute(LC2H.MODID, "lc-kernel-multichunk/" + runId)
                .critical()
                .threadSafe()
                .timeout(NODE_TIMEOUT)
                .work(() -> {
                    if (MULTICHUNK_ROLE_WARMUP) {
                        KERNEL.warmRoleLookups(input);
                    }
                    return KERNEL.planMultiChunk(input);
                })
                .submit()
                .handle((result, throwable) -> resolveSingleGraphResult(input, multiCoord, result, throwable))
                .thenCompose(java.util.function.Function.identity())
                .whenComplete((ignored, throwable) ->
                    org.admany.lc2h.dev.diagnostics.Lc2hTimingRegistry.record("kernel.submit_multichunk_complete", System.nanoTime() - startNs));
        } catch (Throwable t) {
            LC2H.LOGGER.warn("LC kernel optimized submission failed for {}; falling back to scalar kernel: {}", multiCoord, t.toString());
            return fallbackSingle(input, multiCoord, t);
        }
    }

    public static CompletableFuture<List<MultiChunk>> submitMultiChunkBatch(IDimensionInfo provider,
                                                                            List<ChunkCoord> multiCoords,
                                                                            int areaSize) {
        if (provider == null || multiCoords == null || multiCoords.isEmpty()) {
            return CompletableFuture.completedFuture(List.of());
        }
        if (multiCoords.size() == 1) {
            return submitMultiChunk(provider, multiCoords.get(0), areaSize)
                .thenApply(List::of);
        }

        LostCityKernelSignature signature = LostCityKernelSignature.from(provider, KERNEL.capabilities());
        String dimensionId = signature.dimensionId();
        String localityKey = dimensionId + ":batch:" + multiCoords.get(0).chunkX() + ":" + multiCoords.get(0).chunkZ();
        String batchKey = localityKey + ":area:" + areaSize + ":count:" + multiCoords.size();

        try {
            long startNs = System.nanoTime();
            PARALLEL_BATCH_COUNT.increment();
            PARALLEL_BATCH_TASKS.add(multiCoords.size());
            ArrayList<LostCityKernelInput> inputs = new ArrayList<>(multiCoords.size());
            for (int i = 0; i < multiCoords.size(); i++) {
                ChunkCoord coord = multiCoords.get(i);
                inputs.add(new LostCityKernelInput(provider, coord, areaSize, signature));
            }

            return QuantifiedAPI
                .parallel(LC2H.MODID, "lc-kernel-multichunk-batch")
                .key(uniqueGraphRunKey(batchKey))
                .maxParallelism(Math.min(BATCH_MAX_PARALLELISM, Math.max(1, inputs.size())))
                .range(0, inputs.size())
                .map(index -> {
                    LostCityKernelInput input = inputs.get(index);
                        if (MULTICHUNK_ROLE_WARMUP) {
                            KERNEL.warmRoleLookups(input);
                        }
                        return KERNEL.planMultiChunk(input);
                })
                .submit()
                .handle((plans, throwable) -> resolveBatchGraphResult(
                    provider,
                    multiCoords,
                    areaSize,
                    extractBatchPlans(plans),
                    throwable
                ))
                .thenCompose(java.util.function.Function.identity())
                .whenComplete((ignored, throwable) ->
                    org.admany.lc2h.dev.diagnostics.Lc2hTimingRegistry.record("kernel.parallel_multichunk_batch_complete", System.nanoTime() - startNs));
        } catch (Throwable t) {
            LC2H.LOGGER.warn("LC kernel batch DAG submission failed (tasks={}); falling back to direct scalar kernel: {}",
                multiCoords.size(), t.toString());
            return fallbackBatch(provider, multiCoords, areaSize, t);
        }
    }

    public static MultiChunk computeMultiChunkDirect(IDimensionInfo provider, ChunkCoord multiCoord, int areaSize) {
        DIRECT_COMPUTE_COUNT.increment();
        long startNs = System.nanoTime();
        LostCityKernelInput input = input(provider, multiCoord, areaSize);
        try {
            if (MULTICHUNK_ROLE_WARMUP) {
                KERNEL.warmRoleLookups(input);
            }
            return KERNEL.planMultiChunk(input).multiChunk();
        } finally {
            org.admany.lc2h.dev.diagnostics.Lc2hTimingRegistry.record("kernel.direct_multichunk", System.nanoTime() - startNs);
        }
    }

    public static CompletableFuture<LostCityStageResult> submitTerrainFeatures(IDimensionInfo provider, ChunkCoord coord) {
        LostCityKernelInput input = input(provider, coord, 1);
        return submitStage(input, LostCityKernelStage.PLAN_TERRAIN);
    }

    public static CompletableFuture<LostCityStageResult> submitTerrainCorrections(IDimensionInfo provider, ChunkCoord coord) {
        LostCityKernelInput input = input(provider, coord, 1);
        return submitStage(input, LostCityKernelStage.PLAN_TERRAIN_CORRECTION);
    }

    public static CompletableFuture<LostCityStageResult> submitCityLayout(IDimensionInfo provider, ChunkCoord coord) {
        LostCityKernelInput input = input(provider, coord, 1);
        return submitStage(input, LostCityKernelStage.PLAN_CITY_LAYOUT);
    }

    /**
     * Coalesces tiny, immutable planning stages before crossing the Quantified API boundary.
     * A graph per chunk was pure control-plane amplification: each request created a graph,
     * node, cache lookup and dashboard entry for a few microseconds of useful work.
     */
    private static CompletableFuture<LostCityStageResult> submitStage(LostCityKernelInput input,
                                                                      LostCityKernelStage stage) {
        CompletableFuture<LostCityStageResult> future = new CompletableFuture<>();
        int pending = STAGE_PENDING.incrementAndGet();
        if (pending > STAGE_BATCH_MAX_PENDING) {
            STAGE_PENDING.decrementAndGet();
            STAGE_BATCH_REJECTED.increment();
            future.completeExceptionally(new IllegalStateException("LC2H stage batch queue is full"));
            return future;
        }

        StageBatchKey key = new StageBatchKey(input.provider(), stage, input.signature().dimensionId());
        PendingStageBatch batch = STAGE_BATCHES.computeIfAbsent(key, ignored -> new PendingStageBatch());
        int size = batch.add(new StageRequest(input, future));
        if (size >= STAGE_BATCH_SIZE) {
            flushStageBatch(key, batch);
        } else {
            scheduleStageFlush(key, batch);
        }
        return future;
    }

    private static LostCityKernelInput input(IDimensionInfo provider, ChunkCoord coord, int areaSize) {
        if (provider == null || coord == null || areaSize <= 0) {
            throw new IllegalArgumentException("provider, coord, and areaSize are required");
        }
        LostCityKernelSignature signature = LostCityKernelSignature.from(provider, KERNEL.capabilities());
        return new LostCityKernelInput(provider, coord, areaSize, signature);
    }

    private static void scheduleStageFlush(StageBatchKey key, PendingStageBatch batch) {
        if (!batch.flushScheduled.compareAndSet(false, true)) {
            return;
        }
        AsyncManager.runLater("lc-kernel-stage-batch-flush", () -> flushStageBatch(key, batch),
            STAGE_BATCH_DELAY_MS, Priority.LOW);
    }

    private static void flushStageBatch(StageBatchKey key, PendingStageBatch batch) {
        List<StageRequest> requests = batch.drain(STAGE_BATCH_SIZE);
        batch.flushScheduled.set(false);
        if (requests.isEmpty()) {
            STAGE_BATCHES.remove(key, batch);
            return;
        }
        STAGE_PENDING.addAndGet(-requests.size());
        STAGE_BATCH_COUNT.increment();
        STAGE_BATCH_TASKS.add(requests.size());
        long startNs = System.nanoTime();
        try {
            QuantifiedAPI
                .parallel(LC2H.MODID, "lc-kernel-" + stageLabel(key.stage) + "-batch")
                .key(uniqueGraphRunKey(key.dimensionId + ":" + stageLabel(key.stage)))
                .maxParallelism(Math.min(BATCH_MAX_PARALLELISM, requests.size()))
                .range(0, requests.size())
                .map(index -> computeStage(key.stage, requests.get(index).input))
                .submit()
                .whenComplete((results, throwable) -> {
                    if (throwable != null || results == null || results.size() != requests.size()) {
                        Throwable failure = throwable != null ? throwable
                            : new IllegalStateException("LC2H stage batch returned an invalid result count");
                        requests.forEach(request -> request.future.completeExceptionally(failure));
                    } else {
                        for (int i = 0; i < requests.size(); i++) {
                            requests.get(i).future.complete(results.get(i));
                        }
                    }
                    org.admany.lc2h.dev.diagnostics.Lc2hTimingRegistry.record(
                        "kernel.parallel_stage_batch_complete", System.nanoTime() - startNs);
                });
        } catch (Throwable throwable) {
            requests.forEach(request -> request.future.completeExceptionally(throwable));
        }
        if (!batch.isEmpty()) {
            scheduleStageFlush(key, batch);
        } else {
            STAGE_BATCHES.remove(key, batch);
        }
    }

    private static LostCityStageResult computeStage(LostCityKernelStage stage, LostCityKernelInput input) {
        return switch (stage) {
            case PLAN_TERRAIN -> KERNEL.planTerrainFeatures(input);
            case PLAN_TERRAIN_CORRECTION -> KERNEL.planTerrainCorrections(input);
            case PLAN_CITY_LAYOUT -> KERNEL.planCityLayout(input);
            default -> throw new IllegalArgumentException("Stage is not safe for async batching: " + stage);
        };
    }

    private static String stageLabel(LostCityKernelStage stage) {
        return switch (stage) {
            case PLAN_TERRAIN -> "terrain-features";
            case PLAN_TERRAIN_CORRECTION -> "terrain-corrections";
            case PLAN_CITY_LAYOUT -> "city-layout";
            default -> stage.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
        };
    }

    public static void shutdown() {
        STAGE_BATCHES.forEach((key, batch) -> {
            for (StageRequest request : batch.drain(Integer.MAX_VALUE)) {
                request.future.cancel(false);
            }
        });
        STAGE_BATCHES.clear();
        STAGE_PENDING.set(0);
    }

    private static final class StageBatchKey {
        private final IDimensionInfo provider;
        private final LostCityKernelStage stage;
        private final String dimensionId;
        private final int hash;

        private StageBatchKey(IDimensionInfo provider, LostCityKernelStage stage, String dimensionId) {
            this.provider = provider;
            this.stage = stage;
            this.dimensionId = dimensionId;
            this.hash = 31 * System.identityHashCode(provider) + stage.hashCode();
        }

        @Override
        public boolean equals(Object obj) {
            return this == obj || (obj instanceof StageBatchKey other
                && provider == other.provider && stage == other.stage);
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }

    private static final class PendingStageBatch {
        private final ConcurrentLinkedQueue<StageRequest> requests = new ConcurrentLinkedQueue<>();
        private final AtomicInteger size = new AtomicInteger();
        private final AtomicBoolean flushScheduled = new AtomicBoolean();

        private int add(StageRequest request) {
            requests.add(request);
            return size.incrementAndGet();
        }

        private List<StageRequest> drain(int limit) {
            ArrayList<StageRequest> drained = new ArrayList<>(Math.min(size.get(), limit));
            StageRequest request;
            while (drained.size() < limit && (request = requests.poll()) != null) {
                drained.add(request);
            }
            size.addAndGet(-drained.size());
            return drained;
        }

        private boolean isEmpty() {
            return size.get() == 0;
        }
    }

    private record StageRequest(LostCityKernelInput input, CompletableFuture<LostCityStageResult> future) {
    }

    private static CompletableFuture<MultiChunk> resolveSingleGraphResult(LostCityKernelInput input,
                                                                          ChunkCoord multiCoord,
                                                                          LostCityMultiChunkPlan plan,
                                                                          Throwable throwable) {
        if (throwable != null) {
            SINGLE_DAG_FALLBACK.incrementAndGet();
            LC2H.LOGGER.warn("LC kernel optimized execution failed for {}; falling back to scalar kernel: {}", multiCoord, throwable.toString());
            return fallbackSingle(input, multiCoord, throwable);
        }
        if (plan == null) {
            SINGLE_DAG_NULL_PLAN.incrementAndGet();
            SINGLE_DAG_FALLBACK.incrementAndGet();
            LC2H.LOGGER.warn("LC kernel optimized execution returned null multichunk plan for {}; falling back to scalar kernel (diagnostics={})",
                multiCoord, diagnostics());
            return fallbackSingle(input, multiCoord, null);
        }
        MultiChunk multiChunk = plan.multiChunk();
        if (multiChunk == null) {
            SINGLE_DAG_FALLBACK.incrementAndGet();
            LC2H.LOGGER.warn("LC kernel optimized execution returned plan with null multichunk for {}; falling back to scalar kernel", multiCoord);
            return fallbackSingle(input, multiCoord, null);
        }
        SINGLE_DAG_SUCCESS.incrementAndGet();
        return CompletableFuture.completedFuture(multiChunk);
    }

    private static CompletableFuture<List<MultiChunk>> resolveBatchGraphResult(IDimensionInfo provider,
                                                                               List<ChunkCoord> multiCoords,
                                                                               int areaSize,
                                                                               List<MultiChunk> result,
                                                                               Throwable throwable) {
        if (throwable != null) {
            LC2H.LOGGER.warn("LC kernel batch DAG execution failed (tasks={}); falling back to scalar kernel: {}",
                multiCoords.size(), throwable.toString());
            return fallbackBatch(provider, multiCoords, areaSize, throwable);
        }
        if (result == null || result.size() != multiCoords.size() || result.stream().anyMatch(java.util.Objects::isNull)) {
            LC2H.LOGGER.warn("LC kernel batch DAG returned invalid results (tasks={}); falling back to scalar kernel", multiCoords.size());
            return fallbackBatch(provider, multiCoords, areaSize, null);
        }
        return CompletableFuture.completedFuture(result);
    }

    private static CompletableFuture<MultiChunk> fallbackSingle(LostCityKernelInput input, ChunkCoord multiCoord, Throwable cause) {
        try {
            if (MULTICHUNK_ROLE_WARMUP) {
                KERNEL.warmRoleLookups(input);
            }
            LostCityMultiChunkPlan plan = KERNEL.planMultiChunk(input);
            if (plan == null || plan.multiChunk() == null) {
                throw new IllegalStateException("LC scalar fallback returned null multichunk for " + multiCoord, cause);
            }
            return CompletableFuture.completedFuture(plan.multiChunk());
        } catch (Throwable fallback) {
            CompletableFuture<MultiChunk> failed = new CompletableFuture<>();
            failed.completeExceptionally(fallback);
            return failed;
        }
    }

    private static CompletableFuture<List<MultiChunk>> fallbackBatch(IDimensionInfo provider,
                                                                     List<ChunkCoord> multiCoords,
                                                                     int areaSize,
                                                                     Throwable cause) {
        try {
            ArrayList<MultiChunk> results = new ArrayList<>(multiCoords.size());
            for (ChunkCoord coord : multiCoords) {
                MultiChunk multiChunk = computeMultiChunkDirect(provider, coord, areaSize);
                if (multiChunk == null) {
                    throw new IllegalStateException("LC scalar batch fallback returned null multichunk for " + coord, cause);
                }
                results.add(multiChunk);
            }
            return CompletableFuture.completedFuture(results);
        } catch (Throwable fallback) {
            CompletableFuture<List<MultiChunk>> failed = new CompletableFuture<>();
            failed.completeExceptionally(fallback);
            return failed;
        }
    }

    private static List<MultiChunk> extractBatchPlans(List<LostCityMultiChunkPlan> plans) {
        if (plans == null) {
            return null;
        }
        ArrayList<MultiChunk> results = new ArrayList<>(plans.size());
        for (LostCityMultiChunkPlan plan : plans) {
            if (plan == null || plan.multiChunk() == null) {
                return null;
            }
            results.add(plan.multiChunk());
        }
        return results;
    }

    public static String diagnostics() {
        return "singleSuccess=" + SINGLE_DAG_SUCCESS.get()
            + ", singleNullPlan=" + SINGLE_DAG_NULL_PLAN.get()
            + ", singleFallback=" + SINGLE_DAG_FALLBACK.get()
            + ", graphRuns=" + GRAPH_RUN_SEQUENCE.get()
            + ", direct=" + DIRECT_COMPUTE_COUNT.sum()
            + ", computeSubmits=" + COMPUTE_SUBMIT_COUNT.sum()
            + ", parallelBatches=" + PARALLEL_BATCH_COUNT.sum()
            + ", parallelBatchTasks=" + PARALLEL_BATCH_TASKS.sum()
            + ", stageBatches=" + STAGE_BATCH_COUNT.sum()
            + ", stageBatchTasks=" + STAGE_BATCH_TASKS.sum()
            + ", stagePending=" + STAGE_PENDING.get()
            + ", stageRejected=" + STAGE_BATCH_REJECTED.sum()
            + ", avgStageBatch=" + String.format(java.util.Locale.ROOT, "%.2f",
                STAGE_BATCH_COUNT.sum() == 0L ? 0.0D : STAGE_BATCH_TASKS.sum() / (double) STAGE_BATCH_COUNT.sum());
    }

    private static String uniqueGraphRunKey(String stableKey) {
        String base = stableKey == null || stableKey.isBlank() ? "graph" : stableKey;
        return base + ":run:" + GRAPH_RUN_SEQUENCE.incrementAndGet();
    }
}
