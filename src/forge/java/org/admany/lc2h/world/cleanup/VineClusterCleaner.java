package org.admany.lc2h.world.cleanup;

import mcjty.lostcities.setup.Registration;
import mcjty.lostcities.worldgen.IDimensionInfo;
import org.admany.lc2h.worldgen.lostcities.ChunkRoleProbe;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
import org.admany.lc2h.config.ConfigManager;
import org.admany.lc2h.data.cache.FeatureCache;
import org.admany.lc2h.runtime.Lc2hRuntimeModes;
import org.admany.lc2h.util.server.ServerTickLoad;
import org.admany.quantified.api.QuantifiedAPI;
import org.admany.lc2h.concurrency.async.Priority;
import org.admany.lc2h.worldgen.apply.ChunkShadowMutationPlan;
import org.admany.lc2h.worldgen.apply.ShadowBlockMutationApplier;
import mcjty.lostcities.varia.ChunkCoord;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

@Mod.EventBusSubscriber(modid = LC2H.MODID)
public final class VineClusterCleaner {
    private enum AttachmentFamily {
        VINE,
        CAVE_VINES,
        TWISTING_VINES,
        WEEPING_VINES,
        HANGING_ROOTS,
        CHAIN
    }

    private static volatile boolean initialized = false;
    private static volatile boolean shutdown = false;

    private static final int COMPONENT_LIMIT = 2048;
    private static final int MAX_CHUNKS_PER_SCAN = 64;
    private static final int MAX_REMOVALS_PER_TICK = Math.max(16, Integer.getInteger("lc2h.vine.max_removals_per_tick", 128));
    private static final long MIN_RESCAN_INTERVAL_MS = 15_000L;
    private static final int VINE_SCAN_BATCH_SIZE = Math.max(512, Integer.getInteger("lc2h.vine.scan_batch_size", 2048));
    private static final int VINE_SCAN_CACHE_VERSION = 3;

    private static final Map<ResourceKey<net.minecraft.world.level.Level>, Integer> CHUNK_CURSOR = new ConcurrentHashMap<>();
    private static final Map<ResourceKey<net.minecraft.world.level.Level>, Map<Long, Long>> LAST_SCAN = new ConcurrentHashMap<>();

    private static final long SCAN_PERIOD_SECONDS = Long.getLong("lc2h.vine.scan_period_seconds", 2L);
    private static final long SNAPSHOT_TTL_NS = TimeUnit.SECONDS.toNanos(Long.getLong("lc2h.vine.snapshot_ttl_seconds", 5L));
    private static final int MAX_IN_FLIGHT = Math.max(1, Integer.getInteger("lc2h.vine.max_in_flight", 16));
    private static final AtomicInteger IN_FLIGHT = new AtomicInteger(0);
    private static final AtomicBoolean SCAN_REQUESTED = new AtomicBoolean(false);
    private static final LongAdder SCANS_SUBMITTED = new LongAdder();
    private static final LongAdder SCANS_COMPLETED = new LongAdder();
    private static final LongAdder SCAN_TIME_NS = new LongAdder();
    private static volatile MinecraftServer LAST_SERVER;

    private static final int REMOVAL_Y_OFFSET = 1024;

    private static final class RemovalBatch {
        final ServerLevel level;
        final ChunkPos chunkPos;
        final int[] packedPositions;
        int cursor;

        RemovalBatch(ServerLevel level, ChunkPos chunkPos, int[] packedPositions) {
            this.level = level;
            this.chunkPos = chunkPos;
            this.packedPositions = packedPositions;
        }

        boolean hasRemaining() {
            return cursor < packedPositions.length;
        }
    }

    private static final ConcurrentLinkedQueue<RemovalBatch> REMOVAL_QUEUE = new ConcurrentLinkedQueue<>();
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
        if (!isAutomaticCleanupEnabled() || Lc2hRuntimeModes.anyParityAutorun()) {
            return;
        }
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
        if (!isAutomaticCleanupEnabled() || Lc2hRuntimeModes.anyParityAutorun()) {
            return;
        }
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
        if (!isAutomaticCleanupEnabled()) {
            initialized = false;
            SCAN_REQUESTED.set(false);
            return;
        }
        if (Lc2hRuntimeModes.anyParityAutorun()) {
            shutdown = true;
            initialized = false;
            return;
        }
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
        if (!isAutomaticCleanupEnabled() || Lc2hRuntimeModes.anyParityAutorun()) {
            return;
        }
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

        if (!isCleanupRelevantChunk(dimInfo, level.dimension(), chunkPos.x, chunkPos.z)) {
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
        if (!isAutomaticCleanupEnabled()) {
            return;
        }
        LAST_SERVER = server;
        SCAN_REQUESTED.set(true);
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (!isAutomaticCleanupEnabled() || shutdown || event.phase != TickEvent.Phase.END) {
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
        if (!isAutomaticCleanupEnabled()) {
            return false;
        }
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
        if (shutdown || !isAutomaticCleanupEnabled()) return;

        CompletableFuture<Void> future = AsyncManager.submitTask("vine_cleaner_tick", () -> runTurboScan(server), null, Priority.LOW);

        future.whenComplete((r, t) -> {
            if (!shutdown && isAutomaticCleanupEnabled()) {
                AsyncManager.runLater("vine-cleaner-reschedule", () -> scheduleNextQuantifiedScan(server),
                    TimeUnit.SECONDS.toMillis(SCAN_PERIOD_SECONDS), Priority.LOW);
            }
        });
    }

    private static void runTurboScanOnServerThread(MinecraftServer server) {
        try {
            if (shutdown || !isAutomaticCleanupEnabled()) {
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
                        if (!isCleanupRelevantChunk(dimInfo, level.dimension(), cx, cz)) {
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
        if (!isAutomaticCleanupEnabled()) {
            return false;
        }
        if (!tryEnterScan()) {
            return false;
        }

        try {
            SCANS_SUBMITTED.increment();
            AsyncManager.submitSupplier(
                "vine_cleanup",
                () -> {
                    long startedNs = System.nanoTime();
                    try {
                        performVineScan(level, chunk);
                    } catch (Exception e) {
                        LC2H.LOGGER.error("[LC2H] Error in async vine cleanup: " + e.getMessage(), e);
                    } finally {
                        SCAN_TIME_NS.add(System.nanoTime() - startedNs);
                        SCANS_COMPLETED.increment();
                        exitScan();
                    }
                    return null;
                },
                Priority.LOW
            );
            return true;
        } catch (Throwable ignored) {
        }

        try {
            SCANS_SUBMITTED.increment();
            AsyncManager.submitSupplier(
                "vine_cleanup_fallback",
                () -> {
                    long startedNs = System.nanoTime();
                    try {
                        performVineScan(level, chunk);
                    } finally {
                        SCAN_TIME_NS.add(System.nanoTime() - startedNs);
                        SCANS_COMPLETED.increment();
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
        if (!isAutomaticCleanupEnabled()) {
            return;
        }
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
        int boundedMaxY = Math.max(minY, maxY - 1);

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = minY; y <= boundedMaxY; y++) {
                    cursor.set(baseX + x, y, baseZ + z);
                    try {
                        if (attachmentFamily(chunk.getBlockState(cursor)) != null) {
                            vineStarts.add(cursor.immutable());
                            if (vineStarts.size() >= VINE_SCAN_BATCH_SIZE) {
                                return vineStarts;
                            }
                        }
                    } catch (Throwable ignored) {}
                }
            }
        }

        return vineStarts;
    }

    private static void processVineComponents(ServerLevel level, Set<BlockPos> vineStarts) {
        Set<BlockPos> visited = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();

        for (BlockPos start : vineStarts) {
            if (!visited.add(start)) continue;

            net.minecraft.world.level.block.state.BlockState startState;
            try {
                startState = level.getBlockState(start);
            } catch (Throwable ignored) {
                continue;
            }
            AttachmentFamily family = attachmentFamily(startState);
            if (family == null) {
                continue;
            }

            java.util.List<BlockPos> component = new java.util.ArrayList<>();
            queue.clear();
            queue.add(start);
            component.add(start);

            boolean anyExternallySupportedVine = false;

            while (!queue.isEmpty() && component.size() <= COMPONENT_LIMIT) {
                BlockPos current = queue.removeFirst();
                net.minecraft.world.level.block.state.BlockState currentState;
                try {
                    currentState = level.getBlockState(current);
                } catch (Throwable ignored) {
                    continue;
                }
                if (attachmentFamily(currentState) != family) {
                    continue;
                }
                try {
                    if (hasExternalAttachmentSupport(level, current, currentState, family)) {
                        anyExternallySupportedVine = true;
                    }
                } catch (Throwable ignored) {
                }
                BlockPos[] neighbors = connectedNeighbors(current, family);

                for (BlockPos neighbor : neighbors) {
                    try {
                        net.minecraft.world.level.block.state.BlockState neighborState = level.getBlockState(neighbor);
                        if (attachmentFamily(neighborState) == family) {
                            if (visited.add(neighbor)) {
                                queue.add(neighbor);
                                component.add(neighbor);
                            }
                        }
                    } catch (Throwable ignored) {}
                }
            }

            if (component.size() > COMPONENT_LIMIT) continue;

            if (!anyExternallySupportedVine) {
                removeVineComponent(level, component);
            }
        }
    }

    private static boolean hasExternalAttachmentSupport(ServerLevel level,
                                                        BlockPos pos,
                                                        net.minecraft.world.level.block.state.BlockState state,
                                                        AttachmentFamily family) {
        if (level == null || pos == null || state == null) {
            return false;
        }
        if (family == null || attachmentFamily(state) != family) {
            return false;
        }

        boolean survives;
        try {
            survives = state.canSurvive(level, pos);
        } catch (Throwable ignored) {
            return false;
        }
        if (!survives) {
            return false;
        }

        for (Direction direction : supportDirections(family)) {
            BlockPos neighborPos = pos.relative(direction);
            net.minecraft.world.level.block.state.BlockState neighborState;
            try {
                neighborState = level.getBlockState(neighborPos);
            } catch (Throwable ignored) {
                continue;
            }

            if (neighborState.isAir()) {
                continue;
            }
            if (attachmentFamily(neighborState) == family) {
                continue;
            }
            return true;
        }

        return false;
    }

    private static AttachmentFamily attachmentFamily(net.minecraft.world.level.block.state.BlockState state) {
        if (state == null) {
            return null;
        }
        if (state.is(Blocks.VINE)) {
            return AttachmentFamily.VINE;
        }
        if (state.is(Blocks.CAVE_VINES) || state.is(Blocks.CAVE_VINES_PLANT)) {
            return AttachmentFamily.CAVE_VINES;
        }
        if (state.is(Blocks.TWISTING_VINES) || state.is(Blocks.TWISTING_VINES_PLANT)) {
            return AttachmentFamily.TWISTING_VINES;
        }
        if (state.is(Blocks.WEEPING_VINES) || state.is(Blocks.WEEPING_VINES_PLANT)) {
            return AttachmentFamily.WEEPING_VINES;
        }
        if (state.is(Blocks.HANGING_ROOTS)) {
            return AttachmentFamily.HANGING_ROOTS;
        }
        if (state.is(Blocks.CHAIN)) {
            return AttachmentFamily.CHAIN;
        }
        return null;
    }

    private static BlockPos[] connectedNeighbors(BlockPos pos, AttachmentFamily family) {
        return switch (family) {
            case VINE -> new BlockPos[]{
                pos.north(), pos.south(), pos.east(), pos.west(), pos.above(), pos.below()
            };
            case CAVE_VINES, TWISTING_VINES, WEEPING_VINES, CHAIN -> new BlockPos[]{
                pos.above(), pos.below()
            };
            case HANGING_ROOTS -> new BlockPos[]{pos.above()};
        };
    }

    private static Direction[] supportDirections(AttachmentFamily family) {
        return switch (family) {
            case VINE -> new Direction[]{Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};
            case CAVE_VINES, WEEPING_VINES, HANGING_ROOTS -> new Direction[]{Direction.UP};
            case TWISTING_VINES -> new Direction[]{Direction.DOWN};
            case CHAIN -> new Direction[]{Direction.UP, Direction.DOWN};
        };
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
        Map<Long, ChunkShadowMutationPlan.Builder> removalsByChunk = new HashMap<>();
        for (BlockPos pos : component) {
            if (pos == null) {
                continue;
            }
            int chunkX = pos.getX() >> 4;
            int chunkZ = pos.getZ() >> 4;
            long chunkKey = ChunkPos.asLong(chunkX, chunkZ);
            removalsByChunk.computeIfAbsent(chunkKey, unused -> ChunkShadowMutationPlan.builder(
                    level,
                    new ChunkCoord(level.dimension(), chunkX, chunkZ)))
                .add(pos, Blocks.AIR.defaultBlockState(), 3, false);
        }

        for (ChunkShadowMutationPlan.Builder builder : removalsByChunk.values()) {
            ChunkShadowMutationPlan plan = builder.build();
            if (plan.size() <= 0) {
                continue;
            }
            ShadowBlockMutationApplier.enqueue(plan);
        }
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
            RemovalBatch batch = REMOVAL_QUEUE.poll();
            if (batch == null) {
                break;
            }
            ServerLevel level = batch.level != null ? batch.level : fallbackLevel;
            if (level == null) {
                continue;
            }
            LevelChunk chunk = level.getChunkSource().getChunkNow(batch.chunkPos.x, batch.chunkPos.z);
            if (chunk == null) {
                continue;
            }

            BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
            while (processed < budget && batch.hasRemaining()) {
                unpackLocalRemovalPos(batch.chunkPos, batch.packedPositions[batch.cursor++], cursor);
                try {
                    if (chunk.getBlockState(cursor).is(Blocks.VINE)) {
                        level.removeBlockEntity(cursor);
                        level.setBlock(cursor, Blocks.AIR.defaultBlockState(), 3);
                    }
                } catch (Throwable ignored) {
                }
                processed++;

                if (server != null && ServerTickLoad.shouldPauseNonCritical(server)) {
                    break;
                }
            }

            if (batch.hasRemaining()) {
                REMOVAL_QUEUE.add(batch);
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
        REMOVAL_QUEUE.clear();
        REMOVAL_DRAIN_SCHEDULED.set(false);
        CHUNK_CURSOR.clear();
        LAST_SCAN.clear();
        LOADED_CHUNKS.clear();
    }

    public static boolean isAutomaticCleanupEnabled() {
        return ConfigManager.ENABLE_AUTOMATIC_CHUNK_SCANS
            && ConfigManager.ENABLE_FLOATING_VEGETATION_REMOVAL;
    }

    public static String diagnostics() {
        long submitted = SCANS_SUBMITTED.sum();
        long completed = SCANS_COMPLETED.sum();
        long totalNs = SCAN_TIME_NS.sum();
        double averageMs = completed == 0L ? 0.0D : (totalNs / 1_000_000.0D) / completed;
        int indexedChunks = 0;
        for (LevelChunkIndex index : LOADED_CHUNKS.values()) {
            indexedChunks += index.loaded.size();
        }
        return "automatic=" + isAutomaticCleanupEnabled()
            + " initialized=" + initialized
            + " requested=" + SCAN_REQUESTED.get()
            + " inFlight=" + IN_FLIGHT.get()
            + " indexedChunks=" + indexedChunks
            + " submitted=" + submitted
            + " completed=" + completed
            + " avgMs=" + String.format(java.util.Locale.ROOT, "%.3f", averageMs);
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
            return ChunkRoleProbe.isCity(dimInfo, dim, cx, cz);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isCleanupRelevantChunk(IDimensionInfo dimInfo,
                                                  ResourceKey<net.minecraft.world.level.Level> dim,
                                                  int cx,
                                                  int cz) {
        if (isCityChunk(dimInfo, dim, cx, cz)) {
            return true;
        }
        return isCityChunk(dimInfo, dim, cx + 1, cz)
            || isCityChunk(dimInfo, dim, cx - 1, cz)
            || isCityChunk(dimInfo, dim, cx, cz + 1)
            || isCityChunk(dimInfo, dim, cx, cz - 1);
    }

    private static String vineScanKey(ResourceKey<net.minecraft.world.level.Level> dim, int cx, int cz) {
        return "vine_scan_v" + VINE_SCAN_CACHE_VERSION + "_" + dimKey(dim) + "_" + cx + "_" + cz;
    }

    private static String vineSkipKey(ResourceKey<net.minecraft.world.level.Level> dim, int cx, int cz) {
        return "vine_scan_skip_v" + VINE_SCAN_CACHE_VERSION + "_" + dimKey(dim) + "_" + cx + "_" + cz;
    }

    private static String dimKey(ResourceKey<net.minecraft.world.level.Level> dim) {
        if (dim == null) {
            return "unknown";
        }
        String raw = String.valueOf(dim.location());
        return raw.replace(':', '_').replace('/', '_');
    }

    private static int packLocalRemovalPos(BlockPos pos) {
        int localX = pos.getX() & 15;
        int localZ = pos.getZ() & 15;
        int y = pos.getY() + REMOVAL_Y_OFFSET;
        return (y << 8) | (localX << 4) | localZ;
    }

    private static void unpackLocalRemovalPos(ChunkPos chunkPos, int packed, BlockPos.MutableBlockPos target) {
        int localZ = packed & 15;
        int localX = (packed >>> 4) & 15;
        int y = (packed >>> 8) - REMOVAL_Y_OFFSET;
        target.set(chunkPos.getMinBlockX() + localX, y, chunkPos.getMinBlockZ() + localZ);
    }
}
