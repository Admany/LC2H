package org.admany.lc2h.worldgen.gpu;

import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.varia.Tools;
import mcjty.lostcities.worldgen.ChunkDriver;
import mcjty.lostcities.worldgen.ChunkHeightmap;
import mcjty.lostcities.worldgen.IDimensionInfo;
import mcjty.lostcities.worldgen.LostCityTerrainFeature;
import mcjty.lostcities.worldgen.LostTags;
import mcjty.lostcities.worldgen.lost.BuildingInfo;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.admany.lc2h.LC2H;
import org.admany.lc2h.concurrency.async.AsyncManager;
import org.admany.lc2h.concurrency.async.Priority;
import org.admany.lc2h.dev.diagnostics.Lc2hTimingRegistry;
import org.admany.lc2h.worldgen.scope.WorldGenScope;
import org.admany.quantified.api.vulkan.QuantifiedVulkan;
import org.admany.quantified.api.vulkan.SpirvComputeProgram;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.TimeUnit;

public final class TerrainCorrectionGpuPipeline {
    private static final int COLUMNS = 16 * 16;
    private static final int OUTPUT_WORDS = 4;
    private static final int LOCAL_SIZE = 256;
    private static final int MODE_NONE = 0;
    private static final int MODE_DOWN = 1;
    private static final int MODE_UP = 2;
    private static final byte FLAG_EMPTY = 1;
    private static final byte FLAG_FOLIAGE = 2;
    private static final byte FLAG_AIR = 4;
    private static final byte FLAG_BEDROCK = 8;
    // Kept only for the reusable SPIR-V encoder.  Scheduling now lives in
    // TerrainRegionPlanCache: correctTerrainShape must never wait for a GPU batch.
    private static final int MAX_BATCH = Math.max(1, Integer.getInteger("lc2h.gpu.terrainCorrection.maxBatch", 16));
    private static final int MIN_GPU_BATCH = Math.max(1, Integer.getInteger("lc2h.gpu.terrainCorrection.minGpuBatch", 1));
    private static final long COALESCE_MS = Math.max(0L, Long.getLong("lc2h.gpu.terrainCorrection.coalesceMs", 1L));
    private static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("lc2h.gpu.terrainRegions.enabled", "true"));
    private static final boolean AUDIT = Boolean.parseBoolean(System.getProperty("lc2h.gpu.terrainRegions.audit", "true"));
    private static final boolean FORCE_CPU = Boolean.parseBoolean(System.getProperty("lc2h.gpu.terrainRegions.forceCpu", "false"));
    private static final int MAX_PRIMED = Math.max(128, Integer.getInteger("lc2h.gpu.terrainCorrection.maxPrimed", 4096));
    private static final AtomicBoolean FAILURE_LOGGED = new AtomicBoolean();
    private static final AtomicBoolean CORRECTION_FAILURE_LOGGED = new AtomicBoolean();
    private static final ConcurrentHashMap<BlockState, Byte> STATE_FLAGS = new ConcurrentHashMap<>();
    private static final AtomicLong REQUESTS = new AtomicLong();
    private static final AtomicLong GPU_BATCHES = new AtomicLong();
    private static final AtomicLong GPU_CHUNKS = new AtomicLong();
    private static final AtomicLong GPU_COLUMNS = new AtomicLong();
    private static final AtomicLong CPU_CHUNKS = new AtomicLong();
    private static final AtomicLong SMALL_BATCH_CPU = new AtomicLong();
    private static final AtomicLong MAX_OBSERVED_BATCH = new AtomicLong();
    private static final AtomicLong FALLBACKS = new AtomicLong();
    private static final AtomicLong AUDITS = new AtomicLong();
    private static final AtomicLong AUDIT_FAILURES = new AtomicLong();
    private static final AtomicLong SNAPSHOT_NS = new AtomicLong();
    private static final AtomicLong PLAN_NS = new AtomicLong();
    private static final AtomicLong APPLY_NS = new AtomicLong();
    private static final AtomicLong MOVED_COLUMNS = new AtomicLong();
    private static final AtomicLong PACKED_CAPTURE_FALLBACKS = new AtomicLong();
    private static final AtomicLong EARLY_CAPTURES = new AtomicLong();
    private static final AtomicLong CORRECTION_HOOKS = new AtomicLong();
    // Measures real cross-thread concurrency at this hook, to test whether Lost Cities'
    // striped-lock parallelism (LostCitiesGenerationLocks) ever puts multiple chunks through
    // correctTerrainShape at the same instant, which is a precondition for any real GPU batch.
    private static final java.util.concurrent.atomic.AtomicInteger IN_FLIGHT = new java.util.concurrent.atomic.AtomicInteger();
    private static final AtomicLong MAX_CONCURRENT_IN_FLIGHT = new AtomicLong();
    private static volatile boolean auditDisabled;
    private static volatile String lastFailure = "none";
    private static volatile long runtimeCheckAtNs;
    private static volatile boolean runtimeReady;

    private TerrainCorrectionGpuPipeline() {
    }

    public static boolean tryCorrect(WorldGenLevel world,
                                     ChunkCoord coord,
                                     ChunkHeightmap heightmap,
                                     IDimensionInfo provider,
                                     ChunkDriver driver,
                                     BlockState air) {
        if (!ENABLED || world == null || coord == null || heightmap == null || provider == null || driver == null || air == null) {
            return false;
        }
        CORRECTION_HOOKS.incrementAndGet();
        // Lost Cities' synchronous correction callback may only consume a
        // completed plan. Snapshot capture happens earlier in generate(),
        // after setPrimer and heightmap construction; this callback never
        // queues GPU work and never waits for it.
        if (!TerrainRegionPlanCache.hasUpstreamCapture()) {
            return false;
        }
        // Do not pay for a packed snapshot when Vulkan cannot own the work. Native
        // Lost Cities remains the zero-overhead fallback until QAPI is executable.
        if (!FORCE_CPU && (auditDisabled || !isRuntimeReady())) {
            return false;
        }
        if (!FORCE_CPU && !TerrainRegionPlanCache.mayHaveReady(provider, coord)) {
            // Keep Lost Cities' native scalar path entirely untouched until a
            // validated region mask is actually available for this chunk.
            return false;
        }
        long totalStart = System.nanoTime();
        int concurrent = IN_FLIGHT.incrementAndGet();
        MAX_CONCURRENT_IN_FLIGHT.accumulateAndGet(concurrent, Math::max);
        try {
            Request request = snapshot(world, coord, heightmap, provider, driver, air);
            if (request == null) {
                return false;
            }
            REQUESTS.incrementAndGet();
            Plan plan = request.noop() ? Plan.empty() : TerrainRegionPlanCache.consume(request);
            if (plan == null) {
                // Never issue a one-chunk Vulkan dispatch or wait on a region.
                // The exact scalar calculation is the immediate compatibility path.
                CPU_CHUNKS.incrementAndGet();
                long planStart = System.nanoTime();
                plan = scalarPlan(request);
                PLAN_NS.addAndGet(System.nanoTime() - planStart);
            }
            if (plan == null) {
                return false;
            }
            long applyStart = System.nanoTime();
            apply(request, plan);
            APPLY_NS.addAndGet(System.nanoTime() - applyStart);
            return true;
        } catch (Throwable throwable) {
            FALLBACKS.incrementAndGet();
            if (CORRECTION_FAILURE_LOGGED.compareAndSet(false, true)) {
                LC2H.LOGGER.warn("[LC2H] Terrain correction pipeline failed for {}; native Lost Cities fallback remains active", coord, throwable);
            }
            return false;
        } finally {
            IN_FLIGHT.decrementAndGet();
            Lc2hTimingRegistry.record("gpu.terrain_correction.total", System.nanoTime() - totalStart);
        }
    }

    static Request snapshot(WorldGenLevel world,
                            ChunkCoord coord,
                            ChunkHeightmap heightmap,
                            IDimensionInfo provider,
                            ChunkDriver driver,
                            BlockState air) {
        return driver == null ? null : snapshot(world, coord, heightmap, provider, driver.getPrimer(), driver, air);
    }

    /**
     * Capture immutable post-noise terrain data while Lost Cities is still in
     * generate(). The mutable driver deliberately stays out of the cached
     * request: it is supplied only by the later owning correction callback.
     */
    public static void captureUpstream(WorldGenLevel world,
                                       ChunkCoord coord,
                                       ChunkHeightmap heightmap,
                                       IDimensionInfo provider,
                                       ChunkAccess chunk,
                                       BlockState air) {
        if (!ENABLED || world == null || coord == null || heightmap == null || provider == null || chunk == null || air == null) {
            return;
        }
        if (!TerrainRegionPlanCache.hasUpstreamCapture() || FORCE_CPU || auditDisabled || !isRuntimeReady()) {
            return;
        }
        try {
            Request request = snapshot(world, coord, heightmap, provider, chunk, null, air);
            if (request != null) {
                TerrainRegionPlanCache.capture(request);
            }
        } catch (Throwable throwable) {
            TerrainRegionPlanCache.recordCaptureFailure(throwable);
        }
    }

    private static Request snapshot(WorldGenLevel world,
                                    ChunkCoord coord,
                                    ChunkHeightmap heightmap,
                                    IDimensionInfo provider,
                                    ChunkAccess chunk,
                                    ChunkDriver driver,
                                    BlockState air) {
        long start = System.nanoTime();
        if (chunk == null) {
            return null;
        }
        BuildingInfo info = BuildingInfo.getBuildingInfo(coord, provider);
        if (info.isCity || (info.outsideChunk && info.hasBuilding)) {
            return null;
        }
        BuildingInfo.MinMax c00 = info.getDesiredMaxHeightL2();
        BuildingInfo.MinMax c10 = info.getXmax().getDesiredMaxHeightL2();
        BuildingInfo.MinMax c01 = info.getZmax().getDesiredMaxHeightL2();
        BuildingInfo.MinMax c11 = info.getXmax().getZmax().getDesiredMaxHeightL2();
        int minBuildHeight = world.getMinBuildHeight();
        int maxBuildHeight = world.getMaxBuildHeight();
        if (allOutside(maxBuildHeight, c00, c10, c01, c11)) {
            return Request.noop(coord, heightmap, driver, air, minBuildHeight, maxBuildHeight);
        }

        float min00 = normalizeMin(c00.min, maxBuildHeight, heightmap.getHeight() - 90);
        float min10 = normalizeMin(c10.min, maxBuildHeight, heightmap.getHeight() - 90);
        float min01 = normalizeMin(c01.min, maxBuildHeight, heightmap.getHeight() - 90);
        float min11 = normalizeMin(c11.min, maxBuildHeight, heightmap.getHeight() - 90);
        float max00 = normalizeMax(c00.max, maxBuildHeight, heightmap.getHeight() + 90);
        float max10 = normalizeMax(c10.max, maxBuildHeight, heightmap.getHeight() + 90);
        float max01 = normalizeMax(c01.max, maxBuildHeight, heightmap.getHeight() + 90);
        float max11 = normalizeMax(c11.max, maxBuildHeight, heightmap.getHeight() + 90);
        int[] targetMax = new int[COLUMNS];
        int[] targetMin = new int[COLUMNS];
        for (int x = 0; x < 16; x++) {
            float fx = (15.0f - x) / 15.0f;
            float maxZ1 = max11 + (max01 - max11) * fx;
            float maxZ0 = max10 + (max00 - max10) * fx;
            float minZ1 = min11 + (min01 - min11) * fx;
            float minZ0 = min10 + (min00 - min10) * fx;
            for (int z = 0; z < 16; z++) {
                int column = (x << 4) | z;
                float fz = (15.0f - z) / 15.0f;
                targetMax[column] = Math.min((int) (maxZ1 + (maxZ0 - maxZ1) * fz), maxBuildHeight);
                targetMin[column] = Math.max((int) (minZ1 + (minZ0 - minZ1) * fz), minBuildHeight);
            }
        }
        LevelChunkSection[] sections = chunk.getSections();
        PackedSection[] packedSections = new PackedSection[sections.length];
        for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
            LevelChunkSection section = sections[sectionIndex];
            PackedSection packed = section == null || section.hasOnlyAir()
                ? PackedSection.constant(air)
                : pack(section.getStates());
            if (packed == null) {
                return null;
            }
            packedSections[sectionIndex] = packed;
        }
        SNAPSHOT_NS.addAndGet(System.nanoTime() - start);
        return new Request(WorldGenScope.cache(provider).stableText(), coord, heightmap, driver, air, minBuildHeight, maxBuildHeight,
            info.waterLevel > info.groundLevel, targetMax, targetMin, packedSections, new CompletableFuture<>());
    }

    private static PackedSection pack(PalettedContainer<BlockState> container) {
        PalettedContainerStorageView.Snapshot<BlockState> packed = PalettedContainerStorageView.capture(container, 512);
        if (packed == null) {
            PACKED_CAPTURE_FALLBACKS.incrementAndGet();
            return null;
        }
        List<BlockState> entries = packed.paletteEntries();
        int paletteSize = entries.size();
        BlockState[] states = entries.toArray(BlockState[]::new);
        byte[] stateFlags = new byte[paletteSize];
        for (int i = 0; i < paletteSize; i++) {
            stateFlags[i] = flags(states[i]);
        }
        int bits = packed.bits();
        long[] raw = packed.raw();
        int valuesPerLong = bits == 0 ? 0 : 64 / bits;
        return new PackedSection(bits, valuesPerLong, raw, states, stateFlags);
    }

    private static boolean allOutside(int maxBuildHeight, BuildingInfo.MinMax... values) {
        for (BuildingInfo.MinMax value : values) {
            if (value.min < maxBuildHeight || value.max < maxBuildHeight) {
                return false;
            }
        }
        return true;
    }

    private static float normalizeMin(int value, int maxBuildHeight, int replacement) {
        return value >= maxBuildHeight ? replacement : value;
    }

    private static float normalizeMax(int value, int maxBuildHeight, int replacement) {
        return value >= maxBuildHeight ? replacement : value;
    }

    private static byte flags(BlockState state) {
        return STATE_FLAGS.computeIfAbsent(state, TerrainCorrectionGpuPipeline::classify);
    }

    private static byte classify(BlockState state) {
        int flags = 0;
        if (LostCityTerrainFeature.isEmpty(state)) {
            flags |= FLAG_EMPTY;
        }
        if (Tools.hasTag(state.getBlock(), LostTags.FOLIAGE_TAG)) {
            flags |= FLAG_FOLIAGE;
        }
        if (state.isAir()) {
            flags |= FLAG_AIR;
        }
        if (state.is(Blocks.BEDROCK)) {
            flags |= FLAG_BEDROCK;
        }
        return (byte) flags;
    }

    private static void executeGroup(List<Request> requests) {
        long start = System.nanoTime();
        if (requests.size() < MIN_GPU_BATCH) {
            SMALL_BATCH_CPU.addAndGet(requests.size());
            List<Plan> plans = scalarPlans(requests);
            for (int i = 0; i < requests.size(); i++) {
                requests.get(i).future().complete(plans.get(i));
            }
            PLAN_NS.addAndGet(System.nanoTime() - start);
            return;
        }
        try {
            Batch batch = Batch.encode(requests);
            float[] output = ProgramHolder.PREPARED.submitRequired(batch.taskKey(), batch.dispatch(),
                Duration.ofSeconds(30)).join();
            List<Plan> plans = batch.decode(output);
            if (AUDIT) {
                audit(requests, plans);
            }
            GPU_BATCHES.incrementAndGet();
            GPU_CHUNKS.addAndGet(requests.size());
            GPU_COLUMNS.addAndGet((long) requests.size() * COLUMNS);
            for (int i = 0; i < requests.size(); i++) {
                requests.get(i).future().complete(plans.get(i));
            }
        } catch (Throwable throwable) {
            FALLBACKS.addAndGet(requests.size());
            Throwable cause = unwrap(throwable);
            lastFailure = cause.getClass().getSimpleName() + ": " + String.valueOf(cause.getMessage());
            if (FAILURE_LOGGED.compareAndSet(false, true)) {
                LC2H.LOGGER.warn("[LC2H] Terrain correction Vulkan batch failed; CPU fallback remains authoritative: {}", lastFailure, cause);
            }
            List<Plan> fallback = scalarPlans(requests);
            for (int i = 0; i < requests.size(); i++) {
                requests.get(i).future().complete(fallback.get(i));
            }
        } finally {
            PLAN_NS.addAndGet(System.nanoTime() - start);
        }
    }

    /**
     * Region-cache worker entry. This deliberately runs away from the Lost
     * Cities callback and never exposes a future to that callback.
     */
    static List<Plan> computeRegion(List<Request> requests) {
        if (requests.isEmpty()) {
            return List.of();
        }
        long start = System.nanoTime();
        try {
            Batch batch = Batch.encode(requests);
            float[] output = ProgramHolder.PREPARED.submitRequired(batch.taskKey(), batch.dispatch(),
                Duration.ofSeconds(30)).join();
            List<Plan> plans = batch.decode(output);
            if (AUDIT && !audit(requests, plans)) {
                throw new IllegalStateException("terrain-region audit mismatch");
            }
            GPU_BATCHES.incrementAndGet();
            GPU_CHUNKS.addAndGet(requests.size());
            GPU_COLUMNS.addAndGet((long) requests.size() * COLUMNS);
            return plans;
        } finally {
            PLAN_NS.addAndGet(System.nanoTime() - start);
        }
    }

    static long estimatedBytes(List<Request> requests) {
        if (requests == null || requests.isEmpty()) {
            return 0L;
        }
        long bytes = 0L;
        for (Request request : requests) {
            bytes += (long) request.targetMax().length * Integer.BYTES;
            bytes += (long) request.targetMin().length * Integer.BYTES;
            for (PackedSection section : request.sections()) {
                bytes += (long) section.raw().length * Long.BYTES;
                bytes += section.flags().length;
            }
        }
        return bytes;
    }

    public static void clearRegions() {
        TerrainRegionPlanCache.clearAll();
        STATE_FLAGS.clear();
        auditDisabled = false;
        runtimeReady = false;
        runtimeCheckAtNs = 0L;
    }

    private static List<Plan> scalarPlans(List<Request> requests) {
        CPU_CHUNKS.addAndGet(requests.size());
        List<Plan> plans = new ArrayList<>(requests.size());
        for (Request request : requests) {
            plans.add(scalarPlan(request));
        }
        return plans;
    }

    static Plan scalarPlan(Request request) {
        int[] operations = new int[COLUMNS * OUTPUT_WORDS];
        int minBuildHeight = request.minBuildHeight();
        int maxBuildHeight = request.maxBuildHeight();
        for (int column = 0; column < COLUMNS; column++) {
            int y = maxBuildHeight - 1;
            while (is(request, column, y, FLAG_EMPTY) && y > request.targetMax()[column]) {
                y--;
            }
            if (y > request.targetMax()[column]) {
                int total = y - request.targetMax()[column] + 1;
                set(operations, column, MODE_DOWN, y, request.targetMax()[column], Math.min(total, 6));
                continue;
            }
            y = request.targetMin()[column];
            while (is(request, column, y, FLAG_EMPTY | FLAG_FOLIAGE) && y > minBuildHeight) {
                y--;
            }
            if (y >= request.targetMin()[column]) {
                continue;
            }
            int sourceTop = y;
            int count = 0;
            while (y > 0 && !is(request, column, y, FLAG_AIR | FLAG_BEDROCK)) {
                count++;
                y--;
            }
            if (count > 0) {
                set(operations, column, MODE_UP, sourceTop, request.targetMin()[column], count);
            }
        }
        return new Plan(operations);
    }

    private static boolean is(Request request, int column, int y, int mask) {
        int yIndex = y - request.minBuildHeight();
        if (yIndex < 0 || yIndex >= request.columnHeight()) {
            return (mask & (FLAG_EMPTY | FLAG_AIR)) != 0;
        }
        return (request.flagsAt(column, yIndex) & mask) != 0;
    }

    private static void set(int[] operations, int column, int mode, int sourceTop, int target, int count) {
        int base = column * OUTPUT_WORDS;
        operations[base] = mode;
        operations[base + 1] = sourceTop;
        operations[base + 2] = target;
        operations[base + 3] = count;
    }

    private static boolean audit(List<Request> requests, List<Plan> gpuPlans) {
        boolean clean = true;
        for (int i = 0; i < requests.size(); i++) {
            AUDITS.incrementAndGet();
            Plan cpu = scalarPlan(requests.get(i));
            if (!cpu.sameAs(gpuPlans.get(i))) {
                AUDIT_FAILURES.incrementAndGet();
                auditDisabled = true;
                clean = false;
                LC2H.LOGGER.error("[LC2H] Terrain correction Vulkan audit mismatch at {}; GPU path disabled", requests.get(i).coord());
                gpuPlans.set(i, cpu);
            }
        }
        return clean;
    }

    private static void apply(Request request, Plan plan) {
        if (request.noop()) {
            return;
        }
        int centerHeight = Integer.MIN_VALUE;
        int moved = 0;
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int column = (x << 4) | z;
                int base = column * OUTPUT_WORDS;
                int mode = plan.operations()[base];
                if (mode == MODE_NONE) {
                    continue;
                }
                int sourceTop = plan.operations()[base + 1];
                int target = plan.operations()[base + 2];
                int count = plan.operations()[base + 3];
                int result;
                if (mode == MODE_DOWN) {
                    BlockState[] buffer = new BlockState[count];
                    for (int i = 0; i < count; i++) {
                        buffer[i] = request.state(column, sourceTop - i);
                    }
                    request.driver().current(x, sourceTop, z);
                    while (request.driver().getY() >= target) {
                        request.driver().block(request.air());
                        request.driver().decY();
                    }
                    result = request.driver().getY();
                    for (BlockState state : buffer) {
                        if (request.driver().getY() <= 0) {
                            break;
                        }
                        request.driver().block(state);
                        request.driver().decY();
                    }
                } else {
                    request.driver().current(x, target, z);
                    for (int i = 0; i < count; i++) {
                        request.driver().block(request.state(column, sourceTop - i));
                        request.driver().decY();
                    }
                    result = sourceTop;
                }
                moved++;
                if (x == 8 && z == 8) {
                    centerHeight = Math.max(centerHeight, result);
                }
            }
        }
        MOVED_COLUMNS.addAndGet(moved);
        if (centerHeight != Integer.MIN_VALUE) {
            request.heightmap().setHeight(centerHeight);
        }
    }

    static boolean isRuntimeReady() {
        if (runtimeReady) {
            return true;
        }
        long now = System.nanoTime();
        if (now < runtimeCheckAtNs) {
            return runtimeReady;
        }
        synchronized (TerrainCorrectionGpuPipeline.class) {
            if (now >= runtimeCheckAtNs) {
                try {
                    runtimeReady = QuantifiedVulkan.isGpuReady();
                } catch (Throwable ignored) {
                    runtimeReady = false;
                }
                runtimeCheckAtNs = now + (runtimeReady ? 5_000_000_000L : 250_000_000L);
            }
            return runtimeReady;
        }
    }

    private static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while ((current instanceof CompletionException || current instanceof java.util.concurrent.ExecutionException)
            && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static String rootMessage(Throwable throwable) {
        Throwable root = unwrap(throwable);
        String message = root.getMessage();
        return root.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
    }

    public static String diagnostics() {
        long requests = REQUESTS.get();
        return "enabled=" + ENABLED
            + " ready=" + runtimeReady
            + " forceCpu=" + FORCE_CPU
            + " regions={" + TerrainRegionPlanCache.diagnostics() + "}"
            + " requests=" + requests
            + " gpu=" + GPU_CHUNKS.get() + "/" + GPU_BATCHES.get()
            + " gpuColumns=" + GPU_COLUMNS.get()
            + " cpu=" + CPU_CHUNKS.get()
            + " smallBatchCpu=" + SMALL_BATCH_CPU.get()
            + " minGpuBatch=" + MIN_GPU_BATCH
            + " maxBatch=" + MAX_OBSERVED_BATCH.get()
            + " fallback=" + FALLBACKS.get()
            + " audit=" + AUDITS.get() + "/" + AUDIT_FAILURES.get()
            + " auditDisabled=" + auditDisabled
            + " lastFailure=" + lastFailure
            + " storage=" + PalettedContainerStorageView.mode()
            + " maxConcurrentInFlight=" + MAX_CONCURRENT_IN_FLIGHT.get()
            + " packedFallback=" + PACKED_CAPTURE_FALLBACKS.get()
            + " early=" + EARLY_CAPTURES.get()
            + " correctionHooks=" + CORRECTION_HOOKS.get()
            + " movedColumns=" + MOVED_COLUMNS.get()
            + " avgMs[snapshot=" + ms(SNAPSHOT_NS.get(), requests)
            + " plan=" + ms(PLAN_NS.get(), requests)
            + " apply=" + ms(APPLY_NS.get(), requests) + "]";
    }

    private static String ms(long nanos, long count) {
        return String.format(java.util.Locale.ROOT, "%.3f", count == 0 ? 0.0 : (nanos / 1_000_000.0) / count);
    }

    private record Shape(int minBuildHeight, int columnHeight) {
    }

    static record Request(String scope,
                          ChunkCoord coord,
                           ChunkHeightmap heightmap,
                           ChunkDriver driver,
                           BlockState air,
                           int minBuildHeight,
                           int maxBuildHeight,
                           boolean waterAboveGround,
                           int[] targetMax,
                           int[] targetMin,
                           PackedSection[] sections,
                           CompletableFuture<Plan> future) {
        static Request noop(ChunkCoord coord, ChunkHeightmap heightmap, ChunkDriver driver, BlockState air,
                            int minBuildHeight, int maxBuildHeight) {
            return new Request("noop", coord, heightmap, driver, air, minBuildHeight, maxBuildHeight, false,
                new int[0], new int[0], new PackedSection[0], new CompletableFuture<>());
        }

        int columnHeight() {
            return maxBuildHeight - minBuildHeight;
        }

        boolean noop() {
            return targetMax.length == 0;
        }

        BlockState state(int column, int y) {
            int yIndex = y - minBuildHeight;
            if (yIndex < 0 || yIndex >= columnHeight()) {
                return air;
            }
            return sections[yIndex >> 4].state(column, yIndex & 15);
        }

        byte flagsAt(int column, int yIndex) {
            return sections[yIndex >> 4].flags(column, yIndex & 15);
        }

        long signature() {
            long hash = 0xcbf29ce484222325L;
            hash = signatureMix(hash, minBuildHeight);
            hash = signatureMix(hash, maxBuildHeight);
            for (int value : targetMax) hash = signatureMix(hash, value);
            for (int value : targetMin) hash = signatureMix(hash, value);
            for (PackedSection section : sections) {
                hash = signatureMix(hash, section.bits());
                for (long word : section.raw()) hash = signatureMix(hash, word);
            }
            return hash;
        }

        private static long signatureMix(long hash, long value) {
            return (hash ^ value) * 0x100000001b3L;
        }
    }

    static record PackedSection(int bits,
                                 int valuesPerLong,
                                 long[] raw,
                                 BlockState[] states,
                                 byte[] flags) {
        static PackedSection constant(BlockState state) {
            return new PackedSection(0, 0, new long[0], new BlockState[] {state}, new byte[] {TerrainCorrectionGpuPipeline.flags(state)});
        }

        int paletteId(int column, int localY) {
            if (bits == 0) {
                return 0;
            }
            int x = column >> 4;
            int z = column & 15;
            int index = (localY << 8) | (z << 4) | x;
            int cell = index / valuesPerLong;
            int bitOffset = (index - cell * valuesPerLong) * bits;
            return (int) ((raw[cell] >>> bitOffset) & ((1L << bits) - 1L));
        }

        BlockState state(int column, int localY) {
            return states[paletteId(column, localY)];
        }

        byte flags(int column, int localY) {
            return flags[paletteId(column, localY)];
        }
    }

    static record Plan(int[] operations) {
        static Plan empty() {
            return new Plan(new int[COLUMNS * OUTPUT_WORDS]);
        }

        boolean sameAs(Plan other) {
            return other != null && java.util.Arrays.equals(operations, other.operations);
        }
    }

    /**
     * Public because Quantified API's isolated legacy-Vulkan bridge invokes the
     * workload through reflection from another module/class loader.
     */
    public static final class Batch {
        private final List<Request> requests;
        private final float[] targets;
        private final float[] metadata;
        private final float[] paletteFlags;
        private final float[] rawWords;
        private final int sectionsPerRequest;
        private final int minBuildHeight;
        private final long taskKey;

        private Batch(List<Request> requests, float[] targets, float[] metadata, float[] paletteFlags,
                      float[] rawWords, int sectionsPerRequest, int minBuildHeight, long taskKey) {
            this.requests = requests;
            this.targets = targets;
            this.metadata = metadata;
            this.paletteFlags = paletteFlags;
            this.rawWords = rawWords;
            this.sectionsPerRequest = sectionsPerRequest;
            this.minBuildHeight = minBuildHeight;
            this.taskKey = taskKey;
        }

        static Batch encode(List<Request> requests) {
            Request first = requests.get(0);
            int columns = requests.size() * COLUMNS;
            int sectionCount = first.sections().length;
            float[] targets = new float[columns * 2];
            int paletteWords = 0;
            int rawWordCount = 0;
            for (Request request : requests) {
                for (PackedSection section : request.sections()) {
                    paletteWords += section.flags().length;
                    rawWordCount += section.raw().length * 2;
                }
            }
            float[] metadata = new float[requests.size() * sectionCount * 5];
            float[] paletteFlags = new float[paletteWords];
            float[] rawWords = new float[rawWordCount];
            int globalColumn = 0;
            int sectionIndex = 0;
            int paletteOffset = 0;
            int rawOffset = 0;
            long key = 0xcbf29ce484222325L;
            for (Request request : requests) {
                key = mix(key, request.coord().chunkX());
                key = mix(key, request.coord().chunkZ());
                for (int column = 0; column < COLUMNS; column++, globalColumn++) {
                    targets[globalColumn * 2] = request.targetMax()[column];
                    targets[globalColumn * 2 + 1] = request.targetMin()[column];
                }
                for (PackedSection section : request.sections()) {
                    int meta = sectionIndex++ * 5;
                    metadata[meta] = section.bits();
                    metadata[meta + 1] = section.valuesPerLong();
                    metadata[meta + 2] = rawOffset;
                    metadata[meta + 3] = paletteOffset;
                    metadata[meta + 4] = section.flags().length;
                    for (byte flag : section.flags()) {
                        paletteFlags[paletteOffset++] = flag & 0xff;
                    }
                    for (long word : section.raw()) {
                        rawWords[rawOffset++] = Float.intBitsToFloat((int) word);
                        rawWords[rawOffset++] = Float.intBitsToFloat((int) (word >>> 32));
                    }
                }
            }
            return new Batch(requests, targets, metadata, paletteFlags, rawWords, sectionCount, first.minBuildHeight(), key);
        }

        long taskKey() {
            return taskKey;
        }

        public long estimatedVramBytes() {
            return (long) (targets.length + metadata.length + paletteFlags.length + rawWords.length
                + requests.size() * COLUMNS * OUTPUT_WORDS) * Float.BYTES;
        }

        public int estimatedComputeUnits() {
            return requests.size() * COLUMNS;
        }

        QuantifiedVulkan.Dispatch dispatch() {
            int columns = estimatedComputeUnits();
            return new QuantifiedVulkan.Dispatch(new float[][] {targets, metadata, paletteFlags, rawWords}, columns * OUTPUT_WORDS,
                new int[] {columns, minBuildHeight, sectionsPerRequest},
                (columns + LOCAL_SIZE - 1) / LOCAL_SIZE, 1, 1);
        }

        float[] scalarOutput() {
            List<Plan> plans = scalarPlans(requests);
            float[] output = new float[requests.size() * COLUMNS * OUTPUT_WORDS];
            int offset = 0;
            for (Plan plan : plans) {
                for (int operation : plan.operations()) {
                    output[offset++] = operation;
                }
            }
            return output;
        }

        List<Plan> decode(float[] output) {
            int expected = requests.size() * COLUMNS * OUTPUT_WORDS;
            if (output == null || output.length != expected) {
                throw new IllegalStateException("Prepared Vulkan terrain output length "
                    + (output == null ? -1 : output.length) + " != " + expected);
            }
            List<Plan> plans = new ArrayList<>(requests.size());
            for (int requestIndex = 0; requestIndex < requests.size(); requestIndex++) {
                int[] operations = new int[COLUMNS * OUTPUT_WORDS];
                int source = requestIndex * COLUMNS * OUTPUT_WORDS;
                for (int i = 0; i < operations.length; i++) {
                    operations[i] = Math.round(output[source + i]);
                }
                plans.add(new Plan(operations));
            }
            return plans;
        }

        private static long mix(long hash, int value) {
            hash ^= value;
            return hash * 0x100000001b3L;
        }
    }

    private static final class ProgramHolder {
        private static final SpirvComputeProgram PROGRAM = SpirvComputeProgram.fromResource(
            "lc2h-terrain-correction-v2", TerrainCorrectionGpuPipeline.class,
            "/lc2h/shaders/terrain_correction.comp.spv", 5, 12, LOCAL_SIZE);
        private static final QuantifiedVulkan.PreparedProgram PREPARED = QuantifiedVulkan.prepare(
            "lc2h", "terrain-correction-region", PROGRAM);
    }
}
