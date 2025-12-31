package org.admany.lc2h.util.chunk;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
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
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.event.level.ChunkDataEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.admany.lc2h.LC2H;
import org.admany.lc2h.logging.LCLogger;
import org.admany.lc2h.logging.config.ConfigManager;
import org.admany.lc2h.util.batch.CpuBatchScheduler;
import org.admany.lc2h.util.server.ServerTickLoad;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Mod.EventBusSubscriber(modid = LC2H.MODID)
public class ChunkPostProcessor {

    private static final List<Block> TRACKED_BLOCKS = Arrays.asList(
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
    );

    private static final ThreadLocal<LongSet> PENDING_REMOVALS = ThreadLocal.withInitial(LongOpenHashSet::new);

    private static final int MAX_CHUNKS_PER_TICK = Math.max(1, Integer.getInteger("lc.floating.max_chunks_per_tick", 1));
    private static final int SAMPLES_PER_CHUNK = Math.max(16, Integer.getInteger("lc.floating.samples_per_chunk", 96));
    private static final int MAX_QUEUE = Math.max(256, Integer.getInteger("lc.floating.max_queue", 4096));
    private static final int QUEUE_ALERT_THRESHOLD = Math.max(
        128,
        Integer.getInteger("lc.floating.queue_alert_threshold", Math.min(MAX_QUEUE, 1024))
    );
    private static final double TICK_TIME_BUDGET_MS = Math.max(5D, Double.parseDouble(System.getProperty("lc.floating.tick_budget_ms", "35")));
    // If set (>0), forces a fixed work budget. Otherwise, the post processor auto-tunes.
    private static final double OVERRIDE_MAX_WORK_TIME_PER_TICK_MS = Double.parseDouble(System.getProperty("lc.floating.max_work_ms_per_tick", "-1"));
    private static final double MIN_WORK_TIME_PER_TICK_MS = 0.25D;
    private static final double MAX_WORK_TIME_PER_TICK_MS_PLAYERS = 6.0D;
    private static final double MAX_WORK_TIME_PER_TICK_MS_STARTUP = 12.0D;
    private static final double TARGET_TICK_MS = 20.0D;
    private static final boolean ENABLE_BATCH_DRAIN = Boolean.getBoolean("lc.floating.enable_batch_drain");
    private static final boolean AUTO_RESCAN_STARTUP = Boolean.getBoolean("lc.floating.auto_rescan_startup");

    private static final String DATA_ROOT = "lc2h";
    private static final String DATA_FLAG = "doubleblock_repaired";

    private static final Map<Long, ScanCursor> CHUNK_SCAN_PROGRESS = new ConcurrentHashMap<>();
    private static final LongSet COMPLETED_CHUNKS = new LongOpenHashSet();
    private static final AtomicBoolean LOGGED_QUEUE_ALERT = new AtomicBoolean(false);
    private static final AtomicBoolean LOGGED_QUEUE_HARD_LIMIT = new AtomicBoolean(false);
    private static final AtomicBoolean BATCH_IN_FLIGHT = new AtomicBoolean(false);
    private static volatile boolean RESCAN_TRIGGERED = false;
    private static volatile boolean RESCAN_IN_PROGRESS = false;
    private static volatile long RESCAN_START_TICK = 0;
    private static int FIXED_DOUBLE_BLOCKS = 0;
    private static int FIXED_FLOATING = 0;

    private static volatile double ADAPTIVE_WORK_BUDGET_MS = 1.5D;

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

    public static boolean isTracked(Block block) {
        return TRACKED_BLOCKS.contains(block);
    }

    public static void markForRemovalIfFloating(net.minecraft.server.level.WorldGenRegion region, BlockPos pos) {
        if (!ConfigManager.ENABLE_FLOATING_VEGETATION_REMOVAL) return;

        BlockState state = region.getBlockState(pos);
        if (!isTracked(state.getBlock())) return;

        BlockPos below = pos.below();
        if (region.isEmptyBlock(below) || !region.getBlockState(below).isCollisionShapeFullBlock(region, below)) {
            PENDING_REMOVALS.get().add(pos.asLong());
        }
    }


    public static void processPendingRemovals(ServerLevel level) {
        if (!ConfigManager.ENABLE_FLOATING_VEGETATION_REMOVAL) return;

        LongSet removals = PENDING_REMOVALS.get();
        if (removals.isEmpty()) return;

        BlockState air = Blocks.AIR.defaultBlockState();
        for (long posLong : removals) {
            BlockPos pos = BlockPos.of(posLong);
            if (level.isLoaded(pos) && isTracked(level.getBlockState(pos).getBlock())) {
                // Defensive: if a modded plant ever ends up with a BE, ensure it is removed.
                level.removeBlockEntity(pos);
                level.setBlock(pos, air, 3);
                LCLogger.info("Removed floating vegetation at {}", pos);
            }
        }
        removals.clear();
    }

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!ConfigManager.ENABLE_FLOATING_VEGETATION_REMOVAL && !ConfigManager.ENABLE_ASYNC_DOUBLE_BLOCK_BATCHER) return;
        if (!(event.getChunk() instanceof LevelChunk chunk)) return;

        // IMPORTANT: only track on the server. Client chunk loads can flood the queue on integrated servers.
        if (!(chunk.getLevel() instanceof ServerLevel level)) {
            return;
        }

        long key = chunkKey(chunk.getPos().x, chunk.getPos().z);

        // If we already marked this chunk complete, don't enqueue it again.
        if (COMPLETED_CHUNKS.contains(key)) {
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

            long gate = key;
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
        long key = chunkKey(chunk.getPos().x, chunk.getPos().z);
        CHUNK_SCAN_PROGRESS.remove(key);
        COMPLETED_CHUNKS.remove(key);
    }

    @SubscribeEvent
    public static void onChunkDataLoad(ChunkDataEvent.Load event) {
        if (!(event.getChunk() instanceof LevelChunk chunk)) return;
        if (!(chunk.getLevel() instanceof ServerLevel)) {
            return;
        }
        CompoundTag root = event.getData().getCompound(DATA_ROOT);
        if (root.getBoolean(DATA_FLAG)) {
            COMPLETED_CHUNKS.add(chunkKey(chunk.getPos().x, chunk.getPos().z));
        }
    }

    @SubscribeEvent
    public static void onChunkDataSave(ChunkDataEvent.Save event) {
        if (!(event.getChunk() instanceof LevelChunk chunk)) return;
        if (!(chunk.getLevel() instanceof ServerLevel)) {
            return;
        }
        long key = chunkKey(chunk.getPos().x, chunk.getPos().z);
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
        if (!ConfigManager.ENABLE_FLOATING_VEGETATION_REMOVAL && !ConfigManager.ENABLE_ASYNC_DOUBLE_BLOCK_BATCHER) return;
        if (!(event.level instanceof ServerLevel level) || event.phase != TickEvent.Phase.END) return;

        // Hard governor: never contribute to lag. If the tick is already "spent", skip all post-processing.
        if (ServerTickLoad.shouldPauseNonCritical(level.getServer())) {
            return;
        }

        long tick = level.getServer().getTickCount();
        boolean canAutoRescan = AUTO_RESCAN_STARTUP || level.getServer().getPlayerCount() > 0;
        if (!RESCAN_TRIGGERED && canAutoRescan && tick > 100) { // ~5 seconds after server start (or after first join)
            RESCAN_TRIGGERED = true;
            RESCAN_IN_PROGRESS = true;
            RESCAN_START_TICK = tick;
            COMPLETED_CHUNKS.clear();
            enqueueLoadedChunks(level);
            LCLogger.info("LC2H ChunkPostProcessor rescan triggered (auto after 5s)");
        }

        // Optional legacy mode: drain backlog in a single scheduled task.
        // Disabled by default because it can starve server ticks when backlog is large.
        if (ENABLE_BATCH_DRAIN) {
            if (!CHUNK_SCAN_PROGRESS.isEmpty() && BATCH_IN_FLIGHT.compareAndSet(false, true)) {
                submitBatch(level);
            }
            if (BATCH_IN_FLIGHT.get()) {
                return; // batch will handle draining; skip incremental work
            }
        }

        double avgTick = level.getServer().getAverageTickTime();
        if (avgTick > TICK_TIME_BUDGET_MS) {
            return;
        }

        if (ConfigManager.ENABLE_FLOATING_VEGETATION_REMOVAL) {
            processPendingRemovals(level);
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

        int processed = 0;
        for (var it = CHUNK_SCAN_PROGRESS.entrySet().iterator(); it.hasNext() && processed < maxChunksThisTick; ) {
            if (System.nanoTime() > deadlineNs) {
                break;
            }
            Map.Entry<Long, ScanCursor> entry = it.next();
            long key = entry.getKey();
            int chunkX = (int) (key >> 32);
            int chunkZ = (int) key;

            if (!level.hasChunk(chunkX, chunkZ)) {
                continue;
            }

            LevelChunk chunk = level.getChunk(chunkX, chunkZ);
            ScanCursor cursor = entry.getValue();
            ScanCursor next = processChunk(level, chunk, cursor, deadlineNs);
            if (next == null) {
                markChunkComplete(chunk);
                it.remove();
            } else {
                entry.setValue(next);
            }
            processed++;
        }

        if (RESCAN_IN_PROGRESS && CHUNK_SCAN_PROGRESS.isEmpty()) {
            long durationTicks = Math.max(1, level.getServer().getTickCount() - RESCAN_START_TICK);
            LCLogger.info("LC2H ChunkPostProcessor rescan completed: fixed double-blocks={}, removed floating={}, duration={} ticks",
                    FIXED_DOUBLE_BLOCKS, FIXED_FLOATING, durationTicks);
            FIXED_DOUBLE_BLOCKS = 0;
            FIXED_FLOATING = 0;
            RESCAN_IN_PROGRESS = false;
        }
    }

    private static ScanCursor processChunk(ServerLevel level, LevelChunk chunk, ScanCursor cursor, long deadlineNs) {
        int maxY = level.getMaxBuildHeight() - 1;
        int baseX = chunk.getPos().getMinBlockX();
        int baseZ = chunk.getPos().getMinBlockZ();

        ScanCursor current = cursor;
        int samples = 0;
        while (samples < SAMPLES_PER_CHUNK) {
            if (System.nanoTime() > deadlineNs) {
                // Yield to the next tick; keep the cursor so we can resume.
                return current;
            }
            if (current.y > maxY) {
                return null;
            }

            int sectionIndex = chunk.getSectionIndex(current.y);
            LevelChunkSection section = chunk.getSection(sectionIndex);
            if (!sectionHasInterestingBlocks(section)) {
                int nextY = chunk.getMinBuildHeight() + sectionIndex * 16 + 16;
                current = new ScanCursor(0, Math.max(nextY, current.y + 1), 0);
                continue;
            }

            BlockPos pos = new BlockPos(baseX + current.x, current.y, baseZ + current.z);
            BlockState state = chunk.getBlockState(pos);

            if (ConfigManager.ENABLE_FLOATING_VEGETATION_REMOVAL && isTracked(state.getBlock())) {
                BlockPos below = pos.below();
                if (level.isEmptyBlock(below) || !level.getBlockState(below).isCollisionShapeFullBlock(level, below)) {
                    level.removeBlockEntity(pos);
                    level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
                    LCLogger.debug("Removed floating vegetation on chunk scan at {}", pos);
                    FIXED_FLOATING++;
                }
            }

            if (ConfigManager.ENABLE_ASYNC_DOUBLE_BLOCK_BATCHER && hasDoubleHalf(state)) {
                repairDoubleHalf(level, pos, state);
            }

            samples++;
            current = current.advance(maxY);
        }

        return current;
    }

    private static int computeMaxChunksThisTick(double workBudgetMs) {
        // Conservative cap; actual time budget is the primary limiter.
        // ~0.75ms per chunk is a rough upper bound for typical scans.
        int byBudget = (int) Math.ceil(workBudgetMs / 0.75D);
        return Math.max(1, Math.min(8, Math.max(MAX_CHUNKS_PER_TICK, byBudget)));
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
        while (!CHUNK_SCAN_PROGRESS.isEmpty()) {
            // Even in batch mode, never monopolize the server thread.
            if (level.getServer().getAverageTickTime() > TICK_TIME_BUDGET_MS * 1.5) {
                break;
            }
            var it = CHUNK_SCAN_PROGRESS.entrySet().iterator();
            if (!it.hasNext()) break;
            Map.Entry<Long, ScanCursor> entry = it.next();
            long key = entry.getKey();
            int chunkX = (int) (key >> 32);
            int chunkZ = (int) key;
            if (!level.hasChunk(chunkX, chunkZ)) {
                it.remove();
                continue;
            }
            LevelChunk chunk = level.getChunk(chunkX, chunkZ);
            // Use a generous deadline in batch mode, but still allow yielding.
            long deadlineNs = System.nanoTime() + (long) (Math.max(2.0D, ADAPTIVE_WORK_BUDGET_MS * 4.0D) * 1_000_000.0);
            ScanCursor next = processChunk(level, chunk, entry.getValue(), deadlineNs);
            if (next == null) {
                markChunkComplete(chunk);
                it.remove();
            } else {
                entry.setValue(next);
            }
            processed++;
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
            Iterable<net.minecraft.server.level.ChunkHolder> holders = resolveChunkHolders(map);
            if (holders != null) {
                for (net.minecraft.server.level.ChunkHolder holder : holders) {
                    LevelChunk chunk = holder.getTickingChunk();
                    if (chunk != null) {
                        long key = chunkKey(chunk.getPos().x, chunk.getPos().z);
                        int minY = chunk.getLevel().getMinBuildHeight();
                        CHUNK_SCAN_PROGRESS.computeIfAbsent(key, k -> new ScanCursor(0, minY, 0));
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

    private static boolean sectionHasInterestingBlocks(LevelChunkSection section) {
        if (section.hasOnlyAir()) {
            return false;
        }
        PalettedContainer<BlockState> states = section.getStates();
        return states.maybeHas(ChunkPostProcessor::isInterestingState);
    }

    private static boolean isInterestingState(BlockState state) {
        return isTracked(state.getBlock()) || hasDoubleHalf(state);
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
                level.setBlock(upperPos, newUpper, 2);
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
                        level.setBlock(support, Blocks.DIRT.defaultBlockState(), 2);
                    }
                    BlockState newLower = copyHalf(state, halfProp, DoubleBlockHalf.LOWER);
                    level.setBlock(lowerPos, newLower, 2);
                    level.setBlock(pos, copyHalf(state, halfProp, DoubleBlockHalf.UPPER), 2);
                } else if (canReplace(level, lowerPos) && state.canSurvive(level, lowerPos)) {
                    BlockState newLower = copyHalf(state, halfProp, DoubleBlockHalf.LOWER);
                    level.setBlock(lowerPos, newLower, 2);
                } else {
                    level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
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
        long key = chunkKey(chunk.getPos().x, chunk.getPos().z);
        COMPLETED_CHUNKS.add(key);
    }

    public static void forceRescanChunk(ServerLevel level, net.minecraft.world.level.ChunkPos pos) {
        long key = chunkKey(pos.x, pos.z);
        COMPLETED_CHUNKS.remove(key);
        int minY = level.getMinBuildHeight();
        CHUNK_SCAN_PROGRESS.put(key, new ScanCursor(0, minY, 0));
        LCLogger.info("ChunkPostProcessor: forced rescan queued for chunk ({}, {})", pos.x, pos.z);
    }

    private static long chunkKey(int chunkX, int chunkZ) {
        return (((long) chunkX) << 32) | (chunkZ & 0xffffffffL);
    }
}
