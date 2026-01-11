package org.admany.lc2h.diagnostics;

import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.admany.lc2h.LC2H;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;


public final class StallDetector {
    private static final long DEFAULT_THRESHOLD_MS = 10_000L;
    private static final long WATCHER_PERIOD_MS = 1_000L;

    private final ScheduledExecutorService watchdog;
    private final AtomicLong lastHeartbeat = new AtomicLong(System.currentTimeMillis());
    private final AtomicBoolean active = new AtomicBoolean(false);
    private final AtomicBoolean alreadyReported = new AtomicBoolean(false);
    private final AtomicLong lastTickCount = new AtomicLong(-1);

    private final MinecraftServer server;
    private final long thresholdMs;

    private StallDetector(MinecraftServer server, long thresholdMs) {
        this.server = server;
        this.thresholdMs = thresholdMs;
        this.watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "lc2h-stall-watcher");
            t.setDaemon(true);
            return t;
        });
    }

    private static volatile StallDetector INSTANCE = null;

    public static void start(MinecraftServer server) {
        if (INSTANCE != null) return;
        long threshold = Long.getLong("lc2h.stall.threshold_ms", DEFAULT_THRESHOLD_MS);
        StallDetector d = new StallDetector(server, threshold);
        INSTANCE = d;
        d._start();
    }

    public static void stop() {
        StallDetector inst = INSTANCE;
        if (inst == null) return;
        inst._stop();
        INSTANCE = null;
    }

    public static void triggerDump(MinecraftServer server) {
        if (server == null) return;
        long now = System.currentTimeMillis();
        try {
            new StallDetector(server, 0).writeStallDump(now, 0); 
        } catch (Throwable t) {
            LC2H.LOGGER.error("[LC2H] Failed to trigger manual stall dump: {}", t.getMessage(), t);
        }
    }

    private void _start() {
        if (!active.compareAndSet(false, true)) return;
        MinecraftForge.EVENT_BUS.register(this);

        watchdog.scheduleAtFixedRate(this::checkForStall, WATCHER_PERIOD_MS, WATCHER_PERIOD_MS, TimeUnit.MILLISECONDS);

        if (org.admany.lc2h.logging.config.ConfigManager.ENABLE_DEBUG_LOGGING) {
            LC2H.LOGGER.info("[LC2H] StallDetector started (threshold {} ms)", thresholdMs);
        } else {
            LC2H.LOGGER.debug("[LC2H] StallDetector started (threshold {} ms)", thresholdMs);
        }
    }

    private void _stop() {
        if (!active.compareAndSet(true, false)) return;
        try {
            MinecraftForge.EVENT_BUS.unregister(this);
        } catch (Throwable ignored) {}
        watchdog.shutdownNow();
        LC2H.LOGGER.info("[LC2H] StallDetector stopped");
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent ev) {
        if (ev.phase == TickEvent.Phase.END) {
            lastHeartbeat.set(System.currentTimeMillis());
            lastTickCount.set(server.getTickCount());
            alreadyReported.set(false);
        }
    }

    private void checkForStall() {
        try {
            long now = System.currentTimeMillis();
            long last = lastHeartbeat.get();
            long diff = now - last;

            long currentTick = server.getTickCount();
            long lastTick = lastTickCount.get();
            if (lastTick != -1 && currentTick == lastTick) {
                return;
            }

            if (diff > thresholdMs) {
                if (alreadyReported.compareAndSet(false, true)) {
                    if (org.admany.lc2h.logging.config.ConfigManager.ENABLE_DEBUG_LOGGING) {
                        LC2H.LOGGER.warn("[LC2H] Possible main-thread stall detected: last heartbeat {} ms ago - generating diagnostics dump", diff);
                    } else {
                        LC2H.LOGGER.debug("[LC2H] Possible main-thread stall detected: last heartbeat {} ms ago - generating diagnostics dump", diff);
                    }
                    try {
                        writeStallDump(now, diff);
                    } catch (Throwable t) {
                        LC2H.LOGGER.error("[LC2H] Failed to write stall dump: {}", t.getMessage(), t);
                    }
                }
            }

        } catch (Throwable t) {
            LC2H.LOGGER.error("[LC2H] Error in StallDetector watchdog: {}", t.getMessage(), t);
        }
    }

    private void writeStallDump(long now, long diffMs) throws IOException {
        Path logDir = net.minecraftforge.fml.loading.FMLPaths.GAMEDIR.get().resolve("logs");
        try { Files.createDirectories(logDir); } catch (Throwable t) {

            logDir = Path.of(".").toAbsolutePath().normalize();
        }

        String ts = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC).format(Instant.ofEpochMilli(now));
        Path out = logDir.resolve("lc2h-stall-" + ts + ".log");
        if (org.admany.lc2h.logging.config.ConfigManager.ENABLE_DEBUG_LOGGING) {
            LC2H.LOGGER.info("[LC2H] Attempting to write stall dump to: {}", out.toAbsolutePath());
        } else {
            LC2H.LOGGER.debug("[LC2H] Attempting to write stall dump to: {}", out.toAbsolutePath());
        }

        StringBuilder sb = new StringBuilder();
        sb.append("LC2H stall dump\n");
        sb.append("Time: ").append(Instant.ofEpochMilli(now)).append(" (UTC)\n");
        sb.append("Main thread heartbeat age (ms): ").append(diffMs).append("\n\n");


        sb.append("== LC2H DIAGNOSTICS SNAPSHOT ==\n");
        try {
            sb.append("AsyncChunkWarmup.regionBufferSize = ").append(org.admany.lc2h.worldgen.async.warmup.AsyncChunkWarmup.getRegionBufferSize()).append('\n');
            sb.append("AsyncChunkWarmup.activeBatches = ").append(org.admany.lc2h.worldgen.async.warmup.AsyncChunkWarmup.getActiveBatchCount()).append('\n');
            sb.append("AsyncChunkWarmup.cacheHits = ").append(org.admany.lc2h.worldgen.async.warmup.AsyncChunkWarmup.getCacheHits()).append('\n');
            sb.append("AsyncChunkWarmup.cacheMisses = ").append(org.admany.lc2h.worldgen.async.warmup.AsyncChunkWarmup.getCacheMisses()).append('\n');
        } catch (Throwable ignored) {}

        try {
            sb.append("AsyncMultiChunkPlanner.plannedCount = ").append(org.admany.lc2h.worldgen.async.planner.AsyncMultiChunkPlanner.getPlannedCount()).append('\n');
            sb.append("AsyncMultiChunkPlanner.gpuCacheSize = ").append(org.admany.lc2h.worldgen.async.planner.AsyncMultiChunkPlanner.getGpuDataCacheSize()).append('\n');
        } catch (Throwable ignored) {}

        try {
            org.admany.lc2h.worldgen.async.planner.PlannerBatchQueue.PlannerBatchStats stats =
                org.admany.lc2h.worldgen.async.planner.PlannerBatchQueue.snapshotStats();
            sb.append("PlannerBatchQueue.stats = ").append(stats).append('\n');
            appendStallAnalysis(sb, server, stats);
        } catch (Throwable ignored) {}

        try {
            sb.append("MainThreadChunkApplier.queueSize = ").append(org.admany.lc2h.core.MainThreadChunkApplier.getQueueSize()).append('\n');
        } catch (Throwable ignored) {}

        try {
            sb.append("TweaksActorSystem.inFlight = ").append(org.admany.lc2h.tweaks.TweaksActorSystem.getInFlightCount()).append('\n');
        } catch (Throwable ignored) {}

        try {
            sb.append("FloatingVegetationRemover.pendingScans = ").append(org.admany.lc2h.util.chunk.ChunkPostProcessor.getPendingScanCount()).append('\n');
        } catch (Throwable ignored) {}

        try {
            org.admany.lc2h.util.spawn.SpawnSearchScheduler.appendDiagnostics(sb, server);
        } catch (Throwable ignored) {}

        sb.append('\n');

        sb.append("== THREAD DUMP ==\n");
        ThreadMXBean tm = ManagementFactory.getThreadMXBean();
        boolean lockedMonitors = true;
        boolean lockedSynchronizers = true;
        ThreadInfo[] infos = tm.dumpAllThreads(lockedMonitors, lockedSynchronizers);
        for (ThreadInfo ti : infos) {
            sb.append(ti.toString());
            sb.append(System.lineSeparator());
        }


        try {
            long tick = server.getTickCount();
            sb.append("\nServer tick count: ").append(tick).append('\n');
        } catch (Throwable ignored) {}

        // Safe write
        Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
        LC2H.LOGGER.warn("[LC2H] Wrote LC2H stall diagnostics to {}", out.toAbsolutePath());
    }

    private static void appendStallAnalysis(StringBuilder sb,
                                            MinecraftServer server,
                                            org.admany.lc2h.worldgen.async.planner.PlannerBatchQueue.PlannerBatchStats stats) {
        if (sb == null || stats == null) {
            return;
        }
        sb.append("== LC2H STALL ANALYSIS ==\n");
        int buildingInfoPending = 0;
        try {
            Integer pending = stats.pendingByKind().get(org.admany.lc2h.worldgen.async.planner.PlannerTaskKind.BUILDING_INFO);
            buildingInfoPending = pending != null ? pending : 0;
        } catch (Throwable ignored) {
        }
        org.admany.lc2h.util.spawn.SpawnSearchScheduler.SpawnSearchSnapshot spawnSnap =
            org.admany.lc2h.util.spawn.SpawnSearchScheduler.snapshot(server);
        int flingRisk = org.admany.lc2h.util.spawn.SpawnSearchScheduler.countFlingBackRisk(server);

        sb.append("spawnSearchActive=").append(spawnSnap.active())
            .append(" activeDimensions=").append(spawnSnap.activeDimensions())
            .append(" resolved=").append(spawnSnap.resolved())
            .append(" pendingTeleports=").append(spawnSnap.pendingTeleports())
            .append('\n');
        sb.append("spawnSearchPending=").append(spawnSnap.pendingCandidates())
            .append(" pendingChunks=").append(spawnSnap.pendingChunks())
            .append(" needsBuildingInfo=").append(spawnSnap.needsBuildingInfo())
            .append(" reqBuildings=").append(spawnSnap.requiredBuildings())
            .append(" reqParts=").append(spawnSnap.requiredParts())
            .append('\n');
        sb.append("plannerPending=").append(stats.pendingTasks())
            .append(" buildingInfoPending=").append(buildingInfoPending)
            .append(" flingBackRiskPlayers=").append(flingRisk)
            .append('\n');

        if (spawnSnap.active() && spawnSnap.needsBuildingInfo() && buildingInfoPending > 0) {
            sb.append("possibleCause=spawn-search waiting on building info backlog\n");
        } else if (spawnSnap.active() && spawnSnap.pendingCandidates() > 0) {
            sb.append("possibleCause=spawn-search scan still in progress\n");
        } else if (buildingInfoPending > 0) {
            sb.append("possibleCause=building info queue backlog\n");
        }
        if (flingRisk > 0) {
            sb.append("flingBackCause=players near old spawn without pending teleport entry\n");
        } else if (spawnSnap.active() && spawnSnap.pendingTeleports() == 0) {
            sb.append("flingBackCause=spawn-search unresolved; fallback spawn still active\n");
        }
        sb.append('\n');
    }
}
