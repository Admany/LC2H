package org.admany.lc2h.worldgen.terrain;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Runtime counters for the density blender. Kept outside the mixin so the
 * diagnostics command can read them before transformation. */
public final class MountainCityBlendDiagnostics {

    private static final AtomicLong SEEN = new AtomicLong();
    private static final AtomicLong NO_PROVIDER = new AtomicLong();
    private static final AtomicLong NO_PROFILE = new AtomicLong();
    private static final AtomicLong CENTER_IS_CITY = new AtomicLong();
    private static final AtomicLong APPLIED = new AtomicLong();
    private static final AtomicLong BLEND_CALLS = new AtomicLong();
    private static final AtomicLong DENSITY_OFFSET_CALLS = new AtomicLong();
    private static final AtomicLong DENSITY_SHIFTED_SAMPLES = new AtomicLong();
    private static final AtomicLong DENSITY_SURFACE_FALLBACKS = new AtomicLong();
    private static final AtomicLong DENSITY_POSITIVE_SHIFT_CALLS = new AtomicLong();
    private static final AtomicLong DENSITY_DEPTH_REJECTS = new AtomicLong();
    private static final AtomicLong DENSITY_SOLID_INPUTS = new AtomicLong();
    private static final AtomicLong DENSITY_AIR_INPUTS = new AtomicLong();
    private static final AtomicLong MAX_DENSITY_SHIFT_MILLI = new AtomicLong();
    private static final AtomicLong NOISE_SHAPED_CHUNKS = new AtomicLong();
    private static final AtomicLong NOISE_SHAPE_ATTEMPTS = new AtomicLong();
    private static final AtomicLong NOISE_SHAPE_CANDIDATES = new AtomicLong();
    private static final AtomicLong NOISE_SHAPED_COLUMNS = new AtomicLong();
    private static final AtomicLong NOISE_REMOVED_BLOCKS = new AtomicLong();
    private static final AtomicLong NATURAL_CORRECTION_SKIPS = new AtomicLong();
    private static final AtomicLong MOUNTAIN_CORRECTION_SKIPS = new AtomicLong();
    private static final AtomicLong CORRECTION_VETO_NO_INFO = new AtomicLong();
    private static final AtomicLong CORRECTION_VETO_DIRECT_STRUCTURE = new AtomicLong();
    private static final AtomicLong CORRECTION_VETO_OUTSIDE_BUILDING = new AtomicLong();
    private static final AtomicLong CORRECTION_VETO_HIGHWAY = new AtomicLong();
    private static final AtomicLong CORRECTION_VETO_RAIL = new AtomicLong();
    private static final AtomicLong CORRECTION_VETO_NO_HEIGHTMAP = new AtomicLong();
    private static final AtomicLong CORRECTION_VETO_NO_TARGET = new AtomicLong();
    private static final AtomicLong CORRECTION_VETO_CITY_CLEARANCE = new AtomicLong();
    private static final AtomicLong GATE_SEEN = new AtomicLong();
    private static final AtomicLong GATE_ALREADY_ACTIVE = new AtomicLong();
    private static final AtomicLong GATE_READY_REBUILDS = new AtomicLong();
    private static final AtomicLong GATE_DEFERRED = new AtomicLong();
    private static final AtomicLong GATE_RESUMED = new AtomicLong();
    private static final AtomicLong GATE_NO_REGION = new AtomicLong();
    private static final AtomicLong GATE_FAILURES = new AtomicLong();

    /** The decision recorded at generation time. A later warm-cache lookup is not
     * a substitute for this value. */
    public record GenerationOutcome(String summary, double centreShift) {
    }

    /** Per-target evidence for the chunk that actually entered fillFromNoise. */
    public record ChunkTraceSnapshot(
        long gateSeen,
        long gateAlreadyActive,
        long gateReadyRebuilds,
        long gateDeferred,
        long gateResumed,
        long gateNoRegion,
        long gateFailures,
        long blenderSeen,
        long blenderApplied,
        long noiseTransformCalls,
        double noiseTransformShift,
        boolean targetFieldPositive,
        double targetShift,
        String lastGateDecision,
        long updatedAtMs
    ) {
    }

    private static final class ChunkTraceState {
        private final AtomicLong gateSeen = new AtomicLong();
        private final AtomicLong gateAlreadyActive = new AtomicLong();
        private final AtomicLong gateReadyRebuilds = new AtomicLong();
        private final AtomicLong gateDeferred = new AtomicLong();
        private final AtomicLong gateResumed = new AtomicLong();
        private final AtomicLong gateNoRegion = new AtomicLong();
        private final AtomicLong gateFailures = new AtomicLong();
        private final AtomicLong blenderSeen = new AtomicLong();
        private final AtomicLong blenderApplied = new AtomicLong();
        private final AtomicLong noiseTransformCalls = new AtomicLong();
        private final AtomicLong noiseTransformShiftMilli = new AtomicLong();
        private final AtomicLong targetShiftMilli = new AtomicLong();
        private volatile boolean targetFieldPositive;
        private volatile String lastGateDecision;
        private volatile long updatedAtMs;

        private void touch() {
            updatedAtMs = System.currentTimeMillis();
        }

        private ChunkTraceSnapshot snapshot() {
            return new ChunkTraceSnapshot(
                gateSeen.get(), gateAlreadyActive.get(), gateReadyRebuilds.get(),
                gateDeferred.get(), gateResumed.get(), gateNoRegion.get(),
                gateFailures.get(), blenderSeen.get(), blenderApplied.get(),
                noiseTransformCalls.get(), noiseTransformShiftMilli.get() / 1000.0D,
                targetFieldPositive, targetShiftMilli.get() / 1000.0D,
                lastGateDecision, updatedAtMs);
        }
    }

    private static final ConcurrentHashMap<OutcomeKey, GenerationOutcome> CHUNK_OUTCOMES = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<OutcomeKey, ChunkTraceState> CHUNK_TRACES = new ConcurrentHashMap<>();
    private static final int MAX_RECORDED_CHUNKS = 32768;

    private MountainCityBlendDiagnostics() {
    }

    private record OutcomeKey(String dimension, int chunkX, int chunkZ) {
    }

    private static OutcomeKey key(ResourceKey<Level> dimension, int chunkX, int chunkZ) {
        return new OutcomeKey(dimension == null ? "<unknown>" : dimension.location().toString(), chunkX, chunkZ);
    }

    private static void record(ResourceKey<Level> dimension,
                               int chunkX,
                               int chunkZ,
                               String outcome,
                               double centreShift) {
        if (CHUNK_OUTCOMES.size() > MAX_RECORDED_CHUNKS) {
            CHUNK_OUTCOMES.clear();
        }
        CHUNK_OUTCOMES.put(key(dimension, chunkX, chunkZ), new GenerationOutcome(outcome, centreShift));
    }

    private static ChunkTraceState trace(ResourceKey<Level> dimension, int chunkX, int chunkZ) {
        OutcomeKey key = key(dimension, chunkX, chunkZ);
        ChunkTraceState existing = CHUNK_TRACES.get(key);
        if (existing != null) {
            return existing;
        }
        if (CHUNK_TRACES.size() >= MAX_RECORDED_CHUNKS) {
            return null;
        }
        ChunkTraceState created = new ChunkTraceState();
        ChunkTraceState raced = CHUNK_TRACES.putIfAbsent(key, created);
        return raced == null ? created : raced;
    }

    /** Records the actual target chunk, rather than a Blender region centre. */
    public static void gateSeen(ResourceKey<Level> dimension, int chunkX, int chunkZ) {
        ChunkTraceState state = trace(dimension, chunkX, chunkZ);
        if (state == null) {
            return;
        }
        state.gateSeen.incrementAndGet();
        state.touch();
    }

    public static void gateDecision(ResourceKey<Level> dimension,
                                    int chunkX,
                                    int chunkZ,
                                    String decision) {
        ChunkTraceState state = trace(dimension, chunkX, chunkZ);
        if (state == null) {
            return;
        }
        switch (decision == null ? "" : decision) {
            case "ALREADY_ACTIVE" -> state.gateAlreadyActive.incrementAndGet();
            case "READY_REBUILD" -> state.gateReadyRebuilds.incrementAndGet();
            case "DEFERRED" -> state.gateDeferred.incrementAndGet();
            case "RESUMED" -> state.gateResumed.incrementAndGet();
            case "NO_REGION" -> state.gateNoRegion.incrementAndGet();
            case "FAILURE" -> state.gateFailures.incrementAndGet();
            default -> {
            }
        }
        state.lastGateDecision = decision;
        state.touch();
    }

    /** Records whether the immutable field had positive demand for the target. */
    public static void targetPlan(ResourceKey<Level> dimension,
                                  int chunkX,
                                  int chunkZ,
                                  double shift) {
        ChunkTraceState state = trace(dimension, chunkX, chunkZ);
        if (state == null) {
            return;
        }
        state.targetFieldPositive |= shift > 0.0D;
        long milli = Math.round(Math.max(0.0D, shift) * 1000.0D);
        state.targetShiftMilli.accumulateAndGet(milli, Math::max);
        state.touch();
    }

    public static void blenderSeen(ResourceKey<Level> dimension, int chunkX, int chunkZ) {
        ChunkTraceState state = trace(dimension, chunkX, chunkZ);
        if (state == null) {
            return;
        }
        state.blenderSeen.incrementAndGet();
        state.touch();
    }

    public static void blenderApplied(ResourceKey<Level> dimension, int chunkX, int chunkZ) {
        ChunkTraceState state = trace(dimension, chunkX, chunkZ);
        if (state == null) {
            return;
        }
        state.blenderApplied.incrementAndGet();
        state.touch();
    }

    /** Record the first positive NoiseChunk transform for the owning chunk. */
    public static void noiseTransform(ResourceKey<Level> dimension,
                                      int chunkX,
                                      int chunkZ,
                                      double shift) {
        if (!(shift > 0.0D)) {
            return;
        }
        ChunkTraceState state = trace(dimension, chunkX, chunkZ);
        if (state == null || !state.noiseTransformCalls.compareAndSet(0L, 1L)) {
            return;
        }
        long milli = Math.round(shift * 1000.0D);
        state.noiseTransformShiftMilli.set(milli);
        state.touch();
    }

    public static ChunkTraceSnapshot chunkTrace(ResourceKey<Level> dimension, int chunkX, int chunkZ) {
        ChunkTraceState state = CHUNK_TRACES.get(key(dimension, chunkX, chunkZ));
        return state == null ? null : state.snapshot();
    }

    /** Outcome recorded when this chunk was generated, or null if never seen. */
    public static String recordedOutcome(ResourceKey<Level> dimension, int chunkX, int chunkZ) {
        GenerationOutcome outcome = CHUNK_OUTCOMES.get(key(dimension, chunkX, chunkZ));
        return outcome == null ? null : outcome.summary();
    }

    public static GenerationOutcome recordedGenerationOutcome(ResourceKey<Level> dimension, int chunkX, int chunkZ) {
        return CHUNK_OUTCOMES.get(key(dimension, chunkX, chunkZ));
    }

    /** Drops generation outcomes with the world-scoped terrain state. */
    public static void clearLifecycleState() {
        CHUNK_OUTCOMES.clear();
        CHUNK_TRACES.clear();
    }

    public static void seen() {
        SEEN.incrementAndGet();
    }

    public static void noProvider() {
        NO_PROVIDER.incrementAndGet();
    }

    public static void noProfile() {
        NO_PROFILE.incrementAndGet();
    }

    public static void applied(ResourceKey<Level> dimension, int chunkX, int chunkZ, double centreShift) {
        APPLIED.incrementAndGet();
        if (centreShift > 0.0D) {
            CENTER_IS_CITY.incrementAndGet();
        }
        record(dimension, chunkX, chunkZ,
            "APPLIED (shift field installed; centre column lowered by "
                + String.format(java.util.Locale.ROOT, "%.1f", centreShift) + " blocks)",
            centreShift);
    }

    public static void blendCall() {
        BLEND_CALLS.incrementAndGet();
    }

    public static void densityOffsetCall() {
        DENSITY_OFFSET_CALLS.incrementAndGet();
    }

    public static void densityShifted(double shift) {
        if (!(shift > 0.0D)) {
            return;
        }
        DENSITY_SHIFTED_SAMPLES.incrementAndGet();
        long milli = Math.round(shift * 1000.0D);
        MAX_DENSITY_SHIFT_MILLI.accumulateAndGet(milli, Math::max);
    }

    public static void densitySurfaceFallback() {
        DENSITY_SURFACE_FALLBACKS.incrementAndGet();
    }

    public static void densityPositiveShift() { DENSITY_POSITIVE_SHIFT_CALLS.incrementAndGet(); }

    public static void densityDepthReject() { DENSITY_DEPTH_REJECTS.incrementAndGet(); }

    public static void densityInput(double input) {
        if (input > 0.0D) {
            DENSITY_SOLID_INPUTS.incrementAndGet();
        } else {
            DENSITY_AIR_INPUTS.incrementAndGet();
        }
    }

    public static void gateSeen() { GATE_SEEN.incrementAndGet(); }

    public static void gateAlreadyActive() { GATE_ALREADY_ACTIVE.incrementAndGet(); }

    public static void gateReadyRebuild() { GATE_READY_REBUILDS.incrementAndGet(); }

    public static void gateDeferred() { GATE_DEFERRED.incrementAndGet(); }

    public static void gateResumed() { GATE_RESUMED.incrementAndGet(); }

    public static void gateNoRegion() { GATE_NO_REGION.incrementAndGet(); }

    public static void gateFailure() { GATE_FAILURES.incrementAndGet(); }

    public static void noiseShaped(int columns, int removedBlocks) {
        if (columns <= 0 || removedBlocks <= 0) {
            return;
        }
        NOISE_SHAPED_CHUNKS.incrementAndGet();
        NOISE_SHAPED_COLUMNS.addAndGet(columns);
        NOISE_REMOVED_BLOCKS.addAndGet(removedBlocks);
    }

    public static void noiseShapeAttempted(int candidateColumns) {
        NOISE_SHAPE_ATTEMPTS.incrementAndGet();
        NOISE_SHAPE_CANDIDATES.addAndGet(Math.max(0, candidateColumns));
    }

    public static void naturalCorrectionSkip() {
        NATURAL_CORRECTION_SKIPS.incrementAndGet();
    }

    public static void mountainCorrectionSkip() {
        MOUNTAIN_CORRECTION_SKIPS.incrementAndGet();
        NATURAL_CORRECTION_SKIPS.incrementAndGet();
    }

    /** Record why the native Lost Cities correction was kept. */
    public static void mountainCorrectionVeto(String reason) {
        if (reason == null) {
            return;
        }
        switch (reason) {
            case "noInfo" -> CORRECTION_VETO_NO_INFO.incrementAndGet();
            case "directStructure" -> CORRECTION_VETO_DIRECT_STRUCTURE.incrementAndGet();
            case "outsideBuilding" -> CORRECTION_VETO_OUTSIDE_BUILDING.incrementAndGet();
            case "highway" -> CORRECTION_VETO_HIGHWAY.incrementAndGet();
            case "rail" -> CORRECTION_VETO_RAIL.incrementAndGet();
            case "noHeightmap" -> CORRECTION_VETO_NO_HEIGHTMAP.incrementAndGet();
            case "noTarget" -> CORRECTION_VETO_NO_TARGET.incrementAndGet();
            case "cityClearance" -> CORRECTION_VETO_CITY_CLEARANCE.incrementAndGet();
            default -> {
            }
        }
    }

    public static String diagnostics() {
        return "seen=" + SEEN.get()
            + ", noProvider=" + NO_PROVIDER.get()
            + ", noProfile=" + NO_PROFILE.get()
            + ", shiftedCentre=" + CENTER_IS_CITY.get()
            + ", applied=" + APPLIED.get()
            + ", blendCalls=" + BLEND_CALLS.get()
            + ", densityOffsetCalls=" + DENSITY_OFFSET_CALLS.get()
            + ", densityShiftedSamples=" + DENSITY_SHIFTED_SAMPLES.get()
            + ", densitySurfaceFallbacks=" + DENSITY_SURFACE_FALLBACKS.get()
            + ", densityPositiveShiftCalls=" + DENSITY_POSITIVE_SHIFT_CALLS.get()
            + ", densityDepthRejects=" + DENSITY_DEPTH_REJECTS.get()
            + ", densityInput[solid=" + DENSITY_SOLID_INPUTS.get()
            + ",air=" + DENSITY_AIR_INPUTS.get() + "]"
            + ", gate[seen=" + GATE_SEEN.get()
            + ",active=" + GATE_ALREADY_ACTIVE.get()
            + ",readyRebuild=" + GATE_READY_REBUILDS.get()
            + ",deferred=" + GATE_DEFERRED.get()
            + ",resumed=" + GATE_RESUMED.get()
            + ",noRegion=" + GATE_NO_REGION.get()
            + ",failures=" + GATE_FAILURES.get() + "]"
            + ", maxDensityShift=" + String.format(java.util.Locale.ROOT, "%.3f",
                MAX_DENSITY_SHIFT_MILLI.get() / 1000.0D)
            + ", noiseShape[attempts=" + NOISE_SHAPE_ATTEMPTS.get()
            + ",candidates=" + NOISE_SHAPE_CANDIDATES.get()
            + ",chunks=" + NOISE_SHAPED_CHUNKS.get()
            + ",columns=" + NOISE_SHAPED_COLUMNS.get()
            + ",removed=" + NOISE_REMOVED_BLOCKS.get() + "]"
            + ", naturalCorrectionSkips=" + NATURAL_CORRECTION_SKIPS.get()
            + ", mountainCorrectionSkips=" + MOUNTAIN_CORRECTION_SKIPS.get()
            + ", correctionVetoes={noInfo=" + CORRECTION_VETO_NO_INFO.get()
            + ",directStructure=" + CORRECTION_VETO_DIRECT_STRUCTURE.get()
            + ",outsideBuilding=" + CORRECTION_VETO_OUTSIDE_BUILDING.get()
            + ",highway=" + CORRECTION_VETO_HIGHWAY.get()
            + ",rail=" + CORRECTION_VETO_RAIL.get()
            + ",noHeightmap=" + CORRECTION_VETO_NO_HEIGHTMAP.get()
            + ",noTarget=" + CORRECTION_VETO_NO_TARGET.get()
            + ",cityClearance=" + CORRECTION_VETO_CITY_CLEARANCE.get() + "}";
    }
}
