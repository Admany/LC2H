package org.admany.lc2h.parallel;

import org.admany.lc2h.LC2H;
import org.admany.lc2h.async.AsyncManager;
import org.admany.lc2h.async.Priority;
import org.admany.quantified.api.parallel.ParallelCompute;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.Function;

public final class ParallelWorkQueue {
    private static final AtomicLong SEQUENCE = new AtomicLong();
    private static final ConcurrentHashMap<String, AtomicLong> EWMA_NANOS_PER_SLICE = new ConcurrentHashMap<>();

    private static final ScheduledExecutorService BACKOFF_TIMER = Executors.newSingleThreadScheduledExecutor(newNamedDaemonFactory("LC2H-ParallelBackoff"));
    private static final AtomicLong LAST_QUEUE_FULL_LOG_NANOS = new AtomicLong(0L);

    private static final long SMALL_BATCH_THRESHOLD_MS = Long.getLong("lc2h.parallel.smallThresholdMs", 100L);
    private static final int SMALL_BATCH_MAX_PARALLELISM = Integer.getInteger("lc2h.parallel.smallMaxParallelism", 2);

    private static final int QUEUE_FULL_RETRIES = Math.max(0, Integer.getInteger("lc2h.parallel.queueFullRetries", 6));
    private static final long QUEUE_FULL_BASE_DELAY_MS = Math.max(1L, Long.getLong("lc2h.parallel.queueFullDelayMs", 6L));

    private ParallelWorkQueue() {
    }

    public static <T> CompletableFuture<List<T>> dispatch(String name,
                                                          List<Supplier<T>> suppliers,
                                                          Consumer<ParallelSliceResult<T>> sliceListener) {
        return dispatch(name, suppliers, sliceListener, ParallelWorkOptions.none());
    }

    public static <T> CompletableFuture<List<T>> dispatch(String name,
                                                          List<Supplier<T>> suppliers,
                                                          Consumer<ParallelSliceResult<T>> sliceListener,
                                                          ParallelWorkOptions<T> options) {
        return dispatchInternal(name, suppliers, sliceListener, options, 0);
    }

    private static <T> CompletableFuture<List<T>> dispatchInternal(String name,
                                                                   List<Supplier<T>> suppliers,
                                                                   Consumer<ParallelSliceResult<T>> sliceListener,
                                                                   ParallelWorkOptions<T> options,
                                                                   int attempt) {
        if (suppliers.isEmpty()) {
            return CompletableFuture.completedFuture(List.of());
        }

        final long startNanos = System.nanoTime();
        final int sliceCount = suppliers.size();

        List<Integer> slices = new ArrayList<>(suppliers.size());
        for (int i = 0; i < suppliers.size(); i++) {
            slices.add(i);
        }
        ParallelCompute.Builder<Integer, T, List<T>> builder = ParallelCompute.<Integer, T>builder(LC2H.MODID, name, SEQUENCE.incrementAndGet())
            .slices(() -> slices)
            .sliceExecutor(index -> {
                T value = suppliers.get(index).get();
                if (sliceListener != null) {
                    sliceListener.accept(new ParallelSliceResult<>(index, value));
                }
                return value;
            })
            .reducer(results -> {
                List<T> ordered = new ArrayList<>(results.size());
                ordered.addAll(results);
                return Collections.unmodifiableList(ordered);
            });

        long estimatedSequentialMs = estimateSequentialMs(name, sliceCount);
        if (estimatedSequentialMs > 0 && estimatedSequentialMs <= SMALL_BATCH_THRESHOLD_MS) {
            builder.maxParallelism(Math.max(1, SMALL_BATCH_MAX_PARALLELISM));
        } else if (estimatedSequentialMs == 0 && sliceCount <= 8) {
            builder.maxParallelism(Math.max(1, SMALL_BATCH_MAX_PARALLELISM));
        }

        ParallelWorkOptions<T> effective = options == null ? ParallelWorkOptions.none() : options;
        if (effective.cacheEnabled()) {
            Duration ttl = effective.cacheTtl();
            if (ttl == null || ttl.isZero() || ttl.isNegative()) {
                ttl = Duration.ofMinutes(30);
            }
            long maxEntries = effective.cacheMaxEntries() <= 0 ? 1024L : effective.cacheMaxEntries();
            Function<Integer, String> keyFunction = effective.cacheKeyFunction();
            Function<Integer, String> cacheKeySupplier = index -> {
                if (keyFunction == null || index == null) {
                    return null;
                }
                return keyFunction.apply(index);
            };
            if (effective.cachePersistent()) {
                configurePersistentCache(
                    builder,
                    effective.cacheName() == null ? name : effective.cacheName(),
                    cacheKeySupplier,
                    effective.cacheSerializer(),
                    effective.cacheDeserializer(),
                    ttl,
                    maxEntries,
                    effective.cacheCompression(),
                    effective.cacheCopyOnWrite()
                );
            } else {
                configureMemoryCache(
                    builder,
                    effective.cacheName() == null ? name : effective.cacheName(),
                    cacheKeySupplier,
                    effective.cacheSerializer(),
                    effective.cacheDeserializer(),
                    ttl,
                    maxEntries,
                    effective.cacheCopyOnWrite()
                );
            }
        }
        return submitWithQueueFullHandling(name, sliceCount, startNanos, suppliers, sliceListener, options, builder, attempt);
    }

    private static <T> void configurePersistentCache(ParallelCompute.Builder<Integer, T, List<T>> builder,
                                                     String cacheName,
                                                     Function<Integer, String> keyFunction,
                                                     Function<T, byte[]> serializer,
                                                     Function<byte[], T> deserializer,
                                                     Duration ttl,
                                                     long maxEntries,
                                                     boolean compression,
                                                     boolean copyOnWrite) {
        try {
            builder.getClass()
                .getMethod("persistentSliceCache", String.class, Function.class, Function.class, Function.class,
                    Duration.class, long.class, boolean.class, boolean.class)
                .invoke(builder, cacheName, keyFunction, serializer, deserializer, ttl, maxEntries, compression, copyOnWrite);
            return;
        } catch (ReflectiveOperationException ignored) {
        }
        builder.persistentSliceCache(cacheName, keyFunction, serializer, deserializer, ttl, maxEntries, compression);
    }

    private static <T> void configureMemoryCache(ParallelCompute.Builder<Integer, T, List<T>> builder,
                                                 String cacheName,
                                                 Function<Integer, String> keyFunction,
                                                 Function<T, byte[]> serializer,
                                                 Function<byte[], T> deserializer,
                                                 Duration ttl,
                                                 long maxEntries,
                                                 boolean copyOnWrite) {
        try {
            builder.getClass()
                .getMethod("memorySliceCache", String.class, Function.class, Function.class, Function.class,
                    Duration.class, long.class, boolean.class)
                .invoke(builder, cacheName, keyFunction, serializer, deserializer, ttl, maxEntries, copyOnWrite);
            return;
        } catch (ReflectiveOperationException ignored) {
        }
        builder.memorySliceCache(cacheName, keyFunction, serializer, deserializer, ttl, maxEntries);
    }

    private static <T> CompletableFuture<List<T>> submitWithQueueFullHandling(String name,
                                                                              int sliceCount,
                                                                              long startNanos,
                                                                              List<Supplier<T>> suppliers,
                                                                              Consumer<ParallelSliceResult<T>> sliceListener,
                                                                              ParallelWorkOptions<T> options,
                                                                              ParallelCompute.Builder<Integer, T, List<T>> builder,
                                                                              int attempt) {
        final CompletableFuture<List<T>> submitted;
        try {
            submitted = builder.submit();
        } catch (Throwable t) {
            Throwable root = unwrap(t);
            if (isQueueFull(root)) {
                return handleQueueFull(name, suppliers, sliceListener, options, attempt, root);
            }
            CompletableFuture<List<T>> failed = new CompletableFuture<>();
            failed.completeExceptionally(root);
            return failed;
        }

        return submitted.handle((result, throwable) -> {
            if (throwable == null) {
                recordTiming(name, sliceCount, System.nanoTime() - startNanos);
                return CompletableFuture.completedFuture(result);
            }
            Throwable root = unwrap(throwable);
            if (isQueueFull(root)) {
                return handleQueueFull(name, suppliers, sliceListener, options, attempt, root);
            }
            CompletableFuture<List<T>> failed = new CompletableFuture<>();
            failed.completeExceptionally(root);
            return failed;
        }).thenCompose(Function.identity());
    }

    private static <T> CompletableFuture<List<T>> handleQueueFull(String name,
                                                                  List<Supplier<T>> suppliers,
                                                                  Consumer<ParallelSliceResult<T>> sliceListener,
                                                                  ParallelWorkOptions<T> options,
                                                                  int attempt,
                                                                  Throwable root) {
        rateLimitedQueueFullLog(name, suppliers.size(), attempt, root);

        if (attempt < QUEUE_FULL_RETRIES) {
            CompletableFuture<List<T>> retryFuture = new CompletableFuture<>();
            long delayMs = QUEUE_FULL_BASE_DELAY_MS * (long) (attempt + 1);
            BACKOFF_TIMER.schedule(() -> {
                try {
                    dispatchInternal(name, suppliers, sliceListener, options, attempt + 1)
                        .whenComplete((result, throwable) -> {
                        if (throwable != null) {
                            retryFuture.completeExceptionally(throwable);
                        } else {
                            retryFuture.complete(result);
                        }
                    });
                } catch (Throwable t) {
                    retryFuture.completeExceptionally(t);
                }
            }, delayMs, TimeUnit.MILLISECONDS);
            return retryFuture;
        }

        // Ultimate fallback: run the batch on the normal LC2H async scheduler.
        // This keeps progress even under Quantified parallel queue saturation.
        return AsyncManager.submitSupplier("queuefull-fallback-" + (name == null ? "work" : name), () -> {
            List<T> out = new ArrayList<>(suppliers.size());
            for (int i = 0; i < suppliers.size(); i++) {
                T value = suppliers.get(i).get();
                if (sliceListener != null) {
                    sliceListener.accept(new ParallelSliceResult<>(i, value));
                }
                out.add(value);
            }
            return Collections.unmodifiableList(out);
        }, Priority.LOW);
    }

    private static void rateLimitedQueueFullLog(String name, int sliceCount, int attempt, Throwable root) {
        long now = System.nanoTime();
        long last = LAST_QUEUE_FULL_LOG_NANOS.get();
        if (now - last < TimeUnit.SECONDS.toNanos(3)) {
            return;
        }
        if (!LAST_QUEUE_FULL_LOG_NANOS.compareAndSet(last, now)) {
            return;
        }
        String label = name == null ? "<unnamed>" : name;
        LC2H.LOGGER.warn("Quantified parallel queue full for '{}' (slices={}, attempt={}); applying backpressure", label, sliceCount, attempt);
        LC2H.LOGGER.debug("Queue-full root cause: {}", root.toString());
    }

    private static boolean isQueueFull(Throwable t) {
        if (!(t instanceof IllegalStateException)) {
            return false;
        }
        String msg = t.getMessage();
        return msg != null && msg.toLowerCase().contains("queue is full");
    }

    private static Throwable unwrap(Throwable t) {
        if (t instanceof CompletionException || t instanceof java.util.concurrent.ExecutionException) {
            Throwable c = t.getCause();
            return c == null ? t : unwrap(c);
        }
        return t;
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

    private static long estimateSequentialMs(String name, int sliceCount) {
        if (name == null || sliceCount <= 0) {
            return 0L;
        }
        AtomicLong ewma = EWMA_NANOS_PER_SLICE.get(name);
        if (ewma == null) {
            return 0L;
        }
        long nanosPerSlice = ewma.get();
        if (nanosPerSlice <= 0L) {
            return 0L;
        }
        long totalNanos = nanosPerSlice * (long) sliceCount;
        return totalNanos / 1_000_000L;
    }

    private static void recordTiming(String name, int sliceCount, long elapsedNanos) {
        if (name == null || sliceCount <= 0 || elapsedNanos <= 0L) {
            return;
        }
        long samplePerSlice = Math.max(1L, elapsedNanos / (long) sliceCount);
        AtomicLong ewma = EWMA_NANOS_PER_SLICE.computeIfAbsent(name, k -> new AtomicLong(0L));
        while (true) {
            long old = ewma.get();
            long updated = old == 0L ? samplePerSlice : (old - (old >> 3)) + (samplePerSlice >> 3);
            if (ewma.compareAndSet(old, updated)) {
                return;
            }
        }
    }

    public static CompletableFuture<List<mcjty.lostcities.worldgen.lost.MultiChunk>> dispatchMultiChunkBatch(List<Supplier<mcjty.lostcities.worldgen.lost.MultiChunk>> suppliers) {
        return dispatch("multi-chunk-batch", suppliers, null);
    }

    public static CompletableFuture<Void> dispatchRunnables(String name, List<Runnable> runnables) {
        if (runnables.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        List<Supplier<Void>> suppliers = new ArrayList<>(runnables.size());
        for (Runnable runnable : runnables) {
            suppliers.add(() -> {
                runnable.run();
                return null;
            });
        }
        return dispatch(name, suppliers, null, ParallelWorkOptions.none()).thenApply(ignored -> null);
    }
}
