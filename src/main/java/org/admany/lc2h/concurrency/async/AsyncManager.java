package org.admany.lc2h.concurrency.async;

import net.minecraft.server.MinecraftServer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.admany.lc2h.LC2H;
import org.admany.lc2h.util.server.ServerTickLoad;
import org.admany.lc2h.dev.diagnostics.AsyncIssueMonitor;
import org.admany.lc2h.worldgen.async.planner.AsyncBuildingInfoPlanner;
import org.admany.lc2h.worldgen.async.planner.AsyncMultiChunkPlanner;
import org.admany.quantified.api.QuantifiedAPI;
import org.admany.quantified.api.model.QuantifiedTask;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.concurrent.ConcurrentHashMap;
import java.util.ArrayList;
import java.util.Comparator;

@Mod.EventBusSubscriber(modid = LC2H.MODID)
public class AsyncManager {

    private static final Queue<MainThreadTask> mainThreadQueue = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger mainThreadQueueSize = new AtomicInteger(0);
    private static final AtomicLong mainThreadQueueOldestMs = new AtomicLong(0L);
    private static volatile MinecraftServer serverRef;
    private static boolean initialized = true;
    private static final boolean quantifiedBypass = false;
    private static final boolean quantifiedAvailable;
    private static final int MAIN_QUEUE_SOFT_LIMIT = Math.max(256, Integer.getInteger("lc2h.mainqueue.soft_limit", 4096));
    private static final int MAIN_QUEUE_HARD_LIMIT = Math.max(MAIN_QUEUE_SOFT_LIMIT, Integer.getInteger("lc2h.mainqueue.hard_limit", 16384));
    private static final long MAIN_QUEUE_DROP_AGE_MS = Math.max(1_000L, Long.getLong("lc2h.mainqueue.drop_age_ms", 20_000L));
    private static final long MAIN_QUEUE_DROP_LOG_INTERVAL_MS = Math.max(1_000L, Long.getLong("lc2h.mainqueue.drop_log_interval_ms", 5_000L));
    private static final AtomicLong MAIN_QUEUE_DROPPED = new AtomicLong(0L);
    private static final AtomicLong MAIN_QUEUE_LAST_DROP_LOG_MS = new AtomicLong(0L);
    private static final ConcurrentHashMap<String, AtomicLong> MAIN_QUEUE_PRODUCERS = new ConcurrentHashMap<>();

    private static final ExecutorService FALLBACK_EXECUTOR = Executors.newFixedThreadPool(
        Math.max(2, Integer.getInteger("lc2h.async.fallbackThreads", Math.max(2, Runtime.getRuntime().availableProcessors() / 2))),
        newNamedDaemonFactory("LC2H-Async")
    );

    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(
        newNamedDaemonFactory("LC2H-Timer")
    );

    static {
        boolean available = false;
        try {
            available = true;
            LC2H.LOGGER.info("AsyncManager using Quantified API scheduler");
        } catch (Throwable t) {
            LC2H.LOGGER.warn("AsyncManager could not initialize Quantified API integration; falling back to LC2H executor: {}", t.toString());
            LC2H.LOGGER.debug("Quantified init error", t);
        }
        quantifiedAvailable = available;
    }

    public static boolean isInitialized() {
        return initialized;
    }

    public static void drainFinalizers() {
    }

    public static <T> CompletableFuture<T> submitTask(String taskName, Runnable task, T result, Priority priority) {
        return submitCallable(taskName, () -> {
            task.run();
            return result;
        }, priority, false);
    }

    public static <T> CompletableFuture<T> submitTask(String taskName, Runnable task, T result) {
        return submitTask(taskName, task, result, Priority.LOW);
    }

    public static <T> CompletableFuture<T> submitSupplier(String taskName, java.util.function.Supplier<T> supplier, Priority priority) {
        return submitCallable(taskName, supplier, priority, false);
    }

    public static <T> CompletableFuture<T> submitSupplier(String taskName, java.util.function.Supplier<T> supplier) {
        return submitSupplier(taskName, supplier, Priority.LOW);
    }

    public static <T> CompletableFuture<T> submitSupplier(String taskName, java.util.function.Supplier<T> supplier, Priority priority, boolean gpuPreferred) {
        return submitCallable(taskName, supplier, priority, gpuPreferred);
    }

    private static <T> CompletableFuture<T> submitCallable(String taskName, Supplier<T> supplier, Priority priority, boolean gpuPreferred) {
        LC2H.LOGGER.debug("Submitting async task '{}' priority={} gpuPreferred={}", taskName, priority, gpuPreferred);

        MinecraftServer server = serverRef;
        if (server == null) {
            try {
                server = ServerLifecycleHooks.getCurrentServer();
            } catch (Throwable ignored) {
            }
        }
        if (server != null && !LC2H.isAsyncReady(server)) {
            try {
                return CompletableFuture.completedFuture(supplier.get());
            } catch (Throwable t) {
                CompletableFuture<T> failed = new CompletableFuture<>();
                failed.completeExceptionally(t);
                return failed;
            }
        }

        if (!quantifiedBypass && quantifiedAvailable) {
            try {
                QuantifiedTask.Builder<T> builder = QuantifiedTask.builder("lc2h", taskName, supplier);
                if (priority == Priority.HIGH) {
                    builder.priorityForeground();
                } else {
                    builder.priorityBackground();
                }
                if (gpuPreferred) {
                    builder.gpuPreferred();
                }
                CompletableFuture<T> future = QuantifiedAPI.submit(builder);
                LC2H.LOGGER.debug("Task '{}' submitted to Quantified API", taskName);
                return AsyncIssueMonitor.track(taskName, wrapTaskFuture(taskName, future));
            } catch (Throwable t) {
                LC2H.LOGGER.error("Quantified API submit failed for task '{}': {}", taskName, t.toString());
                LC2H.LOGGER.debug("Quantified submit error", t);
            }
        }

        LC2H.LOGGER.debug("Running async task '{}' on LC2H fallback executor", taskName);
        return AsyncIssueMonitor.track(taskName,
            wrapTaskFuture(taskName, CompletableFuture.supplyAsync(supplier, FALLBACK_EXECUTOR)));
    }

    public static <T> CompletableFuture<List<T>> submitBatch(String batchName, List<Supplier<T>> suppliers, Priority priority) {
        return submitBatch(batchName, suppliers, priority, false);
    }

    public static <T> CompletableFuture<List<T>> submitBatch(String batchName, List<Supplier<T>> suppliers, Priority priority, boolean gpuPreferred) {
        LC2H.LOGGER.debug("Submitting async batch '{}' tasks={} priority={} gpuPreferred={}", batchName, suppliers.size(), priority, gpuPreferred);

        if (quantifiedAvailable) {
            java.util.List<CompletableFuture<T>> futures = new java.util.ArrayList<>(suppliers.size());
            for (int i = 0; i < suppliers.size(); i++) {
                String taskName = batchName + "-" + i;
                futures.add(wrapBatchFuture(batchName, i, submitCallable(taskName, suppliers.get(i), priority, gpuPreferred)));
            }
            return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> futures.stream().map(CompletableFuture::join).toList());
        }

        java.util.List<CompletableFuture<T>> futures = new java.util.ArrayList<>(suppliers.size());
        for (int i = 0; i < suppliers.size(); i++) {
            String taskName = batchName + "-" + i;
            futures.add(wrapBatchFuture(batchName, i, submitCallable(taskName, suppliers.get(i), priority, gpuPreferred)));
        }
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .thenApply(v -> futures.stream().map(CompletableFuture::join).toList());
    }

    private static <T> CompletableFuture<T> wrapBatchFuture(String batchName, int index, CompletableFuture<T> future) {
        if (future == null) {
            CompletableFuture<T> failed = new CompletableFuture<>();
            failed.completeExceptionally(new IllegalStateException("LC2H batch future was null"));
            return failed;
        }
        return future.whenComplete((result, throwable) -> {
            if (throwable != null) {
                String name = batchName + "-" + index;
                LC2H.LOGGER.error("Async batch task '{}' failed: {}", name, throwable.toString());
                LC2H.LOGGER.debug("Async batch task error", throwable);
            }
        });
    }

    private static <T> CompletableFuture<T> wrapTaskFuture(String taskName, CompletableFuture<T> future) {
        if (future == null) {
            CompletableFuture<T> failed = new CompletableFuture<>();
            failed.completeExceptionally(new IllegalStateException("LC2H task future was null"));
            return failed;
        }
        return future.whenComplete((result, throwable) -> {
            if (throwable != null) {
                LC2H.LOGGER.error("Async task '{}' failed: {}", taskName, throwable.toString());
                LC2H.LOGGER.debug("Async task error", throwable);
            }
        });
    }

    public static CompletableFuture<Void> runLater(String taskName, Runnable task, long delayMs, Priority priority) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        long effectiveDelay = Math.max(0L, delayMs);
        TIMER.schedule(() -> {
            try {
                submitTask(taskName, task, null, priority)
                    .whenComplete((ignored, throwable) -> {
                        if (throwable != null) {
                            future.completeExceptionally(throwable);
                        } else {
                            future.complete(null);
                        }
                    });
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        }, effectiveDelay, TimeUnit.MILLISECONDS);
        return future;
    }

    private static ThreadFactory newNamedDaemonFactory(String prefix) {
        AtomicInteger idx = new AtomicInteger(1);
        return runnable -> {
            Thread thread = new Thread(runnable);
            thread.setName(prefix + "-" + idx.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        };
    }

    public static void processMainThreadQueue() {
        MainThreadTask task;
        while ((task = mainThreadQueue.poll()) != null) {
            mainThreadQueueSize.decrementAndGet();
            try {
                task.action.run();
            } catch (Exception e) {
                LC2H.LOGGER.error("Error processing main thread queue: {}", e.getMessage());
                LC2H.LOGGER.debug("Main thread queue error", e);
            }
        }
        updateOldestAfterDrain();
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        // Remember a live server instance for fast path main thread scheduling, reducing reliance on tick END draining, aka no bottleneck there :].
        serverRef = event.getServer();

        if (event.phase != TickEvent.Phase.END) {
            return;
        }

        MinecraftServer server = event.getServer();
        AsyncBuildingInfoPlanner.drainReadyResults();
        AsyncBuildingInfoPlanner.drainLimiterRetries(server);
        AsyncMultiChunkPlanner.drainWarmRetries(server);

        if (mainThreadQueue.isEmpty()) {
            return;
        }
        if (ServerTickLoad.shouldPauseNonCritical(server)) {
            return;
        }

        long baseBudgetNs = TimeUnit.MICROSECONDS.toNanos(Long.getLong("lc2h.mainqueue.budget_us", 500L));
        int baseMaxTasks = Math.max(1, Integer.getInteger("lc2h.mainqueue.max_tasks", 8));
        double scale = ServerTickLoad.getBudgetScale(server);
        long budgetNs = Math.max(1L, (long) (baseBudgetNs * scale));
        int maxTasks = Math.max(1, (int) Math.round(baseMaxTasks * scale));

        long start = System.nanoTime();
        int ran = 0;
        MainThreadTask task;
        while (ran < maxTasks && (task = mainThreadQueue.poll()) != null) {
            mainThreadQueueSize.decrementAndGet();
            try {
                task.action.run();
            } catch (Exception e) {
                LC2H.LOGGER.error("Error processing main thread queue: {}", e.getMessage());
                LC2H.LOGGER.debug("Main thread queue error", e);
            }
            ran++;
            if ((System.nanoTime() - start) >= budgetNs) {
                break;
            }
            if (ServerTickLoad.shouldPauseNonCritical(server)) {
                break;
            }
        }
        updateOldestAfterPartialDrain();
    }

    public static void syncToMain(Runnable action) {
        if (action == null) {
            return;
        }
        recordProducer();
        MinecraftServer server = serverRef;
        if (server == null && shouldRunInlineClient()) {
            action.run();
            return;
        }
        if (server != null && !LC2H.isAsyncReady(server)) {
            return;
        }
        int sizeNow = mainThreadQueueSize.get();
        if (sizeNow >= MAIN_QUEUE_HARD_LIMIT
            || (sizeNow >= MAIN_QUEUE_SOFT_LIMIT && shouldDropForBackpressure(server))) {
            long dropped = MAIN_QUEUE_DROPPED.incrementAndGet();
            maybeLogDrop(dropped, sizeNow);
            return;
        }
        long nowMs = System.currentTimeMillis();
        mainThreadQueue.add(new MainThreadTask(action, nowMs));
        if (mainThreadQueueSize.incrementAndGet() == 1) {
            mainThreadQueueOldestMs.set(nowMs);
        }
    }

    public static int getMainThreadQueueSize() {
        return Math.max(0, mainThreadQueueSize.get());
    }

    public static long getMainThreadQueueOldestAgeMs() {
        long oldest = mainThreadQueueOldestMs.get();
        if (oldest <= 0L) {
            return 0L;
        }
        return Math.max(0L, System.currentTimeMillis() - oldest);
    }

    private static void updateOldestAfterPartialDrain() {
        if (mainThreadQueue.isEmpty()) {
            mainThreadQueueOldestMs.set(0L);
            return;
        }
        MainThreadTask next = mainThreadQueue.peek();
        if (next != null) {
            mainThreadQueueOldestMs.set(next.enqueuedMs);
        }
    }

    private static void updateOldestAfterDrain() {
        if (mainThreadQueue.isEmpty()) {
            mainThreadQueueOldestMs.set(0L);
        } else {
            updateOldestAfterPartialDrain();
        }
    }

    public static void cleanupOldEntries(Object center, int radius) {
    }

    // Intentionally no reflection: Quantified is a hard dependency for LC2H.

    private static boolean shouldRunInlineClient() {
        if (FMLEnvironment.dist != Dist.CLIENT) {
            return false;
        }
        try {
            return ServerLifecycleHooks.getCurrentServer() == null;
        } catch (Throwable ignored) {
            return true;
        }
    }

    private static final class MainThreadTask {
        private final Runnable action;
        private final long enqueuedMs;

        private MainThreadTask(Runnable action, long enqueuedMs) {
            this.action = action;
            this.enqueuedMs = enqueuedMs;
        }
    }

    private static boolean shouldDropForBackpressure(MinecraftServer server) {
        long age = getMainThreadQueueOldestAgeMs();
        if (age < MAIN_QUEUE_DROP_AGE_MS) {
            return false;
        }
        if (server == null) {
            return true;
        }
        return ServerTickLoad.shouldPauseNonCritical(server);
    }

    private static void maybeLogDrop(long dropped, int sizeNow) {
        long now = System.currentTimeMillis();
        long last = MAIN_QUEUE_LAST_DROP_LOG_MS.get();
        if (now - last < MAIN_QUEUE_DROP_LOG_INTERVAL_MS) {
            return;
        }
        if (MAIN_QUEUE_LAST_DROP_LOG_MS.compareAndSet(last, now)) {
            LC2H.LOGGER.warn("[LC2H] Main thread queue overloaded (size={}, dropped={}); applying backpressure",
                sizeNow, dropped);
        }
    }

    private static void recordProducer() {
        String key = "unknown";
        try {
            StackTraceElement[] trace = Thread.currentThread().getStackTrace();
            for (int i = 3; i < Math.min(trace.length, 24); i++) {
                StackTraceElement el = trace[i];
                if (el == null) {
                    continue;
                }
                String cls = el.getClassName();
                if (cls == null) {
                    continue;
                }
                if (cls.startsWith("org.admany.lc2h.concurrency.async.AsyncManager")
                    || cls.startsWith("java.")
                    || cls.startsWith("jdk.")
                    || cls.startsWith("sun.")
                    || cls.startsWith("org.spongepowered.")) {
                    continue;
                }
                key = cls + "#" + el.getMethodName();
                break;
            }
        } catch (Throwable ignored) {
        }
        MAIN_QUEUE_PRODUCERS.computeIfAbsent(key, k -> new AtomicLong()).incrementAndGet();
    }

    public static List<String> getMainQueueTopProducers(int limit) {
        if (limit <= 0) {
            return List.of();
        }
        List<java.util.Map.Entry<String, AtomicLong>> entries = new ArrayList<>(MAIN_QUEUE_PRODUCERS.entrySet());
        entries.sort(Comparator.comparingLong(e -> -e.getValue().get()));
        List<String> top = new ArrayList<>(Math.min(limit, entries.size()));
        for (int i = 0; i < entries.size() && i < limit; i++) {
            var entry = entries.get(i);
            top.add(entry.getKey() + "=" + entry.getValue().get());
        }
        return top;
    }
}
