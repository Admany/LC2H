package org.admany.lc2h.concurrency.async;

import net.minecraft.server.MinecraftServer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.admany.lc2h.LC2H;
import org.admany.lc2h.util.server.ServerTickLoad;
import org.admany.quantified.api.QuantifiedAPI;
import org.admany.quantified.api.model.QuantifiedTask;
import org.admany.quantified.core.common.async.task.ModPriorityManager;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

@Mod.EventBusSubscriber(modid = LC2H.MODID)
public class AsyncManager {

    private static final Queue<Runnable> mainThreadQueue = new ConcurrentLinkedQueue<>();
    private static final AtomicBoolean drainScheduled = new AtomicBoolean(false);
    private static final AtomicLong lastFastpathScheduleNs = new AtomicLong(0L);
    private static volatile MinecraftServer serverRef;
    private static boolean initialized = true;
    private static final boolean quantifiedBypass = false;
    private static final boolean quantifiedAvailable;
    private static final int FASTPATH_MIN_QUEUE = Math.max(1, Integer.getInteger("lc2h.mainqueue.fastpath_min_size", 32));
    private static final long FASTPATH_MIN_INTERVAL_NS =
        TimeUnit.MICROSECONDS.toNanos(Long.getLong("lc2h.mainqueue.fastpath_min_interval_us", 250L));

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
            String noCap = String.valueOf(Long.MAX_VALUE);
            System.setProperty("quantified.maxTasksPerMod", noCap);
            ModPriorityManager.setMaxTasksForMod("lc2h", Long.MAX_VALUE);
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
                return wrapTaskFuture(taskName, future);
            } catch (Throwable t) {
                LC2H.LOGGER.error("Quantified API submit failed for task '{}': {}", taskName, t.toString());
                LC2H.LOGGER.debug("Quantified submit error", t);
            }
        }

        LC2H.LOGGER.debug("Running async task '{}' on LC2H fallback executor", taskName);
        return wrapTaskFuture(taskName, CompletableFuture.supplyAsync(supplier, FALLBACK_EXECUTOR));
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
        Runnable action;
        while ((action = mainThreadQueue.poll()) != null) {
            try {
                action.run();
            } catch (Exception e) {
                LC2H.LOGGER.error("Error processing main thread queue: {}", e.getMessage());
                LC2H.LOGGER.debug("Main thread queue error", e);
            }
        }
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        // Remember a live server instance for fast path main thread scheduling, reducing reliance on tick END draining, aka no bottleneck there :].
        serverRef = event.getServer();

        if (event.phase != TickEvent.Phase.END) {
            return;
        }

        if (mainThreadQueue.isEmpty()) {
            return;
        }

        MinecraftServer server = event.getServer();
        if (ServerTickLoad.shouldPauseNonCritical(server)) {
            return;
        }

        final long budgetNs = TimeUnit.MICROSECONDS.toNanos(Long.getLong("lc2h.mainqueue.budget_us", 500L));
        final int maxTasks = Math.max(1, Integer.getInteger("lc2h.mainqueue.max_tasks", 8));

        long start = System.nanoTime();
        int ran = 0;
        Runnable action;
        while (ran < maxTasks && (action = mainThreadQueue.poll()) != null) {
            try {
                action.run();
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
    }

    public static void syncToMain(Runnable action) {
        if (action == null) {
            return;
        }
        MinecraftServer server = serverRef;
        if (server == null && shouldRunInlineClient()) {
            action.run();
            return;
        }
        mainThreadQueue.add(action);

        // Fast path: ask the server's own main-thread executor to run a budgeted drain ASAP.
        // This reduces reliance on tick END draining (which can become a throughput ceiling
        // when async producers outpace the per-tick budget), while still keeping all work on
        // the main thread and respecting the same lag guard and budgets.
        if (server != null && mainThreadQueue.size() >= FASTPATH_MIN_QUEUE) {
            maybeScheduleBudgetedDrain(server);
        }
    }

    private static void maybeScheduleBudgetedDrain(MinecraftServer server) {
        long now = System.nanoTime();
        long last = lastFastpathScheduleNs.get();
        if ((now - last) < FASTPATH_MIN_INTERVAL_NS) {
            return;
        }
        if (lastFastpathScheduleNs.compareAndSet(last, now)) {
            scheduleBudgetedDrain(server);
        }
    }

    private static void scheduleBudgetedDrain(MinecraftServer server) {
        if (server == null) {
            return;
        }
        if (!drainScheduled.compareAndSet(false, true)) {
            return;
        }

        try {
            server.execute(() -> {
                drainScheduled.set(false);
                drainBudgeted(server);

                // If producers raced ahead, schedule another pass. This stays coalesced and
                // still obeys the same budgets always, but avoid hammering the server executor.
                if (!mainThreadQueue.isEmpty() && mainThreadQueue.size() >= FASTPATH_MIN_QUEUE) {
                    maybeScheduleBudgetedDrain(server);
                }
            });
        } catch (Throwable ignored) {
            // If we can't schedule onto the server executor for any reason, the tick END
            // drain remains as a safe fallback, just in case xd.
            drainScheduled.set(false);
        }
    }

    private static void drainBudgeted(MinecraftServer server) {
        if (mainThreadQueue.isEmpty()) {
            return;
        }
        if (server != null && ServerTickLoad.shouldPauseNonCritical(server)) {
            return;
        }

        final long budgetNs = TimeUnit.MICROSECONDS.toNanos(Long.getLong("lc2h.mainqueue.budget_us", 500L));
        final int maxTasks = Math.max(1, Integer.getInteger("lc2h.mainqueue.max_tasks", 8));

        long start = System.nanoTime();
        int ran = 0;
        Runnable action;
        while (ran < maxTasks && (action = mainThreadQueue.poll()) != null) {
            try {
                action.run();
            } catch (Exception e) {
                LC2H.LOGGER.error("Error processing main thread queue: {}", e.getMessage());
                LC2H.LOGGER.debug("Main thread queue error", e);
            }
            ran++;
            if ((System.nanoTime() - start) >= budgetNs) {
                break;
            }
            if (server != null && ServerTickLoad.shouldPauseNonCritical(server)) {
                break;
            }
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
}
