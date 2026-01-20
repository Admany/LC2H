package org.admany.lc2h.world.cleanup;

import mcjty.lostcities.setup.Registration;
import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.IDimensionInfo;
import mcjty.lostcities.worldgen.lost.BuildingInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.admany.lc2h.LC2H;
import org.admany.lc2h.concurrency.async.AsyncManager;
import org.admany.lc2h.data.cache.FeatureCache;
import org.admany.lc2h.util.server.ServerTickLoad;
import org.admany.quantified.api.QuantifiedAPI;
import org.admany.quantified.api.model.QuantifiedTask;
import org.admany.lc2h.concurrency.async.Priority;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Mod.EventBusSubscriber(modid = LC2H.MODID)
public final class VineClusterCleaner {
    private static volatile boolean initialized = false;
    private static volatile boolean shutdown = false;

    private static final int COMPONENT_LIMIT = 2048;
    private static final int MAX_CHUNKS_PER_SCAN = 64;
    private static final int MAX_REMOVALS_PER_TICK = Math.max(16, Integer.getInteger("lc2h.vine.max_removals_per_tick", 128));
    private static final long MIN_RESCAN_INTERVAL_MS = 15_000L;
    private static final int VINE_SCAN_BATCH_SIZE = 256;

    private static final Map<ResourceKey<net.minecraft.world.level.Level>, Integer> CHUNK_CURSOR = new ConcurrentHashMap<>();
    private static final Map<ResourceKey<net.minecraft.world.level.Level>, Map<Long, Long>> LAST_SCAN = new ConcurrentHashMap<>();

    private static final long SCAN_PERIOD_SECONDS = Long.getLong("lc2h.vine.scan_period_seconds", 2L);
    private static final long SNAPSHOT_TTL_NS = TimeUnit.SECONDS.toNanos(Long.getLong("lc2h.vine.snapshot_ttl_seconds", 5L));
    private static final int MAX_IN_FLIGHT = Math.max(1, Integer.getInteger("lc2h.vine.max_in_flight", 16));
    private static final AtomicInteger IN_FLIGHT = new AtomicInteger(0);
    private static final AtomicBoolean SCAN_REQUESTED = new AtomicBoolean(false);
    private static volatile MinecraftServer LAST_SERVER;

    private record RemovalTask(ServerLevel level, BlockPos pos) {}
    private static final ConcurrentLinkedQueue<RemovalTask> REMOVAL_QUEUE = new ConcurrentLinkedQueue<>();
    private static final AtomicBoolean REMOVAL_DRAIN_SCHEDULED = new AtomicBoolean(false);

    private static final class LevelChunkIndex {
        final ConcurrentHashMap<Long, Boolean> loaded = new ConcurrentHashMap<>();
        final AtomicBoolean dirty = new AtomicBoolean(true);
        volatile long[] snapshot = new long[0];
        volatile long snapshotNs = 0L;
    }

    private static final Map<ResourceKey<net.minecraft.world.level.Level>, LevelChunkIndex> LOADED_CHUNKS = new ConcurrentHashMap<>();

    private VineClusterCleaner() {
    }

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (shutdown) {
            return;
        }
        if (!(event.getChunk() instanceof LevelChunk chunk)) {
            return;
        }
        if (!(chunk.getLevel() instanceof ServerLevel level)) {
            return;
        }

        ResourceKey<net.minecraft.world.level.Level> dim = level.dimension();
        LevelChunkIndex idx = LOADED_CHUNKS.computeIfAbsent(dim, d -> new LevelChunkIndex());
        idx.loaded.put(chunk.getPos().toLong(), Boolean.TRUE);
        idx.dirty.set(true);
    }

    @SubscribeEvent
    public static void onChunkUnload(ChunkEvent.Unload event) {
        if (shutdown) {
            return;
        }
        if (!(event.getChunk() instanceof LevelChunk chunk)) {
            return;
        }
        if (!(chunk.getLevel() instanceof ServerLevel level)) {
            return;
        }

        ResourceKey<net.minecraft.world.level.Level> dim = level.dimension();
        LevelChunkIndex idx = LOADED_CHUNKS.get(dim);
        if (idx == null) {
            return;
        }
        idx.loaded.remove(chunk.getPos().toLong());
        idx.dirty.set(true);
    }

    public static void initialize(MinecraftServer server) {
        if (initialized || shutdown) {
            return;
        }
        if (server == null) {
            return;
        }

        initialized = true;
        if (startQuantifiedLoop(server)) {
            return;
        }

        ScheduledExecutorService fallbackScheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "LC2H-VineCleaner");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
        fallbackScheduler.scheduleAtFixedRate(() -> runTurboScan(server), SCAN_PERIOD_SECONDS, SCAN_PERIOD_SECONDS, TimeUnit.SECONDS);
        if (org.admany.lc2h.config.ConfigManager.ENABLE_DEBUG_LOGGING) {
            LC2H.LOGGER.info("[LC2H] VineClusterCleaner initialized with fallback scheduler (every {}s, auto-managed)", SCAN_PERIOD_SECONDS);
        } else {
            LC2H.LOGGER.debug("[LC2H] VineClusterCleaner initialized with fallback scheduler (every {}s, auto-managed)", SCAN_PERIOD_SECONDS);
        }
    }

    public static void cleanVinesOnFirstLoad(ServerLevel level, ChunkPos chunkPos) {
        if (shutdown || level == null || chunkPos == null) {
            return;
        }

        IDimensionInfo dimInfo = getDimensionInfo(level);
        if (dimInfo == null) {
            return;
        }

        MinecraftServer server = level.getServer();
        if (server != null && ServerTickLoad.shouldPauseNonCritical(server)) {
            return;
        }

        if (!initialized) {
            if (server != null) {
                initialize(server);
            }
        }

        // This ensures the chunk is in the loaded index even if events were missed.
        LevelChunkIndex idx = LOADED_CHUNKS.computeIfAbsent(level.dimension(), d -> new LevelChunkIndex());
        idx.loaded.put(chunkPos.toLong(), Boolean.TRUE);
        idx.dirty.set(true);

        LevelChunk chunk = level.getChunkSource().getChunkNow(chunkPos.x, chunkPos.z);
        if (chunk == null) {
            return;
        }

        String cacheKey = vineScanKey(level.dimension(), chunkPos.x, chunkPos.z);
        if (FeatureCache.get(cacheKey) != null) {
            return;
        }

        String skipKey = vineSkipKey(level.dimension(), chunkPos.x, chunkPos.z);
        if (FeatureCache.get(skipKey) != null) {
            return;
        }

        if (!isCityChunk(dimInfo, level.dimension(), chunkPos.x, chunkPos.z)) {
            FeatureCache.put(skipKey, Boolean.TRUE);
            return;
        }

        long now = System.currentTimeMillis();
        Map<Long, Long> lastScanForLevel = LAST_SCAN.computeIfAbsent(level.dimension(), key -> new ConcurrentHashMap<>());
        Long prevScan = lastScanForLevel.get(chunkPos.toLong());
        if (prevScan != null && (now - prevScan) < MIN_RESCAN_INTERVAL_MS) {
            return;
        }

        if (scanChunkForVinesAsync(level, chunk)) {
            lastScanForLevel.put(chunkPos.toLong(), now);
        }
    }

    private static void runTurboScan(MinecraftServer server) {
        LAST_SERVER = server;
        SCAN_REQUESTED.set(true);
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (shutdown || event.phase != TickEvent.Phase.END) {
            return;
        }
        if (!SCAN_REQUESTED.compareAndSet(true, false)) {
            return;
        }
        MinecraftServer server = event.getServer();
        if (server == null) {
            server = LAST_SERVER;
        }
        if (server == null) {
            return;
        }
        try {
            runTurboScanOnServerThread(server);
        } catch (Throwable t) {
            LC2H.LOGGER.error("[LC2H] Failed to submit VineClusterCleaner scan", t);
        }
    }

    private static boolean startQuantifiedLoop(MinecraftServer server) {
        try {
            scheduleNextQuantifiedScan(server);
            if (org.admany.lc2h.config.ConfigManager.ENABLE_DEBUG_LOGGING) {
                LC2H.LOGGER.info("[LC2H] VineClusterCleaner initialized using Quantified API scheduler (every {}s, auto-managed)", SCAN_PERIOD_SECONDS);
            } else {
                LC2H.LOGGER.debug("[LC2H] VineClusterCleaner initialized using Quantified API scheduler (every {}s, auto-managed)", SCAN_PERIOD_SECONDS);
            }
            return true;
        } catch (Throwable t) {
            LC2H.LOGGER.debug("[LC2H] Quantified API scheduler unavailable, falling back to dedicated thread", t);
            return false;
        }
    }

    private static void scheduleNextQuantifiedScan(MinecraftServer server) {
        if (shutdown) return;
        
        CompletableFuture<Void> future = QuantifiedAPI.submit(
                QuantifiedTask.<Void>builder(LC2H.MODID, "vine_cleaner_tick", () -> {
                    runTurboScan(server);
                    return null;
                })
        );

        future.whenComplete((r, t) -> {
            if (!shutdown) {
                AsyncManager.runLater("vine-cleaner-reschedule", () -> scheduleNextQuantifiedScan(server),
                    TimeUnit.SECONDS.toMillis(SCAN_PERIOD_SECONDS), Priority.LOW);
            }
        });
    }

    private static void runTurboScanOnServerThread(MinecraftServer server) {
        try {
            if (shutdown) {
                return;
            }

            // We never start a scan if the tick is already tight.
            if (ServerTickLoad.shouldPauseNonCritical(server)) {
                return;
            }

            for (ServerLevel level : server.getAllLevels()) {
                IDimensionInfo dimInfo = getDimensionInfo(level);
                if (dimInfo == null) {
                    continue;
                }
                if (ServerTickLoad.shouldPauseNonCritical(server)) {
                    break;
                }
                long[] loaded = snapshotLoadedChunkKeys(level);
                int size = loaded.length;
                if (size <= 0) continue;
                int cursor = CHUNK_CURSOR.getOrDefault(level.dimension(), 0);
                if (cursor >= size) cursor = 0;

                int budget = calculateDynamicBudget(size);
                if (ServerTickLoad.getElapsedMsInCurrentTick() >= 12.0D || ServerTickLoad.getAverageTickMs(server, 50.0D) >= 35.0D) {
                    budget = Math.min(budget, 4);
                }
                int maxToProcess = Math.min(size, budget);
                if (maxToProcess <= 0) {
                    CHUNK_CURSOR.put(level.dimension(), cursor);
                    continue;
                }

                Map<Long, Long> lastScanForLevel = LAST_SCAN.computeIfAbsent(level.dimension(), key -> new ConcurrentHashMap<>());
                int processed = 0;
                int attempts = 0;
                long now = System.currentTimeMillis();

                while (attempts < size && processed < maxToProcess) {
                    if (ServerTickLoad.shouldPauseNonCritical(server)) {
                        break;
                    }
                    long packedPos = loaded[cursor];
                    cursor++;
                    if (cursor >= size) cursor = 0;
                    attempts++;

                    long chunkKey = packedPos;
                    Long prevScan = lastScanForLevel.get(chunkKey);
                    if (prevScan != null && (now - prevScan) < MIN_RESCAN_INTERVAL_MS) {
                        continue;
                    }

                    int cx = ChunkPos.getX(packedPos);
                    int cz = ChunkPos.getZ(packedPos);
                    LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                    if (chunk == null) {
                        continue;
                    }

                    try {
                        String cacheKey = vineScanKey(level.dimension(), cx, cz);
                        if (FeatureCache.get(cacheKey) != null) {
                            continue;
                        }
                        String skipKey = vineSkipKey(level.dimension(), cx, cz);
                        if (FeatureCache.get(skipKey) != null) {
                            continue;
                        }
                        if (!isCityChunk(dimInfo, level.dimension(), cx, cz)) {
                            FeatureCache.put(skipKey, Boolean.TRUE);
                            continue;
                        }
                        if (scanChunkForVinesAsync(level, chunk)) {
                            processed++;
                            lastScanForLevel.put(chunkKey, now);
                        }
                    } catch (Throwable t) {
                        LC2H.LOGGER.debug("[LC2H] VineClusterCleaner chunk scan failed for " + cx + "," + cz + ": " + t.getMessage());
                    }
                }

                cleanupScanData(lastScanForLevel, loaded);

                CHUNK_CURSOR.put(level.dimension(), cursor);
            }
        } catch (Throwable t) {
            LC2H.LOGGER.error("[LC2H] VineClusterCleaner server scan failed", t);
        }
    }

    private static int calculateDynamicBudget(int loadedChunks) {
        if (loadedChunks <= 100) return MAX_CHUNKS_PER_SCAN;
        if (loadedChunks <= 500) return MAX_CHUNKS_PER_SCAN / 2;
        if (loadedChunks <= 1000) return MAX_CHUNKS_PER_SCAN / 4;
        return Math.max(4, MAX_CHUNKS_PER_SCAN / 8);
    }

    private static void cleanupScanData(Map<Long, Long> lastScanForLevel, long[] loaded) {
        if (loaded == null) {
            return;
        }
        if (lastScanForLevel.size() > loaded.length * 2 + 128) {
            Set<Long> active = new HashSet<>(loaded.length);
            for (long cp : loaded) {
                active.add(cp);
            }
            lastScanForLevel.keySet().removeIf(key -> !active.contains(key));
        }
    }

    private static boolean scanChunkForVinesAsync(ServerLevel level, LevelChunk chunk) {
        if (!tryEnterScan()) {
            return false;
        }
        QuantifiedTask.Builder<Void> builder = QuantifiedTask.<Void>builder(LC2H.MODID, "vine_cleanup", () -> {
            try {
                performVineScan(level, chunk);
            } catch (Exception e) {
                LC2H.LOGGER.error("[LC2H] Error in async vine cleanup: " + e.getMessage(), e);
            } finally {
                exitScan();
            }
            return null;
        });

        try {
            QuantifiedAPI.submit(builder);
            return true;
        } catch (IllegalStateException notRegistered) {
            try {
                QuantifiedAPI.register(LC2H.MODID);
                QuantifiedAPI.submit(builder);
                return true;
            } catch (Throwable ignored) {
            }
        } catch (Throwable ignored) {
        }

        try {
            AsyncManager.submitSupplier(
                "vine_cleanup_fallback",
                () -> {
                    try {
                        performVineScan(level, chunk);
                    } finally {
                        exitScan();
                    }
                    return null;
                },
                org.admany.lc2h.concurrency.async.Priority.LOW
            );
            return true;
        } catch (Throwable t) {
            exitScan();
            return false;
        }
    }

    private static void performVineScan(ServerLevel level, LevelChunk chunk) {
        try {
            ChunkPos chunkPos = chunk.getPos();
            String cacheKey = vineScanKey(level.dimension(), chunkPos.x, chunkPos.z);

            if (FeatureCache.get(cacheKey) != null) {
                return; 
            }

            String skipKey = vineSkipKey(level.dimension(), chunkPos.x, chunkPos.z);
            if (FeatureCache.get(skipKey) != null) {
                return;
            }

            IDimensionInfo dimInfo = getDimensionInfo(level);
            if (dimInfo == null || !isCityChunk(dimInfo, level.dimension(), chunkPos.x, chunkPos.z)) {
                FeatureCache.put(skipKey, Boolean.TRUE);
                return;
            }

            int baseX = chunkPos.getMinBlockX();
            int baseZ = chunkPos.getMinBlockZ();
            int minY = level.getMinBuildHeight();
            int maxY = level.getMaxBuildHeight();

            Set<BlockPos> vineStarts = collectVineStarts(chunk, baseX, baseZ, minY, maxY);
            if (vineStarts.isEmpty()) {
                FeatureCache.put(cacheKey, Boolean.TRUE);
                return;
            }

            processVineComponents(level, vineStarts);

            FeatureCache.put(cacheKey, Boolean.TRUE);

        } catch (Throwable t) {
            LC2H.LOGGER.error("[LC2H] VineClusterCleaner scanChunk failed", t);
        }
    }

    private static boolean tryEnterScan() {
        int inFlight = IN_FLIGHT.incrementAndGet();
        if (inFlight > MAX_IN_FLIGHT) {
            IN_FLIGHT.decrementAndGet();
            return false;
        }
        return true;
    }

    private static void exitScan() {
        IN_FLIGHT.decrementAndGet();
    }

    private static Set<BlockPos> collectVineStarts(LevelChunk chunk, int baseX, int baseZ, int minY, int maxY) {
        Set<BlockPos> vineStarts = new HashSet<>();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        for (int x = 0; x < 16; x += 2) {
            for (int z = 0; z < 16; z += 2) {
                for (int y = minY; y < maxY; y += 2) {
                    cursor.set(baseX + x, y, baseZ + z);
                    try {
                        if (chunk.getBlockState(cursor).getBlock() == Blocks.VINE) {
                            vineStarts.add(cursor.immutable());
                            checkAdjacentVines(chunk, cursor, vineStarts, baseX, baseZ);
                        }
                    } catch (Throwable ignored) {}
                }
            }
        }

        return vineStarts;
    }

    private static void checkAdjacentVines(LevelChunk chunk, BlockPos.MutableBlockPos center, Set<BlockPos> vineStarts, int baseX, int baseZ) {
        BlockPos[] neighbors = {center.north(), center.south(), center.east(), center.west()};
        for (BlockPos n : neighbors) {
            if (n.getX() >= baseX && n.getX() < baseX + 16 && n.getZ() >= baseZ && n.getZ() < baseZ + 16) {
                try {
                    if (chunk.getBlockState(n).getBlock() == Blocks.VINE && vineStarts.size() < VINE_SCAN_BATCH_SIZE) {
                        vineStarts.add(n);
                    }
                } catch (Throwable ignored) {}
            }
        }
    }

    private static void processVineComponents(ServerLevel level, Set<BlockPos> vineStarts) {
        Set<BlockPos> visited = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();

        for (BlockPos start : vineStarts) {
            if (!visited.add(start)) continue;

            java.util.List<BlockPos> component = new java.util.ArrayList<>();
            queue.clear();
            queue.add(start);
            component.add(start);

            boolean anySupport = false;
            boolean endTouchesSupport = false;
            boolean foundEnd = false;

            while (!queue.isEmpty() && component.size() <= COMPONENT_LIMIT) {
                BlockPos current = queue.removeFirst();
                BlockPos[] neighbors = {
                    current.north(), current.south(), current.east(), current.west(),
                    current.above(), current.below()
                };

                int vineNeighborCount = 0;
                boolean currentTouchesSupport = false;

                for (BlockPos neighbor : neighbors) {
                    try {
                        net.minecraft.world.level.block.state.BlockState neighborState = level.getBlockState(neighbor);
                        if (neighborState.getBlock() == Blocks.VINE) {
                            vineNeighborCount++;
                            if (visited.add(neighbor)) {
                                queue.add(neighbor);
                                component.add(neighbor);
                            }
                        } else if (!neighborState.isAir()) {
                            currentTouchesSupport = true;
                        }
                    } catch (Throwable ignored) {}
                }

                if (currentTouchesSupport) anySupport = true;
                if (vineNeighborCount <= 1) {
                    foundEnd = true;
                    if (currentTouchesSupport) endTouchesSupport = true;
                }
            }

            if (component.size() > COMPONENT_LIMIT) continue;

            boolean keep = endTouchesSupport || (!foundEnd && anySupport);

            if (!keep) {
                removeVineComponent(level, component);
            }
        }
    }

    private static void removeVineComponent(ServerLevel level, java.util.List<BlockPos> component) {
        if (component == null || component.isEmpty() || level == null || shutdown) {
            return;
        }
        enqueueRemoval(level, component);
    }

    private static void enqueueRemoval(ServerLevel level, java.util.List<BlockPos> component) {
        if (shutdown || level == null || component == null || component.isEmpty()) {
            return;
        }
        for (BlockPos pos : component) {
            if (pos != null) {
                REMOVAL_QUEUE.add(new RemovalTask(level, pos));
            }
        }
        scheduleRemovalDrain(level);
    }

    private static void scheduleRemovalDrain(ServerLevel level) {
        if (shutdown || level == null) {
            return;
        }
        if (!REMOVAL_DRAIN_SCHEDULED.compareAndSet(false, true)) {
            return;
        }
        AsyncManager.syncToMain(() -> drainRemovalQueue(level));
    }

    private static void drainRemovalQueue(ServerLevel fallbackLevel) {
        REMOVAL_DRAIN_SCHEDULED.set(false);
        if (shutdown) {
            return;
        }

        MinecraftServer server = fallbackLevel != null ? fallbackLevel.getServer() : null;
        if (server != null && ServerTickLoad.shouldPauseNonCritical(server)) {
            AsyncManager.runLater("vine-removal-drain", () -> scheduleRemovalDrain(fallbackLevel), 50L, Priority.LOW);
            return;
        }

        int budget = MAX_REMOVALS_PER_TICK;
        if (ServerTickLoad.getElapsedMsInCurrentTick() >= 12.0D) {
            budget = Math.min(budget, 8);
        }

        int processed = 0;
        while (processed < budget) {
            RemovalTask task = REMOVAL_QUEUE.poll();
            if (task == null) {
                break;
            }
            ServerLevel level = task.level != null ? task.level : fallbackLevel;
            if (level == null) {
                continue;
            }
            try {
                level.removeBlockEntity(task.pos);
                level.setBlock(task.pos, Blocks.AIR.defaultBlockState(), 3);
            } catch (Throwable ignored) {
            }
            processed++;

            if (server != null && ServerTickLoad.shouldPauseNonCritical(server)) {
                break;
            }
        }

        if (!REMOVAL_QUEUE.isEmpty()) {
            scheduleRemovalDrain(fallbackLevel);
        }
    }

    private static long[] snapshotLoadedChunkKeys(ServerLevel level) {
        ResourceKey<net.minecraft.world.level.Level> dim = level.dimension();
        LevelChunkIndex idx = LOADED_CHUNKS.computeIfAbsent(dim, d -> new LevelChunkIndex());

        long now = System.nanoTime();
        boolean shouldRefresh = idx.dirty.get() || idx.snapshot.length == 0 || (now - idx.snapshotNs) >= SNAPSHOT_TTL_NS;
        if (!shouldRefresh) {
            return idx.snapshot;
        }

        long[] snap = new long[idx.loaded.size()];
        int i = 0;
        for (Long key : idx.loaded.keySet()) {
            if (key == null) continue;
            if (i >= snap.length) {
                break;
            }
            snap[i++] = key;
        }
        if (i != snap.length) {
            snap = java.util.Arrays.copyOf(snap, i);
        }
        idx.snapshot = snap;
        idx.snapshotNs = now;
        idx.dirty.set(false);
        return snap;
    }

    public static void shutdown() {
        shutdown = true;
        initialized = false;
        CHUNK_CURSOR.clear();
        LAST_SCAN.clear();
        LOADED_CHUNKS.clear();
    }

    private static IDimensionInfo getDimensionInfo(ServerLevel level) {
        try {
            return Registration.LOSTCITY_FEATURE.get().getDimensionInfo(level);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isCityChunk(IDimensionInfo dimInfo, ResourceKey<net.minecraft.world.level.Level> dim, int cx, int cz) {
        try {
            return BuildingInfo.isCity(new ChunkCoord(dim, cx, cz), dimInfo);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String vineScanKey(ResourceKey<net.minecraft.world.level.Level> dim, int cx, int cz) {
        return "vine_scan_" + dimKey(dim) + "_" + cx + "_" + cz;
    }

    private static String vineSkipKey(ResourceKey<net.minecraft.world.level.Level> dim, int cx, int cz) {
        return "vine_scan_skip_" + dimKey(dim) + "_" + cx + "_" + cz;
    }

    private static String dimKey(ResourceKey<net.minecraft.world.level.Level> dim) {
        if (dim == null) {
            return "unknown";
        }
        String raw = String.valueOf(dim.location());
        return raw.replace(':', '_').replace('/', '_');
    }
}
