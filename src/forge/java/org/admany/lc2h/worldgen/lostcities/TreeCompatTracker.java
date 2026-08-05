package org.admany.lc2h.worldgen.lostcities;

import mcjty.lostcities.varia.ChunkCoord;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class TreeCompatTracker {

    public enum HookState {
        UNAVAILABLE,
        AVAILABLE_UNSEEN,
        OBSERVED,
        BLOCKED
    }

    public enum FallbackReason {
        UNSUPPORTED_MIDGARD_STRUCTURE,
        MISSING_RUNTIME_DEPENDENCY,
        INVALID_CAPTURE,
        ROOT_REJECTED,
        OVERLAP_REJECTED,
        UNLOADED_DESTINATION
    }

    private static final EnumMap<DeferredTreeCaptureContext.CaptureSource, AtomicLong> CAPTURES =
        new EnumMap<>(DeferredTreeCaptureContext.CaptureSource.class);
    private static final EnumMap<DeferredTreeCaptureContext.CaptureSource, AtomicLong> CAPTURED_BLOCKS =
        new EnumMap<>(DeferredTreeCaptureContext.CaptureSource.class);
    private static final EnumMap<DeferredTreeCaptureContext.CaptureSource, AtomicLong> APPLIED =
        new EnumMap<>(DeferredTreeCaptureContext.CaptureSource.class);
    private static final EnumMap<FallbackReason, AtomicLong> FALLBACKS =
        new EnumMap<>(FallbackReason.class);
    private static final ConcurrentHashMap<String, HookRecord> HOOKS = new ConcurrentHashMap<>();
    private static final AtomicLong MIDGARD_TRUNK_POSTPASS_OBSERVED = new AtomicLong();

    static {
        for (DeferredTreeCaptureContext.CaptureSource source : DeferredTreeCaptureContext.CaptureSource.values()) {
            CAPTURES.put(source, new AtomicLong());
            CAPTURED_BLOCKS.put(source, new AtomicLong());
            APPLIED.put(source, new AtomicLong());
        }
        for (FallbackReason reason : FallbackReason.values()) {
            FALLBACKS.put(reason, new AtomicLong());
        }
        registerHook("treeplacer.grow_tree", "com.outrightwings.growth.TreePlacer#growTree(ServerLevel,ChunkGenerator,BlockPos,BlockState,RandomSource,boolean)", HookState.AVAILABLE_UNSEEN, null);
        registerHook("biomesoplenty.tree_feature.place", "net.minecraft.world.level.levelgen.feature.TreeFeature#place(FeaturePlaceContext) inherited by biomesoplenty BOPTreeFeature", HookState.UNAVAILABLE, "Biomes O' Plenty tree base class not present in active runtime");
        registerHook("midgard.structure.place", "dev.corgitaco.ohthetreesyoullgrow...TreeFromStructureNBTFeature#place(FeaturePlaceContext)", HookState.UNAVAILABLE, "OHTTYG structure tree feature class not present in active dev runtime");
        registerHook("midgard.structure_v2.place", "dev.corgitaco.ohthetreesyoullgrow...TreeFromStructureNBTFeatureV2#place(FeaturePlaceContext)", HookState.UNAVAILABLE, "OHTTYG structure tree feature V2 class not present in active dev runtime");
        registerHook("midgard.structure_trunk_postpass", "dev.corgitaco.ohthetreesyoullgrow...TreeFromStructureNBTFeature#placeKnownBlockPositions(Map,WorldGenLevel)", HookState.UNAVAILABLE, "OHTTYG feature class not present in active dev runtime");
        refreshRuntimeAvailability();
    }

    private TreeCompatTracker() {
    }

    public static void registerHook(String id, String target, HookState state, String note) {
        HOOKS.put(id, new HookRecord(id, target, state, note));
    }

    public static void markHookAvailable(String id, String note) {
        HookRecord record = HOOKS.get(id);
        if (record != null) {
            record.markAvailable(note);
        }
    }

    public static void markHookObserved(String id) {
        HookRecord record = HOOKS.get(id);
        if (record != null) {
            record.markObserved();
        }
    }

    public static void markHookBlocked(String id, String note) {
        HookRecord record = HOOKS.get(id);
        if (record != null) {
            record.markBlocked(note);
        }
    }

    public static void recordCapture(DeferredTreeCaptureContext.CaptureSource source, int blockCount) {
        DeferredTreeCaptureContext.CaptureSource effective = source == null
            ? DeferredTreeCaptureContext.CaptureSource.VANILLA_TREE
            : source;
        CAPTURES.get(effective).incrementAndGet();
        CAPTURED_BLOCKS.get(effective).addAndGet(Math.max(0, blockCount));
    }

    public static void recordApplied(DeferredTreeCaptureContext.CaptureSource source) {
        DeferredTreeCaptureContext.CaptureSource effective = source == null
            ? DeferredTreeCaptureContext.CaptureSource.VANILLA_TREE
            : source;
        APPLIED.get(effective).incrementAndGet();
    }

    /**
     * Stable counters for runtime compatibility harnesses. This avoids parsing
     * human diagnostics while keeping the production placement path untouched.
     */
    public static long captureCount(DeferredTreeCaptureContext.CaptureSource source) {
        DeferredTreeCaptureContext.CaptureSource effective = source == null
            ? DeferredTreeCaptureContext.CaptureSource.VANILLA_TREE
            : source;
        return CAPTURES.get(effective).get();
    }

    public static long appliedCount(DeferredTreeCaptureContext.CaptureSource source) {
        DeferredTreeCaptureContext.CaptureSource effective = source == null
            ? DeferredTreeCaptureContext.CaptureSource.VANILLA_TREE
            : source;
        return APPLIED.get(effective).get();
    }

    public static void recordFallback(FallbackReason reason) {
        AtomicLong counter = FALLBACKS.get(reason);
        if (counter != null) {
            counter.incrementAndGet();
        }
    }

    public static void recordMidgardTrunkPostpass() {
        MIDGARD_TRUNK_POSTPASS_OBSERVED.incrementAndGet();
        markHookObserved("midgard.structure_trunk_postpass");
    }

    public static boolean hasPendingMidgardStructureBlocker() {
        HookRecord record = HOOKS.get("midgard.structure_trunk_postpass");
        return record != null && record.state() != HookState.OBSERVED;
    }

    public static List<String> summaryLines() {
        refreshRuntimeAvailability();
        List<String> lines = new ArrayList<>();
        lines.add("TreeCompat: " + compactSummary());
        for (HookRecord record : HOOKS.values().stream().sorted((a, b) -> a.id.compareTo(b.id)).toList()) {
            lines.add("TreeCompatHook " + record.id + ": target=" + record.target + " state=" + record.state().name().toLowerCase(Locale.ROOT)
                + " seen=" + record.invocations() + " firstObservedMs=" + record.firstObservedAtMs()
                + (record.note() == null || record.note().isBlank() ? "" : " note=" + record.note()));
        }
        return lines;
    }

    public static String compactSummary() {
        refreshRuntimeAvailability();
        return "captures[" + sourceSummary(CAPTURES) + "] applied[" + sourceSummary(APPLIED)
            + "] blocks[" + sourceSummary(CAPTURED_BLOCKS) + "] fallbacks[" + fallbackSummary() + "] midgardPostPass=" + MIDGARD_TRUNK_POSTPASS_OBSERVED.get();
    }

    public static void refreshRuntimeAvailability() {
        updateAvailability(
            "treeplacer.grow_tree",
            "com.outrightwings.growth.TreePlacer",
            "TreePlacer class present in active runtime");
        updateAvailability(
            "biomesoplenty.tree_feature.place",
            "biomesoplenty.common.worldgen.feature.tree.BOPTreeFeature",
            "Biomes O' Plenty tree base class present; inherited TreeFeature hook is active");
        updateAvailability(
            "midgard.structure.place",
            "dev.corgitaco.ohthetreesyoullgrow.world.level.levelgen.feature.TreeFromStructureNBTFeature",
            "OHTTYG structure tree feature class present in active runtime");
        updateAvailability(
            "midgard.structure_v2.place",
            "dev.corgitaco.ohthetreesyoullgrow.world.level.levelgen.feature.TreeFromStructureNBTFeatureV2",
            "OHTTYG structure tree feature V2 class present in active runtime");
        updateAvailability(
            "midgard.structure_trunk_postpass",
            "dev.corgitaco.ohthetreesyoullgrow.world.level.levelgen.feature.TreeFromStructureNBTFeature",
            "OHTTYG structure tree feature class present in active runtime");
    }

    public static long deterministicTransactionId(ResourceKey<Level> dim,
                                                  long seed,
                                                  BlockPos origin,
                                                  DeferredTreeCaptureContext.CaptureSource source) {
        long hash = 0xcbf29ce484222325L;
        hash = mix(hash, seed);
        hash = mix(hash, origin == null ? 0L : origin.asLong());
        hash = mix(hash, source == null ? 0L : source.ordinal() + 1L);
        String dimId = dim == null || dim.location() == null ? "unknown" : dim.location().toString();
        for (int i = 0; i < dimId.length(); i++) {
            hash ^= dimId.charAt(i);
            hash *= 0x100000001b3L;
        }
        return hash == 0L ? 1L : hash;
    }

    public static DeferredTreeCaptureContext.CaptureSource classifyTreePlacerSource(BlockState state) {
        ResourceLocation id = state == null || state.getBlock() == null ? null : ForgeRegistries.BLOCKS.getKey(state.getBlock());
        if (id != null && "midgard".equals(id.getNamespace())) {
            return DeferredTreeCaptureContext.CaptureSource.MIDGARD_TREEPLACER;
        }
        return DeferredTreeCaptureContext.CaptureSource.TREEPLACER;
    }

    public static String beginMidgardStructureBlocker(BlockPos origin, ResourceKey<Level> dim, long seed) {
        markHookBlocked(
            "midgard.structure_trunk_postpass",
            "observed post-pass without proven full outer placement boundary; origin=" + formatOrigin(origin, dim, seed));
        recordFallback(FallbackReason.UNSUPPORTED_MIDGARD_STRUCTURE);
        return formatOrigin(origin, dim, seed);
    }

    private static String sourceSummary(EnumMap<?, AtomicLong> values) {
        List<String> parts = new ArrayList<>();
        for (var entry : values.entrySet()) {
            parts.add(entry.getKey() + "=" + entry.getValue().get());
        }
        return String.join(",", parts);
    }

    private static String fallbackSummary() {
        List<String> parts = new ArrayList<>();
        for (var entry : FALLBACKS.entrySet()) {
            parts.add(entry.getKey().name().toLowerCase(Locale.ROOT) + "=" + entry.getValue().get());
        }
        return String.join(",", parts);
    }

    private static long mix(long hash, long value) {
        hash ^= value;
        hash *= 0x100000001b3L;
        return hash;
    }

    private static void updateAvailability(String id, String className, String note) {
        HookRecord record = HOOKS.get(id);
        if (record == null || record.state() == HookState.OBSERVED || record.state() == HookState.BLOCKED) {
            return;
        }
        try {
            Class.forName(className, false, TreeCompatTracker.class.getClassLoader());
            record.markAvailable(note);
        } catch (Throwable ignored) {
            record.markUnavailable(className + " not present in active runtime");
        }
    }

    private static String formatOrigin(BlockPos origin, ResourceKey<Level> dim, long seed) {
        String dimId = dim == null || dim.location() == null ? "unknown" : dim.location().toString();
        if (origin == null) {
            return "dim=" + dimId + ",seed=" + seed + ",origin=unknown";
        }
        return "dim=" + dimId + ",seed=" + seed + ",origin=" + origin.getX() + "," + origin.getY() + "," + origin.getZ()
            + ",chunk=" + new ChunkCoord(dim, origin.getX() >> 4, origin.getZ() >> 4);
    }

    private static final class HookRecord {
        private final String id;
        private final String target;
        private final AtomicLong invocations = new AtomicLong();
        private volatile HookState state;
        private volatile long firstObservedAtMs;
        private volatile String note;

        private HookRecord(String id, String target, HookState state, String note) {
            this.id = Objects.requireNonNull(id, "id");
            this.target = Objects.requireNonNull(target, "target");
            this.state = state == null ? HookState.UNAVAILABLE : state;
            this.note = note;
        }

        private synchronized void markAvailable(String note) {
            if (state == HookState.OBSERVED) {
                return;
            }
            state = HookState.AVAILABLE_UNSEEN;
            if (note != null && !note.isBlank()) {
                this.note = note;
            }
        }

        private synchronized void markUnavailable(String note) {
            if (state == HookState.OBSERVED || state == HookState.BLOCKED) {
                return;
            }
            state = HookState.UNAVAILABLE;
            if (note != null && !note.isBlank()) {
                this.note = note;
            }
        }

        private void markObserved() {
            invocations.incrementAndGet();
            if (firstObservedAtMs == 0L) {
                synchronized (this) {
                    if (firstObservedAtMs == 0L) {
                        firstObservedAtMs = System.currentTimeMillis();
                    }
                    state = HookState.OBSERVED;
                    note = null;
                }
            } else {
                state = HookState.OBSERVED;
            }
        }

        private synchronized void markBlocked(String note) {
            if (state == HookState.OBSERVED) {
                return;
            }
            state = HookState.BLOCKED;
            if (note != null && !note.isBlank()) {
                this.note = note;
            }
        }

        private HookState state() {
            return state;
        }

        private long invocations() {
            return invocations.get();
        }

        private long firstObservedAtMs() {
            return firstObservedAtMs;
        }

        private String note() {
            return note;
        }
    }
}
