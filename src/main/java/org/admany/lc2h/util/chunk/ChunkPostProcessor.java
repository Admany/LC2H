package org.admany.lc2h.util.chunk;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.event.level.ChunkDataEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import mcjty.lostcities.setup.Registration;
import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.IDimensionInfo;
import mcjty.lostcities.worldgen.lost.BuildingInfo;
import org.admany.lc2h.LC2H;
import org.admany.lc2h.log.LCLogger;
import org.admany.lc2h.config.ConfigManager;
import org.admany.lc2h.util.batch.CpuBatchScheduler;
import org.admany.lc2h.util.server.ServerRescheduler;
import org.admany.lc2h.util.server.ServerTickLoad;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Mod.EventBusSubscriber(modid = LC2H.MODID)
public class ChunkPostProcessor {

    private static final Set<Block> TRACKED_BLOCKS = Set.copyOf(Arrays.asList(
            Blocks.GRASS,
            Blocks.FERN,
            Blocks.TALL_GRASS,
            Blocks.DANDELION,
            Blocks.POPPY,
            Blocks.BLUE_ORCHID,
            Blocks.ALLIUM,
            Blocks.AZURE_BLUET,
            Blocks.RED_TULIP,
            Blocks.ORANGE_TULIP,
            Blocks.WHITE_TULIP,
            Blocks.PINK_TULIP,
            Blocks.OXEYE_DAISY,
            Blocks.CORNFLOWER,
            Blocks.LILY_OF_THE_VALLEY,
            Blocks.WITHER_ROSE,
            Blocks.SUNFLOWER,
            Blocks.LILAC,
            Blocks.ROSE_BUSH,
            Blocks.PEONY,
            Blocks.DEAD_BUSH,
            Blocks.SUGAR_CANE,
            Blocks.BAMBOO,
            Blocks.SEAGRASS,
            Blocks.TALL_SEAGRASS,
            Blocks.KELP,
            Blocks.KELP_PLANT
    ));
    private static final String HORROR_ELEMENT_NAMESPACE = "horror_element_mod";
    private static final ConcurrentHashMap<Block, Boolean> TRACKED_BLOCK_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Block, Boolean> TREE_PROTECTED_BLOCK_CACHE = new ConcurrentHashMap<>();

    private static final int MAX_CHUNKS_PER_TICK = Math.max(1, Integer.getInteger("lc.floating.max_chunks_per_tick", 1));
    private static final int MAX_ASYNC_CHUNKS_PER_TICK = Math.max(1, Integer.getInteger("lc.floating.async_chunks_per_tick", MAX_CHUNKS_PER_TICK));
    private static final boolean ENABLE_THREADED_SCAN = Boolean.parseBoolean(System.getProperty("lc.floating.threaded_scan", "true"));
    private static final int SAMPLES_PER_CHUNK = Math.max(16, Integer.getInteger("lc.floating.samples_per_chunk", 96));
    private static final int MIN_SAMPLES_PER_TASK = Math.max(8, Integer.getInteger("lc.floating.min_samples_per_task", 24));
    private static final int MAX_QUEUE = Math.max(256, Integer.getInteger("lc.floating.max_queue", 4096));
    private static final int QUEUE_ALERT_THRESHOLD = Math.max(
        128,
        Integer.getInteger("lc.floating.queue_alert_threshold", Math.min(MAX_QUEUE, 1024))
    );
    private static final int MAX_PENDING_FLOATING_CHECKS = Math.max(256, Integer.getInteger("lc.floating.max_pending_checks", 8192));
    private static final int MAX_FLOATING_CHECKS_PER_TICK = Math.max(8, Integer.getInteger("lc.floating.max_checks_per_tick", 128));
    private static final double TICK_TIME_BUDGET_MS = Math.max(5D, Double.parseDouble(System.getProperty("lc.floating.tick_budget_ms", "35")));
    // If set (>0), forces a fixed work budget. Otherwise, the post processor auto-tunes.
    private static final double OVERRIDE_MAX_WORK_TIME_PER_TICK_MS = Double.parseDouble(System.getProperty("lc.floating.max_work_ms_per_tick", "-1"));
    private static final double MIN_WORK_TIME_PER_TICK_MS = 0.25D;
    private static final double MAX_WORK_TIME_PER_TICK_MS_PLAYERS = 6.0D;
    private static final double MAX_WORK_TIME_PER_TICK_MS_STARTUP = 12.0D;
    private static final double TARGET_TICK_MS = 20.0D;
    private static final boolean ENABLE_BATCH_DRAIN = Boolean.getBoolean("lc.floating.enable_batch_drain");
    private static final boolean AUTO_RESCAN_STARTUP = Boolean.getBoolean("lc.floating.auto_rescan_startup");
    private static final boolean ENABLE_FLOATING_SCAN = Boolean.getBoolean("lc.floating.enable_scan");
    private static final int SAFE_SET_FLAGS = 2;
    private static final BlockState AIR_STATE = Blocks.AIR.defaultBlockState();
    private static final ThreadLocal<BlockPos.MutableBlockPos> LOCAL_SCAN_POS =
        ThreadLocal.withInitial(BlockPos.MutableBlockPos::new);
    private static final ThreadLocal<BlockPos.MutableBlockPos> LOCAL_BELOW_POS =
        ThreadLocal.withInitial(BlockPos.MutableBlockPos::new);

    private static final String DATA_ROOT = "lc2h";
    private static final String DATA_FLAG = "doubleblock_repaired";

    private static final Map<ChunkScanKey, ScanCursor> CHUNK_SCAN_PROGRESS = new ConcurrentHashMap<>();
    private static final Set<ChunkScanKey> INFLIGHT_CHUNK_SCANS = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static final Set<ChunkScanKey> COMPLETED_CHUNKS = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static final AtomicBoolean LOGGED_QUEUE_ALERT = new AtomicBoolean(false);
    private static final AtomicBoolean LOGGED_QUEUE_HARD_LIMIT = new AtomicBoolean(false);
    private static final AtomicBoolean LOGGED_FLOATING_QUEUE_HARD_LIMIT = new AtomicBoolean(false);
    private static final AtomicBoolean BATCH_IN_FLIGHT = new AtomicBoolean(false);
    private static final AtomicBoolean FLOATING_DRAIN_REQUESTED = new AtomicBoolean(false);
    private static volatile long LAST_FLOATING_DRAIN_TICK = -1L;
    private static volatile boolean RESCAN_TRIGGERED = false;
    private static volatile boolean RESCAN_IN_PROGRESS = false;
    private static volatile long RESCAN_START_TICK = 0;
    private static int FIXED_DOUBLE_BLOCKS = 0;
    private static int FIXED_FLOATING = 0;

    private static volatile double ADAPTIVE_WORK_BUDGET_MS = 1.5D;

    private static final ConcurrentHashMap<ResourceKey<Level>, PendingCheckQueue> PENDING_FLOATING = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<ResourceKey<Level>, IDimensionInfo> DIMENSION_INFO_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<ChunkScanKey, Boolean> CITY_CHUNK_CACHE = new ConcurrentHashMap<>();

    private static final class PendingCheckQueue {
        private final ConcurrentLinkedQueue<Long> queue = new ConcurrentLinkedQueue<>();
        private final AtomicInteger size = new AtomicInteger();
        private volatile ResourceKey<Level> dimensionKey;
        private volatile ServerLevel level;
    }

    private record ScanCursor(int x, int y, int z) {
        ScanCursor advance(int maxY) {
            int nx = x + 1;
            int ny = y;
            int nz = z;
            if (nx >= 16) {
                nx = 0;
                nz++;
            }
            if (nz >= 16) {
                nz = 0;
                ny++;
            }
            if (ny > maxY) {
                ny = maxY;
            }
            return new ScanCursor(nx, ny, nz);
        }
    }

    private record ChunkScanResult(ChunkScanKey key, ScanCursor next, List<Long> floating, List<Long> doubleBlocks) {
    }

    public static boolean isTracked(Block block) {
        if (block == null) {
            return false;
        }
        Boolean cached = TRACKED_BLOCK_CACHE.get(block);
        if (cached != null) {
            return cached;
        }

        boolean tracked = TRACKED_BLOCKS.contains(block);
        if (!tracked) {
            ResourceLocation key = ForgeRegistries.BLOCKS.getKey(block);
            tracked = key != null && HORROR_ELEMENT_NAMESPACE.equals(key.getNamespace());
        }
        TRACKED_BLOCK_CACHE.put(block, tracked);
        return tracked;
    }

    private static boolean shouldWatchFloatingCandidate(BlockState state) {
        if (state == null) {
            return false;
        }
        return isTracked(state.getBlock())
            || isPotentialFloatingSourceFluid(state)
            || isHorrorElementBlock(state);
    }

    private static boolean isPotentialFloatingSourceFluid(BlockState state) {
        if (state == null) {
            return false;
        }
        net.minecraft.world.level.material.FluidState fluidState = state.getFluidState();
        return fluidState != null
            && fluidState.isSource()
            && (fluidState.is(FluidTags.WATER) || fluidState.is(FluidTags.LAVA));
    }

    private static boolean isHorrorElementBlock(BlockState state) {
        if (state == null) {
            return false;
        }
        ResourceLocation key = ForgeRegistries.BLOCKS.getKey(state.getBlock());
        return key != null && HORROR_ELEMENT_NAMESPACE.equals(key.getNamespace());
    }

    private static boolean isVanillaAir(BlockState state) {
        if (state == null || !state.isAir()) {
            return false;
        }
        ResourceLocation key = ForgeRegistries.BLOCKS.getKey(state.getBlock());
        return key != null && "minecraft".equals(key.getNamespace());
    }

    private static boolean shouldRemoveFloatingCandidate(ServerLevel level, BlockPos pos, BlockState state) {
        if (level == null || pos == null || state == null) {
            return false;
        }
        if (!shouldWatchFloatingCandidate(state)) {
            return false;
        }

        BlockPos below = pos.below();
        BlockState belowState = level.getBlockState(below);
        boolean unsupported = belowState.isAir() || !belowState.isCollisionShapeFullBlock(level, below);

        if (isPotentialFloatingSourceFluid(state)) {
            return unsupported && isFloatingFluidDisconnected(level, pos, state);
        }
        if (isHorrorElementBlock(state)) {
            return isFullySurroundedByVanillaAir(level, pos);
        }
        if (isTracked(state.getBlock())) {
            return unsupported;
        }
        return false;
    }

    private static boolean isFullySurroundedByVanillaAir(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null) {
            return false;
        }
        for (Direction direction : Direction.values()) {
            BlockPos neighborPos = pos.relative(direction);
            if (!level.isLoaded(neighborPos)) {
                return false;
            }
            BlockState neighborState = level.getBlockState(neighborPos);
            if (!isVanillaAir(neighborState)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isFullySurroundedByVanillaAir(net.minecraft.server.level.WorldGenRegion region, BlockPos pos) {
        if (region == null || pos == null) {
            return false;
        }
        for (Direction direction : Direction.values()) {
            BlockState neighborState = region.getBlockState(pos.relative(direction));
            if (!isVanillaAir(neighborState)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isFloatingFluidDisconnected(ServerLevel level, BlockPos pos, BlockState state) {
        if (level == null || pos == null || state == null) {
            return false;
        }
        net.minecraft.world.level.material.FluidState sourceFluid = state.getFluidState();
        if (sourceFluid == null || !sourceFluid.isSource()) {
            return false;
        }
        for (Direction direction : Direction.values()) {
            BlockPos neighborPos = pos.relative(direction);
            if (!level.isLoaded(neighborPos)) {
                return false;
            }
            BlockState neighborState = level.getBlockState(neighborPos);
            if (neighborState.isAir()) {
                continue;
            }
            if (!neighborState.getCollisionShape(level, neighborPos).isEmpty() || !neighborState.canBeReplaced()) {
                return false;
            }
        }
        return true;
    }

    private static boolean isFloatingFluidDisconnected(net.minecraft.server.level.WorldGenRegion region, BlockPos pos, BlockState state) {
        if (region == null || pos == null || state == null) {
            return false;
        }
        net.minecraft.world.level.material.FluidState sourceFluid = state.getFluidState();
        if (sourceFluid == null || !sourceFluid.isSource()) {
            return false;
        }
        for (Direction direction : Direction.values()) {
            BlockPos neighborPos = pos.relative(direction);
            BlockState neighborState = region.getBlockState(neighborPos);
            if (neighborState.isAir()) {
                continue;
            }
            if (!neighborState.getCollisionShape(region, neighborPos).isEmpty() || !neighborState.canBeReplaced()) {
                return false;
            }
        }
        return true;
    }

    private record ChunkScanKey(ResourceLocation dimension, int chunkX, int chunkZ) {
    }

    private static final java.util.concurrent.atomic.AtomicReference<java.lang.reflect.Method> WORLDGENREGION_LEVEL_METHOD =
        new java.util.concurrent.atomic.AtomicReference<>();
    private static final java.util.concurrent.ConcurrentHashMap<ChunkScanKey, java.util.concurrent.ConcurrentHashMap<Long, Boolean>>
        PROTECTED_TREE_BLOCKS = new java.util.concurrent.ConcurrentHashMap<>();

    public static void markForRemovalIfFloating(net.minecraft.server.level.WorldGenRegion region, BlockPos pos) {
        if (!ConfigManager.ENABLE_FLOATING_VEGETATION_REMOVAL) return;

        BlockState state = region.getBlockState(pos);
        if (!shouldWatchFloatingCandidate(state)) return;

        BlockPos below = pos.below();
        BlockState belowState = region.getBlockState(below);
        boolean unsupported = belowState.isAir() || !belowState.isCollisionShapeFullBlock(region, below);
        boolean enqueue = false;

        if (isPotentialFloatingSourceFluid(state)) {
            enqueue = unsupported && isFloatingFluidDisconnected(region, pos, state);
        } else if (isHorrorElementBlock(state)) {
            enqueue = isFullySurroundedByVanillaAir(region, pos);
        } else if (isTracked(state.getBlock())) {
            enqueue = unsupported;
        }

        if (enqueue) {
            try {
                ServerLevel level = resolveServerLevel(region);
                if (level != null) {
                    enqueueFloatingCheck(level, pos);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private static ServerLevel resolveServerLevel(net.minecraft.server.level.WorldGenRegion region) {
        if (region == null) {
            return null;
        }
        java.lang.reflect.Method method = WORLDGENREGION_LEVEL_METHOD.get();
        if (method == null || method.getDeclaringClass() != region.getClass()) {
            method = findWorldGenRegionLevelMethod(region.getClass());
            if (method != null) {
                WORLDGENREGION_LEVEL_METHOD.set(method);
            }
        }
        if (method == null) {
            return null;
        }
        try {
            Object value = method.invoke(region);
            return value instanceof ServerLevel level ? level : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static java.lang.reflect.Method findWorldGenRegionLevelMethod(Class<?> type) {
        // Prefer newer method names when present.
        try {
            java.lang.reflect.Method m = type.getMethod("getServerLevel");
            m.setAccessible(true);
            return m;
        } catch (Throwable ignored) {
        }
        try {
            java.lang.reflect.Method m = type.getMethod("getLevel");
            m.setAccessible(true);
            return m;
        } catch (Throwable ignored) {
        }
        return null;
    }

    @SubscribeEvent
    public static void onBlockPlaced(BlockEvent.EntityPlaceEvent event) {
        if (!ConfigManager.ENABLE_FLOATING_VEGETATION_REMOVAL) return;
        if (event instanceof BlockEvent.EntityMultiPlaceEvent) {
            return;
        }
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        BlockState placed = event.getBlockSnapshot().getCurrentBlock();
        if (shouldWatchFloatingCandidate(placed)) {
            enqueueFloatingCheck(level, event.getPos());
        }
    }

    @SubscribeEvent
    public static void onBlockMultiPlaced(BlockEvent.EntityMultiPlaceEvent event) {
        if (!ConfigManager.ENABLE_FLOATING_VEGETATION_REMOVAL) return;
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        for (net.minecraftforge.common.util.BlockSnapshot snapshot : event.getReplacedBlockSnapshots()) {
            BlockState placed = snapshot.getCurrentBlock();
            if (shouldWatchFloatingCandidate(placed)) {
                enqueueFloatingCheck(level, snapshot.getPos());
            }
        }
    }

    @SubscribeEvent
    public static void onBlockBroken(BlockEvent.BreakEvent event) {
        if (!ConfigManager.ENABLE_FLOATING_VEGETATION_REMOVAL) return;
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        BlockPos above = event.getPos().above();
        BlockState state = level.getBlockState(above);
        if (shouldWatchFloatingCandidate(state)) {
            enqueueFloatingCheck(level, above);
        }
    }

    @SubscribeEvent
    public static void onNeighborNotify(BlockEvent.NeighborNotifyEvent event) {
        if (!ConfigManager.ENABLE_FLOATING_VEGETATION_REMOVAL) return;
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        if (!event.getNotifiedSides().contains(net.minecraft.core.Direction.DOWN)) {
            return;
        }
        BlockState state = event.getState();
        if (shouldWatchFloatingCandidate(state)) {
            enqueueFloatingCheck(level, event.getPos());
        }
    }

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        boolean floatingScanEnabled = ConfigManager.ENABLE_FLOATING_VEGETATION_REMOVAL && ENABLE_FLOATING_SCAN;
        boolean doubleBlockEnabled = ConfigManager.ENABLE_ASYNC_DOUBLE_BLOCK_BATCHER;
        if (!floatingScanEnabled && !doubleBlockEnabled) return;
        if (!(event.getChunk() instanceof LevelChunk chunk)) return;

        // IMPORTANT: only track on the server. Client chunk loads can flood the queue on integrated servers.
        if (!(chunk.getLevel() instanceof ServerLevel level)) {
            return;
        }

        ChunkScanKey key = chunkKey(level.dimension().location(), chunk.getPos().x, chunk.getPos().z);
        boolean cityChunk = floatingScanEnabled && isCityChunkCached(level, chunk.getPos().x, chunk.getPos().z);
        boolean effectiveFloating = floatingScanEnabled && cityChunk;

        // If we already marked this chunk complete, don't enqueue it again.
        if (COMPLETED_CHUNKS.contains(key)) {
            return;
        }
        if (!chunkHasInterestingBlocks(chunk, effectiveFloating, doubleBlockEnabled)) {
            COMPLETED_CHUNKS.add(key);
            return;
        }

        int backlog = CHUNK_SCAN_PROGRESS.size();
        if (backlog >= MAX_QUEUE) {
            if (LOGGED_QUEUE_HARD_LIMIT.compareAndSet(false, true)) {
                LCLogger.warn(
                    "ChunkPostProcessor backlog hard limit reached ({} queued chunks ≥ max_queue {}). Skipping enqueue of new chunk scans until backlog reduces.",
                    backlog,
                    MAX_QUEUE
                );
            }
            return;
        }

        if (backlog >= QUEUE_ALERT_THRESHOLD) {
            if (LOGGED_QUEUE_ALERT.compareAndSet(false, true)) {
                LCLogger.warn(
                    "ChunkPostProcessor backlog elevated ({} queued chunks >= alert threshold {}). Throttling new work.",
                    backlog,
                    QUEUE_ALERT_THRESHOLD
                );
            }

            try {
                if (ServerTickLoad.shouldPauseNonCritical(level.getServer())) {
                    return;
                }
            } catch (Throwable ignored) {
            }

            long gate = ((long) key.chunkX() << 32) ^ (key.chunkZ() & 0xffffffffL);
            if (key.dimension() != null) {
                gate ^= key.dimension().hashCode();
            }
            if ((gate & 7L) != 0L) {
                return;
            }
        } else if (backlog < QUEUE_ALERT_THRESHOLD / 2) {
            LOGGED_QUEUE_ALERT.set(false);
            LOGGED_QUEUE_HARD_LIMIT.set(false);
        }

        // During spawn prep (no players), avoid continuously increasing backlog.
        try {
            if (level.getServer().getPlayerCount() == 0 && backlog >= QUEUE_ALERT_THRESHOLD) {
                return;
            }
        } catch (Throwable ignored) {
        }

        int minY = chunk.getLevel().getMinBuildHeight();
        CHUNK_SCAN_PROGRESS.computeIfAbsent(key, k -> new ScanCursor(0, minY, 0));
    }

    @SubscribeEvent
    public static void onChunkUnload(ChunkEvent.Unload event) {
        if (!(event.getChunk() instanceof LevelChunk chunk)) return;
        if (!(chunk.getLevel() instanceof ServerLevel)) {
            return;
        }
        ChunkScanKey key = chunkKey(chunk.getLevel().dimension().location(), chunk.getPos().x, chunk.getPos().z);
        CHUNK_SCAN_PROGRESS.remove(key);
        COMPLETED_CHUNKS.remove(key);
        CITY_CHUNK_CACHE.remove(key);
        PROTECTED_TREE_BLOCKS.remove(key);
    }

    @SubscribeEvent
    public static void onChunkDataLoad(ChunkDataEvent.Load event) {
        if (!(event.getChunk() instanceof LevelChunk chunk)) return;
        if (!(chunk.getLevel() instanceof ServerLevel)) {
            return;
        }
        CompoundTag root = event.getData().getCompound(DATA_ROOT);
        if (root.getBoolean(DATA_FLAG)) {
            COMPLETED_CHUNKS.add(chunkKey(chunk.getLevel().dimension().location(), chunk.getPos().x, chunk.getPos().z));
        }
    }

    @SubscribeEvent
    public static void onChunkDataSave(ChunkDataEvent.Save event) {
        if (!(event.getChunk() instanceof LevelChunk chunk)) return;
        if (!(chunk.getLevel() instanceof ServerLevel)) {
            return;
        }
        ChunkScanKey key = chunkKey(chunk.getLevel().dimension().location(), chunk.getPos().x, chunk.getPos().z);
        if (!COMPLETED_CHUNKS.contains(key)) {
            return;
        }
        CompoundTag data = event.getData();
        CompoundTag root = data.getCompound(DATA_ROOT);
        root.putBoolean(DATA_FLAG, true);
        data.put(DATA_ROOT, root);
    }

    public static int getPendingScanCount() {
        return CHUNK_SCAN_PROGRESS.size();
    }

    @SubscribeEvent
    public static void onLevelTick(TickEvent.LevelTickEvent event) {
        boolean floatingScanEnabled = ConfigManager.ENABLE_FLOATING_VEGETATION_REMOVAL && ENABLE_FLOATING_SCAN;
        if (!floatingScanEnabled && !ConfigManager.ENABLE_ASYNC_DOUBLE_BLOCK_BATCHER) return;
        if (!(event.level instanceof ServerLevel level) || event.phase != TickEvent.Phase.END) return;

        // Hard governor: never contribute to lag. If the tick is already "spent", skip all post-processing.
        if (ServerTickLoad.shouldPauseNonCritical(level.getServer())) {
            return;
        }

        long tick = level.getServer().getTickCount();
        if (FLOATING_DRAIN_REQUESTED.get() && LAST_FLOATING_DRAIN_TICK != tick) {
            LAST_FLOATING_DRAIN_TICK = tick;
            drainFloatingChecks(level.getServer());
        }
        boolean canAutoRescan = AUTO_RESCAN_STARTUP || level.getServer().getPlayerCount() > 0;
        if (!RESCAN_TRIGGERED && canAutoRescan && tick > 100) { // ~5 seconds after server start (or after first join)
            RESCAN_TRIGGERED = true;
            RESCAN_IN_PROGRESS = true;
            RESCAN_START_TICK = tick;
            COMPLETED_CHUNKS.clear();
            enqueueLoadedChunks(level);
            LCLogger.info("LC2H ChunkPostProcessor rescan triggered (auto after 5s)");
        }

        double avgTick = level.getServer().getAverageTickTime();

        // Optional legacy mode: drain backlog in a single scheduled task.
        // Disabled by default because it can starve server ticks when backlog is large.
        boolean useBatchDrain = !ENABLE_THREADED_SCAN && (ENABLE_BATCH_DRAIN
            || (CHUNK_SCAN_PROGRESS.size() >= QUEUE_ALERT_THRESHOLD && avgTick < (TICK_TIME_BUDGET_MS * 0.65D)));
        if (useBatchDrain) {
            if (!CHUNK_SCAN_PROGRESS.isEmpty() && BATCH_IN_FLIGHT.compareAndSet(false, true)) {
                submitBatch(level);
            }
            if (BATCH_IN_FLIGHT.get()) {
                return; // batch will handle draining; skip incremental work
            }
        }

        if (avgTick > TICK_TIME_BUDGET_MS) {
            return;
        }

        // Auto-tuned time-slicing: use spare tick time without causing spikes.
        int playerCount = 0;
        try {
            playerCount = level.getServer().getPlayerCount();
        } catch (Throwable ignored) {
        }

        double workBudgetMs = computeWorkBudgetMs(avgTick, playerCount, CHUNK_SCAN_PROGRESS.size());
        long deadlineNs = System.nanoTime() + (long) (workBudgetMs * 1_000_000.0);
        int maxChunksThisTick = computeMaxChunksThisTick(workBudgetMs);
        int scanSamplesPerTask = computeScanSamplesPerTask(workBudgetMs, avgTick, playerCount, ENABLE_THREADED_SCAN);

        int processed = 0;
        ResourceLocation dimension = level.dimension().location();
        for (var it = CHUNK_SCAN_PROGRESS.entrySet().iterator(); it.hasNext() && processed < maxChunksThisTick; ) {
            if (System.nanoTime() > deadlineNs) {
                break;
            }
            Map.Entry<ChunkScanKey, ScanCursor> entry = it.next();
            ChunkScanKey key = entry.getKey();
            if (!dimension.equals(key.dimension())) {
                continue;
            }
            int chunkX = key.chunkX();
            int chunkZ = key.chunkZ();

            if (!level.hasChunk(chunkX, chunkZ)) {
                continue;
            }

            LevelChunk chunk = level.getChunk(chunkX, chunkZ);
            ScanCursor cursor = entry.getValue();
            if (ENABLE_THREADED_SCAN) {
                if (INFLIGHT_CHUNK_SCANS.add(key)) {
                    boolean allowFloating = floatingScanEnabled && isCityChunkCached(level, chunkX, chunkZ);
                    submitAsyncScan(level, key, chunk, cursor, allowFloating, scanSamplesPerTask);
                    processed++;
                }
                if (processed >= MAX_ASYNC_CHUNKS_PER_TICK) {
                    break;
                }
                continue;
            }
            boolean allowFloating = floatingScanEnabled && isCityChunkCached(level, chunkX, chunkZ);
            ScanCursor next = processChunk(level, chunk, cursor, deadlineNs, allowFloating, scanSamplesPerTask);
            if (next == null) {
                markChunkComplete(chunk);
                it.remove();
            } else {
                entry.setValue(next);
            }
            processed++;
        }

        if (ENABLE_THREADED_SCAN) {
            maybeFinishRescan(level);
        } else if (RESCAN_IN_PROGRESS && CHUNK_SCAN_PROGRESS.isEmpty()) {
            long durationTicks = Math.max(1, level.getServer().getTickCount() - RESCAN_START_TICK);
            LCLogger.info("LC2H ChunkPostProcessor rescan completed: fixed double-blocks={}, removed floating={}, duration={} ticks",
                    FIXED_DOUBLE_BLOCKS, FIXED_FLOATING, durationTicks);
            FIXED_DOUBLE_BLOCKS = 0;
            FIXED_FLOATING = 0;
            RESCAN_IN_PROGRESS = false;
        }
    }

    private static ScanCursor processChunk(ServerLevel level, LevelChunk chunk, ScanCursor cursor, long deadlineNs, boolean floatingScanEnabled, int sampleBudget) {
        int minY = level.getMinBuildHeight();
        int maxY = level.getMaxBuildHeight() - 1;
        int baseX = chunk.getPos().getMinBlockX();
        int baseZ = chunk.getPos().getMinBlockZ();
        LevelChunkSection[] sections = chunk.getSections();
        boolean doubleBlockEnabled = ConfigManager.ENABLE_ASYNC_DOUBLE_BLOCK_BATCHER;
        int budget = Math.max(MIN_SAMPLES_PER_TASK, Math.min(SAMPLES_PER_CHUNK, sampleBudget));

        ScanCursor current = cursor;
        int samples = 0;
        while (samples < budget) {
            if (System.nanoTime() > deadlineNs) {
                // Yield to the next tick; keep the cursor so we can resume.
                return current;
            }
            if (current.y < minY) {
                current = new ScanCursor(current.x, minY, current.z);
            }
            if (current.y > maxY) {
                return null;
            }

            int sectionIndex = chunk.getSectionIndex(current.y);
            if (sectionIndex < 0 || sectionIndex >= sections.length) {
                current = new ScanCursor(0, Math.max(minY, current.y + 1), 0);
                continue;
            }
            LevelChunkSection section = sections[sectionIndex];
            if (!sectionHasInterestingBlocks(section, floatingScanEnabled, doubleBlockEnabled)) {
                int nextY = minY + sectionIndex * 16 + 16;
                current = new ScanCursor(0, Math.max(nextY, current.y + 1), 0);
                continue;
            }

            BlockPos.MutableBlockPos pos = LOCAL_SCAN_POS.get();
            pos.set(baseX + current.x, current.y, baseZ + current.z);
            BlockState state = chunk.getBlockState(pos);

            if (floatingScanEnabled && shouldWatchFloatingCandidate(state)) {
                if (shouldRemoveFloatingCandidate(level, pos, state)) {
                    level.removeBlockEntity(pos);
                    level.setBlock(pos, AIR_STATE, SAFE_SET_FLAGS);
                    LCLogger.debug("Removed floating vegetation on chunk scan at {}", pos);
                    FIXED_FLOATING++;
                }
            }

            if (doubleBlockEnabled && hasDoubleHalf(state)) {
                repairDoubleHalf(level, pos, state);
            }

            samples++;
            current = current.advance(maxY);
        }

        return current;
    }

    private static void submitAsyncScan(ServerLevel level, ChunkScanKey key, LevelChunk chunk, ScanCursor cursor, boolean floatingScanEnabled, int sampleBudget) {
        int budget = Math.max(MIN_SAMPLES_PER_TASK, Math.min(SAMPLES_PER_CHUNK, sampleBudget));
        CpuBatchScheduler.submit("chunk_post_scan", () -> {
            try {
                ChunkScanResult result = scanChunkAsync(key, chunk, cursor, floatingScanEnabled, budget);
                ServerRescheduler.runOnServer(() -> applyScanResult(level, result));
            } catch (Throwable t) {
                INFLIGHT_CHUNK_SCANS.remove(key);
            }
        });
    }

    private static ChunkScanResult scanChunkAsync(ChunkScanKey key, LevelChunk chunk, ScanCursor cursor, boolean floatingScanEnabled, int sampleBudget) {
        int minY = chunk.getLevel().getMinBuildHeight();
        int maxY = chunk.getLevel().getMaxBuildHeight() - 1;
        int baseX = chunk.getPos().getMinBlockX();
        int baseZ = chunk.getPos().getMinBlockZ();
        LevelChunkSection[] sections = chunk.getSections();
        boolean doubleBlockEnabled = ConfigManager.ENABLE_ASYNC_DOUBLE_BLOCK_BATCHER;
        int budget = Math.max(MIN_SAMPLES_PER_TASK, Math.min(SAMPLES_PER_CHUNK, sampleBudget));

        ScanCursor current = cursor;
        int samples = 0;
        List<Long> floating = null;
        List<Long> doubleBlocks = null;

        while (samples < budget) {
            if (current.y < minY) {
                current = new ScanCursor(current.x, minY, current.z);
            }
            if (current.y > maxY) {
                return new ChunkScanResult(key, null, floating == null ? List.of() : floating, doubleBlocks == null ? List.of() : doubleBlocks);
            }

            int sectionIndex = chunk.getSectionIndex(current.y);
            if (sectionIndex < 0 || sectionIndex >= sections.length) {
                current = new ScanCursor(0, Math.max(minY, current.y + 1), 0);
                continue;
            }
            LevelChunkSection section = sections[sectionIndex];
            if (!sectionHasInterestingBlocks(section, floatingScanEnabled, doubleBlockEnabled)) {
                int nextY = minY + sectionIndex * 16 + 16;
                current = new ScanCursor(0, Math.max(nextY, current.y + 1), 0);
                continue;
            }

            BlockPos.MutableBlockPos pos = LOCAL_SCAN_POS.get();
            pos.set(baseX + current.x, current.y, baseZ + current.z);
            BlockState state;
            try {
                state = chunk.getBlockState(pos);
            } catch (Throwable ignored) {
                current = current.advance(maxY);
                samples++;
                continue;
            }

            if (floatingScanEnabled && shouldWatchFloatingCandidate(state)) {
                BlockPos.MutableBlockPos below = LOCAL_BELOW_POS.get();
                below.set(pos.getX(), pos.getY() - 1, pos.getZ());
                try {
                    BlockState belowState = chunk.getBlockState(below);
                    boolean unsupported = belowState.isAir() || !belowState.isCollisionShapeFullBlock(chunk.getLevel(), below);
                    boolean queueCandidate = false;
                    if (isPotentialFloatingSourceFluid(state) || isHorrorElementBlock(state)) {
                        queueCandidate = belowState.isAir();
                    } else if (isTracked(state.getBlock())) {
                        queueCandidate = unsupported;
                    }
                    if (queueCandidate) {
                        if (floating == null) {
                            floating = new java.util.ArrayList<>();
                        }
                        floating.add(pos.asLong());
                    }
                } catch (Throwable ignored) {
                }
            }

            if (doubleBlockEnabled && hasDoubleHalf(state)) {
                if (doubleBlocks == null) {
                    doubleBlocks = new java.util.ArrayList<>();
                }
                doubleBlocks.add(pos.asLong());
            }

            samples++;
            current = current.advance(maxY);
        }

        return new ChunkScanResult(key, current, floating == null ? List.of() : floating, doubleBlocks == null ? List.of() : doubleBlocks);
    }

    private static void applyScanResult(ServerLevel level, ChunkScanResult result) {
        if (result == null) {
            return;
        }
        ChunkScanKey key = result.key();
        INFLIGHT_CHUNK_SCANS.remove(key);
        if (level == null) {
            return;
        }
        if (!level.hasChunk(key.chunkX(), key.chunkZ())) {
            return;
        }

        LevelChunk chunk = level.getChunk(key.chunkX(), key.chunkZ());
        if (chunk == null) {
            return;
        }

        for (Long posLong : result.floating()) {
            if (posLong == null) {
                continue;
            }
            BlockPos pos = BlockPos.of(posLong);
            if (!level.isLoaded(pos)) {
                continue;
            }
            BlockState state = level.getBlockState(pos);
            if (shouldRemoveFloatingCandidate(level, pos, state)) {
                level.removeBlockEntity(pos);
                level.setBlock(pos, AIR_STATE, SAFE_SET_FLAGS);
                FIXED_FLOATING++;
            }
        }

        for (Long posLong : result.doubleBlocks()) {
            if (posLong == null) {
                continue;
            }
            BlockPos pos = BlockPos.of(posLong);
            if (!level.isLoaded(pos)) {
                continue;
            }
            BlockState state = level.getBlockState(pos);
            if (hasDoubleHalf(state)) {
                repairDoubleHalf(level, pos, state);
            }
        }

        if (result.next() == null) {
            markChunkComplete(chunk);
            CHUNK_SCAN_PROGRESS.remove(key);
        } else {
            CHUNK_SCAN_PROGRESS.put(key, result.next());
        }

        maybeFinishRescan(level);
    }

    private static void maybeFinishRescan(ServerLevel level) {
        if (!RESCAN_IN_PROGRESS) {
            return;
        }
        if (!CHUNK_SCAN_PROGRESS.isEmpty()) {
            return;
        }
        if (!INFLIGHT_CHUNK_SCANS.isEmpty()) {
            return;
        }
        long durationTicks = Math.max(1, level.getServer().getTickCount() - RESCAN_START_TICK);
        LCLogger.info("LC2H ChunkPostProcessor rescan completed: fixed double-blocks={}, removed floating={}, duration={} ticks",
                FIXED_DOUBLE_BLOCKS, FIXED_FLOATING, durationTicks);
        FIXED_DOUBLE_BLOCKS = 0;
        FIXED_FLOATING = 0;
        RESCAN_IN_PROGRESS = false;
    }

    private static int computeMaxChunksThisTick(double workBudgetMs) {
        // Conservative cap; actual time budget is the primary limiter.
        // ~0.75ms per chunk is a rough upper bound for typical scans.
        int byBudget = (int) Math.ceil(workBudgetMs / 0.75D);
        return Math.max(1, Math.min(8, Math.max(MAX_CHUNKS_PER_TICK, byBudget)));
    }

    private static int computeScanSamplesPerTask(double workBudgetMs, double avgTickMs, int playerCount, boolean threaded) {
        int samples = threaded ? Math.max(MIN_SAMPLES_PER_TASK, SAMPLES_PER_CHUNK / 2) : SAMPLES_PER_CHUNK;

        if (avgTickMs >= TICK_TIME_BUDGET_MS * 0.95D || workBudgetMs <= 0.5D) {
            samples = Math.max(MIN_SAMPLES_PER_TASK, samples / 2);
        } else if (avgTickMs >= TICK_TIME_BUDGET_MS * 0.85D || workBudgetMs <= 1.0D) {
            samples = Math.max(MIN_SAMPLES_PER_TASK, (samples * 2) / 3);
        } else if (playerCount <= 0 && workBudgetMs >= 2.0D) {
            samples = Math.min(SAMPLES_PER_CHUNK, samples + 16);
        }

        return Math.max(MIN_SAMPLES_PER_TASK, Math.min(SAMPLES_PER_CHUNK, samples));
    }

    private static double computeWorkBudgetMs(double avgTickMs, int playerCount, int backlog) {
        if (OVERRIDE_MAX_WORK_TIME_PER_TICK_MS > 0.0D) {
            return Math.max(MIN_WORK_TIME_PER_TICK_MS, OVERRIDE_MAX_WORK_TIME_PER_TICK_MS);
        }

        double cap = (playerCount <= 0) ? MAX_WORK_TIME_PER_TICK_MS_STARTUP : MAX_WORK_TIME_PER_TICK_MS_PLAYERS;
        // Target is the lesser of vanilla 20ms or our configured "don't contribute above" threshold.
        double target = Math.min(TARGET_TICK_MS, TICK_TIME_BUDGET_MS);
        double slack = target - avgTickMs;

        // If we have lots of slack, ramp up; if we're close to target, ramp down.
        double next = ADAPTIVE_WORK_BUDGET_MS + (slack * 0.05D);

        // If we are already trending slow, cut harder to avoid spirals.
        if (avgTickMs > target * 0.90D) {
            next *= 0.85D;
        }
        if (avgTickMs > target) {
            next *= 0.50D;
        }

        // If backlog is high and we have spare time, allow a gentle boost.
        if (backlog >= QUEUE_ALERT_THRESHOLD && slack > 3.0D) {
            next += 0.5D;
        }

        // Clamp and slightly smooth to avoid oscillation.
        next = Math.max(MIN_WORK_TIME_PER_TICK_MS, Math.min(cap, next));
        ADAPTIVE_WORK_BUDGET_MS = (ADAPTIVE_WORK_BUDGET_MS * 0.70D) + (next * 0.30D);
        return ADAPTIVE_WORK_BUDGET_MS;
    }

    private static void submitBatch(ServerLevel level) {
        CpuBatchScheduler.submit("chunk_post_batch", () -> level.getServer().execute(() -> {
            try {
                drainAll(level);
            } finally {
                BATCH_IN_FLIGHT.set(false);
            }
        }));
    }

    private static void drainAll(ServerLevel level) {
        int processed = 0;
        ResourceLocation dimension = level.dimension().location();
        while (!CHUNK_SCAN_PROGRESS.isEmpty()) {
            // Even in batch mode, never monopolize the server thread.
            if (level.getServer().getAverageTickTime() > TICK_TIME_BUDGET_MS * 1.5) {
                break;
            }
            boolean processedEntry = false;
            var it = CHUNK_SCAN_PROGRESS.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<ChunkScanKey, ScanCursor> entry = it.next();
                ChunkScanKey key = entry.getKey();
                if (!dimension.equals(key.dimension())) {
                    continue;
                }
                int chunkX = key.chunkX();
                int chunkZ = key.chunkZ();
                if (!level.hasChunk(chunkX, chunkZ)) {
                    it.remove();
                    processedEntry = true;
                    break;
                }
                LevelChunk chunk = level.getChunk(chunkX, chunkZ);
                // Use a generous deadline in batch mode, but still allow yielding.
                long deadlineNs = System.nanoTime() + (long) (Math.max(2.0D, ADAPTIVE_WORK_BUDGET_MS * 4.0D) * 1_000_000.0);
                boolean allowFloating = ConfigManager.ENABLE_FLOATING_VEGETATION_REMOVAL
                    && ENABLE_FLOATING_SCAN
                    && isCityChunkCached(level, chunkX, chunkZ);
                int playerCount = 0;
                try {
                    playerCount = level.getServer().getPlayerCount();
                } catch (Throwable ignored) {
                }
                int scanSamplesPerTask = computeScanSamplesPerTask(
                    Math.max(2.0D, ADAPTIVE_WORK_BUDGET_MS),
                    level.getServer().getAverageTickTime(),
                    playerCount,
                    false
                );
                ScanCursor next = processChunk(level, chunk, entry.getValue(), deadlineNs, allowFloating, scanSamplesPerTask);
                if (next == null) {
                    markChunkComplete(chunk);
                    it.remove();
                } else {
                    entry.setValue(next);
                }
                processed++;
                processedEntry = true;
                break;
            }
            if (!processedEntry) {
                break;
            }
            if ((processed & 63) == 0 && level.getServer().getAverageTickTime() > TICK_TIME_BUDGET_MS * 2) {
                break;
            }
        }
        if (RESCAN_IN_PROGRESS && CHUNK_SCAN_PROGRESS.isEmpty()) {
            long durationTicks = Math.max(1, level.getServer().getTickCount() - RESCAN_START_TICK);
            LCLogger.info("LC2H ChunkPostProcessor rescan completed: fixed double-blocks={}, removed floating={}, duration={} ticks",
                    FIXED_DOUBLE_BLOCKS, FIXED_FLOATING, durationTicks);
            FIXED_DOUBLE_BLOCKS = 0;
            FIXED_FLOATING = 0;
            RESCAN_IN_PROGRESS = false;
        }
        if (CHUNK_SCAN_PROGRESS.isEmpty()) {
            BATCH_IN_FLIGHT.set(false);
        }
    }

    private static void enqueueLoadedChunks(ServerLevel level) {
        try {
            ServerChunkCache cache = level.getChunkSource();
            ChunkMap map = resolveChunkMap(cache);
            if (map == null) {
                LCLogger.warn("ChunkPostProcessor: could not resolve ChunkMap via reflection");
                return;
            }
            boolean floatingScanEnabled = ConfigManager.ENABLE_FLOATING_VEGETATION_REMOVAL && ENABLE_FLOATING_SCAN;
            boolean doubleBlockEnabled = ConfigManager.ENABLE_ASYNC_DOUBLE_BLOCK_BATCHER;
            int backlog = CHUNK_SCAN_PROGRESS.size();
            if (backlog >= MAX_QUEUE) {
                if (LOGGED_QUEUE_HARD_LIMIT.compareAndSet(false, true)) {
                    LCLogger.warn(
                        "ChunkPostProcessor backlog hard limit reached ({} queued chunks >= max_queue {}). Skipping rescan enqueue.",
                        backlog,
                        MAX_QUEUE
                    );
                }
                return;
            }
            Iterable<net.minecraft.server.level.ChunkHolder> holders = resolveChunkHolders(map);
            if (holders != null) {
                for (net.minecraft.server.level.ChunkHolder holder : holders) {
                    LevelChunk chunk = holder.getTickingChunk();
                    if (chunk != null) {
                        boolean cityChunk = floatingScanEnabled && isCityChunkCached(level, chunk.getPos().x, chunk.getPos().z);
                        boolean effectiveFloating = floatingScanEnabled && cityChunk;
                        if (!chunkHasInterestingBlocks(chunk, effectiveFloating, doubleBlockEnabled)) {
                            ChunkScanKey key = chunkKey(level.dimension().location(), chunk.getPos().x, chunk.getPos().z);
                            COMPLETED_CHUNKS.add(key);
                            continue;
                        }
                        ChunkScanKey key = chunkKey(level.dimension().location(), chunk.getPos().x, chunk.getPos().z);
                        int minY = chunk.getLevel().getMinBuildHeight();
                        if (CHUNK_SCAN_PROGRESS.putIfAbsent(key, new ScanCursor(0, minY, 0)) == null) {
                            backlog++;
                            if (backlog >= MAX_QUEUE) {
                                if (LOGGED_QUEUE_HARD_LIMIT.compareAndSet(false, true)) {
                                    LCLogger.warn(
                                        "ChunkPostProcessor backlog hard limit reached ({} queued chunks >= max_queue {}). Halting rescan enqueue.",
                                        backlog,
                                        MAX_QUEUE
                                    );
                                }
                                break;
                            }
                        }
                    }
                }
            } else {
                LCLogger.warn("ChunkPostProcessor: could not iterate chunk holders for rescan");
            }
        } catch (Throwable t) {
            LCLogger.warn("Failed to enqueue loaded chunks for rescan: {}", t.toString());
        }
    }

    private static ChunkMap resolveChunkMap(ServerChunkCache cache) {
        try {
            var field = cache.getClass().getDeclaredField("chunkMap");
            field.setAccessible(true);
            Object obj = field.get(cache);
            if (obj instanceof ChunkMap map) return map;
        } catch (NoSuchFieldException ignored) {
        } catch (Throwable t) {
            LCLogger.debug("ChunkPostProcessor: error accessing chunkMap field: {}", t.toString());
        }

        try {
            for (var f : cache.getClass().getDeclaredFields()) {
                if (ChunkMap.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    Object obj = f.get(cache);
                    if (obj instanceof ChunkMap map) return map;
                }
            }
        } catch (Throwable t) {
            LCLogger.debug("ChunkPostProcessor: reflective chunkMap lookup failed: {}", t.toString());
        }
        return null;
    }

    private static Iterable<net.minecraft.server.level.ChunkHolder> resolveChunkHolders(ChunkMap map) {
        String[] methodCandidates = new String[]{"getChunks", "m_140338_"};
        for (String name : methodCandidates) {
            try {
                var m = map.getClass().getDeclaredMethod(name);
                m.setAccessible(true);
                Object res = m.invoke(map);
                if (res instanceof Iterable<?> iterable) {
                    java.util.ArrayList<net.minecraft.server.level.ChunkHolder> list = new java.util.ArrayList<>();
                    for (Object o : iterable) {
                        if (o instanceof net.minecraft.server.level.ChunkHolder holder) {
                            list.add(holder);
                        }
                    }
                    if (!list.isEmpty()) return list;
                }
            } catch (NoSuchMethodException ignored) {
            } catch (Throwable t) {
                LCLogger.debug("ChunkPostProcessor: chunk holder method {} failed: {}", name, t.toString());
            }
        }

        try {
            for (var f : map.getClass().getDeclaredFields()) {
                f.setAccessible(true);
                Object val = f.get(map);
                if (val instanceof java.util.Map<?, ?> m) {
                    java.util.ArrayList<net.minecraft.server.level.ChunkHolder> list = new java.util.ArrayList<>();
                    for (Object o : m.values()) {
                        if (o instanceof net.minecraft.server.level.ChunkHolder holder) {
                            list.add(holder);
                        }
                    }
                    if (!list.isEmpty()) return list;
                } else if (val instanceof Iterable<?> iterable) {
                    java.util.ArrayList<net.minecraft.server.level.ChunkHolder> list = new java.util.ArrayList<>();
                    for (Object o : iterable) {
                        if (o instanceof net.minecraft.server.level.ChunkHolder holder) {
                            list.add(holder);
                        }
                    }
                    if (!list.isEmpty()) return list;
                }
            }
        } catch (Throwable t) {
            LCLogger.debug("ChunkPostProcessor: chunk holder field scan failed: {}", t.toString());
        }
        return null;
    }

    private static boolean sectionHasInterestingBlocks(LevelChunkSection section,
                                                       boolean floatingScanEnabled,
                                                       boolean doubleBlockEnabled) {
        if (section.hasOnlyAir()) {
            return false;
        }
        if (!floatingScanEnabled && !doubleBlockEnabled) {
            return false;
        }
        PalettedContainer<BlockState> states = section.getStates();
        return states.maybeHas(state -> {
            if (state == null) {
                return false;
            }
            if (doubleBlockEnabled && hasDoubleHalf(state)) {
                return true;
            }
            return floatingScanEnabled && shouldWatchFloatingCandidate(state);
        });
    }

    private static boolean chunkHasInterestingBlocks(LevelChunk chunk,
                                                     boolean floatingScanEnabled,
                                                     boolean doubleBlockEnabled) {
        LevelChunkSection[] sections = chunk.getSections();
        for (LevelChunkSection section : sections) {
            if (section != null && sectionHasInterestingBlocks(section, floatingScanEnabled, doubleBlockEnabled)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasDoubleHalf(BlockState state) {
        return state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF);
    }

    private static void repairDoubleHalf(ServerLevel level, BlockPos pos, BlockState state) {
        Property<DoubleBlockHalf> halfProp = BlockStateProperties.DOUBLE_BLOCK_HALF;
        DoubleBlockHalf half = state.getValue(halfProp);
        boolean isDoublePlant = state.getBlock() instanceof net.minecraft.world.level.block.DoublePlantBlock;

        if (half == DoubleBlockHalf.LOWER) {
            BlockPos upperPos = pos.above();
            BlockState upperState = level.getBlockState(upperPos);
            boolean missingUpper = !upperState.is(state.getBlock()) || !upperState.hasProperty(halfProp) || upperState.getValue(halfProp) != DoubleBlockHalf.UPPER;

            if (missingUpper && canReplace(level, upperPos)) {
                BlockState newUpper = copyHalf(state, halfProp, DoubleBlockHalf.UPPER);
                level.setBlock(upperPos, newUpper, SAFE_SET_FLAGS);
            }
        } else {
            BlockPos lowerPos = pos.below();
            BlockState lowerState = level.getBlockState(lowerPos);
            boolean missingLower = !lowerState.is(state.getBlock()) || !lowerState.hasProperty(halfProp) || lowerState.getValue(halfProp) != DoubleBlockHalf.LOWER;

            if (missingLower) {
                if (isDoublePlant && canReplace(level, lowerPos)) {
                    BlockPos support = lowerPos.below();
                    BlockState supportState = level.getBlockState(support);
                    if (!supportState.isCollisionShapeFullBlock(level, support)) {
                        level.setBlock(support, Blocks.DIRT.defaultBlockState(), SAFE_SET_FLAGS);
                    }
                    BlockState newLower = copyHalf(state, halfProp, DoubleBlockHalf.LOWER);
                    level.setBlock(lowerPos, newLower, SAFE_SET_FLAGS);
                    level.setBlock(pos, copyHalf(state, halfProp, DoubleBlockHalf.UPPER), SAFE_SET_FLAGS);
                } else if (canReplace(level, lowerPos) && state.canSurvive(level, lowerPos)) {
                    BlockState newLower = copyHalf(state, halfProp, DoubleBlockHalf.LOWER);
                    level.setBlock(lowerPos, newLower, SAFE_SET_FLAGS);
                } else {
                    level.setBlock(pos, Blocks.AIR.defaultBlockState(), SAFE_SET_FLAGS);
                }
            }
        }
    }

    private static BlockState copyHalf(BlockState original, Property<DoubleBlockHalf> prop, DoubleBlockHalf value) {
        return original.setValue(prop, value);
    }

    private static boolean canReplace(ServerLevel level, BlockPos pos) {
        BlockState target = level.getBlockState(pos);
        return target.isAir() || target.canBeReplaced();
    }

    private static void markChunkComplete(LevelChunk chunk) {
        ChunkScanKey key = chunkKey(chunk.getLevel().dimension().location(), chunk.getPos().x, chunk.getPos().z);
        COMPLETED_CHUNKS.add(key);
    }

    public static void forceRescanChunk(ServerLevel level, net.minecraft.world.level.ChunkPos pos) {
        ChunkScanKey key = chunkKey(level.dimension().location(), pos.x, pos.z);
        COMPLETED_CHUNKS.remove(key);
        int backlog = CHUNK_SCAN_PROGRESS.size();
        if (backlog >= MAX_QUEUE) {
            if (LOGGED_QUEUE_HARD_LIMIT.compareAndSet(false, true)) {
                LCLogger.warn(
                    "ChunkPostProcessor backlog hard limit reached ({} queued chunks >= max_queue {}). Skipping forced rescan enqueue.",
                    backlog,
                    MAX_QUEUE
                );
            }
            return;
        }
        int minY = level.getMinBuildHeight();
        CHUNK_SCAN_PROGRESS.put(key, new ScanCursor(0, minY, 0));
        LCLogger.info("ChunkPostProcessor: forced rescan queued for chunk ({}, {})", pos.x, pos.z);
    }

    private static ChunkScanKey chunkKey(ResourceLocation dimension, int chunkX, int chunkZ) {
        return new ChunkScanKey(dimension, chunkX, chunkZ);
    }

    private static void enqueueFloatingCheck(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null) {
            return;
        }
        if (!shouldScanFloatingInChunk(level, pos.getX() >> 4, pos.getZ() >> 4)) {
            return;
        }
        ResourceKey<Level> dimension = level.dimension();
        PendingCheckQueue bucket = PENDING_FLOATING.computeIfAbsent(dimension, k -> new PendingCheckQueue());
        bucket.dimensionKey = dimension;
        bucket.level = level;
        int pending = bucket.size.incrementAndGet();
        if (pending > MAX_PENDING_FLOATING_CHECKS) {
            bucket.size.decrementAndGet();
            if (LOGGED_FLOATING_QUEUE_HARD_LIMIT.compareAndSet(false, true)) {
                LCLogger.warn(
                    "ChunkPostProcessor floating check queue full ({} >= {}). Dropping new checks until backlog reduces.",
                    pending,
                    MAX_PENDING_FLOATING_CHECKS
                );
            }
            return;
        }
        bucket.queue.add(pos.asLong());
        if (pending < MAX_PENDING_FLOATING_CHECKS / 2) {
            LOGGED_FLOATING_QUEUE_HARD_LIMIT.set(false);
        }
        FLOATING_DRAIN_REQUESTED.set(true);
    }

    private static void drainFloatingChecks(MinecraftServer server) {
        if (!ConfigManager.ENABLE_FLOATING_VEGETATION_REMOVAL) {
            FLOATING_DRAIN_REQUESTED.set(false);
            return;
        }
        if (server != null && ServerTickLoad.shouldPauseNonCritical(server)) {
            FLOATING_DRAIN_REQUESTED.set(true);
            return;
        }
        int remainingBudget = MAX_FLOATING_CHECKS_PER_TICK;
        boolean pending = false;
        for (PendingCheckQueue bucket : PENDING_FLOATING.values()) {
            if (remainingBudget <= 0) {
                pending = pending || bucket.size.get() > 0;
                continue;
            }
            ServerLevel level = bucket.level;
            if (level == null && server != null && bucket.dimensionKey != null) {
                level = server.getLevel(bucket.dimensionKey);
                bucket.level = level;
            }
            if (level == null) {
                pending = pending || bucket.size.get() > 0;
                continue;
            }
            while (remainingBudget > 0) {
                Long posLong = bucket.queue.poll();
                if (posLong == null) {
                    break;
                }
                if (bucket.size.decrementAndGet() < 0) {
                    bucket.size.set(0);
                }
                BlockPos pos = BlockPos.of(posLong);
                if (!level.isLoaded(pos)) {
                    continue;
                }
                if (!shouldScanFloatingInChunk(level, pos.getX() >> 4, pos.getZ() >> 4)) {
                    continue;
                }
                BlockState state = level.getBlockState(pos);
                if (shouldRemoveFloatingCandidate(level, pos, state)) {
                    level.removeBlockEntity(pos);
                    level.setBlock(pos, AIR_STATE, SAFE_SET_FLAGS);
                    FIXED_FLOATING++;
                }
                remainingBudget--;
            }
            if (bucket.size.get() > 0) {
                pending = true;
            }
        }
        if (pending) {
            FLOATING_DRAIN_REQUESTED.set(true);
            return;
        }
        FLOATING_DRAIN_REQUESTED.set(false);
    }

    private static boolean shouldScanFloatingInChunk(ServerLevel level, int cx, int cz) {
        if (level == null) {
            return false;
        }
        if (!ConfigManager.ENABLE_FLOATING_VEGETATION_REMOVAL || !ENABLE_FLOATING_SCAN) {
            return false;
        }
        return isCityChunkCached(level, cx, cz);
    }

    private static IDimensionInfo getDimensionInfo(ServerLevel level) {
        if (level == null) {
            return null;
        }
        ResourceKey<Level> dim = level.dimension();
        IDimensionInfo cached = DIMENSION_INFO_CACHE.get(dim);
        if (cached != null) {
            return cached;
        }
        try {
            IDimensionInfo info = Registration.LOSTCITY_FEATURE.get().getDimensionInfo(level);
            if (info != null) {
                DIMENSION_INFO_CACHE.put(dim, info);
            }
            return info;
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static void markTreePlacement(net.minecraft.server.level.WorldGenRegion region, BlockPos pos, BlockState state) {
        if (!ConfigManager.CITY_BLEND_TREE_SEAM_FIX) {
            return;
        }
        if (region == null || pos == null || state == null) {
            return;
        }
        if (!isTreeProtectedBlock(state)) {
            return;
        }
        ServerLevel level = resolveServerLevel(region);
        if (level == null) {
            return;
        }
        int cx = pos.getX() >> 4;
        int cz = pos.getZ() >> 4;
        if (!isCityChunkCached(level, cx, cz)) {
            return;
        }
        ChunkScanKey key = chunkKey(level.dimension().location(), cx, cz);
        PROTECTED_TREE_BLOCKS
            .computeIfAbsent(key, k -> new java.util.concurrent.ConcurrentHashMap<>())
            .put(pos.asLong(), Boolean.TRUE);
    }

    public static boolean shouldPreventTreeAirOverwrite(net.minecraft.server.level.WorldGenRegion region, BlockPos pos, BlockState newState) {
        if (!ConfigManager.CITY_BLEND_TREE_SEAM_FIX) {
            return false;
        }
        if (region == null || pos == null || newState == null) {
            return false;
        }
        if (!newState.isAir()) {
            return false;
        }
        ServerLevel level = resolveServerLevel(region);
        if (level == null) {
            return false;
        }
        int cx = pos.getX() >> 4;
        int cz = pos.getZ() >> 4;
        if (!isCityChunkCached(level, cx, cz)) {
            return false;
        }
        BlockState existing = region.getBlockState(pos);
        if (!isTreeProtectedBlock(existing)) {
            return false;
        }
        ChunkScanKey key = chunkKey(level.dimension().location(), cx, cz);
        java.util.concurrent.ConcurrentHashMap<Long, Boolean> protectedSet = PROTECTED_TREE_BLOCKS.get(key);
        if (protectedSet != null && protectedSet.containsKey(pos.asLong())) {
            return true;
        }
        return true;
    }

    private static boolean isTreeProtectedBlock(BlockState state) {
        if (state == null) {
            return false;
        }
        Block block = state.getBlock();
        Boolean cached = TREE_PROTECTED_BLOCK_CACHE.get(block);
        if (cached != null) {
            return cached;
        }
        boolean protectedTree = state.is(BlockTags.LEAVES) || state.is(BlockTags.LOGS);
        if (!protectedTree) {
            ResourceLocation key = ForgeRegistries.BLOCKS.getKey(block);
            protectedTree = key != null && HORROR_ELEMENT_NAMESPACE.equals(key.getNamespace());
        }
        TREE_PROTECTED_BLOCK_CACHE.put(block, protectedTree);
        return protectedTree;
    }

    public static boolean isTreeProtectedBlockForDebug(BlockState state) {
        return isTreeProtectedBlock(state);
    }

    public static int getProtectedTreeBlockCount(ServerLevel level, int cx, int cz) {
        if (level == null) {
            return 0;
        }
        ChunkScanKey key = chunkKey(level.dimension().location(), cx, cz);
        java.util.concurrent.ConcurrentHashMap<Long, Boolean> protectedSet = PROTECTED_TREE_BLOCKS.get(key);
        return protectedSet == null ? 0 : protectedSet.size();
    }

    public static boolean isSeamChunk(ServerLevel level, int cx, int cz) {
        if (level == null) {
            return false;
        }
        boolean originCity = isCityChunkCached(level, cx, cz);
        if (isCityChunkCached(level, cx + 1, cz) != originCity) return true;
        if (isCityChunkCached(level, cx - 1, cz) != originCity) return true;
        if (isCityChunkCached(level, cx, cz + 1) != originCity) return true;
        if (isCityChunkCached(level, cx, cz - 1) != originCity) return true;
        return false;
    }

    private static boolean isCityChunkCached(ServerLevel level, int cx, int cz) {
        ChunkScanKey key = chunkKey(level.dimension().location(), cx, cz);
        Boolean cached = CITY_CHUNK_CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        IDimensionInfo dimInfo = getDimensionInfo(level);
        boolean isCity = false;
        if (dimInfo != null) {
            try {
                isCity = BuildingInfo.isCity(new ChunkCoord(level.dimension(), cx, cz), dimInfo);
            } catch (Throwable ignored) {
                isCity = false;
            }
        }
        CITY_CHUNK_CACHE.put(key, isCity);
        return isCity;
    }
}
