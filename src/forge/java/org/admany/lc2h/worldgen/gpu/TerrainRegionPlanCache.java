package org.admany.lc2h.worldgen.gpu;

import org.admany.lc2h.LC2H;
import org.admany.lc2h.concurrency.async.AsyncManager;
import org.admany.lc2h.concurrency.async.Priority;
import org.admany.lc2h.worldgen.scope.WorldGenScope;
import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.IDimensionInfo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * World-scoped, non-blocking terrain-operation cache.  It owns only immutable
 * snapshots and operation masks; ChunkDriver and Minecraft world access stay on
 * Lost Cities' owning worldgen thread.
 */
final class TerrainRegionPlanCache {
    private static final int SCHEMA = 1;
    private static final int SIDE = Math.max(1, Integer.getInteger("lc2h.gpu.terrainRegions.side", 16));
    private static final int MAX_BATCH = SIDE * SIDE;
    private static final int MIN_SUBMIT = Math.min(MAX_BATCH,
        Math.max(2, Integer.getInteger("lc2h.gpu.terrainRegions.minSubmit", 16)));
    private static final long COALESCE_MS = Math.max(1L, Long.getLong("lc2h.gpu.terrainRegions.coalesceMs", 8L));
    private static final int MAX_REGIONS = Math.max(8, Integer.getInteger("lc2h.gpu.terrainRegions.maxRegions", 128));
    /**
     * Captures are made at Lost Cities' post-heightmap generate() boundary.
     * This is a real upstream producer, not a WorldGenRegion walk-ahead or a
     * correction-hook dispatch.  The completed plan is only consumed when it
     * is already available; every incomplete/stale region remains on the
     * exact scalar path with no wait on chunk generation.
     */
    // A region plan is only useful when it was captured before the owning
    // correction callback.  The real Forge lane has so far shown zero such
    // coverage: first-pass chunks finish correction before the isolated Vulkan
    // runtime becomes executable.  Keep this opt-in until an ahead-of-time
    // producer proves non-zero audited coverage; otherwise capture merely adds
    // packing/allocation work to the CPU path it was meant to relieve.
    private static final boolean UPSTREAM_CAPTURE = Boolean.parseBoolean(
        System.getProperty("lc2h.gpu.terrainRegions.upstreamCapture", "false"));

    private static final ConcurrentHashMap<RegionKey, Region> REGIONS = new ConcurrentHashMap<>();
    private static final AtomicLong COLLECTING = new AtomicLong();
    private static final AtomicLong SUBMITTED = new AtomicLong();
    private static final AtomicLong READY = new AtomicLong();
    private static final AtomicLong INVALID = new AtomicLong();
    private static final AtomicLong CAPTURED = new AtomicLong();
    private static final AtomicLong GPU_COVERED = new AtomicLong();
    private static final AtomicLong CPU_IMMEDIATE = new AtomicLong();
    private static final AtomicLong STALE_REJECTS = new AtomicLong();
    private static final AtomicLong AUDIT_PASSES = new AtomicLong();
    private static final AtomicLong AUDIT_FAILURES = new AtomicLong();
    private static final AtomicLong MAX_BATCH_OBSERVED = new AtomicLong();
    private static final AtomicLong UPLOAD_BYTES = new AtomicLong();
    private static final AtomicLong READBACK_BYTES = new AtomicLong();
    private static final AtomicLong GPU_PLAN_NS = new AtomicLong();
    private static volatile long invalidLifecycle = Long.MIN_VALUE;
    private static volatile String firstFailure = "none";

    private TerrainRegionPlanCache() {
    }

    static void capture(TerrainCorrectionGpuPipeline.Request request) {
        if (request == null || request.noop() || invalidForCurrentLifecycle()) {
            return;
        }
        if (REGIONS.size() >= MAX_REGIONS) {
            // Regions are immutable and opportunistic. Dropping old, unconsumed
            // work is safe because correctTerrainShape always has scalar CPU.
            REGIONS.clear();
        }
        RegionKey key = key(request);
        Region region = REGIONS.computeIfAbsent(key, Region::new);
        Entry entry = new Entry(request.signature(), request, null);
        Entry previous = region.entries.putIfAbsent(chunkKey(request), entry);
        if (previous != null) {
            if (previous.signature != entry.signature) {
                region.entries.put(chunkKey(request), entry);
                STALE_REJECTS.incrementAndGet();
            } else {
                return;
            }
        }
        CAPTURED.incrementAndGet();
        COLLECTING.accumulateAndGet(REGIONS.size(), Math::max);
        if (region.entries.size() >= MIN_SUBMIT && region.submitted.compareAndSet(false, true)) {
            schedule(region);
        }
    }

    static boolean hasUpstreamCapture() {
        return UPSTREAM_CAPTURE;
    }

    static void recordCaptureFailure(Throwable throwable) {
        if ("none".equals(firstFailure)) {
            firstFailure = "capture " + rootMessage(throwable);
            LC2H.LOGGER.warn("[LC2H] Vulkan terrain-region capture failed; native Lost Cities correction remains active: {}", firstFailure);
        }
    }

    static TerrainCorrectionGpuPipeline.Plan consume(TerrainCorrectionGpuPipeline.Request current) {
        if (current == null || current.noop() || invalidForCurrentLifecycle()) {
            CPU_IMMEDIATE.incrementAndGet();
            return null;
        }
        Region region = REGIONS.get(key(current));
        if (region == null) {
            CPU_IMMEDIATE.incrementAndGet();
            return null;
        }
        Entry entry = region.entries.get(chunkKey(current));
        if (entry == null || entry.plan == null) {
            CPU_IMMEDIATE.incrementAndGet();
            return null;
        }
        if (entry.signature != current.signature()) {
            STALE_REJECTS.incrementAndGet();
            CPU_IMMEDIATE.incrementAndGet();
            return null;
        }
        GPU_COVERED.incrementAndGet();
        return entry.plan;
    }

    static boolean mayHaveReady(IDimensionInfo provider, ChunkCoord coord) {
        if (provider == null || coord == null || invalidForCurrentLifecycle()) {
            return false;
        }
        Region region = REGIONS.get(key(WorldGenScope.cache(provider).stableText(), coord));
        if (region == null || region.invalid) {
            return false;
        }
        Entry entry = region.entries.get(((long) coord.chunkX() << 32) ^ (coord.chunkZ() & 0xffffffffL));
        return entry != null && entry.plan != null;
    }

    static void clearAll() {
        REGIONS.clear();
        invalidLifecycle = Long.MIN_VALUE;
        firstFailure = "none";
    }

    static int regionOriginForTest(int chunk) {
        return Math.floorDiv(chunk, SIDE) * SIDE;
    }

    private static void schedule(Region region) {
        SUBMITTED.incrementAndGet();
        AsyncManager.runLater("terrain-region-plan-" + region.key.originX + "-" + region.key.originZ,
            () -> compute(region), COALESCE_MS, Priority.HIGH).whenComplete((ignored, failure) -> {
                if (failure != null) {
                    invalidate(region, "region scheduling: " + rootMessage(failure));
                }
            });
    }

    private static void compute(Region region) {
        List<Map.Entry<Long, Entry>> entries = new ArrayList<>(region.entries.entrySet());
        entries.sort(Comparator.comparingLong(Map.Entry::getKey));
        if (entries.size() < MIN_SUBMIT || invalidForCurrentLifecycle()) {
            return;
        }
        if (entries.size() > MAX_BATCH) {
            entries = entries.subList(0, MAX_BATCH);
        }
        Map<Shape, List<Map.Entry<Long, Entry>>> shapes = new HashMap<>();
        for (Map.Entry<Long, Entry> item : entries) {
            TerrainCorrectionGpuPipeline.Request request = item.getValue().request;
            shapes.computeIfAbsent(new Shape(request.minBuildHeight(), request.columnHeight()), ignored -> new ArrayList<>()).add(item);
        }
        for (List<Map.Entry<Long, Entry>> group : shapes.values()) {
            long started = System.nanoTime();
            try {
                List<TerrainCorrectionGpuPipeline.Request> requests = group.stream().map(item -> item.getValue().request).toList();
                long inputBytes = TerrainCorrectionGpuPipeline.estimatedBytes(requests);
                List<TerrainCorrectionGpuPipeline.Plan> plans = TerrainCorrectionGpuPipeline.computeRegion(requests);
                if (plans.size() != group.size()) {
                    throw new IllegalStateException("terrain region plan count " + plans.size() + " != " + group.size());
                }
                for (int i = 0; i < group.size(); i++) {
                    Map.Entry<Long, Entry> item = group.get(i);
                    Entry expected = item.getValue();
                    region.entries.replace(item.getKey(), expected, expected.withPlan(plans.get(i)));
                }
                READY.addAndGet(group.size());
                AUDIT_PASSES.addAndGet(group.size());
                MAX_BATCH_OBSERVED.accumulateAndGet(group.size(), Math::max);
                UPLOAD_BYTES.addAndGet(inputBytes);
                READBACK_BYTES.addAndGet((long) group.size() * 16 * 16 * 4 * Float.BYTES);
            } catch (Throwable failure) {
                AUDIT_FAILURES.incrementAndGet();
                String detail = rootMessage(failure);
                invalidate(region, detail, detail.contains("audit mismatch"));
                return;
            } finally {
                GPU_PLAN_NS.addAndGet(System.nanoTime() - started);
            }
        }
    }

    private static void invalidate(Region region, String detail) {
        invalidate(region, detail, false);
    }

    private static void invalidate(Region region, String detail, boolean lifecycleWide) {
        region.invalid = true;
        if (lifecycleWide) {
            invalidLifecycle = WorldGenScope.activeLifecycleId();
        }
        INVALID.incrementAndGet();
        if ("none".equals(firstFailure)) {
            firstFailure = detail + " region=" + region.key.shortText();
            LC2H.LOGGER.warn("[LC2H] Vulkan terrain-region {}: {}",
                lifecycleWide ? "cache disabled for lifecycle " + invalidLifecycle : "plan invalidated", firstFailure);
        }
    }

    private static boolean invalidForCurrentLifecycle() {
        return invalidLifecycle == WorldGenScope.activeLifecycleId();
    }

    private static RegionKey key(TerrainCorrectionGpuPipeline.Request request) {
        return key(request.scope(), request.coord());
    }

    private static RegionKey key(String scope, ChunkCoord coord) {
        return new RegionKey(scope, SCHEMA,
            Math.floorDiv(coord.chunkX(), SIDE) * SIDE, Math.floorDiv(coord.chunkZ(), SIDE) * SIDE);
    }

    private static long chunkKey(TerrainCorrectionGpuPipeline.Request request) {
        return ((long) request.coord().chunkX() << 32) ^ (request.coord().chunkZ() & 0xffffffffL);
    }

    private static String rootMessage(Throwable throwable) {
        Throwable root = throwable;
        while ((root instanceof java.util.concurrent.CompletionException || root instanceof java.util.concurrent.ExecutionException)
            && root.getCause() != null) root = root.getCause();
        return root.getClass().getSimpleName() + ": " + String.valueOf(root.getMessage());
    }

    static String diagnostics() {
        long captured = CAPTURED.get();
        return "enabled=true capture=" + (UPSTREAM_CAPTURE ? "upstream-generate" : "disabled")
            + " side=" + SIDE + " schema=" + SCHEMA
            + " regions[collecting=" + COLLECTING.get() + ",submitted=" + SUBMITTED.get() + ",ready=" + READY.get() + ",invalid=" + INVALID.get() + "]"
            + " captured=" + captured + " gpuCovered=" + GPU_COVERED.get() + " cpuImmediate=" + CPU_IMMEDIATE.get()
            + " stale=" + STALE_REJECTS.get() + " audit=" + AUDIT_PASSES.get() + "/" + AUDIT_FAILURES.get()
            + " maxBatch=" + MAX_BATCH_OBSERVED.get() + "/" + MAX_BATCH
            + " bytes[up=" + UPLOAD_BYTES.get() + ",down=" + READBACK_BYTES.get() + "]"
            + " avgGpuPlanMs=" + formatMs(GPU_PLAN_NS.get(), Math.max(1L, SUBMITTED.get()))
            + " disabled=" + invalidForCurrentLifecycle() + " firstFailure=" + firstFailure;
    }

    private static String formatMs(long nanos, long count) {
        return String.format(java.util.Locale.ROOT, "%.3f", nanos / 1_000_000.0 / count);
    }

    private record RegionKey(String scope, int schema, int originX, int originZ) {
        String shortText() {
            return originX + "," + originZ + " schema=" + schema;
        }
    }

    private static final class Region {
        final RegionKey key;
        final ConcurrentHashMap<Long, Entry> entries = new ConcurrentHashMap<>();
        final AtomicBoolean submitted = new AtomicBoolean();
        volatile boolean invalid;

        Region(RegionKey key) {
            this.key = key;
        }
    }

    private record Entry(long signature, TerrainCorrectionGpuPipeline.Request request,
                         TerrainCorrectionGpuPipeline.Plan plan) {
        Entry withPlan(TerrainCorrectionGpuPipeline.Plan completed) {
            return new Entry(signature, request, completed);
        }
    }

    private record Shape(int minBuildHeight, int columnHeight) {
    }
}
