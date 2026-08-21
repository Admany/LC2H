package org.admany.lc2h.data.cache;

import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.ChunkHeightmap;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.admany.lc2h.worldgen.scope.WorldGenScope;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;

public final class AsyncHeightmapCoordinator {
    private static final int THREADS = Math.max(2, Integer.getInteger(
        "lc2h.heightmap.asyncThreads",
        Math.min(8, Math.max(2, Runtime.getRuntime().availableProcessors() / 2))
    ));
    private static final int CACHE_LIMIT = Math.max(2048,
        Integer.getInteger("lc2h.heightmap.sharedCache", 16_384));
    private static final AtomicInteger THREAD_ID = new AtomicInteger();
    private static final ThreadFactory THREAD_FACTORY = task -> {
        Thread thread = new Thread(task, "lc2h-heightmap-" + THREAD_ID.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    };
    private static final ThreadPoolExecutor EXECUTOR = new ThreadPoolExecutor(
        THREADS,
        THREADS,
        30L,
        TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(Math.max(256, Integer.getInteger("lc2h.heightmap.asyncQueue", 2048))),
        THREAD_FACTORY,
        new ThreadPoolExecutor.AbortPolicy()
    );
    private static final ConcurrentHashMap<WorldGenScope.CacheScope, State> STATES = new ConcurrentHashMap<>();
    private static volatile long activeLifecycle = Long.MIN_VALUE;

    private AsyncHeightmapCoordinator() {
    }

    public static ChunkHeightmap get(WorldGenScope.CacheScope scope,
                                     ChunkCoord requested,
                                     SamplePlan sample,
                                     Function<SamplePlan, ChunkHeightmap> calculation) {
        pruneOldLifecycles(scope.lifecycleId());
        State state = STATES.computeIfAbsent(scope, ignored -> new State());
        ChunkHeightmap cached = state.completed.get(requested);
        if (cached != null) {
            return cached;
        }

        CompletableFuture<ChunkHeightmap> created = new CompletableFuture<>();
        CompletableFuture<ChunkHeightmap> flight = state.flights.putIfAbsent(sample.flightKey(), created);
        if (flight == null) {
            flight = created;
            /*
             * getHeightmap() is a hard Lost Cities dependency. Scheduling its
             * cold result elsewhere and immediately joining it only turns a
             * normal calculation into a cross-thread stall. Calculate on the
             * owning worldgen caller, then publish the immutable result for
             * later chunks. Speculative prefetch remains async below.
             */
            calculateAndPublish(state, sample, calculation, created);
        } else {
            ChunkHeightmap completed = flight.getNow(null);
            if (completed == null) {
                /*
                 * Never park a worldgen worker behind an older speculative
                 * request. The exact calculation is deterministic, so a local
                 * fallback is safer than making startup wait on a foreign
                 * worker which may itself be yielding to worldgen.
                 */
                return calculation.apply(sample);
            }
        }
        ChunkHeightmap completed = state.completed.get(requested);
        return completed != null ? completed : flight.join();
    }

    public static void prefetch(WorldGenScope.CacheScope scope,
                                Iterable<SamplePlan> samples,
                                Function<SamplePlan, ChunkHeightmap> calculation) {
        pruneOldLifecycles(scope.lifecycleId());
        State state = STATES.computeIfAbsent(scope, ignored -> new State());
        for (SamplePlan sample : samples) {
            if (state.completed.containsKey(sample.sampler())) {
                continue;
            }
            CompletableFuture<ChunkHeightmap> created = new CompletableFuture<>();
            CompletableFuture<ChunkHeightmap> existing = state.flights.putIfAbsent(sample.flightKey(), created);
            if (existing == null) {
                try {
                    EXECUTOR.execute(() -> calculateAndPublish(state, sample, calculation, created));
                } catch (RejectedExecutionException rejected) {
                    // Prefetch is speculative. Drop it cleanly when the bounded
                    // queue is full so worldgen callers never inherit the work.
                    state.flights.remove(sample.flightKey(), created);
                }
            }
        }
    }

    private static void calculateAndPublish(State state,
                                            SamplePlan sample,
                                            Function<SamplePlan, ChunkHeightmap> calculation,
                                            CompletableFuture<ChunkHeightmap> flight) {
        try {
            ChunkHeightmap heightmap = calculation.apply(sample);
            if (sample.size() > 1) {
                for (int x = 0; x < sample.size(); x++) {
                    for (int z = 0; z < sample.size(); z++) {
                        ChunkCoord key = new ChunkCoord(
                            sample.sampler().dimension(),
                            sample.top() + x * sample.directionX(),
                            sample.left() + z * sample.directionZ()
                        );
                        publish(state, key, new ChunkHeightmap(heightmap));
                    }
                }
            } else {
                publish(state, sample.sampler(), heightmap);
            }
            trim(state);
            flight.complete(heightmap);
        } catch (Throwable failure) {
            flight.completeExceptionally(failure);
        } finally {
            state.flights.remove(sample.flightKey(), flight);
        }
    }

    private static void publish(State state, ChunkCoord key, ChunkHeightmap value) {
        ChunkHeightmap previous = state.completed.putIfAbsent(key, value);
        state.order.add(new Entry(key, previous == null ? value : previous));
    }

    private static void trim(State state) {
        while (state.completed.size() > CACHE_LIMIT) {
            Entry oldest = state.order.poll();
            if (oldest == null) {
                return;
            }
            state.completed.remove(oldest.key(), oldest.value());
        }
    }

    private static void pruneOldLifecycles(long lifecycle) {
        if (activeLifecycle == lifecycle) {
            return;
        }
        activeLifecycle = lifecycle;
        STATES.keySet().removeIf(scope -> scope.lifecycleId() != lifecycle);
    }

    public record SamplePlan(ChunkCoord sampler,
                             int top,
                             int left,
                             int directionX,
                             int directionZ,
                             int size,
                             FlightKey flightKey) {
    }

    public record FlightKey(ResourceKey<Level> dimension,
                            int top,
                            int left,
                            int directionX,
                            int directionZ,
                            int size) {
    }

    private record Entry(ChunkCoord key, ChunkHeightmap value) {
    }

    private static final class State {
        private final ConcurrentHashMap<ChunkCoord, ChunkHeightmap> completed = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<FlightKey, CompletableFuture<ChunkHeightmap>> flights = new ConcurrentHashMap<>();
        private final ConcurrentLinkedQueue<Entry> order = new ConcurrentLinkedQueue<>();
    }
}
