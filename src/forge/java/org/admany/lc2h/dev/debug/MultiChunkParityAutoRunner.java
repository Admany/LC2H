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
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

@Mod.EventBusSubscriber(modid = LC2H.MODID)
public final class MultiChunkParityAutoRunner {
    private static final long SETTLE_TIMEOUT_MS = Math.max(5_000L, Long.getLong("lc2h.parity.settleTimeoutMs", 45_000L));
    private static final long SETTLE_LOG_INTERVAL_MS = Math.max(1_000L, Long.getLong("lc2h.parity.settleLogIntervalMs", 5_000L));
    private static final boolean AUTO = Boolean.getBoolean("lc2h.parity.auto");
    private static final boolean AUTO_STOP = Boolean.getBoolean("lc2h.parity.auto.stop");
    private static final boolean GENERATE_CHUNKS = Boolean.parseBoolean(System.getProperty("lc2h.parity.generateChunks", "false"));
    private static final boolean REQUIRE_SEED_MATCH = Boolean.parseBoolean(System.getProperty("lc2h.parity.requireSeedMatch", "true"));
    private static final int PRIME_CHUNKS_PER_TICK = Math.max(1, Integer.getInteger("lc2h.parity.primeChunksPerTick", 4));
    private static final int DYNAMIC_PRIME_DISCOVER_PER_WAVE = Math.max(1, Integer.getInteger("lc2h.parity.dynamicPrimeDiscoverPerWave", 64));
    private static final int DYNAMIC_PRIME_CHUNK_LIMIT = Math.max(PRIME_CHUNKS_PER_TICK, Integer.getInteger("lc2h.parity.dynamicPrimeChunkLimit", 512));
    private static final int RADIUS = Math.max(0, Integer.getInteger("lc2h.parity.radius", 2));
    private static final int SAMPLES = Math.max(1, Integer.getInteger("lc2h.parity.samples", 128));
    private static final String DIMENSIONS = System.getProperty("lc2h.parity.dimensions", "minecraft:overworld,lostcities:lostcity").trim();
    private static final String CENTERS = System.getProperty("lc2h.parity.centers", "0,0").trim();
    private static final String PROFILES = System.getProperty("lc2h.parity.profiles", "").trim();
    private static final String OUTPUT = System.getProperty("lc2h.parity.output", "logs/lc2h-parity-report.txt").trim();
    private static final AtomicBoolean EXECUTED = new AtomicBoolean(false);
    private static volatile ActiveRun ACTIVE_RUN;

    private MultiChunkParityAutoRunner() {
    }

    public static void applyProfileOverrides() {
        if (!AUTO || PROFILES.isBlank()) {
            return;
        }
        for (String entry : PROFILES.split(";")) {
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
            LC2H.LOGGER.info("[LC2H] Parity autorun override: {} -> {}", dimension.location(), profile);
        }
        try {
            Config.resetProfileCache();
        } catch (Throwable ignored) {
        }
    }

    public static void maybeRun(MinecraftServer server) {
        if (!AUTO || server == null || Lc2hRuntimeModes.baselineMode()) {
            return;
        }
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
        if (run == null || run.server() != event.getServer()) {
            return;
        }
        try {
            run.step();
        } catch (Throwable t) {
            run.lines().add("abort reason=exception error=" + t.getClass().getSimpleName() + ":" + t.getMessage());
            LC2H.LOGGER.error("[LC2H] Parity autorun failed", t);
            run.finish();
        }
    }

    private static ActiveRun createRun(MinecraftServer server) {
        List<ResourceKey<Level>> dimensions = parseDimensions();
        List<int[]> centers = parseCenters();
        ArrayList<String> lines = new ArrayList<>();
        lines.add("LC2H parity autorun");
        lines.add("startedAt=" + Instant.now());
        lines.add("radius=" + RADIUS + " samples=" + SAMPLES + " generateChunks=" + GENERATE_CHUNKS + " requireSeedMatch=" + REQUIRE_SEED_MATCH + " primeChunksPerTick=" + PRIME_CHUNKS_PER_TICK);
        lines.add("dimensions=" + dimensions);
        lines.add("centers=" + formatCenters(centers));
        lines.add("profileOverrides=" + (PROFILES.isBlank() ? "<none>" : PROFILES));
        SeedValidation seedValidation = readSeedValidation(server);
        if (seedValidation != null) {
            lines.add(seedValidation.summary());
            if (seedValidation.mismatch()) {
                String line = "abort reason=seed-mismatch configuredSeed=" + seedValidation.configuredSeed()
                    + " actualSeed=" + seedValidation.actualSeed()
                    + " levelName=" + seedValidation.levelName();
                lines.add(line);
                LC2H.LOGGER.warn("[LC2H] {}", seedValidation.summary());
                if (REQUIRE_SEED_MATCH) {
                    LC2H.LOGGER.error("[LC2H] {}", line);
                    writeReport(server, lines);
                    if (AUTO_STOP) {
                        server.halt(false);
                    }
                    return null;
                }
                LC2H.LOGGER.warn("[LC2H] {}", line);
            } else {
                LC2H.LOGGER.info("[LC2H] {}", seedValidation.summary());
            }
        }

        ArrayList<ParityTarget> targets = new ArrayList<>();
        for (ResourceKey<Level> dimension : dimensions) {
            ServerLevel level = server.getLevel(dimension);
            if (level == null) {
                String line = "skip dimension=" + dimension.location() + " reason=level-unavailable";
                lines.add(line);
                LC2H.LOGGER.warn("[LC2H] {}", line);
                continue;
            }
            IDimensionInfo provider;
            try {
                provider = Registration.LOSTCITY_FEATURE.get().getDimensionInfo(level);
                if (provider != null) {
                    provider.setWorld(level);
                }
            } catch (Throwable t) {
                String line = "skip dimension=" + dimension.location() + " reason=provider-error error=" + t.getClass().getSimpleName() + ":" + t.getMessage();
                lines.add(line);
                LC2H.LOGGER.warn("[LC2H] {}", line);
                continue;
            }
            if (provider == null) {
                String line = "skip dimension=" + dimension.location() + " reason=provider-missing";
                lines.add(line);
                LC2H.LOGGER.warn("[LC2H] {}", line);
                continue;
            }

            int areaSize;
            try {
                areaSize = provider.getWorldStyle().getMultiSettings().areasize();
            } catch (Throwable t) {
                String line = "skip dimension=" + dimension.location() + " reason=areasize-error error=" + t.getClass().getSimpleName() + ":" + t.getMessage();
                lines.add(line);
                LC2H.LOGGER.warn("[LC2H] {}", line);
                continue;
            }

            for (int[] center : centers) {
                ChunkCoord centerMulti = new ChunkCoord(dimension, center[0], center[1]);
                targets.add(new ParityTarget(level, provider, centerMulti, areaSize, center[0], center[1], RADIUS));
            }
        }
        return new ActiveRun(server, lines, targets);
    }

    private static void writeReport(MinecraftServer server, List<String> lines) {
        if (OUTPUT.isBlank()) {
            return;
        }
        try {
            Path path = server.getFile(OUTPUT).toPath();
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(path, lines);
            LC2H.LOGGER.info("[LC2H] Wrote parity autorun report to {}", path.toAbsolutePath());
        } catch (IOException e) {
            LC2H.LOGGER.warn("[LC2H] Failed to write parity autorun report: {}", e.getMessage());
        }
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

    private static String formatCenters(List<int[]> centers) {
        ArrayList<String> out = new ArrayList<>();
        for (int[] center : centers) {
            out.add(center[0] + "," + center[1]);
        }
        return out.toString();
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
                return new SeedValidation(levelName, configuredSeed, overworld.getSeed(), parsedSeed != overworld.getSeed(), "parsed numeric seed");
            } catch (NumberFormatException ignored) {
                return new SeedValidation(levelName, configuredSeed, overworld.getSeed(), false, "configured seed is not numeric");
            }
        } catch (IOException e) {
            return new SeedValidation("<unreadable>", "", overworld.getSeed(), false, "server.properties read failed: " + e.getMessage());
        }
    }

    private record SeedValidation(String levelName, String configuredSeed, long actualSeed, boolean mismatch, String note) {
        private String summary() {
            return "seedValidation levelName=" + levelName
                + " configuredSeed=" + (configuredSeed.isBlank() ? "<blank>" : configuredSeed)
                + " actualSeed=" + actualSeed
                + " mismatch=" + mismatch
                + " note=" + note;
        }
    }

    private record ParityTarget(ServerLevel level, IDimensionInfo provider, ChunkCoord centerMulti, int areaSize, int centerX, int centerZ,
                                int minChunkX, int maxChunkX, int minChunkZ, int maxChunkZ) {
        private ParityTarget(ServerLevel level, IDimensionInfo provider, ChunkCoord centerMulti, int areaSize, int centerX, int centerZ, int radius) {
            this(
                level,
                provider,
                centerMulti,
                areaSize,
                centerX,
                centerZ,
                centerMulti.chunkX() * areaSize - radius,
                centerMulti.chunkX() * areaSize + areaSize - 1 + radius,
                centerMulti.chunkZ() * areaSize - radius,
                centerMulti.chunkZ() * areaSize + areaSize - 1 + radius
            );
        }
    }

    private static final class ActiveRun {
        private enum SettlePhase {
            PRE_REPORT,
            POST_REPORT
        }

        private final MinecraftServer server;
        private final ArrayList<String> lines;
        private final List<ParityTarget> targets;
        private int targetIndex;
        private ChunkPrimingSession primingSession;
        private DeferredTreeSettlePriming settlePriming;
        private ParityTarget settlingTarget;
        private SettlePhase settlePhase;
        private long settleStartedAtMs;
        private long lastSettleLogAtMs;
        private boolean settleTimedOut;
        private boolean finished;

        private ActiveRun(MinecraftServer server, List<String> lines, List<ParityTarget> targets) {
            this.server = server;
            this.lines = new ArrayList<>(lines);
            this.targets = List.copyOf(targets);
            this.targetIndex = 0;
        }

        private MinecraftServer server() {
            return server;
        }

        private ArrayList<String> lines() {
            return lines;
        }

        private void step() {
            if (finished) {
                return;
            }
            if (settlingTarget != null) {
                if (!settleStep()) {
                    return;
                }
                ParityTarget settledTarget = settlingTarget;
                SettlePhase completedPhase = settlePhase;
                boolean timedOut = settleTimedOut;
                settlingTarget = null;
                settlePhase = null;
                settleStartedAtMs = 0L;
                lastSettleLogAtMs = 0L;
                settleTimedOut = false;
                if (timedOut) {
                    lines.add("abort reason=settle-timeout center=" + settledTarget.centerX() + "," + settledTarget.centerZ()
                        + " dim=" + settledTarget.level().dimension().location()
                        + " phase=" + completedPhase.name().toLowerCase());
                    finish();
                    return;
                }
                if (completedPhase == SettlePhase.PRE_REPORT) {
                    runParityReport(settledTarget);
                    beginSettle(settledTarget, SettlePhase.POST_REPORT);
                    if (primingSession != null) {
                        primingSession.close();
                        primingSession = null;
                    }
                    return;
                }
                targetIndex++;
                return;
            }
            if (targetIndex >= targets.size()) {
                finish();
                return;
            }
            ParityTarget target = targets.get(targetIndex);
            if (GENERATE_CHUNKS && !primeStep(target)) {
                return;
            }
            logRuntimeState("post-prime", target);
            beginSettle(target, SettlePhase.PRE_REPORT);
        }

        private boolean primeStep(ParityTarget target) {
            if (primingSession == null) {
                ArrayList<ChunkPos> coords = new ArrayList<>();
                for (int chunkX = target.minChunkX(); chunkX <= target.maxChunkX(); chunkX++) {
                    for (int chunkZ = target.minChunkZ(); chunkZ <= target.maxChunkZ(); chunkZ++) {
                        coords.add(new ChunkPos(chunkX, chunkZ));
                    }
                }
                String label = "center=" + target.centerX() + "," + target.centerZ()
                    + " dim=" + target.level().dimension().location()
                    + " chunkWindow=" + target.minChunkX() + "," + target.minChunkZ() + " -> " + target.maxChunkX() + "," + target.maxChunkZ();
                primingSession = new ChunkPrimingSession(target.level(), label, coords, PRIME_CHUNKS_PER_TICK, lines::add);
                logRuntimeState("pre-prime", target);
            }
            return primingSession.step();
        }

        private void runParityReport(ParityTarget target) {
            target.provider().setWorld(target.level());
            long started = System.nanoTime();
            MultiChunkParityHarness.ParityReport report = MultiChunkParityHarness.run(
                target.provider(),
                target.centerMulti(),
                target.areaSize(),
                RADIUS,
                SAMPLES
            );
            long elapsedMs = Math.round((System.nanoTime() - started) / 1_000_000.0D);
            String line = "report center=" + target.centerX() + "," + target.centerZ()
                + " dim=" + target.level().dimension().location()
                + " elapsedMs=" + elapsedMs + " " + report.summary();
            lines.add(line);
            for (String detail : report.summaryLines()) {
                lines.add("detail " + detail);
            }
            LC2H.LOGGER.info("[LC2H] {}", line);
            for (String detail : report.summaryLines()) {
                LC2H.LOGGER.info("[LC2H] {}", detail);
            }
            if (GENERATE_CHUNKS) {
                GeneratedWorldAgreementHarness.AgreementReport agreement = GeneratedWorldAgreementHarness.run(
                    target.provider(),
                    target.centerMulti(),
                    target.areaSize()
                );
                String agreementLine = "agreement center=" + target.centerX() + "," + target.centerZ()
                    + " dim=" + target.level().dimension().location()
                    + " " + agreement.summary();
                lines.add(agreementLine);
                for (String detail : agreement.summaryLines()) {
                    lines.add("detail " + detail);
                }
                LC2H.LOGGER.info("[LC2H] {}", agreementLine);
                for (String detail : agreement.summaryLines()) {
                    LC2H.LOGGER.info("[LC2H] {}", detail);
                }
            }
        }

        private void beginSettle(ParityTarget target, SettlePhase phase) {
            settlingTarget = target;
            settlePhase = phase;
            settleStartedAtMs = System.currentTimeMillis();
            lastSettleLogAtMs = 0L;
            settleTimedOut = false;
            if (settlePriming == null && target != null) {
                String label = "center=" + target.centerX() + "," + target.centerZ()
                    + " dim=" + target.level().dimension().location();
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

        private void logRuntimeState(String stage, ParityTarget target) {
            String prefix = "runtime " + stage
                + " center=" + target.centerX() + "," + target.centerZ()
                + " dim=" + target.level().dimension().location();
            lines.add(prefix + " shadow=" + ShadowBlockMutationApplier.diagnostics());
            for (String detail : ShadowBlockMutationApplier.pendingTransactionDetails()) {
                lines.add(prefix + " shadowTx=" + detail);
            }
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
            boolean shadowPending = ShadowBlockMutationApplier.hasPendingWork();
            int pendingTransactions = ShadowBlockMutationApplier.getPendingTransactionCount();
            long heldTickets = ShadowBlockMutationApplier.getHeldTicketCount();
            int pendingTrees = settlePriming == null
                ? DeferredTreeQueue.pendingCount(settlingTarget.level())
                : settlePriming.relevantPendingTrees();
            int readyTrees = settlePriming == null
                ? DeferredTreeQueue.readyCount(settlingTarget.level())
                : settlePriming.relevantReadyTrees();
            int globalPendingTrees = DeferredTreeQueue.pendingCount(settlingTarget.level());
            int globalReadyTrees = DeferredTreeQueue.readyCount(settlingTarget.level());
            if (!shadowPending
                && pendingTransactions == 0
                && heldTickets == 0L
                && pendingTrees == 0
                && readyTrees == 0
                && globalPendingTrees == 0
                && globalReadyTrees == 0) {
                lines.add("settle done center=" + settlingTarget.centerX() + "," + settlingTarget.centerZ()
                    + " dim=" + settlingTarget.level().dimension().location()
                    + " waitMs=" + Math.max(0L, now - settleStartedAtMs)
                    + " pendingTrees=" + pendingTrees
                    + " globalPendingTrees=" + globalPendingTrees
                    + " globalReadyTrees=" + globalReadyTrees
                    + " tickets=" + ShadowBlockMutationApplier.getTicketAcquiredCount() + "/" + ShadowBlockMutationApplier.getTicketReleasedCount());
                logRuntimeState(settlePhase == SettlePhase.PRE_REPORT ? "pre-report-settled" : "post-agreement", settlingTarget);
                return true;
            }
            if (lastSettleLogAtMs == 0L || (now - lastSettleLogAtMs) >= SETTLE_LOG_INTERVAL_MS) {
                lastSettleLogAtMs = now;
                lines.add("settle pending center=" + settlingTarget.centerX() + "," + settlingTarget.centerZ()
                    + " dim=" + settlingTarget.level().dimension().location()
                    + " waitMs=" + Math.max(0L, now - settleStartedAtMs)
                    + " pendingTransactions=" + pendingTransactions
                    + " heldTickets=" + heldTickets
                    + " pendingTrees=" + pendingTrees
                    + " readyTrees=" + readyTrees
                    + " globalPendingTrees=" + globalPendingTrees
                    + " globalReadyTrees=" + globalReadyTrees
                    + " settlePrime[" + (settlePriming == null ? "<none>" : settlePriming.diagnostics()) + "]");
                for (String detail : ShadowBlockMutationApplier.pendingTransactionDetails()) {
                    lines.add("settle tx " + detail);
                }
            }
            if ((now - settleStartedAtMs) >= SETTLE_TIMEOUT_MS) {
                settleTimedOut = true;
                lines.add("settle timeout center=" + settlingTarget.centerX() + "," + settlingTarget.centerZ()
                    + " dim=" + settlingTarget.level().dimension().location()
                    + " waitMs=" + Math.max(0L, now - settleStartedAtMs)
                    + " pendingTransactions=" + pendingTransactions
                    + " heldTickets=" + heldTickets
                    + " pendingTrees=" + pendingTrees
                    + " readyTrees=" + readyTrees
                    + " globalPendingTrees=" + globalPendingTrees
                    + " globalReadyTrees=" + globalReadyTrees
                    + " settlePrime[" + (settlePriming == null ? "<none>" : settlePriming.diagnostics()) + "]");
                logRuntimeState("settle-timeout", settlingTarget);
                List<String> details = settlePriming == null
                    ? DeferredTreeQueue.pendingDetails(settlingTarget.level(), 8)
                    : settlePriming.relevantPendingDetails(8);
                for (String detail : details) {
                    lines.add("settle tree " + detail);
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
            if (settlePriming != null) {
                settlePriming.close();
                settlePriming = null;
            }
            settlingTarget = null;
            ACTIVE_RUN = null;
            writeReport(server, lines);
            if (AUTO_STOP) {
                server.halt(false);
            }
        }

        private List<ChunkPos> initialSettleSeedChunks(ParityTarget target) {
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
}
