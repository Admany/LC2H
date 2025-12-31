package org.admany.lc2h.worldgen.async.planner;

import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.IDimensionInfo;
import mcjty.lostcities.worldgen.lost.MultiChunk;
import org.admany.lc2h.LC2H;
import org.admany.lc2h.mixin.accessor.MultiChunkAccessor;
import org.admany.lc2h.mixin.accessor.MultiChunkInvoker;
import org.admany.lc2h.parallel.AdaptiveBatchController;
import org.admany.lc2h.parallel.AdaptiveConcurrencyLimiter;
import org.admany.lc2h.parallel.ParallelWorkOptions;
import org.admany.lc2h.parallel.ParallelWorkQueue;
import org.admany.lc2h.worldgen.async.snapshot.MultiChunkSnapshot;
import org.admany.lc2h.worldgen.gpu.GPUMemoryManager;
import org.admany.lc2h.worldgen.async.warmup.AsyncChunkWarmup;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.resources.ResourceKey;

public final class AsyncMultiChunkPlanner {

    private static final ConcurrentHashMap<ChunkCoord, CompletableFuture<MultiChunk>> PLANNED = new ConcurrentHashMap<>();
    private static final ThreadLocal<Integer> INTERNAL_CALL_DEPTH = ThreadLocal.withInitial(() -> 0);
    private static final ConcurrentHashMap<ChunkCoord, Boolean> WARM_BUILDING_INFO_SUBMITTED = new ConcurrentHashMap<>();
    private static final Semaphore WARM_SEMAPHORE = new Semaphore(2);

    private static final int MULTICHUNK_PARALLELISM_OVERRIDE = Integer.getInteger("lc2h.multichunk.parallelism", -1);
    private static final int MULTICHUNK_MAX = Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors()));
    private static final AdaptiveConcurrencyLimiter MULTICHUNK_LIMITER = new AdaptiveConcurrencyLimiter(2, 1, MULTICHUNK_MAX);

    public static final ConcurrentHashMap<ChunkCoord, float[]> GPU_DATA_CACHE = new ConcurrentHashMap<>();

    private static final java.util.List<java.util.function.Supplier<MultiChunk>> PENDING_SUPPLIERS = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    private static final java.util.List<CompletableFuture<MultiChunk>> PENDING_FUTURES = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    private static final java.util.List<ChunkCoord> PENDING_KEYS = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    private static final java.util.List<IDimensionInfo> PENDING_PROVIDERS = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    private static final int MAX_PENDING = 128;
    private static final AtomicBoolean PENDING_FLUSH_SCHEDULED = new AtomicBoolean(false);
    private static final long PENDING_FLUSH_DELAY_MS = 15;
    private static final long PENDING_FLUSH_DELAY_LATENCY_SENSITIVE_MS = 15;

    private AsyncMultiChunkPlanner() {
    }

    public static MultiChunk tryConsumePrepared(IDimensionInfo provider, ChunkCoord coord) {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(coord, "coord");

        ChunkCoord multiCoord = toMultiCoord(provider, coord);
        Map<ChunkCoord, MultiChunk> cache = MultiChunkAccessor.lc2h$getCache();
        synchronized (cache) {
            MultiChunk existing = cache.get(multiCoord);
            if (existing != null) {
                return existing;
            }
        }

        CompletableFuture<MultiChunk> future = PLANNED.get(multiCoord);
        if (future != null && future.isDone() && !future.isCompletedExceptionally() && !future.isCancelled()) {
            MultiChunk prepared = future.getNow(null);
            if (prepared != null) {
                return integrateResult(provider, multiCoord, prepared);
            }
        }
        return null;
    }

    public static MultiChunk resolve(IDimensionInfo provider, ChunkCoord coord) {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(coord, "coord");

        int areaSize = provider.getWorldStyle().getMultiSettings().areasize();
        ChunkCoord multiCoord = toMultiCoord(coord, areaSize);

        Map<ChunkCoord, MultiChunk> cache = MultiChunkAccessor.lc2h$getCache();
        synchronized (cache) {
            MultiChunk existing = cache.get(multiCoord);
            if (existing != null) {
                return existing;
            }
        }
        if (!isInternalComputation()) {
            CompletableFuture<MultiChunk> future = PLANNED.computeIfAbsent(multiCoord, key -> submitMultiChunkCompute(provider, areaSize, key));
            try {
                if (!future.isCancelled() && !future.isCompletedExceptionally()) {
                    MultiChunk prepared = future.getNow(null);
                    if (prepared == null && !future.isDone()) {
                        prepared = future.join();
                    }
                    if (prepared != null) {
                        return integrateResult(provider, multiCoord, prepared);
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        MultiChunk computed = executeInternal(() -> computeMultiChunk(provider, areaSize, multiCoord));
        return integrateResult(provider, multiCoord, computed);
    }

    public static void ensureScheduled(IDimensionInfo provider, ChunkCoord coord) {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(coord, "coord");

        if (isInternalComputation()) {
            return;
        }

        int areaSize = provider.getWorldStyle().getMultiSettings().areasize();
        ChunkCoord multiCoord = toMultiCoord(coord, areaSize);

        Map<ChunkCoord, MultiChunk> cache = MultiChunkAccessor.lc2h$getCache();
        synchronized (cache) {
            if (cache.containsKey(multiCoord)) {
                return;
            }
        }

        PLANNED.computeIfAbsent(multiCoord, key -> submitMultiChunkCompute(provider, areaSize, key));
    }

    public static void onSynchronousResult(IDimensionInfo provider, ChunkCoord coord, MultiChunk multiChunk) {
        if (provider == null || coord == null || multiChunk == null || isInternalComputation()) {
            return;
        }

        try {
            if (!org.admany.lc2h.util.server.ServerRescheduler.isServerAvailable()) {
                return;
            }
        } catch (Throwable ignored) {}

        int areaSize = provider.getWorldStyle().getMultiSettings().areasize();
        ChunkCoord multiCoord = toMultiCoord(coord, areaSize);

        Map<ChunkCoord, MultiChunk> cache = MultiChunkAccessor.lc2h$getCache();
        synchronized (cache) {
            if (!cache.containsKey(multiCoord)) {
                cache.put(multiCoord, multiChunk);
            }
        }
        PLANNED.remove(multiCoord);

        try {
            ChunkCoord topLeft = new ChunkCoord(multiCoord.dimension(), multiCoord.chunkX() * areaSize, multiCoord.chunkZ() * areaSize);
            org.admany.lc2h.util.lostcities.BuildingInfoCacheInvalidator.invalidateArea(topLeft, areaSize);
            AsyncBuildingInfoPlanner.invalidateArea(topLeft, areaSize);
        } catch (Throwable ignored) {
        }

        scheduleWarmBuildingInfo(provider, multiChunk, multiCoord);
    }

    public static void preSchedule(IDimensionInfo provider, ChunkCoord coord) {
        if (provider == null || coord == null) {
            return;
        }

        boolean debugLogging = AsyncChunkWarmup.isWarmupDebugLoggingEnabled();

        if (GPUMemoryManager.getGPUData(coord, GPU_DATA_CACHE) != null) {
            if (debugLogging) {
                LC2H.LOGGER.debug("Used GPU data for multichunk planning in {}", coord);
            }
            return;
        }

        if (debugLogging) {
            LC2H.LOGGER.debug("Starting preSchedule for {}", coord);
        }
        long startTime = System.nanoTime();

        try {
            ensureScheduled(provider, coord);
            long endTime = System.nanoTime();
            if (debugLogging) {
                LC2H.LOGGER.debug("Finished preSchedule for {} in {} ms", coord, (endTime - startTime) / 1_000_000);
            }
        } catch (Throwable t) {
            LC2H.LOGGER.error("Synchronous multichunk planning failed for {}: {}", coord, t.getMessage());
            throw t;
        }
    }

    public static int getPlannedCount() {
        return PLANNED.size();
    }

    public static int getGpuDataCacheSize() {
        return GPU_DATA_CACHE.size();
    }

    public static void flushPendingBatches() {
        synchronized (PENDING_SUPPLIERS) {
            if (!PENDING_SUPPLIERS.isEmpty()) {
                submitBatch();
            }
        }
    }

    public static void shutdown() {
        try {
            LC2H.LOGGER.info("AsyncMultiChunkPlanner: Shutting down warmup executor");

            synchronized (PENDING_SUPPLIERS) {
                for (CompletableFuture<MultiChunk> future : PENDING_FUTURES) {
                    future.cancel(true);
                }
                PENDING_SUPPLIERS.clear();
                PENDING_FUTURES.clear();
                PENDING_KEYS.clear();
                PENDING_PROVIDERS.clear();
            }

            PLANNED.clear();
            WARM_BUILDING_INFO_SUBMITTED.clear();
            GPU_DATA_CACHE.clear();

            LC2H.LOGGER.info("AsyncMultiChunkPlanner: Shutdown complete");
        } catch (Exception e) {
            LC2H.LOGGER.error("AsyncMultiChunkPlanner: Error during shutdown", e);
        }
    }

    private static MultiChunk computeMultiChunkSync(IDimensionInfo provider, int areaSize, ChunkCoord multiCoord) {
        return executeInternal(() -> computeMultiChunk(provider, areaSize, multiCoord));
    }

    private static ChunkCoord toMultiCoord(IDimensionInfo provider, ChunkCoord coord) {
        int areaSize = provider.getWorldStyle().getMultiSettings().areasize();
        return toMultiCoord(coord, areaSize);
    }

    private static ChunkCoord toMultiCoord(ChunkCoord coord, int areaSize) {
        return new ChunkCoord(coord.dimension(), Math.floorDiv(coord.chunkX(), areaSize), Math.floorDiv(coord.chunkZ(), areaSize));
    }

    private static MultiChunk computeMultiChunk(IDimensionInfo provider, int areaSize, ChunkCoord multiCoord) {
        if (MULTICHUNK_PARALLELISM_OVERRIDE > 0) {
            MULTICHUNK_LIMITER.setLimit(MULTICHUNK_PARALLELISM_OVERRIDE);
        }

        AdaptiveConcurrencyLimiter.Token token = MULTICHUNK_LIMITER.enter();
        long start = System.nanoTime();
        try {
            boolean trace = Boolean.parseBoolean(System.getProperty("lc2h.multichunkTrace", "false"));
            if (trace || org.admany.lc2h.logging.config.ConfigManager.ENABLE_DEBUG_LOGGING) {
                try {
                    var settings = provider.getWorldStyle().getMultiSettings();
                    LC2H.LOGGER.debug("[AsyncMultiChunkPlanner] computeMultiChunk start coord={} areaSize={} min={} max={} attempts={} correctStyleFactor={}",
                        multiCoord, areaSize,
                        settings.minimum(), settings.maximum(), settings.attempts(), settings.correctStyleFactor());
                } catch (Throwable ignored) {
                }
            }

            MultiChunk multiChunk = new MultiChunk(multiCoord, areaSize);
            MultiChunk result = ((MultiChunkInvoker) multiChunk).lc2h$calculateBuildings(provider);

            long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
            if (elapsedMs >= 500L) {
                LC2H.LOGGER.info("[AsyncMultiChunkPlanner] computeMultiChunk({}) finished in {} ms", multiCoord, elapsedMs);
            } else if (trace || org.admany.lc2h.logging.config.ConfigManager.ENABLE_DEBUG_LOGGING) {
                LC2H.LOGGER.debug("[AsyncMultiChunkPlanner] computeMultiChunk({}) finished in {} ms", multiCoord, elapsedMs);
            }
            return result;
        } finally {
            token.close();
        }
    }

    private static MultiChunk integrateResult(IDimensionInfo provider, ChunkCoord multiCoord, MultiChunk prepared) {
        return integrateResult(provider, multiCoord, prepared, null);
    }

    private static MultiChunk integrateResult(IDimensionInfo provider, ChunkCoord multiCoord, MultiChunk prepared, java.util.function.Consumer<Runnable> runnableCollector) {
        MultiChunk gameCompatible = translateToGameCompatible(prepared, provider);

        Runnable applicator = () -> {
            Map<ChunkCoord, MultiChunk> cache = MultiChunkAccessor.lc2h$getCache();
            synchronized (cache) {
                if (!cache.containsKey(multiCoord)) {
                    cache.put(multiCoord, gameCompatible);
                }
            }

            try {
                int areaSize = provider.getWorldStyle().getMultiSettings().areasize();
                ChunkCoord topLeft = new ChunkCoord(multiCoord.dimension(), multiCoord.chunkX() * areaSize, multiCoord.chunkZ() * areaSize);
                org.admany.lc2h.util.lostcities.BuildingInfoCacheInvalidator.invalidateArea(topLeft, areaSize);
                org.admany.lc2h.worldgen.async.planner.AsyncBuildingInfoPlanner.invalidateArea(topLeft, areaSize);
            } catch (Throwable ignored) {
            }

            PLANNED.remove(multiCoord);
            scheduleWarmBuildingInfo(provider, gameCompatible, multiCoord);
        };

        if (runnableCollector != null) {
            runnableCollector.accept(applicator);
        } else {
            String threadName = Thread.currentThread().getName();
            if ("Server thread".equals(threadName)) {
                applicator.run();
            } else {
                org.admany.lc2h.core.MainThreadChunkApplier.enqueueChunkApplication(multiCoord, applicator);
            }
        }
        return gameCompatible;
    }

    private static MultiChunk translateToGameCompatible(MultiChunk asyncResult, IDimensionInfo provider) {
        if (asyncResult == null) {
            return null;
        }
        try {
            byte[] snapshot = MultiChunkSnapshot.encode(asyncResult);
            if (snapshot != null) {
                MultiChunk decoded = MultiChunkSnapshot.decode(snapshot);
                if (decoded != null) {
                    return decoded;
                }
            }
        } catch (Throwable ignored) {
        }

        return asyncResult;
    }

    private static void warmBuildingInfo(IDimensionInfo provider, MultiChunk prepared) {
        boolean acquired = false;
        try {
            WARM_SEMAPHORE.acquireUninterruptibly();
            acquired = true;
            MultiChunkAccessor accessor = (MultiChunkAccessor) prepared;
            ChunkCoord topLeft = accessor.lc2h$getTopLeft();
            int areaSize = accessor.lc2h$getAreaSize();

            for (int dx = 0; dx < areaSize; dx++) {
                for (int dz = 0; dz < areaSize; dz++) {
                    ChunkCoord target = new ChunkCoord(topLeft.dimension(), topLeft.chunkX() + dx, topLeft.chunkZ() + dz);
                    AsyncBuildingInfoPlanner.syncWarmup(provider, target);
                }
            }
        } finally {
            if (acquired) {
                WARM_SEMAPHORE.release();
            }
        }
    }

    private static <T> T executeInternal(java.util.function.Supplier<T> supplier) {
        INTERNAL_CALL_DEPTH.set(INTERNAL_CALL_DEPTH.get() + 1);
        try {
            return supplier.get();
        } finally {
            INTERNAL_CALL_DEPTH.set(INTERNAL_CALL_DEPTH.get() - 1);
        }
    }

    public static void syncWarmup(IDimensionInfo provider, ChunkCoord coord) {
        if (provider == null || coord == null) {
            return;
        }

        int areaSize = provider.getWorldStyle().getMultiSettings().areasize();
        ChunkCoord multiCoord = toMultiCoord(coord, areaSize);

        Map<ChunkCoord, MultiChunk> cache = MultiChunkAccessor.lc2h$getCache();
        synchronized (cache) {
            if (cache.containsKey(multiCoord)) {
                return;
            }
        }

        try {
            MultiChunk computed = computeMultiChunkSync(provider, areaSize, multiCoord);
            integrateResult(provider, multiCoord, computed);
        } catch (Throwable t) {
            LC2H.LOGGER.error("Sync warmup failed for {}: {}", coord, t.getMessage());
        }
    }

    public static boolean isInternalComputation() {
        return INTERNAL_CALL_DEPTH.get() > 0;
    }

    private static CompletableFuture<MultiChunk> submitMultiChunkCompute(IDimensionInfo provider, int areaSize, ChunkCoord key) {
        java.util.function.Supplier<MultiChunk> supplier = () -> computeMultiChunk(provider, areaSize, key);
        CompletableFuture<MultiChunk> future = new CompletableFuture<>();

        synchronized (PENDING_SUPPLIERS) {
            if (PENDING_SUPPLIERS.size() >= MAX_PENDING) {
                LC2H.LOGGER.debug("Pending suppliers at limit (" + MAX_PENDING + "), forcing flush before enqueue for " + key);
                submitBatch();
            }
            PENDING_SUPPLIERS.add(supplier);
            PENDING_FUTURES.add(future);
            PENDING_KEYS.add(key);
            PENDING_PROVIDERS.add(provider);

            int dynamicBatchSize = Math.max(8, AdaptiveBatchController.multiChunkBatchSize());
            if (PENDING_SUPPLIERS.size() >= dynamicBatchSize) {
                submitBatch();
            } else {
                long delay = isLatencySensitiveThread() ? PENDING_FLUSH_DELAY_LATENCY_SENSITIVE_MS : PENDING_FLUSH_DELAY_MS;
                schedulePendingFlush(delay);
            }
        }

        return future;
    }

    private static void submitBatch() {
        java.util.List<java.util.function.Supplier<MultiChunk>> suppliers = new java.util.ArrayList<>(PENDING_SUPPLIERS);
        java.util.List<CompletableFuture<MultiChunk>> futures = new java.util.ArrayList<>(PENDING_FUTURES);
        java.util.List<ChunkCoord> keys = new java.util.ArrayList<>(PENDING_KEYS);
        java.util.List<IDimensionInfo> providers = new java.util.ArrayList<>(PENDING_PROVIDERS);

        PENDING_SUPPLIERS.clear();
        PENDING_FUTURES.clear();
        PENDING_KEYS.clear();
        PENDING_PROVIDERS.clear();

        LC2H.LOGGER.debug("Submitting batched multi-chunk compute with " + suppliers.size() + " tasks");

        try {
            org.admany.lc2h.worldgen.gpu.GPUMemoryManager.continuousCleanup();
        } catch (Throwable t) {
            LC2H.LOGGER.debug("Skipped GPU cleanup before batch submit: {}", t.toString());
        }

        ParallelWorkOptions<MultiChunk> options = buildMultiChunkCacheOptions(keys, providers);
        ParallelWorkQueue.dispatch("multi-chunk-batch", suppliers, event -> {
                MultiChunk result = event.result();
                int index = event.index();
                CompletableFuture<MultiChunk> future = futures.get(index);
                ChunkCoord key = keys.get(index);
                if (result != null) {
                    future.complete(result);
                } else {
                    future.completeExceptionally(new RuntimeException("Batch compute failed for " + key));
                }
            }, options)
            .thenAccept(results -> {
                scheduleBatchIntegration(providers, keys, results);
                GPUMemoryManager.continuousCleanup();
            }).exceptionally(t -> {
                Throwable root = t;
                while (root instanceof java.util.concurrent.CompletionException && root.getCause() != null) {
                    root = root.getCause();
                }
                LC2H.LOGGER.error("Batched multi-chunk compute failed (tasks={}, firstKey={})", keys.size(), keys.isEmpty() ? "<none>" : keys.get(0), root);
                for (CompletableFuture<MultiChunk> f : futures) {
                    f.completeExceptionally(t);
                }
                return null;
            });
    }

    private static void scheduleBatchIntegration(java.util.List<IDimensionInfo> providers,
                                                 java.util.List<ChunkCoord> keys,
                                                 java.util.List<MultiChunk> results) {
        if (providers == null || keys == null || results == null) {
            return;
        }
        int total = Math.min(Math.min(providers.size(), keys.size()), results.size());
        if (total <= 0) {
            return;
        }

        final java.util.concurrent.atomic.AtomicInteger cursor = new java.util.concurrent.atomic.AtomicInteger(0);
        final int step = Math.max(64, Integer.getInteger("lc2h.batchIntegration.step", 256));
        Runnable integrator = new Runnable() {
            @Override
            public void run() {
                int start = cursor.getAndAdd(step);
                if (start >= total) {
                    return;
                }
                int end = Math.min(total, start + step);
                for (int i = start; i < end; i++) {
                    MultiChunk result = results.get(i);
                    if (result == null) {
                        continue;
                    }
                    ChunkCoord key = keys.get(i);
                    IDimensionInfo provider = providers.get(i);
                    try {
                        integrateResult(provider, key, result);
                    } catch (Throwable t) {
                        LC2H.LOGGER.debug("Batch integration failed for {}: {}", key, t.getMessage());
                    } finally {
                        try {
                            GPUMemoryManager.removeGPUData(key);
                        } catch (Throwable ignored) {
                        }
                    }
                }
                if (end < total) {
                    org.admany.lc2h.util.server.ServerRescheduler.runOnServer(this);
                }
            }
        };

        org.admany.lc2h.util.server.ServerRescheduler.runOnServer(integrator);
    }

    private static void schedulePendingFlush(long delayMs) {
        if (!PENDING_FLUSH_SCHEDULED.compareAndSet(false, true)) {
            return;
        }
        long effectiveDelay = Math.max(0L, delayMs);
        org.admany.lc2h.async.AsyncManager.runLater("multichunk-pending-flush", () -> {
            try {
                synchronized (PENDING_SUPPLIERS) {
                    if (!PENDING_SUPPLIERS.isEmpty()) {
                        submitBatch();
                    }
                }
            } finally {
                PENDING_FLUSH_SCHEDULED.set(false);
            }
        }, effectiveDelay, org.admany.lc2h.async.Priority.LOW);
    }

    private static boolean isLatencySensitiveThread() {
        String name = Thread.currentThread().getName();
        if (name == null) return false;
        return "Server thread".equals(name)
            || name.contains("Render thread")
            || name.contains("Client thread")
            || name.contains("main");
    }

    private static ParallelWorkOptions<MultiChunk> buildMultiChunkCacheOptions(List<ChunkCoord> keys,
                                                                               List<IDimensionInfo> providers) {
        List<ChunkCoord> coordSnapshot = List.copyOf(keys);
        List<SeedDescriptor> seedSnapshot = providers == null ? List.of() : providers.stream()
            .map(AsyncMultiChunkPlanner::descriptorForProvider)
            .toList();
        return ParallelWorkOptions.persistentCache(
            "multichunk-snapshots",
            index -> {
                if (index == null || index < 0 || index >= coordSnapshot.size()) {
                    return null;
                }
                ChunkCoord coord = coordSnapshot.get(index);
                SeedDescriptor descriptor = index < seedSnapshot.size() ? seedSnapshot.get(index) : SeedDescriptor.UNKNOWN;
                return cacheKey(descriptor, coord);
            },
            MultiChunkSnapshot::encode,
            MultiChunkSnapshot::decode,
            Duration.ofMinutes(20),
            4096,
            true
        );
    }

    private static SeedDescriptor descriptorForProvider(IDimensionInfo provider) {
        if (provider == null) {
            return SeedDescriptor.UNKNOWN;
        }
        long seed = provider.getSeed();
        String dimensionId = "unknown";
        String profileName = "unknown";
        String worldStyleName = "unknown";
        String multiSettingsSignature = "unknown";
        try {
            ResourceKey<net.minecraft.world.level.Level> type = provider.getType();
            if (type != null) {
                dimensionId = String.valueOf(type.location());
            }
        } catch (Throwable ignored) {
        }
        try {
            var profile = provider.getProfile();
            if (profile != null && profile.getName() != null) {
                profileName = profile.getName();
            }
        } catch (Throwable ignored) {
        }
        try {
            var worldStyle = provider.getWorldStyle();
            if (worldStyle != null && worldStyle.getName() != null) {
                worldStyleName = worldStyle.getName();
            }
            if (worldStyle != null) {
                var settings = worldStyle.getMultiSettings();
                if (settings != null) {
                    multiSettingsSignature = settings.areasize() + ":"
                        + settings.minimum() + ":"
                        + settings.maximum() + ":"
                        + settings.attempts() + ":"
                        + String.format(java.util.Locale.ROOT, "%.3f", settings.correctStyleFactor());
                }
            }
        } catch (Throwable ignored) {
        }
        return new SeedDescriptor(dimensionId, seed, profileName, worldStyleName, multiSettingsSignature);
    }

    private static String cacheKey(SeedDescriptor descriptor, ChunkCoord coord) {
        String dimension = descriptor.dimension();
        long seed = descriptor.seed();
        String coordDim = coord != null && coord.dimension() != null ? String.valueOf(coord.dimension().location()) : "unknown";
        if (!"unknown".equals(coordDim)) {
            dimension = coordDim;
        }
        int chunkX = coord != null ? coord.chunkX() : 0;
        int chunkZ = coord != null ? coord.chunkZ() : 0;
        return dimension + ":" + seed + ":" + sanitize(descriptor.profile()) + ":" + sanitize(descriptor.worldStyle())
            + ":" + sanitize(descriptor.multiSettings()) + ":" + chunkX + ":" + chunkZ;
    }

    private record SeedDescriptor(String dimension, long seed, String profile, String worldStyle, String multiSettings) {
        private static final SeedDescriptor UNKNOWN = new SeedDescriptor("unknown", 0L, "unknown", "unknown", "unknown");
    }

    private static String sanitize(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value.replace(':', '_').replace('|', '_').replace(' ', '_');
    }

    private static void scheduleWarmBuildingInfo(IDimensionInfo provider, MultiChunk multiChunk, ChunkCoord multiCoord) {
        if (provider == null || multiChunk == null || multiCoord == null) {
            return;
        }

        if (WARM_BUILDING_INFO_SUBMITTED.putIfAbsent(multiCoord, Boolean.TRUE) != null) {
            return;
        }

        org.admany.lc2h.async.AsyncManager.submitTask("warmBuildingInfo", () -> warmBuildingInfo(provider, multiChunk), null, org.admany.lc2h.async.Priority.LOW)
            .whenComplete((ignored, throwable) -> {
                WARM_BUILDING_INFO_SUBMITTED.remove(multiCoord);
                if (throwable != null) {
                    LC2H.LOGGER.error("Warm building info failed for {}: {}", multiCoord, throwable.getMessage());
                }
            });
    }
}
