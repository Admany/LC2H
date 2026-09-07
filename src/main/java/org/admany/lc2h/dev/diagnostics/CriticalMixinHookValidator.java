package org.admany.lc2h.dev.diagnostics;

import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import org.admany.lc2h.LC2H;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public final class CriticalMixinHookValidator {
    public static final String LOST_CITY_FEATURE_PLACE_HEAD = "lostcity_feature.place.head";
    public static final String LOST_CITY_FEATURE_TERRAIN_PATH = "lostcity_feature.terrain_path.before_feature";
    public static final String LOST_CITY_FEATURE_GENERATE_REDIRECT = "lostcity_feature.terrain_generate.redirect";
    public static final String LOST_CITY_FEATURE_PLACE_RETURN = "lostcity_feature.place.return";
    public static final String LOST_CITY_SPHERE_PLACE_HEAD = "lostcity_sphere_feature.place.head";
    public static final String CHUNK_GENERATOR_STRUCTURE_GUARD = "minecraft.chunk_generator.structure_guard";
    public static final String STRUCTURE_START_CITY_GUARD = "minecraft.structure_start.city_guard";
    public static final String DAMAGE_AREA_DISABLE = "lostcities.damage_area.disable";

    private static final long WARN_DELAY_MS = Math.max(10_000L,
        Long.getLong("lc2h.criticalHooks.warnDelayMs", 45_000L));
    private static final long WARN_REPEAT_MS = Math.max(30_000L,
        Long.getLong("lc2h.criticalHooks.warnRepeatMs", TimeUnit.MINUTES.toMillis(5L)));

    private static final Map<String, HookRecord> HOOKS = new ConcurrentHashMap<>();
    private static final AtomicLong WATCH_STARTED_MS = new AtomicLong(System.currentTimeMillis());
    private static final AtomicLong FIRST_WORLDGEN_OPPORTUNITY_MS = new AtomicLong(0L);
    private static final AtomicLong LAST_WARN_MS = new AtomicLong(0L);

    static {
        register(LOST_CITY_FEATURE_PLACE_HEAD,
            "MixinLostCityFeature#lc2h$warmupFeature",
            "mcjty.lostcities.worldgen.LostCityFeature#place(FeaturePlaceContext) HEAD");
        register(LOST_CITY_FEATURE_TERRAIN_PATH,
            "MixinLostCityFeature#lc2h$markTerrainPathAtFeatureFetch",
            "LostCityFeature#place -> IDimensionInfo.getFeature() before terrain generation",
            LOST_CITY_FEATURE_PLACE_HEAD);
        register(LOST_CITY_FEATURE_GENERATE_REDIRECT,
            "MixinLostCityFeature#lc2h$wrapGenerateWithStripeLock",
            "LostCityFeature#m_142674_ -> LostCityTerrainFeature.generate(WorldGenRegion, ChunkAccess)",
            LOST_CITY_FEATURE_TERRAIN_PATH);
        register(LOST_CITY_FEATURE_PLACE_RETURN,
            "MixinLostCityFeature#lc2h$markPlace",
            "mcjty.lostcities.worldgen.LostCityFeature#place(FeaturePlaceContext) RETURN");
        register(LOST_CITY_SPHERE_PLACE_HEAD,
            "MixinLostCitySphereFeature#lc2h$warmupSphere",
            "mcjty.lostcities.worldgen.LostCitySphereFeature#place(FeaturePlaceContext) HEAD");
        register(CHUNK_GENERATOR_STRUCTURE_GUARD,
            "MixinChunkGeneratorSkipStructures#lc2h$skipStructuresInCityChunks",
            "net.minecraft.world.level.chunk.ChunkGenerator#createStructures(...) HEAD");
        register(STRUCTURE_START_CITY_GUARD,
            "MixinStructureStartCityUndergroundGuard#lc2h$skipStructuresNearCityGround",
            "net.minecraft.world.level.levelgen.structure.StructureStart#placeInChunk(...) HEAD");
        register(DAMAGE_AREA_DISABLE,
            "MixinDamageAreaDisable",
            "mcjty.lostcities.worldgen.lost.DamageArea damage query HEAD");
        verifyKnownTargets();
    }

    private CriticalMixinHookValidator() {
    }

    public static void resetWatch() {
        WATCH_STARTED_MS.set(System.currentTimeMillis());
        FIRST_WORLDGEN_OPPORTUNITY_MS.set(0L);
        LAST_WARN_MS.set(0L);
    }

    public static void markWorldgenOpportunity() {
        FIRST_WORLDGEN_OPPORTUNITY_MS.compareAndSet(0L, System.currentTimeMillis());
    }

    public static void markObserved(String id) {
        HookRecord record = HOOKS.get(id);
        if (record != null) {
            record.markObserved();
        }
    }

    public static List<String> summaryLines() {
        List<HookRecord> records = snapshotRecords();
        List<String> lines = new ArrayList<>(records.size() + 3);
        lines.add("CriticalHooks: " + compactSummary(records));
        lines.add(watchLine(records));
        lines.add("CriticalHooksGuide: verifiedUnseen=target resolved but hook never observed, blocked=upstream prerequisite observed first, unresolved=target could not be proven either way, failed=target lookup proved broken in this runtime");
        for (HookRecord record : records) {
            lines.add(record.detailLine(HOOKS));
        }
        return lines;
    }

    public static String compactDiagnostics() {
        return compactSummary(snapshotRecords());
    }

    public static void maybeWarnUnobserved(boolean likelyWorldgenOpportunity) {
        if (!likelyWorldgenOpportunity && FIRST_WORLDGEN_OPPORTUNITY_MS.get() <= 0L) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - WATCH_STARTED_MS.get() < WARN_DELAY_MS) {
            return;
        }
        long previous = LAST_WARN_MS.get();
        if (previous != 0L && now - previous < WARN_REPEAT_MS) {
            return;
        }
        if (!LAST_WARN_MS.compareAndSet(previous, now)) {
            return;
        }
        List<HookRecord> missing = snapshotRecords().stream()
            .filter(record -> record.state(HOOKS).isUnobserved())
            .toList();
        if (missing.isEmpty()) {
            return;
        }
        LC2H.LOGGER.warn(
            "[LC2H] Critical hook validation: {}. blocked={} verifiedButUnseen={} unresolved={} failed={}. Lost Cities worldgen appears eligible; inspect /lc2h hooks for per-hook owner/target detail.",
            compactSummary(snapshotRecords()),
            missing.stream().filter(record -> record.state(HOOKS) == HookState.BLOCKED_NOT_REACHED).map(HookRecord::id).toList(),
            missing.stream().filter(record -> record.state(HOOKS) == HookState.VERIFIED_NOT_OBSERVED).map(HookRecord::id).toList(),
            missing.stream().filter(record -> record.state(HOOKS) == HookState.REGISTERED_NOT_VERIFIED).map(HookRecord::id).toList(),
            missing.stream().filter(record -> record.state(HOOKS) == HookState.FAILED).map(HookRecord::id).toList());
    }

    private static void register(String id, String owner, String expectedTarget) {
        register(id, owner, expectedTarget, null);
    }

    private static void register(String id, String owner, String expectedTarget, String prerequisiteId) {
        HOOKS.putIfAbsent(id, new HookRecord(id, owner, expectedTarget, prerequisiteId));
    }

    private static void verifyKnownTargets() {
        verifyMethod(LOST_CITY_FEATURE_PLACE_HEAD, "mcjty.lostcities.worldgen.LostCityFeature");
        verifyMethod(LOST_CITY_FEATURE_TERRAIN_PATH, "mcjty.lostcities.worldgen.LostCityFeature");
        verifyMethod(LOST_CITY_FEATURE_PLACE_RETURN, "mcjty.lostcities.worldgen.LostCityFeature");
        verifyMethod(LOST_CITY_SPHERE_PLACE_HEAD, "mcjty.lostcities.worldgen.LostCitySphereFeature");
        verifyNamedMethod(CHUNK_GENERATOR_STRUCTURE_GUARD,
            "net.minecraft.world.level.chunk.ChunkGenerator", "createStructures", 5);
        verifyNamedMethod(STRUCTURE_START_CITY_GUARD,
            "net.minecraft.world.level.levelgen.structure.StructureStart", "placeInChunk", 6);
        verifyNamedMethod(DAMAGE_AREA_DISABLE,
            "mcjty.lostcities.worldgen.lost.DamageArea", "hasExplosions", 0);
        verifyGenerateRedirectTarget();
    }

    private static void verifyNamedMethod(String id, String className, String methodName, int parameterCount) {
        HookRecord record = HOOKS.get(id);
        if (record == null) {
            return;
        }
        try {
            Class<?> target = Class.forName(className, false, CriticalMixinHookValidator.class.getClassLoader());
            Method match = null;
            for (Method method : target.getDeclaredMethods()) {
                if (isNamedTarget(method, methodName) && method.getParameterCount() == parameterCount) {
                    match = method;
                    break;
                }
            }
            if (match == null) {
                record.markFailed("target method lookup returned null");
            } else {
                record.markVerified("resolved " + target.getName() + "#" + match.getName());
            }
        } catch (Throwable t) {
            record.markFailed(t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    /** Accept mapped and runtime names in hook diagnostics. */
    private static boolean isNamedTarget(Method method, String mappedName) {
        if (method.getName().equals(mappedName)) {
            return true;
        }
        return ("createStructures".equals(mappedName) && "m_255037_".equals(method.getName()))
            || ("placeInChunk".equals(mappedName) && "m_226850_".equals(method.getName()));
    }

    private static void verifyMethod(String id, String className) {
        HookRecord record = HOOKS.get(id);
        if (record == null) {
            return;
        }
        try {
            Class<?> target = Class.forName(className, false, CriticalMixinHookValidator.class.getClassLoader());
            Method method = resolvePlaceMethod(target);
            if (method == null) {
                record.markFailed("target method lookup returned null");
            } else {
                record.markVerified("resolved " + target.getName() + "#" + method.getName());
            }
        } catch (Throwable t) {
            record.markFailed(t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static Method resolvePlaceMethod(Class<?> target) {
        try {
            return target.getDeclaredMethod("place", FeaturePlaceContext.class);
        } catch (NoSuchMethodException ignored) {
        }
        try {
            return target.getDeclaredMethod("m_142674_", FeaturePlaceContext.class);
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }

    private static void verifyGenerateRedirectTarget() {
        HookRecord record = HOOKS.get(LOST_CITY_FEATURE_GENERATE_REDIRECT);
        if (record == null) {
            return;
        }
        try {
            Class<?> target = Class.forName("mcjty.lostcities.worldgen.LostCityTerrainFeature", false, CriticalMixinHookValidator.class.getClassLoader());
            Method method = target.getDeclaredMethod("generate", WorldGenRegion.class, ChunkAccess.class);
            if (method == null) {
                record.markFailed("target method lookup returned null");
            } else {
                record.markVerified("resolved " + target.getName() + "#" + method.getName());
            }
        } catch (Throwable t) {
            record.markFailed(t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static List<HookRecord> snapshotRecords() {
        List<HookRecord> records = new ArrayList<>(HOOKS.values());
        records.sort(java.util.Comparator.comparing(HookRecord::id));
        return Collections.unmodifiableList(records);
    }

    private static String compactSummary(List<HookRecord> records) {
        long observed = records.stream().filter(record -> record.state(HOOKS) == HookState.OBSERVED).count();
        long blocked = records.stream().filter(record -> record.state(HOOKS) == HookState.BLOCKED_NOT_REACHED).count();
        long verifiedUnseen = records.stream().filter(record -> record.state(HOOKS) == HookState.VERIFIED_NOT_OBSERVED).count();
        long unresolved = records.stream().filter(record -> record.state(HOOKS) == HookState.REGISTERED_NOT_VERIFIED).count();
        long failed = records.stream().filter(record -> record.state(HOOKS) == HookState.FAILED).count();
        return String.format(Locale.ROOT,
            "registered=%d observed=%d blocked=%d verifiedUnseen=%d unresolved=%d failed=%d worldgenOpportunitySeen=%s",
            records.size(),
            observed,
            blocked,
            verifiedUnseen,
            unresolved,
            failed,
            FIRST_WORLDGEN_OPPORTUNITY_MS.get() > 0L);
    }

    private static String watchLine(List<HookRecord> records) {
        long now = System.currentTimeMillis();
        long startedMs = WATCH_STARTED_MS.get();
        long firstOpportunityMs = FIRST_WORLDGEN_OPPORTUNITY_MS.get();
        long watchAgeSec = Math.max(0L, TimeUnit.MILLISECONDS.toSeconds(now - startedMs));
        String opportunity = firstOpportunityMs <= 0L
            ? "not_seen"
            : "seen+" + Math.max(0L, TimeUnit.MILLISECONDS.toSeconds(now - firstOpportunityMs)) + "s";
        long unseen = records.stream().filter(record -> record.state(HOOKS).isUnobserved()).count();
        return String.format(Locale.ROOT,
            "CriticalHooksWatch: watchAge=%ss worldgenOpportunity=%s warnDelay=%ss warnRepeat=%ss unseen=%d",
            watchAgeSec,
            opportunity,
            TimeUnit.MILLISECONDS.toSeconds(WARN_DELAY_MS),
            TimeUnit.MILLISECONDS.toSeconds(WARN_REPEAT_MS),
            unseen);
    }

    private enum HookState {
        REGISTERED_NOT_VERIFIED,
        VERIFIED_NOT_OBSERVED,
        BLOCKED_NOT_REACHED,
        OBSERVED,
        FAILED;

        private boolean isUnobserved() {
            return this != OBSERVED;
        }
    }

    private static final class HookRecord {
        private final String id;
        private final String owner;
        private final String expectedTarget;
        private final String prerequisiteId;
        private final AtomicLong invocationCount = new AtomicLong();
        private final AtomicLong firstObservedMs = new AtomicLong();
        private final AtomicLong lastObservedMs = new AtomicLong();
        private final AtomicLong verifiedAtMs = new AtomicLong();
        private volatile String verificationDetail;
        private volatile String failureReason;

        private HookRecord(String id, String owner, String expectedTarget, String prerequisiteId) {
            this.id = id;
            this.owner = owner;
            this.expectedTarget = expectedTarget;
            this.prerequisiteId = prerequisiteId;
        }

        private String id() {
            return id;
        }

        private void markObserved() {
            long now = System.currentTimeMillis();
            invocationCount.incrementAndGet();
            firstObservedMs.compareAndSet(0L, now);
            lastObservedMs.set(now);
        }

        private void markVerified(String detail) {
            verifiedAtMs.compareAndSet(0L, System.currentTimeMillis());
            if (verificationDetail == null) {
                verificationDetail = detail == null || detail.isBlank() ? "resolved" : detail;
            }
        }

        private void markFailed(String reason) {
            if (failureReason == null) {
                failureReason = reason == null || reason.isBlank() ? "unknown" : reason;
            }
        }

        private HookState state(Map<String, HookRecord> records) {
            if (invocationCount.get() > 0L) {
                return HookState.OBSERVED;
            }
            if (failureReason != null) {
                return HookState.FAILED;
            }
            if (prerequisiteId != null) {
                HookRecord prerequisite = records.get(prerequisiteId);
                if (prerequisite != null && prerequisite.invocationCount.get() > 0L) {
                    return HookState.BLOCKED_NOT_REACHED;
                }
            }
            if (verifiedAtMs.get() > 0L) {
                return HookState.VERIFIED_NOT_OBSERVED;
            }
            return HookState.REGISTERED_NOT_VERIFIED;
        }

        private String detailLine(Map<String, HookRecord> records) {
            HookState state = state(records);
            long first = firstObservedMs.get();
            long last = lastObservedMs.get();
            long verifiedAt = verifiedAtMs.get();
            String firstText = first <= 0L ? "-" : Instant.ofEpochMilli(first).toString();
            String lastText = last <= 0L ? "-" : Instant.ofEpochMilli(last).toString();
            String verifiedText = verifiedAt <= 0L ? "-" : Instant.ofEpochMilli(verifiedAt).toString();
            String verification = verificationDetail == null ? "-" : verificationDetail;
            String failure = failureReason == null ? "-" : failureReason;
            String prerequisite = prerequisiteId == null ? "-" : prerequisiteId;
            String prerequisiteState = "-";
            if (prerequisiteId != null) {
                HookRecord prerequisiteRecord = records.get(prerequisiteId);
                prerequisiteState = prerequisiteRecord == null ? "missing" : prerequisiteRecord.state(records).name();
            }
            return "CriticalHook id=" + id
                + " owner=" + owner
                + " target=\"" + expectedTarget + "\""
                + " state=" + state
                + " prerequisite=" + prerequisite
                + " prerequisiteState=" + prerequisiteState
                + " verifiedAt=" + verifiedText
                + " verification=" + verification
                + " count=" + invocationCount.get()
                + " firstObserved=" + firstText
                + " lastObserved=" + lastText
                + " failure=" + failure;
        }
    }
}
