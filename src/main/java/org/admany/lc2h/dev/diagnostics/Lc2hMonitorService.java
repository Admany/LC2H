package org.admany.lc2h.dev.diagnostics;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import org.admany.lc2h.LC2H;
import org.admany.lc2h.data.cache.FeatureCache;
import org.admany.lc2h.tweaks.TweaksActorSystem;
import org.admany.lc2h.util.chunk.ChunkPostProcessor;
import org.admany.lc2h.util.server.ServerTickLoad;
import org.admany.lc2h.worldgen.apply.MainThreadChunkApplier;
import org.admany.lc2h.worldgen.async.planner.AsyncMultiChunkPlanner;
import org.admany.lc2h.worldgen.async.planner.PlannerBatchQueue;
import org.admany.lc2h.worldgen.async.warmup.AsyncChunkWarmup;
import org.admany.lc2h.worldgen.gpu.GPUMemoryManager;
import org.admany.lc2h.worldgen.lostcities.DeferredTreeQueue;
import org.admany.lc2h.worldgen.seams.SeamOwnershipJournal;
import org.admany.quantified.core.common.parallel.metrics.ParallelMetrics;
import org.admany.quantified.core.common.parallel.throttle.ParallelBackpressure;
import org.admany.quantified.core.common.util.TaskScheduler;

import java.io.IOException;
import java.lang.management.ClassLoadingMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Mod.EventBusSubscriber(modid = LC2H.MODID)
public final class Lc2hMonitorService {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final DateTimeFormatter FILE_TS =
        DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").withZone(ZoneId.systemDefault());

    private static volatile Session ACTIVE = null;
    private static volatile FinishedReport LAST_REPORT = null;

    private Lc2hMonitorService() {
    }

    public static synchronized Component start(MinecraftServer server, String initiator, int durationSeconds) {
        if (server == null) {
            return Component.literal("LC2H monitor: no server");
        }
        if (ACTIVE != null) {
            return Component.literal("LC2H monitor already running: " + ACTIVE.elapsedSeconds(server) + "s / " + ACTIVE.durationSeconds + "s");
        }
        int duration = Math.max(10, Math.min(300, durationSeconds));
        Session session = new Session(server, initiator == null ? "unknown" : initiator, duration);
        session.capture(server);
        ACTIVE = session;
        return Component.literal("LC2H monitor started for " + duration + "s");
    }

    public static synchronized Component status(MinecraftServer server) {
        Session active = ACTIVE;
        if (active != null && server != null) {
            MonitorSample latest = active.latestSample();
            String latestLine = latest == null ? "collecting first sample"
                : String.format(Locale.ROOT,
                    "samples=%d avgTick=%.2fms peakPlanner=%d peakApply=%d peakScans=%d cpu=%.1f%% heap=%s",
                    active.samples.size(),
                    latest.avgTickMs,
                    active.summary.peakPlannerPending,
                    active.summary.peakApplyQueue,
                    active.summary.peakPendingScans,
                    latest.processCpuLoad * 100.0D,
                    formatBytes(latest.heapUsedBytes));
            return Component.literal("LC2H monitor running: " + active.elapsedSeconds(server) + "s / " + active.durationSeconds + "s | " + latestLine);
        }
        FinishedReport last = LAST_REPORT;
        if (last == null) {
            return Component.literal("LC2H monitor idle");
        }
        return Component.literal("LC2H monitor idle. Last report: " + last.path.toAbsolutePath());
    }

    public static synchronized Component stop(MinecraftServer server) {
        Session active = ACTIVE;
        if (active == null || server == null) {
            return Component.literal("LC2H monitor is not running");
        }
        FinishedReport report = finalizeSession(active, server, true);
        ACTIVE = null;
        LAST_REPORT = report;
        return Component.literal(report.shortSummary);
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.getServer() == null) {
            return;
        }
        Session active = ACTIVE;
        if (active == null || active.server != event.getServer()) {
            return;
        }
        long tick = event.getServer().getTickCount();
        if (tick >= active.nextSampleTick) {
            active.capture(event.getServer());
            active.nextSampleTick = tick + 20L;
        }
        if (active.elapsedSeconds(event.getServer()) >= active.durationSeconds) {
            synchronized (Lc2hMonitorService.class) {
                if (ACTIVE == active) {
                    FinishedReport report = finalizeSession(active, event.getServer(), false);
                    ACTIVE = null;
                    LAST_REPORT = report;
                    LC2H.LOGGER.info("[LC2H] {}", report.shortSummary);
                }
            }
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        ACTIVE = null;
    }

    private static FinishedReport finalizeSession(Session session, MinecraftServer server, boolean manualStop) {
        session.capture(server);
        session.summary.finish(session);
        Path out = writeReport(session, manualStop);
        String hottest = session.summary.topTimingBuckets.isEmpty()
            ? "n/a"
            : session.summary.topTimingBuckets.get(0).bucket();
        String summary = String.format(Locale.ROOT,
            "LC2H monitor saved to %s | chunksDone=%d | avgTick=%.2fms maxTick=%.2fms | cpu(avg/peak)=%.1f%%/%.1f%% | peaks planner=%d apply=%d scans=%d seams=%d deferred=%d | hottest=%s",
            out.toAbsolutePath(),
            session.summary.deltaGenerateEnd,
            session.summary.avgAvgTickMs,
            session.summary.maxAvgTickMs,
            session.summary.avgProcessCpuLoad * 100.0D,
            session.summary.peakProcessCpuLoad * 100.0D,
            session.summary.peakPlannerPending,
            session.summary.peakApplyQueue,
            session.summary.peakPendingScans,
            session.summary.peakSeamIntents,
            session.summary.peakDeferredPending,
            hottest);
        return new FinishedReport(out, summary);
    }

    private static Path writeReport(Session session, boolean manualStop) {
        Path dir = FMLPaths.GAMEDIR.get().resolve("lc2h").resolve("monitor");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create monitor directory", e);
        }
        String name = "monitor_" + FILE_TS.format(Instant.now()) + (manualStop ? "_manual" : "") + ".json";
        Path out = dir.resolve(name);
        try {
            Files.writeString(out, GSON.toJson(new MonitorReport(session).toJsonModel()));
            return out;
        } catch (IOException e) {
            throw new RuntimeException("Failed to write monitor report", e);
        }
    }

    private static String formatBytes(long bytes) {
        if (bytes <= 0L) {
            return "0B";
        }
        double mb = bytes / (1024.0D * 1024.0D);
        if (mb < 1.0D) {
            return String.format(Locale.ROOT, "%.0fKB", bytes / 1024.0D);
        }
        return String.format(Locale.ROOT, "%.1fMB", mb);
    }

    private static final class Session {
        private final MinecraftServer server;
        private final String initiator;
        private final int durationSeconds;
        private final long startMs;
        private final long startTick;
        private final List<MonitorSample> samples = new ArrayList<>();
        private final Summary summary = new Summary();
        private final Map<Long, Long> lastThreadCpuNs = new ConcurrentHashMap<>();
        private final Map<Long, Long> totalThreadCpuNs = new ConcurrentHashMap<>();
        private final Map<Long, String> threadNames = new ConcurrentHashMap<>();
        private long nextSampleTick;

        private Session(MinecraftServer server, String initiator, int durationSeconds) {
            this.server = server;
            this.initiator = initiator;
            this.durationSeconds = durationSeconds;
            this.startMs = System.currentTimeMillis();
            this.startTick = server.getTickCount();
            this.nextSampleTick = startTick;
        }

        private long elapsedSeconds(MinecraftServer server) {
            if (server == null) {
                return 0L;
            }
            return Math.max(0L, (server.getTickCount() - startTick) / 20L);
        }

        private MonitorSample latestSample() {
            return samples.isEmpty() ? null : samples.get(samples.size() - 1);
        }

        private void capture(MinecraftServer server) {
            MonitorSample sample = MonitorSample.capture(server, this);
            samples.add(sample);
            summary.accept(sample);
            sampleThreadCpu();
        }

        private void sampleThreadCpu() {
            ThreadMXBean bean = ManagementFactory.getThreadMXBean();
            if (!bean.isThreadCpuTimeSupported()) {
                return;
            }
            if (!bean.isThreadCpuTimeEnabled()) {
                try {
                    bean.setThreadCpuTimeEnabled(true);
                } catch (Throwable ignored) {
                    return;
                }
            }
            long[] ids = bean.getAllThreadIds();
            for (long id : ids) {
                long cpuNs = bean.getThreadCpuTime(id);
                if (cpuNs < 0L) {
                    continue;
                }
                ThreadInfo info = bean.getThreadInfo(id);
                if (info != null) {
                    threadNames.put(id, info.getThreadName());
                }
                Long previous = lastThreadCpuNs.put(id, cpuNs);
                if (previous != null && cpuNs >= previous) {
                    totalThreadCpuNs.merge(id, cpuNs - previous, Long::sum);
                }
            }
        }
    }

    private record FinishedReport(Path path, String shortSummary) {
    }

    private static final class MonitorReport {
        private final String initiator;
        private final int durationSeconds;
        private final long startedAtMs;
        private final long startTick;
        private final Summary summary;
        private final List<MonitorSample> samples;
        private final List<ThreadCpuEntry> topThreadCpu;

        private MonitorReport(Session session) {
            this.initiator = session.initiator;
            this.durationSeconds = session.durationSeconds;
            this.startedAtMs = session.startMs;
            this.startTick = session.startTick;
            this.summary = session.summary;
            this.samples = List.copyOf(session.samples);
            this.topThreadCpu = session.totalThreadCpuNs.entrySet().stream()
                .sorted(Map.Entry.<Long, Long>comparingByValue().reversed())
                .limit(12)
                .map(entry -> new ThreadCpuEntry(
                    entry.getKey(),
                    session.threadNames.getOrDefault(entry.getKey(), "unknown"),
                    entry.getValue() / 1_000_000.0D))
                .toList();
        }

        private Map<String, Object> toJsonModel() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("initiator", initiator);
            out.put("durationSeconds", durationSeconds);
            out.put("startedAtMs", startedAtMs);
            out.put("startTick", startTick);
            out.put("summary", summary.toJsonModel());
            out.put("samples", samples.stream().map(MonitorSample::toJsonModel).toList());
            out.put("topThreadCpu", topThreadCpu);
            return out;
        }
    }

    private record ThreadCpuEntry(long threadId, String name, double cpuMs) {
    }

    private static final class Summary {
        private double sumAvgTickMs;
        private double maxAvgTickMs;
        private double sumProcessCpuLoad;
        private double peakProcessCpuLoad;
        private long peakPlannerPending;
        private long peakApplyQueue;
        private long peakPendingScans;
        private long peakWarmupQueue;
        private long peakWarmupActive;
        private long peakParallelQueued;
        private long peakParallelActive;
        private long peakSeamIntents;
        private long peakDeferredPending;
        private long peakHeapUsed;
        private long startGenerateEnd = Long.MIN_VALUE;
        private long endGenerateEnd;
        private long startGenerateSkip = Long.MIN_VALUE;
        private long endGenerateSkip;
        private long startMainThreadApplied = Long.MIN_VALUE;
        private long endMainThreadApplied;
        private long startMainThreadCulled = Long.MIN_VALUE;
        private long endMainThreadCulled;
        private int sampleCount;

        private double avgAvgTickMs;
        private double avgProcessCpuLoad;
        private long deltaGenerateEnd;
        private long deltaGenerateSkip;
        private long deltaMainThreadApplied;
        private long deltaMainThreadCulled;
        private Map<String, Lc2hTimingRegistry.TimingSnapshot> startTimings = Map.of();
        private Map<String, Lc2hTimingRegistry.TimingSnapshot> endTimings = Map.of();
        private List<TimingBucketDelta> topTimingBuckets = List.of();

        private void accept(MonitorSample sample) {
            sampleCount++;
            sumAvgTickMs += sample.avgTickMs;
            maxAvgTickMs = Math.max(maxAvgTickMs, sample.avgTickMs);
            sumProcessCpuLoad += sample.processCpuLoad;
            peakProcessCpuLoad = Math.max(peakProcessCpuLoad, sample.processCpuLoad);
            peakPlannerPending = Math.max(peakPlannerPending, sample.plannerPendingTasks);
            peakApplyQueue = Math.max(peakApplyQueue, sample.mainThreadQueue);
            peakPendingScans = Math.max(peakPendingScans, sample.postProcessPendingScans);
            peakWarmupQueue = Math.max(peakWarmupQueue, sample.warmupQueue);
            peakWarmupActive = Math.max(peakWarmupActive, sample.warmupActiveBatches);
            peakParallelQueued = Math.max(peakParallelQueued, sample.parallelQueuedSlices);
            peakParallelActive = Math.max(peakParallelActive, sample.parallelActiveSlices);
            peakSeamIntents = Math.max(peakSeamIntents, sample.seamPendingIntents);
            peakDeferredPending = Math.max(peakDeferredPending, sample.deferredTreePending);
            peakHeapUsed = Math.max(peakHeapUsed, sample.heapUsedBytes);
            if (startGenerateEnd == Long.MIN_VALUE) {
                startGenerateEnd = sample.chunkGenerateEnd;
                startGenerateSkip = sample.chunkGenerateSkip;
                startMainThreadApplied = sample.mainThreadAppliedTotal;
                startMainThreadCulled = sample.mainThreadCulledTotal;
                startTimings = sample.subsystemTimings;
            }
            endGenerateEnd = sample.chunkGenerateEnd;
            endGenerateSkip = sample.chunkGenerateSkip;
            endMainThreadApplied = sample.mainThreadAppliedTotal;
            endMainThreadCulled = sample.mainThreadCulledTotal;
            endTimings = sample.subsystemTimings;
        }

        private void finish(Session session) {
            if (sampleCount <= 0) {
                return;
            }
            avgAvgTickMs = sumAvgTickMs / sampleCount;
            avgProcessCpuLoad = sumProcessCpuLoad / sampleCount;
            deltaGenerateEnd = Math.max(0L, endGenerateEnd - startGenerateEnd);
            deltaGenerateSkip = Math.max(0L, endGenerateSkip - startGenerateSkip);
            deltaMainThreadApplied = Math.max(0L, endMainThreadApplied - startMainThreadApplied);
            deltaMainThreadCulled = Math.max(0L, endMainThreadCulled - startMainThreadCulled);
            topTimingBuckets = endTimings.entrySet().stream()
                .map(entry -> {
                    Lc2hTimingRegistry.TimingSnapshot delta = entry.getValue().delta(startTimings.get(entry.getKey()));
                    return new TimingBucketDelta(entry.getKey(), delta.count(), delta.totalNs(), delta.maxNs(), delta.avgNs());
                })
                .filter(entry -> entry.count > 0L && entry.totalNs > 0L)
                .sorted(Comparator.comparingLong(TimingBucketDelta::totalNs).reversed())
                .limit(16)
                .collect(Collectors.toList());
        }

        private Map<String, Object> toJsonModel() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("sumAvgTickMs", sumAvgTickMs);
            out.put("maxAvgTickMs", maxAvgTickMs);
            out.put("sumProcessCpuLoad", sumProcessCpuLoad);
            out.put("peakProcessCpuLoad", peakProcessCpuLoad);
            out.put("peakPlannerPending", peakPlannerPending);
            out.put("peakApplyQueue", peakApplyQueue);
            out.put("peakPendingScans", peakPendingScans);
            out.put("peakWarmupQueue", peakWarmupQueue);
            out.put("peakWarmupActive", peakWarmupActive);
            out.put("peakParallelQueued", peakParallelQueued);
            out.put("peakParallelActive", peakParallelActive);
            out.put("peakSeamIntents", peakSeamIntents);
            out.put("peakDeferredPending", peakDeferredPending);
            out.put("peakHeapUsed", peakHeapUsed);
            out.put("startGenerateEnd", startGenerateEnd);
            out.put("endGenerateEnd", endGenerateEnd);
            out.put("startGenerateSkip", startGenerateSkip);
            out.put("endGenerateSkip", endGenerateSkip);
            out.put("startMainThreadApplied", startMainThreadApplied);
            out.put("endMainThreadApplied", endMainThreadApplied);
            out.put("startMainThreadCulled", startMainThreadCulled);
            out.put("endMainThreadCulled", endMainThreadCulled);
            out.put("sampleCount", sampleCount);
            out.put("avgAvgTickMs", avgAvgTickMs);
            out.put("avgProcessCpuLoad", avgProcessCpuLoad);
            out.put("deltaGenerateEnd", deltaGenerateEnd);
            out.put("deltaGenerateSkip", deltaGenerateSkip);
            out.put("deltaMainThreadApplied", deltaMainThreadApplied);
            out.put("deltaMainThreadCulled", deltaMainThreadCulled);
            out.put("startTimings", startTimings);
            out.put("endTimings", endTimings);
            out.put("topTimingBuckets", topTimingBuckets);
            return out;
        }
    }

    private record TimingBucketDelta(String bucket, long count, long totalNs, long maxNs, double avgNs) {
    }

    private static final class MonitorSample {
        private final long wallTimeMs;
        private final long serverTick;
        private final double avgTickMs;
        private final double lastTickMs;
        private final double smoothedTickMs;
        private final int players;
        private final int plannerBatchCount;
        private final int plannerPendingTasks;
        private final Map<String, Integer> plannerPendingByKind;
        private final long plannerPressureDropped;
        private final long plannerDuplicateDropped;
        private final int warmupQueue;
        private final int warmupActiveBatches;
        private final long warmupCacheHits;
        private final long warmupCacheMisses;
        private final int multichunkPlanned;
        private final int multichunkGpuCache;
        private final int mainThreadQueue;
        private final long mainThreadAppliedTotal;
        private final long mainThreadCulledTotal;
        private final int tweaksInflight;
        private final int tweaksValidated;
        private final int postProcessPendingScans;
        private final long viewCullTotal;
        private final long viewCullPlannerQueue;
        private final long viewCullPlannerBatch;
        private final long viewCullMultiChunk;
        private final long viewCullWarmupQueue;
        private final long viewCullWarmupBatch;
        private final long viewCullApply;
        private final long chunkGenerateStart;
        private final long chunkGenerateEnd;
        private final long chunkGenerateSkip;
        private final long chunkBuildingInfo;
        private final long chunkMultichunkIntegrated;
        private final long priorityForeground;
        private final long priorityBackground;
        private final int gpuEntries;
        private final long gpuBytes;
        private final long gpuDiskEntries;
        private final long gpuDiskBytes;
        private final long gpuQuantifiedBytes;
        private final long gpuPromotions;
        private final long featureLocalEntries;
        private final Long featureQuantifiedEntries;
        private final long featureMemoryMb;
        private final boolean featurePressureHigh;
        private final long quantifiedTotalTasks;
        private final long quantifiedGpuTasks;
        private final long quantifiedCpuTasks;
        private final double quantifiedGpuRatio;
        private final long parallelActiveSlices;
        private final long parallelQueuedSlices;
        private final long parallelCacheHits;
        private final long parallelCacheMisses;
        private final int deferredTreePending;
        private final int deferredTreeReady;
        private final int seamPendingChunks;
        private final int seamPendingIntents;
        private final double processCpuLoad;
        private final double systemCpuLoad;
        private final long processCpuTimeNs;
        private final long heapUsedBytes;
        private final long heapCommittedBytes;
        private final long heapMaxBytes;
        private final long nonHeapUsedBytes;
        private final int liveThreads;
        private final int daemonThreads;
        private final int loadedClassCount;
        private final Map<String, Lc2hTimingRegistry.TimingSnapshot> subsystemTimings;

        private MonitorSample(
            long wallTimeMs,
            long serverTick,
            double avgTickMs,
            double lastTickMs,
            double smoothedTickMs,
            int players,
            int plannerBatchCount,
            int plannerPendingTasks,
            Map<String, Integer> plannerPendingByKind,
            long plannerPressureDropped,
            long plannerDuplicateDropped,
            int warmupQueue,
            int warmupActiveBatches,
            long warmupCacheHits,
            long warmupCacheMisses,
            int multichunkPlanned,
            int multichunkGpuCache,
            int mainThreadQueue,
            long mainThreadAppliedTotal,
            long mainThreadCulledTotal,
            int tweaksInflight,
            int tweaksValidated,
            int postProcessPendingScans,
            long viewCullTotal,
            long viewCullPlannerQueue,
            long viewCullPlannerBatch,
            long viewCullMultiChunk,
            long viewCullWarmupQueue,
            long viewCullWarmupBatch,
            long viewCullApply,
            long chunkGenerateStart,
            long chunkGenerateEnd,
            long chunkGenerateSkip,
            long chunkBuildingInfo,
            long chunkMultichunkIntegrated,
            long priorityForeground,
            long priorityBackground,
            int gpuEntries,
            long gpuBytes,
            long gpuDiskEntries,
            long gpuDiskBytes,
            long gpuQuantifiedBytes,
            long gpuPromotions,
            long featureLocalEntries,
            Long featureQuantifiedEntries,
            long featureMemoryMb,
            boolean featurePressureHigh,
            long quantifiedTotalTasks,
            long quantifiedGpuTasks,
            long quantifiedCpuTasks,
            double quantifiedGpuRatio,
            long parallelActiveSlices,
            long parallelQueuedSlices,
            long parallelCacheHits,
            long parallelCacheMisses,
            int deferredTreePending,
            int deferredTreeReady,
            int seamPendingChunks,
            int seamPendingIntents,
            double processCpuLoad,
            double systemCpuLoad,
            long processCpuTimeNs,
            long heapUsedBytes,
            long heapCommittedBytes,
            long heapMaxBytes,
            long nonHeapUsedBytes,
            int liveThreads,
            int daemonThreads,
            int loadedClassCount,
            Map<String, Lc2hTimingRegistry.TimingSnapshot> subsystemTimings
        ) {
            this.wallTimeMs = wallTimeMs;
            this.serverTick = serverTick;
            this.avgTickMs = avgTickMs;
            this.lastTickMs = lastTickMs;
            this.smoothedTickMs = smoothedTickMs;
            this.players = players;
            this.plannerBatchCount = plannerBatchCount;
            this.plannerPendingTasks = plannerPendingTasks;
            this.plannerPendingByKind = plannerPendingByKind;
            this.plannerPressureDropped = plannerPressureDropped;
            this.plannerDuplicateDropped = plannerDuplicateDropped;
            this.warmupQueue = warmupQueue;
            this.warmupActiveBatches = warmupActiveBatches;
            this.warmupCacheHits = warmupCacheHits;
            this.warmupCacheMisses = warmupCacheMisses;
            this.multichunkPlanned = multichunkPlanned;
            this.multichunkGpuCache = multichunkGpuCache;
            this.mainThreadQueue = mainThreadQueue;
            this.mainThreadAppliedTotal = mainThreadAppliedTotal;
            this.mainThreadCulledTotal = mainThreadCulledTotal;
            this.tweaksInflight = tweaksInflight;
            this.tweaksValidated = tweaksValidated;
            this.postProcessPendingScans = postProcessPendingScans;
            this.viewCullTotal = viewCullTotal;
            this.viewCullPlannerQueue = viewCullPlannerQueue;
            this.viewCullPlannerBatch = viewCullPlannerBatch;
            this.viewCullMultiChunk = viewCullMultiChunk;
            this.viewCullWarmupQueue = viewCullWarmupQueue;
            this.viewCullWarmupBatch = viewCullWarmupBatch;
            this.viewCullApply = viewCullApply;
            this.chunkGenerateStart = chunkGenerateStart;
            this.chunkGenerateEnd = chunkGenerateEnd;
            this.chunkGenerateSkip = chunkGenerateSkip;
            this.chunkBuildingInfo = chunkBuildingInfo;
            this.chunkMultichunkIntegrated = chunkMultichunkIntegrated;
            this.priorityForeground = priorityForeground;
            this.priorityBackground = priorityBackground;
            this.gpuEntries = gpuEntries;
            this.gpuBytes = gpuBytes;
            this.gpuDiskEntries = gpuDiskEntries;
            this.gpuDiskBytes = gpuDiskBytes;
            this.gpuQuantifiedBytes = gpuQuantifiedBytes;
            this.gpuPromotions = gpuPromotions;
            this.featureLocalEntries = featureLocalEntries;
            this.featureQuantifiedEntries = featureQuantifiedEntries;
            this.featureMemoryMb = featureMemoryMb;
            this.featurePressureHigh = featurePressureHigh;
            this.quantifiedTotalTasks = quantifiedTotalTasks;
            this.quantifiedGpuTasks = quantifiedGpuTasks;
            this.quantifiedCpuTasks = quantifiedCpuTasks;
            this.quantifiedGpuRatio = quantifiedGpuRatio;
            this.parallelActiveSlices = parallelActiveSlices;
            this.parallelQueuedSlices = parallelQueuedSlices;
            this.parallelCacheHits = parallelCacheHits;
            this.parallelCacheMisses = parallelCacheMisses;
            this.deferredTreePending = deferredTreePending;
            this.deferredTreeReady = deferredTreeReady;
            this.seamPendingChunks = seamPendingChunks;
            this.seamPendingIntents = seamPendingIntents;
            this.processCpuLoad = processCpuLoad;
            this.systemCpuLoad = systemCpuLoad;
            this.processCpuTimeNs = processCpuTimeNs;
            this.heapUsedBytes = heapUsedBytes;
            this.heapCommittedBytes = heapCommittedBytes;
            this.heapMaxBytes = heapMaxBytes;
            this.nonHeapUsedBytes = nonHeapUsedBytes;
            this.liveThreads = liveThreads;
            this.daemonThreads = daemonThreads;
            this.loadedClassCount = loadedClassCount;
            this.subsystemTimings = subsystemTimings;
        }

        private static MonitorSample capture(MinecraftServer server, Session session) {
            int players = 0;
            try {
                if (server.getPlayerList() != null) {
                    players = server.getPlayerList().getPlayerCount();
                }
            } catch (Throwable ignored) {
            }

            PlannerBatchQueue.PlannerBatchStats planner = PlannerBatchQueue.snapshotStats();
            Map<String, Integer> plannerKinds = new LinkedHashMap<>();
            for (Map.Entry<?, Integer> entry : planner.pendingByKind().entrySet()) {
                plannerKinds.put(String.valueOf(entry.getKey()), entry.getValue());
            }

            ViewCullingStats.Snapshot culling = ViewCullingStats.snapshot();
            ChunkGenTracker.GlobalSnapshot chunkStats = ChunkGenTracker.globalSnapshot();
            org.admany.lc2h.data.cache.FeatureCache.CacheStats cacheStats = FeatureCache.snapshot();
            TaskScheduler.SchedulingStats schedulerStats = null;
            try {
                schedulerStats = TaskScheduler.getStats();
            } catch (Throwable ignored) {
            }
            ParallelMetrics.Snapshot parallel = ParallelMetrics.snapshot();
            long parallelActive = parallel.modActiveSlices().values().stream().mapToLong(Long::longValue).sum();
            Map<String, Lc2hTimingRegistry.TimingSnapshot> timings = Lc2hTimingRegistry.snapshot();

            MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
            MemoryUsage heap = memoryBean.getHeapMemoryUsage();
            MemoryUsage nonHeap = memoryBean.getNonHeapMemoryUsage();
            ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
            ClassLoadingMXBean classBean = ManagementFactory.getClassLoadingMXBean();

            double processCpuLoad = 0.0D;
            double systemCpuLoad = 0.0D;
            long processCpuTimeNs = 0L;
            try {
                java.lang.management.OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
                if (osBean instanceof com.sun.management.OperatingSystemMXBean ext) {
                    processCpuLoad = Math.max(0.0D, ext.getProcessCpuLoad());
                    systemCpuLoad = Math.max(0.0D, resolveSystemCpuLoad(ext));
                    processCpuTimeNs = ext.getProcessCpuTime();
                }
            } catch (Throwable ignored) {
            }

            return new MonitorSample(
                System.currentTimeMillis(),
                server.getTickCount(),
                server.getAverageTickTime(),
                ServerTickLoad.getLastTickMs(),
                ServerTickLoad.getSmoothedTickMs(),
                players,
                planner.batchCount(),
                planner.pendingTasks(),
                plannerKinds,
                PlannerBatchQueue.getPressureDropCount(),
                PlannerBatchQueue.getDuplicateDropCount(),
                AsyncChunkWarmup.getRegionBufferSize(),
                AsyncChunkWarmup.getActiveBatchCount(),
                AsyncChunkWarmup.getCacheHits(),
                AsyncChunkWarmup.getCacheMisses(),
                AsyncMultiChunkPlanner.getPlannedCount(),
                AsyncMultiChunkPlanner.getGpuDataCacheSize(),
                MainThreadChunkApplier.getQueueSize(),
                MainThreadChunkApplier.getTotalAppliedCount(),
                MainThreadChunkApplier.getTotalCulledCount(),
                TweaksActorSystem.getInFlightCount(),
                TweaksActorSystem.getValidatedCount(),
                ChunkPostProcessor.getPendingScanCount(),
                culling.total(),
                culling.plannerQueue(),
                culling.plannerBatch(),
                culling.multiChunkPending(),
                culling.warmupQueue(),
                culling.warmupBatch(),
                culling.mainThreadApply(),
                chunkStats.generateStart(),
                chunkStats.generateEnd(),
                chunkStats.generateSkip(),
                chunkStats.buildingInfo(),
                chunkStats.multichunkIntegrated(),
                chunkStats.priorityForeground(),
                chunkStats.priorityBackground(),
                GPUMemoryManager.getCachedEntryCount(),
                GPUMemoryManager.getCachedBytes(),
                GPUMemoryManager.getDiskCacheEntryCount(),
                GPUMemoryManager.getDiskCacheBytes(),
                GPUMemoryManager.getQuantifiedAPICacheSize(),
                GPUMemoryManager.getDiskPromotionCount(),
                cacheStats.localEntries(),
                cacheStats.quantifiedEntries(),
                FeatureCache.getMemoryUsageMB(),
                FeatureCache.isMemoryPressureHigh(),
                schedulerStats == null ? 0L : schedulerStats.totalTasks(),
                schedulerStats == null ? 0L : schedulerStats.gpuTasks(),
                schedulerStats == null ? 0L : schedulerStats.cpuTasks(),
                schedulerStats == null ? 0.0D : schedulerStats.gpuUtilizationRatio(),
                parallelActive,
                ParallelBackpressure.queued(),
                parallel.cacheHits(),
                parallel.cacheMisses(),
                DeferredTreeQueue.pendingCountAll(),
                DeferredTreeQueue.readyCountAll(),
                SeamOwnershipJournal.getPendingChunkCount(),
                SeamOwnershipJournal.getPendingIntentCount(),
                processCpuLoad,
                systemCpuLoad,
                processCpuTimeNs,
                heap.getUsed(),
                heap.getCommitted(),
                heap.getMax(),
                nonHeap.getUsed(),
                threadBean.getThreadCount(),
                threadBean.getDaemonThreadCount(),
                classBean.getLoadedClassCount(),
                timings
            );
        }

        private Map<String, Object> toJsonModel() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("wallTimeMs", wallTimeMs);
            out.put("serverTick", serverTick);
            out.put("avgTickMs", avgTickMs);
            out.put("lastTickMs", lastTickMs);
            out.put("smoothedTickMs", smoothedTickMs);
            out.put("players", players);
            out.put("plannerBatchCount", plannerBatchCount);
            out.put("plannerPendingTasks", plannerPendingTasks);
            out.put("plannerPendingByKind", plannerPendingByKind);
            out.put("plannerPressureDropped", plannerPressureDropped);
            out.put("plannerDuplicateDropped", plannerDuplicateDropped);
            out.put("warmupQueue", warmupQueue);
            out.put("warmupActiveBatches", warmupActiveBatches);
            out.put("warmupCacheHits", warmupCacheHits);
            out.put("warmupCacheMisses", warmupCacheMisses);
            out.put("multichunkPlanned", multichunkPlanned);
            out.put("multichunkGpuCache", multichunkGpuCache);
            out.put("mainThreadQueue", mainThreadQueue);
            out.put("mainThreadAppliedTotal", mainThreadAppliedTotal);
            out.put("mainThreadCulledTotal", mainThreadCulledTotal);
            out.put("tweaksInflight", tweaksInflight);
            out.put("tweaksValidated", tweaksValidated);
            out.put("postProcessPendingScans", postProcessPendingScans);
            out.put("viewCullTotal", viewCullTotal);
            out.put("viewCullPlannerQueue", viewCullPlannerQueue);
            out.put("viewCullPlannerBatch", viewCullPlannerBatch);
            out.put("viewCullMultiChunk", viewCullMultiChunk);
            out.put("viewCullWarmupQueue", viewCullWarmupQueue);
            out.put("viewCullWarmupBatch", viewCullWarmupBatch);
            out.put("viewCullApply", viewCullApply);
            out.put("chunkGenerateStart", chunkGenerateStart);
            out.put("chunkGenerateEnd", chunkGenerateEnd);
            out.put("chunkGenerateSkip", chunkGenerateSkip);
            out.put("chunkBuildingInfo", chunkBuildingInfo);
            out.put("chunkMultichunkIntegrated", chunkMultichunkIntegrated);
            out.put("priorityForeground", priorityForeground);
            out.put("priorityBackground", priorityBackground);
            out.put("gpuEntries", gpuEntries);
            out.put("gpuBytes", gpuBytes);
            out.put("gpuDiskEntries", gpuDiskEntries);
            out.put("gpuDiskBytes", gpuDiskBytes);
            out.put("gpuQuantifiedBytes", gpuQuantifiedBytes);
            out.put("gpuPromotions", gpuPromotions);
            out.put("featureLocalEntries", featureLocalEntries);
            out.put("featureQuantifiedEntries", featureQuantifiedEntries);
            out.put("featureMemoryMb", featureMemoryMb);
            out.put("featurePressureHigh", featurePressureHigh);
            out.put("quantifiedTotalTasks", quantifiedTotalTasks);
            out.put("quantifiedGpuTasks", quantifiedGpuTasks);
            out.put("quantifiedCpuTasks", quantifiedCpuTasks);
            out.put("quantifiedGpuRatio", quantifiedGpuRatio);
            out.put("parallelActiveSlices", parallelActiveSlices);
            out.put("parallelQueuedSlices", parallelQueuedSlices);
            out.put("parallelCacheHits", parallelCacheHits);
            out.put("parallelCacheMisses", parallelCacheMisses);
            out.put("deferredTreePending", deferredTreePending);
            out.put("deferredTreeReady", deferredTreeReady);
            out.put("seamPendingChunks", seamPendingChunks);
            out.put("seamPendingIntents", seamPendingIntents);
            out.put("processCpuLoad", processCpuLoad);
            out.put("systemCpuLoad", systemCpuLoad);
            out.put("processCpuTimeNs", processCpuTimeNs);
            out.put("heapUsedBytes", heapUsedBytes);
            out.put("heapCommittedBytes", heapCommittedBytes);
            out.put("heapMaxBytes", heapMaxBytes);
            out.put("nonHeapUsedBytes", nonHeapUsedBytes);
            out.put("liveThreads", liveThreads);
            out.put("daemonThreads", daemonThreads);
            out.put("loadedClassCount", loadedClassCount);
            out.put("subsystemTimings", subsystemTimings);
            return out;
        }

        private static double resolveSystemCpuLoad(com.sun.management.OperatingSystemMXBean bean) {
            if (bean == null) {
                return 0.0D;
            }
            try {
                java.lang.reflect.Method method = bean.getClass().getMethod("getCpuLoad");
                Object value = method.invoke(bean);
                if (value instanceof Number number) {
                    return number.doubleValue();
                }
            } catch (Throwable ignored) {
            }
            try {
                java.lang.reflect.Method method = bean.getClass().getMethod("getSystemCpuLoad");
                Object value = method.invoke(bean);
                if (value instanceof Number number) {
                    return number.doubleValue();
                }
            } catch (Throwable ignored) {
            }
            return 0.0D;
        }
    }
}
