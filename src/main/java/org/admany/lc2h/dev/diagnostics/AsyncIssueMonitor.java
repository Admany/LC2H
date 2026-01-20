package org.admany.lc2h.dev.diagnostics;

import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.forgespi.language.IModInfo;
import org.admany.lc2h.LC2H;
import org.admany.lc2h.concurrency.async.AsyncManager;
import org.admany.lc2h.util.server.ServerTickLoad;
import org.admany.lc2h.worldgen.async.planner.AsyncBuildingInfoPlanner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.LinkedHashSet;
import java.util.Set;

public final class AsyncIssueMonitor {
    private static final long DEFAULT_TIMEOUT_MS = 30_000L;
    private static final long WATCHER_PERIOD_MS = 2_000L;
    private static final long ISSUE_TTL_MS = Math.max(60_000L,
        Long.getLong("lc2h.asyncwatch.ttl_ms", TimeUnit.HOURS.toMillis(1)));
    private static final long MAIN_QUEUE_AGE_MS = Math.max(1_000L,
        Long.getLong("lc2h.asyncwatch.mainqueue_age_ms", 5_000L));
    private static final int MAIN_QUEUE_SIZE = Math.max(1,
        Integer.getInteger("lc2h.asyncwatch.mainqueue_size", 64));
    private static final double MAIN_QUEUE_AVG_TICK_MS =
        Double.parseDouble(System.getProperty("lc2h.asyncwatch.mainqueue_avg_tick_ms", "0"));
    private static final int BUILDINGINFO_PENDING = Math.max(32,
        Integer.getInteger("lc2h.asyncwatch.buildinginfo_pending", 2000));
    private static final long BUILDINGINFO_RECENT_MS = Math.max(1_000L,
        Long.getLong("lc2h.asyncwatch.buildinginfo_recent_ms", 10_000L));
    private static final String ISSUE_PREFIX = "lc2h-async-issue-";

    private static volatile AsyncIssueMonitor INSTANCE = null;

    private final MinecraftServer server;
    private final long timeoutMs;
    private final ScheduledExecutorService watchdog;
    private final ConcurrentHashMap<CompletableFuture<?>, TrackedTask> tracked = new ConcurrentHashMap<>();
    private final AtomicBoolean active = new AtomicBoolean(false);
    private final AtomicLong lastCleanupMs = new AtomicLong(0L);

    private AsyncIssueMonitor(MinecraftServer server, long timeoutMs) {
        this.server = server;
        this.timeoutMs = timeoutMs;
        this.watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "lc2h-async-issue-watcher");
            t.setDaemon(true);
            return t;
        });
    }

    public static void start(MinecraftServer server) {
        if (server == null || INSTANCE != null) {
            return;
        }
        long timeout = Math.max(1_000L, Long.getLong("lc2h.asyncwatch.timeout_ms", DEFAULT_TIMEOUT_MS));
        AsyncIssueMonitor monitor = new AsyncIssueMonitor(server, timeout);
        INSTANCE = monitor;
        monitor._start();
    }

    public static void stop() {
        AsyncIssueMonitor inst = INSTANCE;
        if (inst == null) {
            return;
        }
        inst._stop();
        INSTANCE = null;
    }

    public static <T> CompletableFuture<T> track(String taskName, CompletableFuture<T> future) {
        AsyncIssueMonitor inst = INSTANCE;
        if (inst == null || future == null) {
            return future;
        }
        inst.trackInternal(taskName, future);
        return future;
    }

    private void _start() {
        if (!active.compareAndSet(false, true)) {
            return;
        }
        cleanupOldIssueDumps();
        watchdog.scheduleAtFixedRate(this::checkForIssues, WATCHER_PERIOD_MS, WATCHER_PERIOD_MS, TimeUnit.MILLISECONDS);
        LC2H.LOGGER.debug("[LC2H] AsyncIssueMonitor started (timeout {} ms)", timeoutMs);
    }

    private void _stop() {
        if (!active.compareAndSet(true, false)) {
            return;
        }
        watchdog.shutdownNow();
        tracked.clear();
        LC2H.LOGGER.debug("[LC2H] AsyncIssueMonitor stopped");
    }

    private <T> void trackInternal(String taskName, CompletableFuture<T> future) {
        String safeName = taskName == null ? "unknown-task" : taskName;
        TrackedTask task = new TrackedTask(safeName, System.currentTimeMillis(), Thread.currentThread().getName(),
            captureCreationStack());
        tracked.put(future, task);
        future.whenComplete((value, throwable) -> {
            tracked.remove(future);
            if (throwable != null) {
                writeIssueDump("failure", task, throwable);
            }
        });
    }

    private void checkForIssues() {
        try {
            long now = System.currentTimeMillis();
            if ((now - lastCleanupMs.get()) > TimeUnit.MINUTES.toMillis(5)) {
                lastCleanupMs.set(now);
                cleanupOldIssueDumps();
            }
            checkMainQueueBacklog(now);
            checkBuildingInfoPressure(now);
            for (TrackedTask task : tracked.values()) {
                if (task == null) {
                    continue;
                }
                long age = now - task.startMs;
                if (age >= timeoutMs && task.reported.compareAndSet(false, true)) {
                    writeIssueDump("timeout", task, null);
                }
            }
        } catch (Throwable t) {
            LC2H.LOGGER.debug("[LC2H] AsyncIssueMonitor watcher error: {}", t.getMessage());
        }
    }

    private void checkMainQueueBacklog(long now) {
        int size = AsyncManager.getMainThreadQueueSize();
        if (size < MAIN_QUEUE_SIZE) {
            return;
        }
        long age = AsyncManager.getMainThreadQueueOldestAgeMs();
        if (age < MAIN_QUEUE_AGE_MS) {
            return;
        }
        if (MAIN_QUEUE_AVG_TICK_MS > 0) {
            double avg = ServerTickLoad.getAverageTickMs(server, 50.0D);
            if (avg < MAIN_QUEUE_AVG_TICK_MS) {
                return;
            }
        }
        writeMainQueueDump(now, size, age);
    }

    private void checkBuildingInfoPressure(long now) {
        AsyncBuildingInfoPlanner.BuildingInfoPressureSnapshot snap = AsyncBuildingInfoPlanner.snapshotPressure();
        if (snap == null || snap.pendingBuildingInfo() < BUILDINGINFO_PENDING) {
            return;
        }
        long lastRetry = snap.lastLimiterRetryMs();
        long lastSpawnRetry = snap.lastSpawnRetryMs();
        boolean recentLimiter = lastRetry > 0 && (now - lastRetry) <= BUILDINGINFO_RECENT_MS;
        boolean recentSpawn = lastSpawnRetry > 0 && (now - lastSpawnRetry) <= BUILDINGINFO_RECENT_MS;
        if (!recentLimiter && !recentSpawn) {
            return;
        }
        writeBuildingInfoDump(now, snap, recentSpawn);
    }

    private void writeBuildingInfoDump(long now,
                                       AsyncBuildingInfoPlanner.BuildingInfoPressureSnapshot snap,
                                       boolean spawnPressure) {
        Path logDir = getIssueLogDir();
        cleanupOldIssueDumps();
        String ts = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC)
            .format(Instant.ofEpochMilli(now));
        Path out = logDir.resolve(ISSUE_PREFIX + "buildinginfo-pressure-" + ts + ".log");

        StringBuilder sb = new StringBuilder();
        sb.append("LC2H async issue report\n");
        sb.append("Time: ").append(Instant.ofEpochMilli(now)).append(" (UTC)\n");
        sb.append("Kind: buildinginfo-pressure\n");
        if (spawnPressure) {
            sb.append("Hint: spawn search pressure (FORCE_SPAWN_* likely enabled)\n");
        }
        sb.append("Pending buildinginfo: ").append(snap.pendingBuildingInfo()).append('\n');
        sb.append("Cache size: ").append(snap.cacheSize()).append('\n');
        sb.append("Spawn prefetch: ").append(snap.spawnPrefetch()).append('\n');
        sb.append("Limiter limit: ").append(snap.limiterLimit()).append('\n');
        sb.append("Limiter available: ").append(snap.limiterAvailable()).append('\n');
        sb.append("Limiter retries: ").append(snap.limiterRetryTotal()).append('\n');
        sb.append("Last limiter retry (ms ago): ").append(ageMs(now, snap.lastLimiterRetryMs())).append('\n');
        sb.append("Spawn retries: ").append(snap.spawnRetryTotal()).append('\n');
        sb.append("Last spawn retry (ms ago): ").append(ageMs(now, snap.lastSpawnRetryMs())).append("\n\n");

        appendLoadedMods(sb);

        try {
            long tick = server != null ? server.getTickCount() : -1L;
            sb.append("Server tick count: ").append(tick).append('\n');
        } catch (Throwable ignored) {
        }

        sb.append("\n== THREAD DUMP ==\n");
        ThreadMXBean tm = ManagementFactory.getThreadMXBean();
        ThreadInfo[] infos = tm.dumpAllThreads(true, true);
        for (ThreadInfo ti : infos) {
            sb.append(ti.toString()).append(System.lineSeparator());
        }

        try {
            Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
            LC2H.LOGGER.warn("[LC2H] Wrote async issue report to {}", out.toAbsolutePath());
        } catch (IOException e) {
            LC2H.LOGGER.warn("[LC2H] Failed to write async issue report: {}", e.getMessage());
        }
    }

    private static long ageMs(long now, long ts) {
        if (ts <= 0L) {
            return -1L;
        }
        return Math.max(0L, now - ts);
    }

    private void writeMainQueueDump(long now, int size, long ageMs) {
        Path logDir = getIssueLogDir();
        cleanupOldIssueDumps();
        String ts = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC)
            .format(Instant.ofEpochMilli(now));
        Path out = logDir.resolve(ISSUE_PREFIX + "main-queue-" + ts + ".log");

        StringBuilder sb = new StringBuilder();
        sb.append("LC2H async issue report\n");
        sb.append("Time: ").append(Instant.ofEpochMilli(now)).append(" (UTC)\n");
        sb.append("Kind: main-queue-backlog\n");
        sb.append("Main queue size: ").append(size).append('\n');
        sb.append("Oldest queued age (ms): ").append(ageMs).append('\n');
        sb.append("Elapsed tick (ms): ").append(String.format(java.util.Locale.ROOT, "%.2f", ServerTickLoad.getElapsedMsInCurrentTick())).append('\n');
        sb.append("Last tick (ms): ").append(String.format(java.util.Locale.ROOT, "%.2f", ServerTickLoad.getLastTickMs())).append('\n');
        sb.append("Smoothed tick (ms): ").append(String.format(java.util.Locale.ROOT, "%.2f", ServerTickLoad.getSmoothedTickMs())).append('\n');
        sb.append("Average tick (ms): ").append(String.format(java.util.Locale.ROOT, "%.2f",
            ServerTickLoad.getAverageTickMs(server, 50.0D))).append("\n\n");

        try {
            java.util.List<String> top = AsyncManager.getMainQueueTopProducers(5);
            if (!top.isEmpty()) {
                sb.append("Top main-queue producers:\n");
                for (String line : top) {
                    sb.append(" - ").append(line).append('\n');
                }
                sb.append('\n');
            }
        } catch (Throwable ignored) {
        }

        appendLoadedMods(sb);

        try {
            long tick = server != null ? server.getTickCount() : -1L;
            sb.append("Server tick count: ").append(tick).append('\n');
        } catch (Throwable ignored) {
        }

        sb.append("\n== THREAD DUMP ==\n");
        ThreadMXBean tm = ManagementFactory.getThreadMXBean();
        ThreadInfo[] infos = tm.dumpAllThreads(true, true);
        for (ThreadInfo ti : infos) {
            sb.append(ti.toString()).append(System.lineSeparator());
        }

        try {
            Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
            LC2H.LOGGER.warn("[LC2H] Wrote async issue report to {}", out.toAbsolutePath());
        } catch (IOException e) {
            LC2H.LOGGER.warn("[LC2H] Failed to write async issue report: {}", e.getMessage());
        }
    }

    private void writeIssueDump(String kind, TrackedTask task, Throwable throwable) {
        if (task == null) {
            return;
        }
        long now = System.currentTimeMillis();
        Path logDir = getIssueLogDir();
        cleanupOldIssueDumps();
        String ts = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC)
            .format(Instant.ofEpochMilli(now));
        Path out = logDir.resolve(ISSUE_PREFIX + kind + "-" + ts + ".log");

        StringBuilder sb = new StringBuilder();
        sb.append("LC2H async issue report\n");
        sb.append("Time: ").append(Instant.ofEpochMilli(now)).append(" (UTC)\n");
        sb.append("Kind: ").append(kind).append('\n');
        sb.append("Task: ").append(task.name).append('\n');
        sb.append("Age (ms): ").append(now - task.startMs).append('\n');
        sb.append("Creator thread: ").append(task.threadName).append('\n');
        sb.append("Tracked tasks: ").append(tracked.size()).append("\n\n");

        if (task.creationStack != null && !task.creationStack.isEmpty()) {
            sb.append("== TASK CREATION STACK ==\n");
            sb.append(task.creationStack).append('\n');
        }

        Set<String> suspects = new LinkedHashSet<>();
        if (task.creationStack != null && !task.creationStack.isEmpty()) {
            suspects.addAll(extractSuspects(task.creationStack));
        }

        if (throwable != null) {
            sb.append("== EXCEPTION ==\n");
            sb.append(throwable).append('\n');
            for (StackTraceElement el : throwable.getStackTrace()) {
                sb.append("    at ").append(el).append('\n');
            }
            sb.append('\n');
            suspects.addAll(extractSuspects(throwable.getStackTrace()));
        }

        if (!suspects.isEmpty()) {
            sb.append("== POSSIBLE MOD SUSPECTS ==\n");
            for (String suspect : suspects) {
                sb.append(" - ").append(suspect).append('\n');
            }
            sb.append('\n');
        }

        appendLoadedMods(sb);

        try {
            long tick = server != null ? server.getTickCount() : -1L;
            sb.append("Server tick count: ").append(tick).append('\n');
        } catch (Throwable ignored) {
        }

        sb.append("\n== THREAD DUMP ==\n");
        ThreadMXBean tm = ManagementFactory.getThreadMXBean();
        ThreadInfo[] infos = tm.dumpAllThreads(true, true);
        for (ThreadInfo ti : infos) {
            sb.append(ti.toString()).append(System.lineSeparator());
        }

        try {
            Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
            LC2H.LOGGER.warn("[LC2H] Wrote async issue report to {}", out.toAbsolutePath());
        } catch (IOException e) {
            LC2H.LOGGER.warn("[LC2H] Failed to write async issue report: {}", e.getMessage());
        }
    }

    private static String captureCreationStack() {
        StackTraceElement[] trace = Thread.currentThread().getStackTrace();
        if (trace == null || trace.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int limit = Math.min(trace.length, 20);
        for (int i = 3; i < limit; i++) {
            sb.append("    at ").append(trace[i]).append('\n');
        }
        return sb.toString().trim();
    }

    private static Set<String> extractSuspects(String stackText) {
        if (stackText == null || stackText.isEmpty()) {
            return java.util.Collections.emptySet();
        }
        Set<String> suspects = new LinkedHashSet<>();
        String[] lines = stackText.split("\\R");
        for (String line : lines) {
            String trimmed = line.trim();
            int atIdx = trimmed.indexOf("at ");
            if (atIdx >= 0) {
                trimmed = trimmed.substring(atIdx + 3).trim();
            }
            int paren = trimmed.indexOf('(');
            if (paren > 0) {
                trimmed = trimmed.substring(0, paren);
            }
            int method = trimmed.lastIndexOf('.');
            if (method <= 0) {
                continue;
            }
            String className = trimmed.substring(0, method);
            String suspect = toSuspectPackage(className);
            if (suspect != null) {
                suspects.add(suspect);
            }
        }
        return suspects;
    }

    private static Set<String> extractSuspects(StackTraceElement[] trace) {
        if (trace == null || trace.length == 0) {
            return java.util.Collections.emptySet();
        }
        Set<String> suspects = new LinkedHashSet<>();
        for (StackTraceElement el : trace) {
            if (el == null) {
                continue;
            }
            String className = el.getClassName();
            String suspect = toSuspectPackage(className);
            if (suspect != null) {
                suspects.add(suspect);
            }
        }
        return suspects;
    }

    private static String toSuspectPackage(String className) {
        if (className == null || className.isEmpty()) {
            return null;
        }
        if (className.startsWith("org.admany.lc2h.")
            || className.startsWith("net.minecraft.")
            || className.startsWith("net.minecraftforge.")
            || className.startsWith("java.")
            || className.startsWith("javax.")
            || className.startsWith("jdk.")
            || className.startsWith("sun.")
            || className.startsWith("com.mojang.")
            || className.startsWith("org.spongepowered.")
            || className.startsWith("org.apache.")) {
            return null;
        }
        String[] parts = className.split("\\.");
        if (parts.length >= 2) {
            return parts[0] + "." + parts[1];
        }
        return className;
    }

    private static void appendLoadedMods(StringBuilder sb) {
        try {
            if (sb == null) {
                return;
            }
            sb.append("== LOADED MODS ==\n");
            for (IModInfo mod : ModList.get().getMods()) {
                if (mod == null) {
                    continue;
                }
                String modId = mod.getModId();
                String name = mod.getDisplayName();
                String version = mod.getVersion() != null ? mod.getVersion().toString() : "unknown";
                sb.append(" - ").append(modId).append(" | ").append(name).append(" | ").append(version).append('\n');
            }
            sb.append('\n');
        } catch (Throwable ignored) {
        }
    }

    private static Path getIssueLogDir() {
        Path logDir = net.minecraftforge.fml.loading.FMLPaths.GAMEDIR.get()
            .resolve("logs").resolve("lc2h").resolve("async-issues");
        try {
            Files.createDirectories(logDir);
            return logDir;
        } catch (Throwable ignored) {
            return Path.of(".").toAbsolutePath().normalize();
        }
    }

    private static void cleanupOldIssueDumps() {
        cleanupOldDumps(getIssueLogDir(), ISSUE_PREFIX, ISSUE_TTL_MS);
    }

    private static void cleanupOldDumps(Path dir, String prefix, long ttlMs) {
        if (dir == null || ttlMs <= 0L) {
            return;
        }
        long cutoff = System.currentTimeMillis() - ttlMs;
        try (var stream = Files.list(dir)) {
            stream.filter(path -> {
                    try {
                        String name = path.getFileName().toString();
                        return name.startsWith(prefix) && name.endsWith(".log");
                    } catch (Throwable ignored) {
                        return false;
                    }
                })
                .forEach(path -> {
                    try {
                        long modified = Files.getLastModifiedTime(path).toMillis();
                        if (modified < cutoff) {
                            Files.deleteIfExists(path);
                        }
                    } catch (Throwable ignored) {
                    }
                });
        } catch (Throwable ignored) {
        }
    }

    private static final class TrackedTask {
        private final String name;
        private final long startMs;
        private final String threadName;
        private final String creationStack;
        private final AtomicBoolean reported = new AtomicBoolean(false);

        private TrackedTask(String name, long startMs, String threadName, String creationStack) {
            this.name = name;
            this.startMs = startMs;
            this.threadName = threadName;
            this.creationStack = creationStack;
        }
    }
}
