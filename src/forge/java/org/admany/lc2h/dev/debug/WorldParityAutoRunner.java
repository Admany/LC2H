package org.admany.lc2h.dev.debug;

import mcjty.lostcities.setup.Config;
import mcjty.lostcities.setup.Registration;
import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.IDimensionInfo;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.admany.lc2h.LC2H;
import org.admany.lc2h.runtime.Lc2hRuntimeModes;
import org.admany.lc2h.worldgen.apply.ShadowBlockMutationApplier;
import org.admany.lc2h.worldgen.lostcities.DeferredTreeQueue;
import org.admany.lc2h.worldgen.lostcities.DeferredTreeEventHandler;
import org.admany.lc2h.worldgen.lostcities.LostCityProfileOverrideManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

@Mod.EventBusSubscriber(modid = LC2H.MODID)
public final class WorldParityAutoRunner {
    private static final long SETTLE_TIMEOUT_MS = Math.max(5_000L, Long.getLong("lc2h.worldparity.settleTimeoutMs", 90_000L));
    private static final long SETTLE_LOG_INTERVAL_MS = Math.max(1_000L, Long.getLong("lc2h.worldparity.settleLogIntervalMs", 5_000L));
    private static final long EXPORT_LOG_INTERVAL_MS = Math.max(1_000L, Long.getLong("lc2h.worldparity.exportLogIntervalMs", 5_000L));
    private static final long TARGET_WAIT_TIMEOUT_MS = Math.max(5_000L, Long.getLong("lc2h.worldparity.targetWaitTimeoutMs", 45_000L));
    private static final long TARGET_WAIT_LOG_INTERVAL_MS = Math.max(1_000L, Long.getLong("lc2h.worldparity.targetWaitLogIntervalMs", 5_000L));
    private static final boolean AUTO = Boolean.getBoolean("lc2h.worldparity.auto");
    private static final boolean AUTO_STOP = Boolean.parseBoolean(System.getProperty("lc2h.worldparity.auto.stop", "true"));
    private static final int PRIME_CHUNKS_PER_TICK = Math.max(1, Integer.getInteger("lc2h.worldparity.primeChunksPerTick", 32));
    private static final int DYNAMIC_PRIME_DISCOVER_PER_WAVE = Math.max(1, Integer.getInteger("lc2h.worldparity.dynamicPrimeDiscoverPerWave", 64));
    private static final int DYNAMIC_PRIME_CHUNK_LIMIT = Math.max(PRIME_CHUNKS_PER_TICK, Integer.getInteger("lc2h.worldparity.dynamicPrimeChunkLimit", 512));
    private static final int EXPORT_CHUNKS_PER_TICK = Math.max(1, Integer.getInteger("lc2h.worldparity.exportChunksPerTick", 1));
    private static final int SETTLE_FORCE_REPLAY_PER_TICK = Math.max(2, Integer.getInteger("lc2h.worldparity.settleForceReplayPerTick", 24));
    private static final int SETTLE_FORCE_REPLAY_BLOCKS_PER_TICK = Math.max(1024, Integer.getInteger("lc2h.worldparity.settleForceReplayBlocksPerTick", 24_576));
    private static final int SETTLE_FORCE_SHADOW_DRAIN_CYCLES = Math.max(1, Integer.getInteger("lc2h.worldparity.settleForceShadowDrainCycles", 6));
    private static final int SETTLE_FORCE_READY_PROMOTIONS_PER_TICK = Math.max(32, Integer.getInteger("lc2h.worldparity.settleForceReadyPromotionsPerTick", 1024));
    private static final int SETTLE_FORCE_READY_CHECKS_PER_TICK = Math.max(128, Integer.getInteger("lc2h.worldparity.settleForceReadyChecksPerTick", 8192));
    private static final int RADIUS = Math.max(0, Integer.getInteger("lc2h.worldparity.radius", 2));
    private static final int SAMPLES = Math.max(1, Integer.getInteger("lc2h.worldparity.samples", 64));
    private static final String DIMENSIONS = System.getProperty("lc2h.worldparity.dimensions", "minecraft:overworld").trim();
    private static final String CENTERS = System.getProperty("lc2h.worldparity.centers", "0,0;-3,0").trim();
    private static final String PROFILES = System.getProperty("lc2h.worldparity.profiles", "").trim();
    private static final String ROLE = System.getProperty("lc2h.worldparity.role", Lc2hRuntimeModes.baselineMode() ? "baseline" : "test").trim().toLowerCase();
    private static final String LANE_ID = System.getProperty("lc2h.worldparity.laneId", ROLE).trim();
    private static final String OUTPUT = System.getProperty("lc2h.worldparity.output", "logs/lc2h-worldparity-export.json").trim();
    private static final String COMPARE_AGAINST = System.getProperty("lc2h.worldparity.compareAgainst", "").trim();
    private static final String COMPARE_OUTPUT = System.getProperty("lc2h.worldparity.compareOutput", "logs/lc2h-worldparity-compare.json").trim();
    private static final AtomicBoolean EXECUTED = new AtomicBoolean(false);
    private static volatile ActiveRun ACTIVE_RUN;

    private WorldParityAutoRunner() {
    }

    public static void applyProfileOverrides(MinecraftServer server) {
        String profiles = effectiveProfilesSpec(server);
        if (!AUTO || profiles.isBlank()) {
            return;
        }
        for (String entry : profiles.split(";")) {
            if (entry == null || entry.isBlank() || !entry.contains("=")) {
                continue;
            }
            String[] parts = entry.split("=", 2);
            ResourceKey<Level> dimension = parseDimension(parts[0].trim());
            String profile = parts[1].trim();
            if (dimension == null || profile.isBlank()) {
                continue;
            }
            LostCityProfileOverrideManager.setOverride(dimension, profile);
            LC2H.LOGGER.info("[LC2H] World parity override: {} -> {}", dimension.location(), profile);
        }
        try {
            Config.resetProfileCache();
        } catch (Throwable ignored) {
        }
    }

    public static void maybeRun(MinecraftServer server) {
        if (!AUTO || server == null) {
            return;
        }
        WorldParityObservedChunkTracker.reset();
        if (!EXECUTED.compareAndSet(false, true)) {
            return;
        }
        ACTIVE_RUN = createRun(server);
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        ActiveRun run = ACTIVE_RUN;
        if (run == null || run.server != event.getServer()) {
            return;
        }
        try {
            run.step();
        } catch (Throwable t) {
            run.lines.add("abort reason=exception error=" + t.getClass().getSimpleName() + ":" + t.getMessage());
            LC2H.LOGGER.error("[LC2H] World parity autorun failed", t);
            run.finish();
        }
    }

    private static ActiveRun createRun(MinecraftServer server) {
        ArrayList<String> lines = new ArrayList<>();
        lines.add("LC2H world parity autorun");
        lines.add("startedAt=" + Instant.now());
        lines.add("role=" + ROLE + " baselineMode=" + Lc2hRuntimeModes.baselineMode());
        lines.add("laneId=" + (LANE_ID.isBlank() ? ROLE : LANE_ID));
        lines.add("radius=" + RADIUS + " samples=" + SAMPLES + " primeChunksPerTick=" + PRIME_CHUNKS_PER_TICK);
        lines.add("dimensions=" + DIMENSIONS);
        lines.add("centers=" + CENTERS);
        String effectiveProfiles = effectiveProfilesSpec(server);
        lines.add("profileOverrides=" + (effectiveProfiles.isBlank() ? "<none>" : effectiveProfiles));
        lines.add("compareAgainst=" + (COMPARE_AGAINST.isBlank() ? "<none>" : COMPARE_AGAINST));

        SeedValidation seedValidation = readSeedValidation(server);
        if (seedValidation != null) {
            lines.add(seedValidation.summary());
            LC2H.LOGGER.info("[LC2H] {}", seedValidation.summary());
            if (!seedValidation.acceptForWorldParity()) {
                String abort = "abort reason=seed-validation configuredSeed="
                    + (seedValidation.configuredSeed().isBlank() ? "<blank>" : seedValidation.configuredSeed())
                    + " actualSeed=" + seedValidation.actualSeed()
                    + " note=" + seedValidation.note();
                lines.add(abort);
                LC2H.LOGGER.warn("[LC2H] {}", abort);
                return new ActiveRun(server, lines, List.of(), List.of(), List.of(), abort);
            }
        }

        List<ResourceKey<Level>> dimensions = parseDimensions();
        List<int[]> centers = parseCenters();
        ArrayList<ExportTarget> targets = new ArrayList<>();
        ArrayList<ResourceKey<Level>> pendingDimensions = new ArrayList<>();

        for (ResourceKey<Level> dimension : dimensions) {
            ServerLevel level = server.getLevel(dimension);
            if (level == null) {
                lines.add("skip dimension=" + dimension.location() + " reason=level-unavailable");
                continue;
            }
            IDimensionInfo provider = Registration.LOSTCITY_FEATURE.get().getDimensionInfo(level);
            if (provider == null) {
                lines.add("pending dimension=" + dimension.location() + " reason=provider-missing");
                pendingDimensions.add(dimension);
                continue;
            }
            provider.setWorld(level);
            int areaSize = provider.getWorldStyle().getMultiSettings().areasize();
            for (int[] center : centers) {
                ChunkCoord centerMulti = new ChunkCoord(dimension, center[0], center[1]);
                ChunkCoord centerChunk = new ChunkCoord(
                    dimension,
                    center[0] * areaSize + (areaSize / 2),
                    center[1] * areaSize + (areaSize / 2)
                );
                ArrayList<ChunkCoord> targetExportCoords =
                    new ArrayList<>(new LinkedHashSet<>(WorldParityHarness.buildMatrix(centerChunk, RADIUS, SAMPLES)));
                targets.add(new ExportTarget(level, provider, centerMulti, centerChunk, areaSize, center[0], center[1], RADIUS, targetExportCoords));
            }
        }
        return new ActiveRun(server, lines, targets, pendingDimensions, centers, null);
    }

    private static List<ResourceKey<Level>> parseDimensions() {
        ArrayList<ResourceKey<Level>> result = new ArrayList<>();
        for (String token : DIMENSIONS.split(",")) {
            ResourceKey<Level> dimension = parseDimension(token.trim());
            if (dimension != null && !result.contains(dimension)) {
                result.add(dimension);
            }
        }
        if (result.isEmpty()) {
            result.add(Level.OVERWORLD);
        }
        return result;
    }

    private static ResourceKey<Level> parseDimension(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        ResourceLocation id = ResourceLocation.tryParse(token);
        return id == null ? null : ResourceKey.create(Registries.DIMENSION, id);
    }

    private static String effectiveProfilesSpec(MinecraftServer server) {
        if (!PROFILES.isBlank()) {
            return PROFILES;
        }
        String inferredProfile = inferOverworldProfile(server);
        if (inferredProfile == null || inferredProfile.isBlank()) {
            return "";
        }
        return "minecraft:overworld=" + inferredProfile;
    }

    private static String inferOverworldProfile(MinecraftServer server) {
        String levelName = null;
        SeedValidation seedValidation = readSeedValidation(server);
        if (seedValidation != null && seedValidation.levelName != null && !seedValidation.levelName.isBlank()) {
            levelName = seedValidation.levelName;
        }
        String fromLevelName = inferProfileToken(levelName);
        if (fromLevelName != null) {
            return fromLevelName;
        }
        if (server != null) {
            try {
                String rootName = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                    .toAbsolutePath()
                    .normalize()
                    .getParent()
                    .getFileName()
                    .toString();
                String fromRunDir = inferProfileToken(rootName);
                if (fromRunDir != null) {
                    return fromRunDir;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static String inferProfileToken(String source) {
        if (source == null || source.isBlank()) {
            return null;
        }
        String normalized = source.toLowerCase(java.util.Locale.ROOT);
        if (normalized.contains("biosphere")) {
            return "biosphere";
        }
        if (normalized.contains("largecities")) {
            return "largecities";
        }
        if (normalized.contains("rarecities")) {
            return "rarecities";
        }
        if (normalized.contains("default")) {
            return "default";
        }
        return null;
    }

    private static List<int[]> parseCenters() {
        ArrayList<int[]> result = new ArrayList<>();
        for (String entry : CENTERS.split(";")) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            String[] parts = entry.trim().split(",");
            if (parts.length != 2) {
                continue;
            }
            try {
                result.add(new int[]{Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim())});
            } catch (NumberFormatException ignored) {
            }
        }
        if (result.isEmpty()) {
            result.add(new int[]{0, 0});
        }
        return result;
    }

    private static SeedValidation readSeedValidation(MinecraftServer server) {
        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        if (overworld == null) {
            return null;
        }
        Path propertiesPath = server.getFile("server.properties").toPath();
        if (!Files.exists(propertiesPath)) {
            return new SeedValidation("<missing>", "", overworld.getSeed(), false, "server.properties missing");
        }
        try {
            Properties properties = new Properties();
            try (var stream = Files.newInputStream(propertiesPath)) {
                properties.load(stream);
            }
            String levelName = properties.getProperty("level-name", "world").trim();
            String configuredSeed = properties.getProperty("level-seed", "").trim();
            if (configuredSeed.isBlank()) {
                return new SeedValidation(levelName, "", overworld.getSeed(), false, "configured seed is blank");
            }
            try {
                long parsedSeed = Long.parseLong(configuredSeed);
                boolean exact = parsedSeed == overworld.getSeed();
                return new SeedValidation(
                    levelName,
                    configuredSeed,
                    overworld.getSeed(),
                    exact,
                    exact ? "parsed numeric seed" : "configured seed does not match actual"
                );
            } catch (NumberFormatException e) {
                return new SeedValidation(levelName, configuredSeed, overworld.getSeed(), false, "configured seed is not numeric");
            }
        } catch (IOException e) {
            return new SeedValidation("<unreadable>", "", overworld.getSeed(), false, "read failed: " + e.getMessage());
        }
    }

    private record SeedValidation(String levelName, String configuredSeed, long actualSeed, boolean acceptedForWorldParity, String note) {
        private String summary() {
            return "seedValidation levelName=" + levelName
                + " configuredSeed=" + (configuredSeed.isBlank() ? "<blank>" : configuredSeed)
                + " actualSeed=" + actualSeed
                + " note=" + note;
        }

        private boolean acceptForWorldParity() {
            return acceptedForWorldParity;
        }
    }

    private record ExportTarget(ServerLevel level, IDimensionInfo provider, ChunkCoord centerMulti, ChunkCoord centerChunk,
                                int areaSize, int centerX, int centerZ,
                                int minChunkX, int maxChunkX, int minChunkZ, int maxChunkZ, List<ChunkCoord> exportCoords) {
        private ExportTarget(ServerLevel level, IDimensionInfo provider, ChunkCoord centerMulti, ChunkCoord centerChunk,
                             int areaSize, int centerX, int centerZ, int radius, List<ChunkCoord> exportCoords) {
            this(
                level,
                provider,
                centerMulti,
                centerChunk,
                areaSize,
                centerX,
                centerZ,
                minChunkX(centerMulti, areaSize, radius, exportCoords),
                maxChunkX(centerMulti, areaSize, radius, exportCoords),
                minChunkZ(centerMulti, areaSize, radius, exportCoords),
                maxChunkZ(centerMulti, areaSize, radius, exportCoords),
                List.copyOf(exportCoords)
            );
        }

        private static int minChunkX(ChunkCoord centerMulti, int areaSize, int radius, List<ChunkCoord> exportCoords) {
            return exportCoords.stream()
                .mapToInt(ChunkCoord::chunkX)
                .min()
                .orElse(centerMulti.chunkX() * areaSize - radius);
        }

        private static int maxChunkX(ChunkCoord centerMulti, int areaSize, int radius, List<ChunkCoord> exportCoords) {
            return exportCoords.stream()
                .mapToInt(ChunkCoord::chunkX)
                .max()
                .orElse(centerMulti.chunkX() * areaSize + areaSize - 1 + radius);
        }

        private static int minChunkZ(ChunkCoord centerMulti, int areaSize, int radius, List<ChunkCoord> exportCoords) {
            return exportCoords.stream()
                .mapToInt(ChunkCoord::chunkZ)
                .min()
                .orElse(centerMulti.chunkZ() * areaSize - radius);
        }

        private static int maxChunkZ(ChunkCoord centerMulti, int areaSize, int radius, List<ChunkCoord> exportCoords) {
            return exportCoords.stream()
                .mapToInt(ChunkCoord::chunkZ)
                .max()
                .orElse(centerMulti.chunkZ() * areaSize + areaSize - 1 + radius);
        }
    }

    private static final class ActiveRun {
        private enum SettlePhase {
            PRE_EXPORT,
            POST_EXPORT
        }

        private final MinecraftServer server;
        private final ArrayList<String> lines;
        private final ArrayList<ExportTarget> targets;
        private final ArrayList<ResourceKey<Level>> pendingDimensions;
        private final List<int[]> centers;
        private final String preflightAbortLine;
        private int targetIndex;
        private ChunkPrimingSession primingSession;
        private ChunkPrimingSession retainedPrimingSession;
        private DeferredTreeSettlePriming settlePriming;
        private ExportTarget exportingTarget;
        private WorldParityHarness.ExportCapture exportCapture;
        private CompletableFuture<ExportWorkResult> exportWorkFuture;
        private long exportStartedAtMs;
        private long lastExportLogAtMs;
        private ExportTarget settlingTarget;
        private SettlePhase settlePhase;
        private long settleStartedAtMs;
        private long lastSettleLogAtMs;
        private boolean settleTimedOut;
        private boolean finished;
        private final long targetWaitStartedAtMs;
        private long lastTargetWaitLogAtMs;

        private ActiveRun(MinecraftServer server, ArrayList<String> lines, List<ExportTarget> targets,
                          List<ResourceKey<Level>> pendingDimensions, List<int[]> centers, String preflightAbortLine) {
            this.server = server;
            this.lines = lines;
            this.targets = new ArrayList<>(targets);
            this.pendingDimensions = new ArrayList<>(pendingDimensions);
            this.centers = List.copyOf(centers);
            this.preflightAbortLine = preflightAbortLine;
            this.targetWaitStartedAtMs = System.currentTimeMillis();
        }

        private void step() {
            if (finished) {
                return;
            }
            if (preflightAbortLine != null) {
                finish();
                return;
            }
            if (!ensureTargetsReady()) {
                return;
            }
            if (exportWorkFuture != null || exportCapture != null || exportingTarget != null) {
                if (!exportStep()) {
                    return;
                }
            }
            if (settlingTarget != null) {
                if (!settleStep()) {
                    return;
                }
                ExportTarget settledTarget = settlingTarget;
                SettlePhase completedPhase = settlePhase;
                boolean timedOut = settleTimedOut;
                settlingTarget = null;
                settlePhase = null;
                settleStartedAtMs = 0L;
                lastSettleLogAtMs = 0L;
                settleTimedOut = false;
                if (timedOut) {
                    lines.add("abort reason=settle-timeout role=" + ROLE
                        + " dim=" + settledTarget.level().dimension().location()
                        + " center=" + settledTarget.centerX() + "," + settledTarget.centerZ()
                        + " phase=" + completedPhase.name().toLowerCase());
                    finish();
                    return;
                }
                if (completedPhase == SettlePhase.PRE_EXPORT) {
                    startExport(settledTarget);
                    return;
                }
                finish();
                return;
            }
            if (targetIndex < targets.size()) {
                ExportTarget target = targets.get(targetIndex);
                if (!primeStep(target)) {
                    return;
                }
                logRuntimeState("post-prime", target);
                targetIndex++;
                beginSettle(target, SettlePhase.PRE_EXPORT);
                return;
            }
            startExport(targets.isEmpty() ? null : targets.get(0));
        }

        private boolean ensureTargetsReady() {
            if (!targets.isEmpty() || pendingDimensions.isEmpty()) {
                return true;
            }
            hydratePendingTargets();
            if (!targets.isEmpty() || pendingDimensions.isEmpty()) {
                return true;
            }
            long now = System.currentTimeMillis();
            if (lastTargetWaitLogAtMs == 0L || (now - lastTargetWaitLogAtMs) >= TARGET_WAIT_LOG_INTERVAL_MS) {
                lastTargetWaitLogAtMs = now;
                lines.add("target-wait pendingDimensions=" + pendingDimensions.size()
                    + " elapsedMs=" + (now - targetWaitStartedAtMs)
                    + " dimensions=" + pendingDimensions.stream().map(d -> d.location().toString()).toList());
            }
            if ((now - targetWaitStartedAtMs) >= TARGET_WAIT_TIMEOUT_MS) {
                lines.add("abort reason=no-targets providerWaitMs=" + (now - targetWaitStartedAtMs)
                    + " pendingDimensions=" + pendingDimensions.stream().map(d -> d.location().toString()).toList());
                finish();
                return false;
            }
            return false;
        }

        private void hydratePendingTargets() {
            for (int i = pendingDimensions.size() - 1; i >= 0; i--) {
                ResourceKey<Level> dimension = pendingDimensions.get(i);
                ServerLevel level = server.getLevel(dimension);
                if (level == null) {
                    continue;
                }
                IDimensionInfo provider;
                try {
                    provider = Registration.LOSTCITY_FEATURE.get().getDimensionInfo(level);
                } catch (Throwable t) {
                    lines.add("pending dimension=" + dimension.location()
                        + " reason=provider-error error=" + t.getClass().getSimpleName() + ":" + t.getMessage());
                    continue;
                }
                if (provider == null) {
                    continue;
                }
                provider.setWorld(level);
                int areaSize;
                try {
                    areaSize = provider.getWorldStyle().getMultiSettings().areasize();
                } catch (Throwable t) {
                    lines.add("skip dimension=" + dimension.location()
                        + " reason=areasize-error error=" + t.getClass().getSimpleName() + ":" + t.getMessage());
                    pendingDimensions.remove(i);
                    continue;
                }
                int added = 0;
                for (int[] center : centers) {
                    ChunkCoord centerMulti = new ChunkCoord(dimension, center[0], center[1]);
                    ChunkCoord centerChunk = new ChunkCoord(
                        dimension,
                        center[0] * areaSize + (areaSize / 2),
                        center[1] * areaSize + (areaSize / 2)
                    );
                    ArrayList<ChunkCoord> targetExportCoords =
                        new ArrayList<>(new LinkedHashSet<>(WorldParityHarness.buildMatrix(centerChunk, RADIUS, SAMPLES)));
                    targets.add(new ExportTarget(level, provider, centerMulti, centerChunk, areaSize, center[0], center[1], RADIUS, targetExportCoords));
                    added++;
                }
                lines.add("dimension-ready dimension=" + dimension.location() + " targetsAdded=" + added + " areaSize=" + areaSize);
                pendingDimensions.remove(i);
            }
        }

        private boolean exportStep() {
            if (exportingTarget == null) {
                return true;
            }
            long now = System.currentTimeMillis();
            if (exportWorkFuture != null) {
                if (!exportWorkFuture.isDone()) {
                    logExportProgress(now, true);
                    return false;
                }
                try {
                    ExportWorkResult result = exportWorkFuture.join();
                    lines.add("export " + result.summary.exportSummary().summary());
                    LC2H.LOGGER.info("[LC2H] World parity export: {}", result.summary.exportSummary().summary());
                    if (result.summary.comparisonSummary() != null) {
                        lines.add("compare " + result.summary.comparisonSummary().summary());
                        LC2H.LOGGER.info("[LC2H] World parity compare: {}", result.summary.comparisonSummary().summary());
                        if (!result.summary.comparisonSummary().environmentMatch()) {
                            throw new IllegalStateException("World parity environment mismatch: " + result.summary.comparisonSummary().environmentDifferences());
                        }
                    }
                } catch (Throwable t) {
                    Throwable cause = t instanceof java.util.concurrent.CompletionException && t.getCause() != null ? t.getCause() : t;
                    lines.add("abort reason=export error=" + cause.getClass().getSimpleName() + ":" + cause.getMessage());
                    LC2H.LOGGER.error("[LC2H] World parity export failed", cause);
                } finally {
                    exportWorkFuture = null;
                    exportCapture = null;
                    ExportTarget completedTarget = exportingTarget;
                    exportingTarget = null;
                    beginSettle(completedTarget, SettlePhase.POST_EXPORT);
                }
                return false;
            }

            int capturedThisTick = 0;
            while (exportCapture != null && exportCapture.hasRemaining() && capturedThisTick < EXPORT_CHUNKS_PER_TICK) {
                exportCapture.captureNext();
                capturedThisTick++;
            }
            logExportProgress(now, false);
            if (exportCapture != null && exportCapture.hasRemaining()) {
                return false;
            }
            Path exportPath = server.getFile(OUTPUT).toPath();
            WorldParityHarness.ExportCapture completedCapture = exportCapture;
            exportWorkFuture = CompletableFuture.supplyAsync(() -> writeExportResult(completedCapture, exportPath));
            exportCapture = null;
            lastExportLogAtMs = 0L;
            return false;
        }

        private void startExport(ExportTarget exportTarget) {
            if (exportTarget == null) {
                lines.add("abort reason=no-targets");
                finish();
                return;
            }
            exportingTarget = exportTarget;
            exportCapture = WorldParityHarness.beginExport(
                server,
                exportTarget.level(),
                exportTarget.provider(),
                ROLE,
                exportTarget.exportCoords(),
                new WorldParityHarness.ExportMetadata(
                    LANE_ID.isBlank() ? ROLE : LANE_ID,
                    exportTarget.centerChunk().chunkX(),
                    exportTarget.centerChunk().chunkZ(),
                    exportTarget.centerMulti().chunkX(),
                    exportTarget.centerMulti().chunkZ()
                )
            );
            exportStartedAtMs = System.currentTimeMillis();
            lastExportLogAtMs = 0L;
            lines.add("export start role=" + ROLE
                + " dim=" + exportTarget.level().dimension().location()
                + " center=" + exportTarget.centerX() + "," + exportTarget.centerZ()
                + " chunks=" + exportCapture.totalChunks()
                + " chunksPerTick=" + EXPORT_CHUNKS_PER_TICK);
        }

        private void logExportProgress(long now, boolean writing) {
            if (exportingTarget == null) {
                return;
            }
            if (lastExportLogAtMs != 0L && (now - lastExportLogAtMs) < EXPORT_LOG_INTERVAL_MS) {
                return;
            }
            lastExportLogAtMs = now;
            if (writing) {
                lines.add("export writing role=" + ROLE
                    + " dim=" + exportingTarget.level().dimension().location()
                    + " center=" + exportingTarget.centerX() + "," + exportingTarget.centerZ()
                    + " elapsedMs=" + Math.max(0L, now - exportStartedAtMs));
                return;
            }
            if (exportCapture == null) {
                return;
            }
            lines.add("export capture role=" + ROLE
                + " dim=" + exportingTarget.level().dimension().location()
                + " center=" + exportingTarget.centerX() + "," + exportingTarget.centerZ()
                + " captured=" + exportCapture.completedChunks() + "/" + exportCapture.totalChunks()
                + " remaining=" + exportCapture.remainingChunks()
                + " elapsedMs=" + Math.max(0L, now - exportStartedAtMs));
        }

        private boolean primeStep(ExportTarget target) {
            if (primingSession == null) {
                ArrayList<ChunkPos> coords = new ArrayList<>();
                for (int chunkX = target.minChunkX(); chunkX <= target.maxChunkX(); chunkX++) {
                    for (int chunkZ = target.minChunkZ(); chunkZ <= target.maxChunkZ(); chunkZ++) {
                        coords.add(new ChunkPos(chunkX, chunkZ));
                    }
                }
                String label = "worldparity role=" + ROLE
                    + " dim=" + target.level().dimension().location()
                    + " center=" + target.centerX() + "," + target.centerZ()
                    + " area=" + target.areaSize()
                    + " chunkWindow=" + target.minChunkX() + "," + target.minChunkZ() + " -> " + target.maxChunkX() + "," + target.maxChunkZ();
                primingSession = new ChunkPrimingSession(target.level(), label, coords, PRIME_CHUNKS_PER_TICK, lines::add, false);
                logRuntimeState("pre-prime", target);
            }
            boolean done = primingSession.step();
            if (!done) {
                return false;
            }
            if (!primingSession.completedSuccessfully()) {
                lines.add("abort reason=prime_" + primingSession.completionStatus().name().toLowerCase()
                    + " role=" + ROLE
                    + " dim=" + target.level().dimension().location()
                    + " center=" + target.centerX() + "," + target.centerZ());
                logRuntimeState("prime-abort", target);
                primingSession.close();
                primingSession = null;
                finish();
                return false;
            }
            retainedPrimingSession = primingSession;
            primingSession = null;
            org.admany.lc2h.dev.diagnostics.DiagnosticsReporter.logPerformanceSnapshot(
                "worldparity-prime-" + target.level().dimension().location());
            return true;
        }

        private void beginSettle(ExportTarget target, SettlePhase phase) {
            settlingTarget = target;
            settlePhase = phase;
            settleStartedAtMs = System.currentTimeMillis();
            lastSettleLogAtMs = 0L;
            settleTimedOut = false;
            if (settlePriming == null && target != null) {
                String label = "worldparity role=" + ROLE
                    + " dim=" + target.level().dimension().location()
                    + " center=" + target.centerX() + "," + target.centerZ();
                settlePriming = new DeferredTreeSettlePriming(
                    target.level(),
                    label,
                    PRIME_CHUNKS_PER_TICK,
                    DYNAMIC_PRIME_DISCOVER_PER_WAVE,
                    DYNAMIC_PRIME_CHUNK_LIMIT,
                    initialSettleSeedChunks(target),
                    lines::add
                );
            }
        }

        private void logRuntimeState(String stage, ExportTarget target) {
            String prefix = "runtime " + stage
                + " role=" + ROLE
                + " dim=" + target.level().dimension().location()
                + " center=" + target.centerX() + "," + target.centerZ();
            lines.add(prefix + " shadow=" + ShadowBlockMutationApplier.diagnostics());
            for (String detail : ShadowBlockMutationApplier.pendingTransactionDetails()) {
                lines.add(prefix + " shadowTx=" + detail);
            }
            lines.add(prefix + " primeGate=" + WorldParityPrimeGate.diagnostics());
            lines.add(prefix + " trees=" + DeferredTreeEventHandler.capturedTreeDiagnostics(target.level()));
        }

        private boolean settleStep() {
            if (settlingTarget == null) {
                return true;
            }
            long now = System.currentTimeMillis();
            if (settlePriming != null) {
                settlePriming.step();
            }
            if (Lc2hRuntimeModes.worldParityAuto()) {
                Set<Long> interestChunks = settlePriming == null ? null : settlePriming.interestChunkKeys();
                if (settlePriming != null) {
                    DeferredTreeQueue.promoteReadyLoaded(
                        settlingTarget.level(),
                        SETTLE_FORCE_READY_PROMOTIONS_PER_TICK,
                        interestChunks,
                        SETTLE_FORCE_READY_CHECKS_PER_TICK
                    );
                }
                DeferredTreeEventHandler.forceReplayReadyForDebug(
                    settlingTarget.level().getServer(),
                    SETTLE_FORCE_REPLAY_PER_TICK,
                    SETTLE_FORCE_REPLAY_BLOCKS_PER_TICK,
                    interestChunks
                );
                ShadowBlockMutationApplier.forceDrainForDebug(SETTLE_FORCE_SHADOW_DRAIN_CYCLES);
            }
            Set<Long> interestChunks = settlePriming == null ? null : settlePriming.interestChunkKeys();
            boolean shadowPending = ShadowBlockMutationApplier.hasPendingWork(settlingTarget.level(), interestChunks);
            int pendingTransactions = ShadowBlockMutationApplier.getPendingTransactionCount(settlingTarget.level(), interestChunks);
            long heldTickets = ShadowBlockMutationApplier.getHeldTicketCount(settlingTarget.level(), interestChunks);
            int pendingTrees = settlePriming == null
                ? DeferredTreeQueue.pendingCount(settlingTarget.level())
                : settlePriming.relevantPendingTrees();
            int readyTrees = settlePriming == null
                ? DeferredTreeQueue.readyCount(settlingTarget.level())
                : settlePriming.relevantReadyTrees();
            int globalPendingTrees = DeferredTreeQueue.pendingCount(settlingTarget.level());
            int globalReadyTrees = DeferredTreeQueue.readyCount(settlingTarget.level());
            boolean settlePrimingActive = settlePriming != null && settlePriming.hasActiveSession();
            if (!shadowPending
                && pendingTransactions == 0
                && heldTickets == 0L
                && pendingTrees == 0
                && readyTrees == 0
                && !settlePrimingActive) {
                lines.add("settle done role=" + ROLE
                    + " dim=" + settlingTarget.level().dimension().location()
                    + " center=" + settlingTarget.centerX() + "," + settlingTarget.centerZ()
                    + " waitMs=" + Math.max(0L, now - settleStartedAtMs)
                    + " pendingTrees=" + pendingTrees
                    + " globalPendingTrees=" + globalPendingTrees
                    + " globalReadyTrees=" + globalReadyTrees
                    + " settlePrimingActive=" + settlePrimingActive
                    + " tickets=" + ShadowBlockMutationApplier.getTicketAcquiredCount() + "/" + ShadowBlockMutationApplier.getTicketReleasedCount());
                logRuntimeState(settlePhase == SettlePhase.PRE_EXPORT ? "pre-export-settled" : "post-export", settlingTarget);
                return true;
            }
            if (lastSettleLogAtMs == 0L || (now - lastSettleLogAtMs) >= SETTLE_LOG_INTERVAL_MS) {
                lastSettleLogAtMs = now;
                lines.add("settle pending role=" + ROLE
                    + " dim=" + settlingTarget.level().dimension().location()
                    + " center=" + settlingTarget.centerX() + "," + settlingTarget.centerZ()
                    + " waitMs=" + Math.max(0L, now - settleStartedAtMs)
                    + " pendingTransactions=" + pendingTransactions
                    + " heldTickets=" + heldTickets
                    + " pendingTrees=" + pendingTrees
                    + " readyTrees=" + readyTrees
                    + " globalPendingTrees=" + globalPendingTrees
                    + " globalReadyTrees=" + globalReadyTrees
                    + " settlePrimingActive=" + settlePrimingActive
                    + " settlePrime[" + (settlePriming == null ? "<none>" : settlePriming.diagnostics()) + "]");
                for (String detail : ShadowBlockMutationApplier.pendingTransactionDetails()) {
                    lines.add("settle tx " + detail);
                }
            }
            if ((now - settleStartedAtMs) >= SETTLE_TIMEOUT_MS) {
                settleTimedOut = true;
                lines.add("settle timeout role=" + ROLE
                    + " dim=" + settlingTarget.level().dimension().location()
                    + " center=" + settlingTarget.centerX() + "," + settlingTarget.centerZ()
                    + " waitMs=" + Math.max(0L, now - settleStartedAtMs)
                    + " pendingTransactions=" + pendingTransactions
                    + " heldTickets=" + heldTickets
                    + " pendingTrees=" + pendingTrees
                    + " readyTrees=" + readyTrees
                    + " globalPendingTrees=" + globalPendingTrees
                    + " globalReadyTrees=" + globalReadyTrees
                    + " settlePrimingActive=" + settlePrimingActive
                    + " settlePrime[" + (settlePriming == null ? "<none>" : settlePriming.diagnostics()) + "]");
                logRuntimeState("settle-timeout", settlingTarget);
                List<String> details = settlePriming == null
                    ? DeferredTreeQueue.pendingDetails(settlingTarget.level(), 8)
                    : settlePriming.relevantPendingDetails(8);
                for (String detail : details) {
                    lines.add("settle tree " + detail);
                }
                List<String> readyDetails = settlePriming == null
                    ? DeferredTreeQueue.readyDetails(settlingTarget.level(), 8, null)
                    : DeferredTreeQueue.readyDetails(settlingTarget.level(), 8, settlePriming.interestChunkKeys());
                for (String detail : readyDetails) {
                    lines.add("settle readyTree " + detail);
                }
                return true;
            }
            return false;
        }

        private void finish() {
            if (finished) {
                return;
            }
            finished = true;
            if (primingSession != null) {
                primingSession.close();
                primingSession = null;
            }
            if (retainedPrimingSession != null) {
                retainedPrimingSession.close();
                retainedPrimingSession = null;
            }
            if (settlePriming != null) {
                settlePriming.close();
                settlePriming = null;
            }
            settlingTarget = null;
            ACTIVE_RUN = null;
            try {
                Path path = server.getFile(OUTPUT + ".report.txt").toPath();
                Path parent = path.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                lines.addAll(PreCaptureTargetTraceRegistry.summaryLines());
                Files.write(path, lines);
            } catch (IOException e) {
                LC2H.LOGGER.warn("[LC2H] Failed to write world parity autorun report: {}", e.getMessage());
            }
            PreCaptureTargetTraceRegistry.writeReport(server);
            WorldParityObservedChunkTracker.reset();
            if (AUTO_STOP) {
                server.halt(false);
            }
        }

        private ExportWorkResult writeExportResult(WorldParityHarness.ExportCapture capture, Path exportPath) {
            try {
                WorldParityHarness.ExportSummary summary =
                    WorldParityHarness.finishExport(capture, exportPath);
                WorldParityHarness.ComparisonSummary comparison = null;
                if ("test".equals(ROLE) && !COMPARE_AGAINST.isBlank()) {
                    Path comparePath = server.getFile(COMPARE_OUTPUT).toPath();
                    comparison = WorldParityHarness.compareExports(resolveCompareAgainstPath(server), exportPath, comparePath);
                }
                return new ExportWorkResult(new ExportWorkSummary(summary, comparison));
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        private Path resolveCompareAgainstPath(MinecraftServer server) {
            Path configured = Path.of(COMPARE_AGAINST);
            if (configured.isAbsolute() && Files.exists(configured)) {
                return configured.normalize();
            }

            Path processRelative = Path.of("").toAbsolutePath().resolve(configured).normalize();
            if (Files.exists(processRelative)) {
                return processRelative;
            }

            Path serverRelative = server.getFile(COMPARE_AGAINST).toPath().toAbsolutePath().normalize();
            if (Files.exists(serverRelative)) {
                return serverRelative;
            }

            Path runDir = server.getFile(".").toPath().toAbsolutePath().normalize();
            Path parentRelative = runDir.getParent() == null ? null : runDir.getParent().resolve(configured).normalize();
            if (parentRelative != null && Files.exists(parentRelative)) {
                return parentRelative;
            }

            return configured.isAbsolute() ? configured.normalize() : (parentRelative != null ? parentRelative : processRelative);
        }

        private List<ChunkPos> initialSettleSeedChunks(ExportTarget target) {
            ArrayList<ChunkPos> seedChunks = new ArrayList<>();
            if (target == null) {
                return seedChunks;
            }
            for (int chunkX = target.minChunkX(); chunkX <= target.maxChunkX(); chunkX++) {
                for (int chunkZ = target.minChunkZ(); chunkZ <= target.maxChunkZ(); chunkZ++) {
                    seedChunks.add(new ChunkPos(chunkX, chunkZ));
                }
            }
            return seedChunks;
        }
    }

    private record ExportWorkResult(ExportWorkSummary summary) {
    }

    private record ExportWorkSummary(WorldParityHarness.ExportSummary exportSummary,
                                     WorldParityHarness.ComparisonSummary comparisonSummary) {
    }
}
