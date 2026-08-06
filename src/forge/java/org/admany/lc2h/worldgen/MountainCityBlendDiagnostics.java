package org.admany.lc2h.worldgen;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Counters for {@code MixinBlenderCityEdge}.
 *
 * <p>Kept as a plain class rather than fields on the mixin itself: the mixin
 * merges its {@code @Unique} members directly into vanilla's {@code Blender}
 * class, which is invisible to normal Java code at compile time (nothing
 * outside the mixin transform can call a method that doesn't exist on the
 * real, untransformed {@code Blender.class}). Routing counters through an
 * ordinary class lets {@code /lc2h diagnostics} read them normally.</p>
 */
public final class MountainCityBlendDiagnostics {

    private static final AtomicLong SEEN = new AtomicLong();
    private static final AtomicLong NO_PROVIDER = new AtomicLong();
    private static final AtomicLong NO_PROFILE = new AtomicLong();
    private static final AtomicLong CENTER_IS_CITY = new AtomicLong();
    private static final AtomicLong APPLIED = new AtomicLong();
    private static final AtomicLong BLEND_CALLS = new AtomicLong();

    /**
     * What actually happened for each chunk AT GENERATION TIME.
     *
     * <p>Re-evaluating the decision later is misleading: ChunkRoleProbe can
     * answer "no city nearby" during noise generation and "city right there"
     * once its cache is warm, so a chunk that was never blended looks like it
     * should have been when inspected afterwards. Recording the decision as
     * it is made is the only way to tell those apart.</p>
     */
    public record GenerationOutcome(String summary, double centreShift) {
    }

    private static final ConcurrentHashMap<OutcomeKey, GenerationOutcome> CHUNK_OUTCOMES = new ConcurrentHashMap<>();
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

    /** Outcome recorded when this chunk was generated, or null if never seen. */
    public static String recordedOutcome(ResourceKey<Level> dimension, int chunkX, int chunkZ) {
        GenerationOutcome outcome = CHUNK_OUTCOMES.get(key(dimension, chunkX, chunkZ));
        return outcome == null ? null : outcome.summary();
    }

    public static GenerationOutcome recordedGenerationOutcome(ResourceKey<Level> dimension, int chunkX, int chunkZ) {
        return CHUNK_OUTCOMES.get(key(dimension, chunkX, chunkZ));
    }

    /**
     * Drops generation-time outcomes at the server lifecycle boundary.
     *
     * <p>The outcome key intentionally stays compact for the hot diagnostic
     * lookup. Clearing it with the world-scoped terrain caches prevents a
     * coordinate in a newly opened world from inheriting an explanation from
     * the previous world.</p>
     */
    public static void clearLifecycleState() {
        CHUNK_OUTCOMES.clear();
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

    public static String diagnostics() {
        return "seen=" + SEEN.get()
            + ", noProvider=" + NO_PROVIDER.get()
            + ", noProfile=" + NO_PROFILE.get()
            + ", shiftedCentre=" + CENTER_IS_CITY.get()
            + ", applied=" + APPLIED.get()
            + ", blendCalls=" + BLEND_CALLS.get();
    }
}
