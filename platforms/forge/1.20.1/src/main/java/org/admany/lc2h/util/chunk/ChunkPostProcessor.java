package org.admany.lc2h.util.chunk;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.SnowyDirtBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.event.level.ChunkDataEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import mcjty.lostcities.setup.Registration;
import mcjty.lostcities.worldgen.IDimensionInfo;
import org.admany.lc2h.LC2H;
import org.admany.lc2h.log.LCLogger;
import org.admany.lc2h.config.ConfigManager;
import org.admany.lc2h.dev.diagnostics.Lc2hTimingRegistry;
import org.admany.lc2h.runtime.Lc2hRuntimeModes;
import org.admany.lc2h.util.batch.CpuBatchScheduler;
import org.admany.lc2h.util.server.ServerRescheduler;
import org.admany.lc2h.util.server.ServerTickLoad;
import org.admany.lc2h.worldgen.apply.ChunkShadowMutationPlan;
import org.admany.lc2h.worldgen.apply.ShadowBlockMutationApplier;
import org.admany.lc2h.worldgen.lostcities.ChunkRoleProbe;
import org.admany.lc2h.worldgen.lostcities.DeferredTreeQueue;
import org.admany.lc2h.world.cleanup.VineClusterCleaner;

import java.util.Arrays;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Mod.EventBusSubscriber(modid = LC2H.MODID)
public class ChunkPostProcessor {

    private static final Set<Block> TRACKED_BLOCKS = Set.copyOf(Arrays.asList(
            resolveGrassBlock(),
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

    /** Resolve the short-grass block across Forge mappings. */
    private static Block resolveGrassBlock() {
        Block block = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("minecraft", "grass"));
        if (block == null || block == Blocks.AIR) {
            block = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("minecraft", "short_grass"));
        }
        return block == null ? Blocks.TALL_GRASS : block;
    }
    private static final String HORROR_ELEMENT_NAMESPACE = "horror_element_mod";
    private static final ConcurrentHashMap<Block, Boolean> TRACKED_BLOCK_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Block, Boolean> TREE_PROTECTED_BLOCK_CACHE = new ConcurrentHashMap<>();
    private static final Direction[] DIRECTIONS = Direction.values();

    private static final int MAX_CHUNKS_PER_TICK = Math.max(1, Integer.getInteger("lc.floating.max_chunks_per_tick", 1));
    private static final int MAX_ASYNC_CHUNKS_PER_TICK = Math.max(1, Integer.getInteger("lc.floating.async_chunks_per_tick", MAX_CHUNKS_PER_TICK));
    private static final boolean ENABLE_THREADED_SCAN = Boolean.parseBoolean(System.getProperty("lc.floating.threaded_scan", "true"));
    private static final int SAMPLES_PER_CHUNK = Math.max(16, Integer.getInteger("lc.floating.samples_per_chunk", 96));
    private static final int MIN_SAMPLES_PER_TASK = Math.max(8, Integer.getInteger("lc.floating.min_samples_per_task", 24));
    private static final int MAX_QUEUE = Math.max(256, Integer.getInteger("lc.floating.max_queue", 4096));
    private static final int MAX_DEFERRED_CHUNK_SCANS = Math.max(1024,
        Integer.getInteger("lc.floating.max_deferred_chunk_scans", 16_384));
    private static final int MAX_DEFERRED_CHUNK_SCANS_PER_TICK = Math.max(1,
        Integer.getInteger("lc.floating.deferred_chunk_scans_per_tick", 8));
    private static final int QUEUE_ALERT_THRESHOLD = Math.max(
        128,
        Integer.getInteger("lc.floating.queue_alert_threshold", Math.min(MAX_QUEUE, 1024))
    );
    private static final int MAX_PENDING_FLOATING_CHECKS = Math.max(256, Integer.getInteger("lc.floating.max_pending_checks", 8192));
    private static final int MAX_PENDING_FLOATING_OVERFLOW = Math.max(1024,
        Integer.getInteger("lc.floating.max_pending_overflow", 65_536));
    private static final int MAX_PENDING_FLOATING_OVERFLOW_CHUNKS = Math.max(256,
        Integer.getInteger("lc.floating.max_pending_overflow_chunks", 16_384));
    private static final int MAX_FLOATING_OVERFLOW_SCANS_PER_TICK = Math.max(1,
        Integer.getInteger("lc.floating.max_overflow_scans_per_tick", 8));
    private static final int MAX_FLOATING_CHECKS_PER_TICK = Math.max(8, Integer.getInteger("lc.floating.max_checks_per_tick", 128));
    private static final int MAX_FLOATING_CHECK_RETRIES = Math.max(2,
        Integer.getInteger("lc.floating.max_check_retries", 12));
    private static final long MAX_FLOATING_RETRY_DELAY_TICKS = Math.max(4L,
        Long.getLong("lc.floating.max_retry_delay_ticks", 40L));
    private static final int MAX_SHADOW_REMOVALS_PER_TICK = Math.max(32, Integer.getInteger("lc.floating.max_shadow_removals_per_tick", 256));
    private static final int MAX_SURFACE_RECONCILES_PER_TICK = Math.max(1,
        Integer.getInteger("lc2h.surface_reconciles_per_tick", 2));
    private static final int MAX_FLUID_CLUSTER_SCAN = Math.max(16, Integer.getInteger("lc.floating.max_fluid_cluster_scan", 96));
    private static final int CITY_FLOATING_SOURCE_MIN_HEIGHT =
        Math.max(4, Integer.getInteger("lc.floating.city_source_column_min_height", 8));
    private static final int CITY_FLOATING_SOURCE_MAX_DEPTH =
        Math.max(16, Integer.getInteger("lc.floating.city_source_column_max_depth", 96));
    private static final int CITY_FLOATING_SOURCE_MIN_EXPOSED_SIDES =
        Math.max(1, Math.min(4, Integer.getInteger("lc.floating.city_source_column_min_exposed_sides", 3)));
    private static final boolean ENABLE_WORLDGEN_SHADOW_APPLY =
        Boolean.parseBoolean(System.getProperty("lc.floating.worldgen_shadow_apply", "true"));
    private static final double TICK_TIME_BUDGET_MS = Math.max(5D, Double.parseDouble(System.getProperty("lc.floating.tick_budget_ms", "35")));
    // If set (>0), forces a fixed work budget. Otherwise, the post processor auto-tunes.
    private static final double OVERRIDE_MAX_WORK_TIME_PER_TICK_MS = Double.parseDouble(System.getProperty("lc.floating.max_work_ms_per_tick", "-1"));
    private static final double MIN_WORK_TIME_PER_TICK_MS = 0.25D;
    private static final double MAX_WORK_TIME_PER_TICK_MS_PLAYERS = 6.0D;
    private static final double MAX_WORK_TIME_PER_TICK_MS_STARTUP = 12.0D;
    private static final double TARGET_TICK_MS = 20.0D;
    private static final boolean ENABLE_BATCH_DRAIN = Boolean.getBoolean("lc.floating.enable_batch_drain");
    private static final boolean AUTO_RESCAN_STARTUP = Boolean.getBoolean("lc.floating.auto_rescan_startup");
    private static final boolean ENABLE_FLOATING_SCAN = Boolean.parseBoolean(
        System.getProperty("lc.floating.enable_scan", "true"));
    private static final int SAFE_SET_FLAGS = 2;
    private static final BlockState AIR_STATE = Blocks.AIR.defaultBlockState();
    private static final int SHADOW_Y_OFFSET = 1024;
    private static final ThreadLocal<BlockPos.MutableBlockPos> LOCAL_SCAN_POS =
        ThreadLocal.withInitial(BlockPos.MutableBlockPos::new);
    private static final ThreadLocal<BlockPos.MutableBlockPos> LOCAL_BELOW_POS =
        ThreadLocal.withInitial(BlockPos.MutableBlockPos::new);
    private static final Lc2hTimingRegistry.TimingHandle TIMING_FLOATING_CANDIDATE_CHECK =
        Lc2hTimingRegistry.bucket("chunk_post.floating_candidate_check");
    private static final Lc2hTimingRegistry.TimingHandle TIMING_DOUBLE_BLOCK_REPAIR =
        Lc2hTimingRegistry.bucket("chunk_post.double_block_repair");
    private static final Lc2hTimingRegistry.TimingHandle TIMING_PROCESS_CHUNK =
        Lc2hTimingRegistry.bucket("chunk_post.process_chunk");
    private static final Lc2hTimingRegistry.TimingHandle TIMING_SUBMIT_ASYNC_SCAN =
        Lc2hTimingRegistry.bucket("chunk_post.submit_async_scan");
    private static final Lc2hTimingRegistry.TimingHandle TIMING_ASYNC_FLOATING_CANDIDATE =
        Lc2hTimingRegistry.bucket("chunk_post.async_floating_candidate");
    private static final Lc2hTimingRegistry.TimingHandle TIMING_SCAN_CHUNK_ASYNC =
        Lc2hTimingRegistry.bucket("chunk_post.scan_chunk_async");
    private static final Lc2hTimingRegistry.TimingHandle TIMING_APPLY_FLOATING_CANDIDATE =
        Lc2hTimingRegistry.bucket("chunk_post.apply_floating_candidate");
    private static final Lc2hTimingRegistry.TimingHandle TIMING_APPLY_DOUBLE_BLOCK_REPAIR =
        Lc2hTimingRegistry.bucket("chunk_post.apply_double_block_repair");
    private static final Lc2hTimingRegistry.TimingHandle TIMING_APPLY_SCAN_RESULT =
        Lc2hTimingRegistry.bucket("chunk_post.apply_scan_result");
    private static final Lc2hTimingRegistry.TimingHandle TIMING_DRAIN_ALL =
        Lc2hTimingRegistry.bucket("chunk_post.drain_all");

    private static final String DATA_ROOT = "lc2h";
    private static final String DATA_FLAG = "doubleblock_repaired";
    private static final String DATA_SCAN_VERSION_FLAG = "postprocess_scan_version";
    private static final int CURRENT_SCAN_VERSION = 5;

    private static final Map<ChunkScanKey, ScanCursor> CHUNK_SCAN_PROGRESS = new ConcurrentHashMap<>();
    private static final Set<ChunkScanKey> INFLIGHT_CHUNK_SCANS = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static final Set<ChunkScanKey> FORCED_CLEANUP_SCANS = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static final Set<ChunkScanKey> COMPLETED_CHUNKS = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static final Set<ChunkScanKey> GENERATED_AUDIT_CHUNKS = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static final Set<ChunkScanKey> DEFERRED_CHUNK_SCANS = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static final Set<ChunkScanKey> GENERATED_SURFACE_RECONCILIATION =
        Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static final AtomicInteger SNOW_RECONCILED_CHUNKS = new AtomicInteger();
    private static final AtomicInteger SNOW_PLACED = new AtomicInteger();
    private static final AtomicInteger SNOW_SURFACE_COLUMNS = new AtomicInteger();
    private static final AtomicInteger SNOW_GRASS_COLUMNS = new AtomicInteger();
    private static final AtomicInteger SNOW_BIOME_COLUMNS = new AtomicInteger();
    private static final AtomicInteger SNOW_HEIGHTMAP_FALLBACKS = new AtomicInteger();
    private static final AtomicLong SNOW_RECONCILE_FAILURES = new AtomicLong();
    private static final AtomicBoolean LOGGED_QUEUE_ALERT = new AtomicBoolean(false);
    private static final AtomicBoolean LOGGED_QUEUE_HARD_LIMIT = new AtomicBoolean(false);
    private static final AtomicBoolean LOGGED_FLOATING_QUEUE_HARD_LIMIT = new AtomicBoolean(false);
    private static final AtomicBoolean LOGGED_FLOATING_CHECK_FAILURE = new AtomicBoolean(false);
    private static final AtomicLong FLOATING_CHECK_RETRIES = new AtomicLong();
    private static final AtomicLong FLOATING_CHECK_RETRY_DROPS = new AtomicLong();
    private static final AtomicLong FLOATING_ANCHOR_MEMO_HITS = new AtomicLong();
    private static final AtomicLong CHUNK_SCAN_DEFERRALS = new AtomicLong();
    private static final AtomicBoolean BATCH_IN_FLIGHT = new AtomicBoolean(false);
    private static final AtomicBoolean FLOATING_DRAIN_REQUESTED = new AtomicBoolean(false);
    private static final AtomicBoolean SHADOW_REMOVAL_DRAIN_REQUESTED = new AtomicBoolean(false);
    private static volatile long LAST_FLOATING_DRAIN_TICK = -1L;
    private static volatile long LAST_SHADOW_REMOVAL_DRAIN_TICK = -1L;
    private static volatile boolean RESCAN_TRIGGERED = false;
    private static volatile boolean RESCAN_IN_PROGRESS = false;
    private static volatile long RESCAN_START_TICK = 0;
    private static int FIXED_DOUBLE_BLOCKS = 0;
    private static int FIXED_FLOATING = 0;

    private static volatile double ADAPTIVE_WORK_BUDGET_MS = 1.5D;

    private static final ConcurrentHashMap<ResourceKey<Level>, PendingCheckQueue> PENDING_FLOATING = new ConcurrentHashMap<>();
    private static final ConcurrentLinkedQueue<ShadowRemovalBatch> SHADOW_REMOVAL_QUEUE = new ConcurrentLinkedQueue<>();
    private static final ConcurrentHashMap<ResourceKey<Level>, IDimensionInfo> DIMENSION_INFO_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<ChunkScanKey, Boolean> CITY_CHUNK_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<ChunkScanKey, Long> RECENT_SCAN_ENQUEUE_MS = new ConcurrentHashMap<>();
    private static final long CHUNK_SCAN_ENQUEUE_COOLDOWN_MS = Math.max(250L,
        Long.getLong("lc.floating.chunk_enqueue_cooldown_ms", 5000L));

    private static final class PendingCheckQueue {
        private final ConcurrentLinkedQueue<PendingFloatingCheck> queue = new ConcurrentLinkedQueue<>();
        private final ConcurrentLinkedQueue<PendingFloatingCheck> overflow = new ConcurrentLinkedQueue<>();
        private final Set<Long> overflowChunks = ConcurrentHashMap.newKeySet();
        private final AtomicInteger size = new AtomicInteger();
        private final AtomicInteger overflowSize = new AtomicInteger();
        private final ConcurrentHashMap<Long, Boolean> dedupe = new ConcurrentHashMap<>();
        private volatile ResourceKey<Level> dimensionKey;
        private volatile ServerLevel level;
    }

    private static final class PendingFloatingCheck {
        private final long packed;
        private final int attempt;
        private final long notBeforeTick;

        private PendingFloatingCheck(long packed, int attempt, long notBeforeTick) {
            this.packed = packed;
            this.attempt = Math.max(0, attempt);
            this.notBeforeTick = Math.max(0L, notBeforeTick);
        }
    }

    private static final class ShadowRemovalBatch {
        private final ServerLevel level;
        private final ChunkPos chunkPos;
        private final int[] packedPositions;
        private int cursor;

        private ShadowRemovalBatch(ServerLevel level, ChunkPos chunkPos, int[] packedPositions) {
            this.level = level;
            this.chunkPos = chunkPos;
            this.packedPositions = packedPositions;
        }

        private boolean hasRemaining() {
            return cursor < packedPositions.length;
        }
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
                return new ScanCursor(0, maxY + 1, 0);
            }
            return new ScanCursor(nx, ny, nz);
        }
    }

    private record ChunkScanResult(ChunkScanKey key, ScanCursor next, List<Long> floating, List<Long> doubleBlocks, boolean forcedCleanup) {
    }

    public static boolean isTracked(Block block) {
        if (block == null) {
            return false;
        }
        if (TRACKED_BLOCKS.contains(block)) {
            return true;
        }
        Boolean cached = TRACKED_BLOCK_CACHE.get(block);
        if (cached != null) {
            return cached;
        }

        ResourceLocation key = ForgeRegistries.BLOCKS.getKey(block);
        boolean tracked = key != null && HORROR_ELEMENT_NAMESPACE.equals(key.getNamespace());
        TRACKED_BLOCK_CACHE.put(block, tracked);
        return tracked;
    }

    /** Fast candidate predicate shared by WorldGenRegion generation hooks. */
    public static boolean isFloatingCandidate(BlockState state) {
        return shouldWatchFloatingCandidate(state);
    }

    private static boolean shouldWatchFloatingCandidate(BlockState state) {
        if (state == null) {
            return false;
        }
        return isTracked(state.getBlock())
            || isPotentialFloatingSourceFluid(state)
            || isHorrorElementBlock(state)
            || isAttachmentDecoration(state)
            || isPotentialOrphanedTreeLeaf(state)
            || isModdedTreeDecoration(state);
    }

    private static boolean isPotentialFloatingSourceFluid(BlockState state) {
        if (state == null) {
            return false;
        }
        if (!(state.getBlock() instanceof LiquidBlock)) {
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
        return shouldRemoveFloatingCandidate(level, pos, state, null);
    }

    private static boolean shouldRemoveFloatingCandidate(ServerLevel level,
                                                         BlockPos pos,
                                                         BlockState state,
                                                         ArtifactAnchorMemo anchorMemo) {
        if (level == null || pos == null || state == null) {
            return false;
        }
        if (!shouldWatchFloatingCandidate(state)) {
            return false;
        }
        if (hasDoubleHalf(state)) {
            return false;
        }

        BlockPos.MutableBlockPos below = LOCAL_BELOW_POS.get();
        below.set(pos.getX(), pos.getY() - 1, pos.getZ());
        BlockState belowState = getLoadedState(level, below);
        if (belowState == null || !hasLoadedNeighborhood(level, pos)) {
            // Wait for a resident neighbour; retry on a later server tick.
            return false;
        }
        boolean unsupported = isUnsupportedSupport(level, below, belowState);

        if (isPotentialFloatingSourceFluid(state)) {
            if (unsupported && isFloatingFluidDisconnected(level, pos, state)) {
                return true;
            }
            return shouldRemoveTallCityFluidSource(level, pos, state);
        }
        if (isAttachmentDecoration(state)) {
            return !canSurviveAt(level, pos, state, unsupported)
                || !hasConnectedArtifactAnchor(level, pos, ArtifactFamily.ATTACHMENT, anchorMemo);
        }
        if (state.is(BlockTags.LEAVES)) {
            return isDecayMarkedLeaf(state) && !hasConnectedTreeAnchor(level, pos);
        }
        if (isModdedTreeDecoration(state)) {
            return !canSurviveAt(level, pos, state, unsupported)
                || !hasConnectedArtifactAnchor(level, pos, ArtifactFamily.TREE_DECORATION, anchorMemo);
        }
        if (isHorrorElementBlock(state)) {
            return shouldRemoveFloatingHorrorElement(level, pos, state, unsupported);
        }
        if (isTracked(state.getBlock())) {
            return !canSurviveAt(level, pos, state, unsupported);
        }
        return false;
    }

    private static boolean shouldRemoveFloatingHorrorElement(ServerLevel level,
                                                             BlockPos pos,
                                                             BlockState state,
                                                             boolean unsupported) {
        if (level == null || pos == null || state == null || !unsupported) {
            return false;
        }
        if (isFullySurroundedByVanillaAir(level, pos)) {
            return true;
        }
        return !hasStableNeighborAnchor(level, pos);
    }

    private static boolean shouldRemoveFloatingHorrorElement(net.minecraft.server.level.WorldGenRegion region,
                                                             BlockPos pos,
                                                             BlockState state,
                                                             boolean unsupported) {
        if (region == null || pos == null || state == null || !unsupported) {
            return false;
        }
        if (isFullySurroundedByVanillaAir(region, pos)) {
            return true;
        }
        return !hasStableNeighborAnchor(region, pos);
    }

    private static boolean isFullySurroundedByVanillaAir(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null) {
            return false;
        }
        for (Direction direction : DIRECTIONS) {
            BlockPos neighborPos = pos.relative(direction);
            BlockState neighborState = getLoadedState(level, neighborPos);
            if (neighborState == null) {
                return false;
            }
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
        for (Direction direction : DIRECTIONS) {
            BlockState neighborState = region.getBlockState(pos.relative(direction));
            if (!isVanillaAir(neighborState)) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasStableNeighborAnchor(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null) {
            return false;
        }
        for (Direction direction : DIRECTIONS) {
            BlockPos neighborPos = pos.relative(direction);
            BlockState neighborState = getLoadedState(level, neighborPos);
            if (neighborState == null) {
                continue;
            }
            if (neighborState.isAir() || neighborState.canBeReplaced()) {
                continue;
            }
            if (shouldWatchFloatingCandidate(neighborState)) {
                continue;
            }
            if (isSolidOrFixedBlock(level, neighborPos, neighborState)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasStableNeighborAnchor(net.minecraft.server.level.WorldGenRegion region, BlockPos pos) {
        if (region == null || pos == null) {
            return false;
        }
        for (Direction direction : DIRECTIONS) {
            BlockPos neighborPos = pos.relative(direction);
            BlockState neighborState = region.getBlockState(neighborPos);
            if (neighborState.isAir() || neighborState.canBeReplaced()) {
                continue;
            }
            if (shouldWatchFloatingCandidate(neighborState)) {
                continue;
            }
            if (isSolidOrFixedBlock(region, neighborPos, neighborState)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isFloatingFluidDisconnected(ServerLevel level, BlockPos pos, BlockState state) {
        if (level == null || pos == null || state == null) {
            return false;
        }
        net.minecraft.world.level.material.FluidState sourceFluid = state.getFluidState();
        if (sourceFluid == null || !sourceFluid.isSource()) {
            return false;
        }
        boolean hasConnectedFluid = false;
        for (Direction direction : DIRECTIONS) {
            BlockPos neighborPos = pos.relative(direction);
            if (!level.isLoaded(neighborPos)) {
                return false;
            }
            BlockState neighborState = getLoadedState(level, neighborPos);
            if (neighborState == null) {
                return false;
            }
            net.minecraft.world.level.material.FluidState neighborFluid = neighborState.getFluidState();
            if (neighborFluid != null && !neighborFluid.isEmpty()) {
                if (isSameFloatingFluidFamily(sourceFluid, neighborFluid)) {
                    hasConnectedFluid = true;
                    continue;
                }
                return false;
            }
            if (neighborState.isAir()) {
                continue;
            }
            if (isSolidOrFixedBlock(level, neighborPos, neighborState)) {
                return false;
            }
        }
        if (!hasConnectedFluid) {
            return true;
        }
        return isFloatingFluidClusterUnsupported(level, pos, sourceFluid);
    }

    private static boolean isFloatingFluidDisconnected(net.minecraft.server.level.WorldGenRegion region, BlockPos pos, BlockState state) {
        if (region == null || pos == null || state == null) {
            return false;
        }
        net.minecraft.world.level.material.FluidState sourceFluid = state.getFluidState();
        if (sourceFluid == null || !sourceFluid.isSource()) {
            return false;
        }
        boolean hasConnectedFluid = false;
        for (Direction direction : DIRECTIONS) {
            BlockPos neighborPos = pos.relative(direction);
            BlockState neighborState = region.getBlockState(neighborPos);
            net.minecraft.world.level.material.FluidState neighborFluid = neighborState.getFluidState();
            if (neighborFluid != null && !neighborFluid.isEmpty()) {
                if (isSameFloatingFluidFamily(sourceFluid, neighborFluid)) {
                    hasConnectedFluid = true;
                    continue;
                }
                return false;
            }
            if (neighborState.isAir()) {
                continue;
            }
            if (isSolidOrFixedBlock(region, neighborPos, neighborState)) {
                return false;
            }
        }
        if (!hasConnectedFluid) {
            return true;
        }
        return isFloatingFluidClusterUnsupported(region, pos, sourceFluid);
    }

    private static boolean isFloatingFluidClusterUnsupported(ServerLevel level,
                                                             BlockPos origin,
                                                             net.minecraft.world.level.material.FluidState sourceFluid) {
        long[] queue = new long[MAX_FLUID_CLUSTER_SCAN + 8];
        long[] visited = new long[MAX_FLUID_CLUSTER_SCAN + 8];
        int head = 0;
        int tail = 0;
        int visitedCount = 0;
        queue[tail++] = origin.asLong();
        BlockPos.MutableBlockPos currentPos = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos belowPos = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos neighborPos = new BlockPos.MutableBlockPos();

        while (head < tail) {
            long packed = queue[head++];
            if (containsPackedPos(visited, visitedCount, packed)) {
                continue;
            }
            if (visitedCount >= MAX_FLUID_CLUSTER_SCAN) {
                return false;
            }
            visited[visitedCount++] = packed;
            currentPos.set(BlockPos.getX(packed), BlockPos.getY(packed), BlockPos.getZ(packed));

            if (!level.isLoaded(currentPos)) {
                return false;
            }
            BlockState currentState = getLoadedState(level, currentPos);
            if (currentState == null) {
                return false;
            }
            net.minecraft.world.level.material.FluidState currentFluid = currentState.getFluidState();
            if (currentFluid == null || currentFluid.isEmpty() || !isSameFloatingFluidFamily(sourceFluid, currentFluid)) {
                continue;
            }

            belowPos.set(currentPos.getX(), currentPos.getY() - 1, currentPos.getZ());
            if (!level.isLoaded(belowPos)) {
                return false;
            }
            BlockState belowState = getLoadedState(level, belowPos);
            if (belowState == null) {
                return false;
            }
            if (!isUnsupportedSupport(level, belowPos, belowState)) {
                return false;
            }

            for (Direction direction : DIRECTIONS) {
                neighborPos.setWithOffset(currentPos, direction);
                if (!level.isLoaded(neighborPos)) {
                    return false;
                }
                BlockState neighborState = getLoadedState(level, neighborPos);
                if (neighborState == null) {
                    return false;
                }
                net.minecraft.world.level.material.FluidState neighborFluid = neighborState.getFluidState();
                if (neighborFluid != null && !neighborFluid.isEmpty()) {
                    if (isSameFloatingFluidFamily(sourceFluid, neighborFluid)) {
                        long neighborPacked = neighborPos.asLong();
                        if (tail < queue.length && !containsPackedPos(visited, visitedCount, neighborPacked)
                            && !containsPackedPos(queue, head, tail, neighborPacked)) {
                            queue[tail++] = neighborPacked;
                        }
                        continue;
                    }
                    return false;
                }
                if (neighborState.isAir()) {
                    continue;
                }
                if (isSolidOrFixedBlock(level, neighborPos, neighborState)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean isFloatingFluidClusterUnsupported(net.minecraft.server.level.WorldGenRegion region,
                                                             BlockPos origin,
                                                             net.minecraft.world.level.material.FluidState sourceFluid) {
        long[] queue = new long[MAX_FLUID_CLUSTER_SCAN + 8];
        long[] visited = new long[MAX_FLUID_CLUSTER_SCAN + 8];
        int head = 0;
        int tail = 0;
        int visitedCount = 0;
        queue[tail++] = origin.asLong();
        BlockPos.MutableBlockPos currentPos = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos belowPos = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos neighborPos = new BlockPos.MutableBlockPos();

        while (head < tail) {
            long packed = queue[head++];
            if (containsPackedPos(visited, visitedCount, packed)) {
                continue;
            }
            if (visitedCount >= MAX_FLUID_CLUSTER_SCAN) {
                return false;
            }
            visited[visitedCount++] = packed;
            currentPos.set(BlockPos.getX(packed), BlockPos.getY(packed), BlockPos.getZ(packed));

            BlockState currentState = region.getBlockState(currentPos);
            net.minecraft.world.level.material.FluidState currentFluid = currentState.getFluidState();
            if (currentFluid == null || currentFluid.isEmpty() || !isSameFloatingFluidFamily(sourceFluid, currentFluid)) {
                continue;
            }

            belowPos.set(currentPos.getX(), currentPos.getY() - 1, currentPos.getZ());
            BlockState belowState = region.getBlockState(belowPos);
            if (!isUnsupportedSupport(region, belowPos, belowState)) {
                return false;
            }

            for (Direction direction : DIRECTIONS) {
                neighborPos.setWithOffset(currentPos, direction);
                BlockState neighborState = region.getBlockState(neighborPos);
                net.minecraft.world.level.material.FluidState neighborFluid = neighborState.getFluidState();
                if (neighborFluid != null && !neighborFluid.isEmpty()) {
                    if (isSameFloatingFluidFamily(sourceFluid, neighborFluid)) {
                        long neighborPacked = neighborPos.asLong();
                        if (tail < queue.length && !containsPackedPos(visited, visitedCount, neighborPacked)
                            && !containsPackedPos(queue, head, tail, neighborPacked)) {
                            queue[tail++] = neighborPacked;
                        }
                        continue;
                    }
                    return false;
                }
                if (neighborState.isAir()) {
                    continue;
                }
                if (isSolidOrFixedBlock(region, neighborPos, neighborState)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean containsPackedPos(long[] values, int size, long packed) {
        for (int i = 0; i < size; i++) {
            if (values[i] == packed) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsPackedPos(long[] values, int startInclusive, int endExclusive, long packed) {
        for (int i = startInclusive; i < endExclusive; i++) {
            if (values[i] == packed) {
                return true;
            }
        }
        return false;
    }

    private static boolean shouldRemoveTallCityFluidSource(ServerLevel level, BlockPos pos, BlockState state) {
        if (level == null || pos == null || state == null) {
            return false;
        }
        net.minecraft.world.level.material.FluidState sourceFluid = state.getFluidState();
        if (sourceFluid == null || sourceFluid.isEmpty() || !sourceFluid.isSource()) {
            return false;
        }
        if (!sourceFluid.is(FluidTags.WATER) && !sourceFluid.is(FluidTags.LAVA)) {
            return false;
        }
        int chunkX = pos.getX() >> 4;
        int chunkZ = pos.getZ() >> 4;
        if (!isCleanupRelevantChunkCached(level, chunkX, chunkZ)) {
            return false;
        }

        BlockPos belowPos = pos.below();
        if (!level.isLoaded(belowPos)) {
            return false;
        }
        BlockState belowState = getLoadedState(level, belowPos);
        if (belowState == null) {
            return false;
        }
        net.minecraft.world.level.material.FluidState belowFluid = belowState.getFluidState();
        if (belowFluid == null || belowFluid.isEmpty() || !isSameFloatingFluidFamily(sourceFluid, belowFluid)) {
            return false;
        }

        int exposedAtSource = countExposedFluidSides(level, pos, sourceFluid);
        if (exposedAtSource < CITY_FLOATING_SOURCE_MIN_EXPOSED_SIDES) {
            return false;
        }
        int exposedBelowSource = countExposedFluidSides(level, belowPos, sourceFluid);
        if (exposedBelowSource < Math.max(1, CITY_FLOATING_SOURCE_MIN_EXPOSED_SIDES - 1)) {
            return false;
        }

        int depth = countFreeHangingFluidColumnDepth(level, pos, sourceFluid);
        return depth >= CITY_FLOATING_SOURCE_MIN_HEIGHT;
    }

    private static int removeFloatingCandidate(ServerLevel level, BlockPos pos, BlockState state) {
        if (level == null || pos == null || state == null) {
            return 0;
        }
        if (isPotentialFloatingSourceFluid(state)) {
            return removeFloatingFluidColumn(level, pos, state.getFluidState());
        }
        level.removeBlockEntity(pos);
        int flags = isAttachmentDecoration(state) ? 3 : SAFE_SET_FLAGS;
        level.setBlock(pos, AIR_STATE, flags);
        return 1;
    }

    private static boolean isAttachmentDecoration(BlockState state) {
        if (state == null) {
            return false;
        }
        if (isConfiguredFloatingVegetation(state)) {
            return true;
        }
        if (state.is(Blocks.VINE)
            || state.is(Blocks.CAVE_VINES)
            || state.is(Blocks.CAVE_VINES_PLANT)
            || state.is(Blocks.TWISTING_VINES)
            || state.is(Blocks.TWISTING_VINES_PLANT)
            || state.is(Blocks.WEEPING_VINES)
            || state.is(Blocks.WEEPING_VINES_PLANT)
            || state.is(Blocks.HANGING_ROOTS)
            || state.is(Blocks.GLOW_LICHEN)) {
            return true;
        }
        ResourceLocation key = ForgeRegistries.BLOCKS.getKey(state.getBlock());
        if (key == null || "minecraft".equals(key.getNamespace())) {
            return false;
        }
        String path = key.getPath();
        return path.contains("vine") || path.contains("lichen") || path.contains("hanging_root");
    }

    public static boolean isConfiguredFloatingVegetation(BlockState state) {
        if (state == null) {
            return false;
        }
        if (state.is(Blocks.GLOW_LICHEN)) {
            return true;
        }
        ResourceLocation id = ForgeRegistries.BLOCKS.getKey(state.getBlock());
        if (id == null) {
            return false;
        }
        String idString = id.toString().toLowerCase(java.util.Locale.ROOT);
        // Use the normalized set published by ConfigManager.
        return ConfigManager.isFloatingVegetationId(idString);
    }

    private static boolean isModdedTreeDecoration(BlockState state) {
        if (state == null) {
            return false;
        }
        ResourceLocation key = ForgeRegistries.BLOCKS.getKey(state.getBlock());
        if (key == null || "minecraft".equals(key.getNamespace())) {
            return false;
        }
        String path = key.getPath();
        return path.contains("branch")
            || path.contains("twig")
            || path.contains("foliage")
            || path.contains("needles")
            || path.contains("frond");
    }

    private static boolean isDecayMarkedLeaf(BlockState state) {
        if (state == null || !state.is(BlockTags.LEAVES) || !state.hasProperty(BlockStateProperties.DISTANCE)) {
            return false;
        }
        int distance = state.getValue(BlockStateProperties.DISTANCE);
        boolean persistent = state.hasProperty(BlockStateProperties.PERSISTENT)
            && state.getValue(BlockStateProperties.PERSISTENT);
        return !persistent && distance >= 7;
    }

    private static boolean isPotentialOrphanedTreeLeaf(BlockState state) {
        return state != null && state.is(BlockTags.LEAVES) && isDecayMarkedLeaf(state);
    }

    private enum ArtifactFamily {
        ATTACHMENT,
        TREE_DECORATION
    }

    private static boolean hasConnectedArtifactAnchor(ServerLevel level, BlockPos start, ArtifactFamily family) {
        return hasConnectedArtifactAnchor(level, start, family, new ArtifactAnchorMemo());
    }

    private enum AnchorStatus {
        ANCHORED,
        UNANCHORED,
        UNKNOWN
    }

    /** Cache anchor checks within one server-tick drain. */
    private static final class ArtifactAnchorMemo {
        private final Map<Long, AnchorStatus> statusByPosition = new HashMap<>();
    }

    private static boolean hasConnectedArtifactAnchor(ServerLevel level,
                                                       BlockPos start,
                                                       ArtifactFamily family,
                                                       ArtifactAnchorMemo memo) {
        if (level == null || start == null || family == null) {
            return true;
        }
        long startKey = start.asLong();
        if (memo != null) {
            AnchorStatus known = memo.statusByPosition.get(startKey);
            if (known != null) {
                FLOATING_ANCHOR_MEMO_HITS.incrementAndGet();
                return known != AnchorStatus.UNANCHORED;
            }
        }
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        HashSet<Long> visited = new HashSet<>();
        queue.add(start.immutable());
        visited.add(start.asLong());
        boolean unknown = false;
        boolean anchored = false;
        while (!queue.isEmpty()) {
            if (visited.size() > 256) {
                unknown = true; // Large components are retained conservatively.
                break;
            }
            BlockPos current = queue.removeFirst();
            for (Direction direction : DIRECTIONS) {
                BlockPos neighbor = current.relative(direction);
                BlockState neighborState = getLoadedState(level, neighbor);
                if (neighborState == null) {
                    unknown = true;
                    continue;
                }
                boolean sameFamily = family == ArtifactFamily.ATTACHMENT
                    ? isAttachmentDecoration(neighborState)
                    : isModdedTreeDecoration(neighborState);
                if (sameFamily) {
                    if (memo != null) {
                        AnchorStatus known = memo.statusByPosition.get(neighbor.asLong());
                        if (known == AnchorStatus.ANCHORED) {
                            anchored = true;
                            break;
                        }
                        if (known == AnchorStatus.UNKNOWN) {
                            unknown = true;
                        }
                    }
                    if (visited.add(neighbor.asLong())) {
                        queue.addLast(neighbor);
                    }
                    continue;
                }
                if (neighborState.isAir() || neighborState.canBeReplaced() || neighborState.is(BlockTags.LEAVES)) {
                    continue;
                }
                if (isSolidOrFixedBlock(level, neighbor, neighborState)) {
                    anchored = true;
                    break;
                }
            }
            if (anchored) {
                break;
            }
        }
        AnchorStatus status = anchored ? AnchorStatus.ANCHORED : (unknown ? AnchorStatus.UNKNOWN : AnchorStatus.UNANCHORED);
        if (memo != null) {
            for (Long visitedPosition : visited) {
                memo.statusByPosition.put(visitedPosition, status);
            }
        }
        return status != AnchorStatus.UNANCHORED;
    }

    /** Check that a leaf component still reaches a log. */
    private static boolean hasConnectedTreeAnchor(ServerLevel level, BlockPos start) {
        if (level == null || start == null) {
            return true;
        }
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        HashSet<Long> visited = new HashSet<>();
        queue.add(start.immutable());
        visited.add(start.asLong());
        while (!queue.isEmpty()) {
            if (visited.size() > 512) {
                return true;
            }
            BlockPos current = queue.removeFirst();
            for (Direction direction : DIRECTIONS) {
                BlockPos neighbor = current.relative(direction);
                BlockState neighborState = getLoadedState(level, neighbor);
                if (neighborState == null) {
                    return true;
                }
                if (isTreeTrunkBlock(neighborState)) {
                    return true;
                }
                if (isTreeLeafBlock(neighborState) && visited.add(neighbor.asLong())) {
                    queue.addLast(neighbor);
                }
            }
        }
        return false;
    }

    private static int removeFloatingCandidate(net.minecraft.server.level.WorldGenRegion region, BlockPos pos, BlockState state) {
        if (!ENABLE_WORLDGEN_SHADOW_APPLY || region == null || pos == null || state == null) {
            return 0;
        }
        if (isPotentialFloatingSourceFluid(state)) {
            return removeFloatingFluidColumn(region, pos, state.getFluidState());
        }
        try {
            return region.setBlock(pos, AIR_STATE, SAFE_SET_FLAGS, 512) ? 1 : 0;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static int removeFloatingFluidColumn(ServerLevel level,
                                                 BlockPos origin,
                                                 net.minecraft.world.level.material.FluidState sourceFluid) {
        if (level == null || origin == null || sourceFluid == null || sourceFluid.isEmpty()) {
            return 0;
        }

        java.util.ArrayList<Integer> packedPositions = new java.util.ArrayList<>();
        int maxDepth = Math.max(CITY_FLOATING_SOURCE_MAX_DEPTH, MAX_FLUID_CLUSTER_SCAN);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos().set(origin);
        while (packedPositions.size() < maxDepth) {
            if (!level.isLoaded(cursor)) {
                break;
            }
            BlockState state = level.getBlockState(cursor);
            net.minecraft.world.level.material.FluidState fluid = state.getFluidState();
            if (fluid == null || fluid.isEmpty() || !isSameFloatingFluidFamily(sourceFluid, fluid)) {
                break;
            }
            packedPositions.add(packShadowRemoval(cursor));
            cursor.move(Direction.DOWN);
        }
        if (packedPositions.isEmpty()) {
            return 0;
        }
        enqueueShadowRemovalBatch(level, new ChunkPos(origin), packedPositions);
        return packedPositions.size();
    }

    private static int removeFloatingFluidColumn(net.minecraft.server.level.WorldGenRegion region,
                                                 BlockPos origin,
                                                 net.minecraft.world.level.material.FluidState sourceFluid) {
        if (!ENABLE_WORLDGEN_SHADOW_APPLY || region == null || origin == null || sourceFluid == null || sourceFluid.isEmpty()) {
            return 0;
        }

        int removed = 0;
        int maxDepth = Math.max(CITY_FLOATING_SOURCE_MAX_DEPTH, MAX_FLUID_CLUSTER_SCAN);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos().set(origin);
        while (removed < maxDepth) {
            BlockState state;
            try {
                state = region.getBlockState(cursor);
            } catch (Throwable ignored) {
                break;
            }
            net.minecraft.world.level.material.FluidState fluid = state.getFluidState();
            if (fluid == null || fluid.isEmpty() || !isSameFloatingFluidFamily(sourceFluid, fluid)) {
                break;
            }
            try {
                if (!region.setBlock(cursor, AIR_STATE, SAFE_SET_FLAGS, 512)) {
                    break;
                }
            } catch (Throwable ignored) {
                break;
            }
            removed++;
            cursor.move(Direction.DOWN);
        }
        return removed;
    }

    private static int countFreeHangingFluidColumnDepth(ServerLevel level,
                                                        BlockPos origin,
                                                        net.minecraft.world.level.material.FluidState sourceFluid) {
        if (level == null || origin == null || sourceFluid == null) {
            return 0;
        }
        BlockPos current = origin;
        int depth = 0;
        while (depth < CITY_FLOATING_SOURCE_MAX_DEPTH) {
            if (!level.isLoaded(current)) {
                return 0;
            }
            BlockState state = getLoadedState(level, current);
            if (state == null) {
                return 0;
            }
            net.minecraft.world.level.material.FluidState fluid = state.getFluidState();
            if (fluid == null || fluid.isEmpty() || !isSameFloatingFluidFamily(sourceFluid, fluid)) {
                break;
            }
            if (hasSolidSideAnchor(level, current, sourceFluid)) {
                return 0;
            }
            depth++;
            current = current.below();
        }
        return depth;
    }

    private static int countExposedFluidSides(ServerLevel level,
                                              BlockPos pos,
                                              net.minecraft.world.level.material.FluidState sourceFluid) {
        if (level == null || pos == null || sourceFluid == null) {
            return 0;
        }
        int openSides = 0;
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos neighborPos = pos.relative(direction);
            if (!level.isLoaded(neighborPos)) {
                return 0;
            }
            BlockState neighborState = getLoadedState(level, neighborPos);
            if (neighborState == null) {
                return 0;
            }
            net.minecraft.world.level.material.FluidState neighborFluid = neighborState.getFluidState();
            if (neighborFluid != null && !neighborFluid.isEmpty() && isSameFloatingFluidFamily(sourceFluid, neighborFluid)) {
                continue;
            }
            if (neighborState.isAir() || neighborState.canBeReplaced()) {
                openSides++;
            }
        }
        return openSides;
    }

    private static boolean hasSolidSideAnchor(ServerLevel level,
                                              BlockPos pos,
                                              net.minecraft.world.level.material.FluidState sourceFluid) {
        if (level == null || pos == null || sourceFluid == null) {
            return true;
        }
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos neighborPos = pos.relative(direction);
            if (!level.isLoaded(neighborPos)) {
                return true;
            }
            BlockState neighborState = getLoadedState(level, neighborPos);
            if (neighborState == null) {
                return true;
            }
            net.minecraft.world.level.material.FluidState neighborFluid = neighborState.getFluidState();
            if (neighborFluid != null && !neighborFluid.isEmpty() && isSameFloatingFluidFamily(sourceFluid, neighborFluid)) {
                continue;
            }
            if (neighborState.isAir()) {
                continue;
            }
            if (isSolidOrFixedBlock(level, neighborPos, neighborState)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSameFloatingFluidFamily(net.minecraft.world.level.material.FluidState sourceFluid,
                                                     net.minecraft.world.level.material.FluidState otherFluid) {
        if (sourceFluid == null || otherFluid == null || otherFluid.isEmpty()) {
            return false;
        }
        if (sourceFluid.is(FluidTags.WATER)) {
            return otherFluid.is(FluidTags.WATER);
        }
        if (sourceFluid.is(FluidTags.LAVA)) {
            return otherFluid.is(FluidTags.LAVA);
        }
        return false;
    }

    private static boolean isSolidOrFixedBlock(ServerLevel level, BlockPos pos, BlockState state) {
        return !state.getCollisionShape(level, pos).isEmpty() || !state.canBeReplaced();
    }

    private static BlockState getLoadedState(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null) {
            return null;
        }
        net.minecraft.world.level.chunk.LevelChunk chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        return chunk == null ? null : chunk.getBlockState(pos);
    }

    /** Check whether the block and its neighbours are resident. */
    private static boolean hasLoadedNeighborhood(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null) {
            return false;
        }
        if (level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) == null) {
            return false;
        }
        for (Direction direction : DIRECTIONS) {
            BlockPos neighbor = pos.relative(direction);
            if (level.getChunkSource().getChunkNow(neighbor.getX() >> 4, neighbor.getZ() >> 4) == null) {
                return false;
            }
        }
        return true;
    }

    private static boolean isSolidOrFixedBlock(net.minecraft.server.level.WorldGenRegion region, BlockPos pos, BlockState state) {
        return !state.getCollisionShape(region, pos).isEmpty() || !state.canBeReplaced();
    }

    private static boolean isUnsupportedSupport(ServerLevel level, BlockPos supportPos, BlockState supportState) {
        if (supportState == null || level == null) {
            return true;
        }
        return supportState.isAir() || !supportState.isCollisionShapeFullBlock(level, supportPos);
    }

    private static boolean isUnsupportedSupport(net.minecraft.server.level.WorldGenRegion region, BlockPos supportPos, BlockState supportState) {
        if (supportState == null) {
            return true;
        }
        return supportState.isAir() || !supportState.isCollisionShapeFullBlock(region, supportPos);
    }

    private record ChunkScanKey(ResourceLocation dimension, int chunkX, int chunkZ) {
    }

    private static final java.util.concurrent.atomic.AtomicReference<java.lang.reflect.Method> WORLDGENREGION_LEVEL_METHOD =
        new java.util.concurrent.atomic.AtomicReference<>();
    private static final java.util.concurrent.ConcurrentHashMap<ChunkScanKey, java.util.concurrent.ConcurrentHashMap<Long, Boolean>>
        PROTECTED_TREE_BLOCKS = new java.util.concurrent.ConcurrentHashMap<>();

    public static void markForRemovalIfFloating(net.minecraft.server.level.WorldGenRegion region, BlockPos pos) {
        if (region == null || pos == null) return;
        markForRemovalIfFloating(region, pos, region.getBlockState(pos));
    }

    public static void markForRemovalIfFloating(net.minecraft.server.level.WorldGenRegion region,
                                                BlockPos pos,
                                                BlockState state) {
        if (!ConfigManager.ENABLE_FLOATING_VEGETATION_REMOVAL) return;
        if (region == null || pos == null || state == null) return;
        if (!shouldWatchFloatingCandidate(state)) return;
        if (hasDoubleHalf(state)) return;

        if (isAttachmentDecoration(state) || isModdedTreeDecoration(state)) {
            ServerLevel worldgenLevel = resolveWorldgenServerLevel(region);
            if (worldgenLevel == null) {
                worldgenLevel = resolveServerLevel(region);
            }
            if (worldgenLevel != null) {
                enqueueFloatingCheck(worldgenLevel, pos);
            }
            return;
        }

        BlockPos below = pos.below();
        BlockState belowState = region.getBlockState(below);
        boolean unsupported = isUnsupportedSupport(region, below, belowState);
        boolean enqueue = false;
        ServerLevel level = null;

        if (isPotentialFloatingSourceFluid(state)) {
            enqueue = unsupported && isFloatingFluidDisconnected(region, pos, state);
            if (!enqueue) {
                level = resolveServerLevel(region);
                if (level != null) {
                    enqueue = shouldRemoveTallCityFluidSource(level, pos, state);
                }
            }
        } else if (state.is(BlockTags.LEAVES)) {
            enqueue = isDecayMarkedLeaf(state);
        } else if (isHorrorElementBlock(state)) {
            enqueue = shouldRemoveFloatingHorrorElement(region, pos, state, unsupported);
        } else if (isTracked(state.getBlock())) {
            enqueue = !canSurviveAt(region, pos, state, unsupported);
        }

        if (enqueue) {
            if (!state.is(BlockTags.LEAVES)) {
                int removed = removeFloatingCandidate(region, pos, state);
                if (removed > 0) {
                    FIXED_FLOATING += removed;
                    return;
                }
            }
            try {
                if (level == null) {
                    level = resolveServerLevel(region);
                }
                if (level != null) {
                    enqueueFloatingCheck(level, pos);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private static boolean canSurviveAt(net.minecraft.server.level.WorldGenRegion region,
                                        BlockPos pos,
                                        BlockState state,
                                        boolean fallbackUnsupported) {
        try {
            return state.canSurvive(region, pos);
        } catch (Throwable ignored) {
            return !fallbackUnsupported;
        }
    }

    private static boolean canSurviveAt(ServerLevel level,
                                        BlockPos pos,
                                        BlockState state,
                                        boolean fallbackUnsupported) {
        if (!hasLoadedNeighborhood(level, pos)) {
            return true;
        }
        try {
            return state.canSurvive(level, pos);
        } catch (Throwable ignored) {
            return !fallbackUnsupported;
        }
    }

    private static boolean hasConnectedTreeAnchor(net.minecraft.server.level.WorldGenRegion region, BlockPos start) {
        if (region == null || start == null) {
            return true;
        }
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        HashSet<Long> visited = new HashSet<>();
        queue.add(start.immutable());
        visited.add(start.asLong());
        while (!queue.isEmpty()) {
            if (visited.size() > 512) {
                return true;
            }
            BlockPos current = queue.removeFirst();
            for (Direction direction : DIRECTIONS) {
                BlockPos neighbor = current.relative(direction);
                BlockState neighborState = region.getBlockState(neighbor);
                if (isTreeTrunkBlock(neighborState)) {
                    return true;
                }
                if (isTreeLeafBlock(neighborState) && visited.add(neighbor.asLong())) {
                    queue.addLast(neighbor);
                }
            }
        }
        return false;
    }

    private static boolean isTreeTrunkBlock(BlockState state) {
        if (state == null) {
            return false;
        }
        if (state.is(BlockTags.LOGS)) {
            return true;
        }
        ResourceLocation key = ForgeRegistries.BLOCKS.getKey(state.getBlock());
        if (key == null || "minecraft".equals(key.getNamespace())) {
            return false;
        }
        String path = key.getPath().toLowerCase(java.util.Locale.ROOT);
        return path.contains("log")
            || path.contains("trunk")
            || path.contains("stem")
            || path.contains("branch")
            || path.contains("bark")
            || path.endsWith("_wood")
            || path.equals("wood");
    }

    private static boolean isTreeLeafBlock(BlockState state) {
        if (state == null) {
            return false;
        }
        if (state.is(BlockTags.LEAVES)) {
            return true;
        }
        ResourceLocation key = ForgeRegistries.BLOCKS.getKey(state.getBlock());
        if (key == null || "minecraft".equals(key.getNamespace())) {
            return false;
        }
        String path = key.getPath().toLowerCase(java.util.Locale.ROOT);
        return path.contains("leaves")
            || path.contains("leaf")
            || path.contains("foliage")
            || path.contains("needles")
            || path.contains("frond");
    }

    private static boolean hasTrackedPlantSelfSupport(BlockState state, BlockState belowState) {
        if (state == null || belowState == null) {
            return false;
        }
        Block block = state.getBlock();
        Block below = belowState.getBlock();
        return (block == Blocks.BAMBOO && below == Blocks.BAMBOO)
            || (block == Blocks.SUGAR_CANE && below == Blocks.SUGAR_CANE)
            || ((block == Blocks.KELP || block == Blocks.KELP_PLANT)
                && (below == Blocks.KELP || below == Blocks.KELP_PLANT));
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

    private static ServerLevel resolveWorldgenServerLevel(net.minecraft.server.level.WorldGenRegion region) {
        try {
            return ((ServerLevelAccessor) region).getLevel();
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Queue a resident-world check without reading Lost Cities state. */
    public static void queueFloatingCheck(ServerLevel level, BlockPos pos) {
        enqueueFloatingCheck(level, pos);
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
        BlockState state = getLoadedState(level, above);
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
        if (!(event.getChunk() instanceof LevelChunk chunk)) return;
        if (!(chunk.getLevel() instanceof ServerLevel level)) {
            return;
        }
        ChunkScanKey key = chunkKey(level.dimension().location(), chunk.getPos().x, chunk.getPos().z);
        boolean generatedAudit = GENERATED_AUDIT_CHUNKS.contains(key);
        if (generatedAudit) {
            COMPLETED_CHUNKS.remove(key);
        }
        boolean floatingScanEnabled = ConfigManager.ENABLE_FLOATING_VEGETATION_REMOVAL && ENABLE_FLOATING_SCAN;
        boolean doubleBlockEnabled = ConfigManager.ENABLE_AUTOMATIC_CHUNK_SCANS
            && ConfigManager.ENABLE_ASYNC_DOUBLE_BLOCK_BATCHER;
        if (!floatingScanEnabled && !doubleBlockEnabled) return;
        boolean cityChunk = floatingScanEnabled && shouldScanFloatingInChunk(level, chunk.getPos().x, chunk.getPos().z);
        boolean effectiveFloating = floatingScanEnabled && (generatedAudit || cityChunk);

        // If we already marked this chunk complete, don't enqueue it again.
        if (!generatedAudit && COMPLETED_CHUNKS.contains(key)) {
            return;
        }
        if (generatedAudit && VineClusterCleaner.isFloatingCleanupEnabled()) {
            try {
                VineClusterCleaner.cleanVinesOnFirstLoad(level, chunk);
            } catch (Throwable failure) {
                LCLogger.debug("ChunkPostProcessor: generated vegetation pass deferred: {}", failure.toString());
            }
        }

        if (!chunkHasInterestingBlocks(chunk, effectiveFloating, doubleBlockEnabled)) {
            GENERATED_AUDIT_CHUNKS.remove(key);
            COMPLETED_CHUNKS.add(key);
            return;
        }

        if (!tryQueueChunkScan(level, chunk, key, generatedAudit)) {
            deferChunkScan(key);
            return;
        }
        if (generatedAudit) {
            FORCED_CLEANUP_SCANS.add(key);
        }
    }

    @SubscribeEvent
    public static void onChunkUnload(ChunkEvent.Unload event) {
        if (!(event.getChunk() instanceof LevelChunk chunk)) return;
        if (!(chunk.getLevel() instanceof ServerLevel)) {
            return;
        }
        ChunkScanKey key = chunkKey(chunk.getLevel().dimension().location(), chunk.getPos().x, chunk.getPos().z);
        CHUNK_SCAN_PROGRESS.remove(key);
        INFLIGHT_CHUNK_SCANS.remove(key);
        FORCED_CLEANUP_SCANS.remove(key);
        DEFERRED_CHUNK_SCANS.remove(key);
        COMPLETED_CHUNKS.remove(key);
        CITY_CHUNK_CACHE.remove(key);
        PROTECTED_TREE_BLOCKS.remove(key);
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        CHUNK_SCAN_PROGRESS.clear();
        INFLIGHT_CHUNK_SCANS.clear();
        FORCED_CLEANUP_SCANS.clear();
        COMPLETED_CHUNKS.clear();
        GENERATED_AUDIT_CHUNKS.clear();
        DEFERRED_CHUNK_SCANS.clear();
        GENERATED_SURFACE_RECONCILIATION.clear();
        PENDING_FLOATING.clear();
        SHADOW_REMOVAL_QUEUE.clear();
        CITY_CHUNK_CACHE.clear();
        RECENT_SCAN_ENQUEUE_MS.clear();
        RESCAN_TRIGGERED = false;
        RESCAN_IN_PROGRESS = false;
        RESCAN_START_TICK = 0L;
        FLOATING_DRAIN_REQUESTED.set(false);
        SHADOW_REMOVAL_DRAIN_REQUESTED.set(false);
    }

    @SubscribeEvent
    public static void onChunkDataLoad(ChunkDataEvent.Load event) {
        if (!(event.getChunk() instanceof LevelChunk chunk)) return;
        if (!(chunk.getLevel() instanceof ServerLevel)) {
            return;
        }
        CompoundTag root = event.getData().getCompound(DATA_ROOT);
        if (root.getInt(DATA_SCAN_VERSION_FLAG) >= CURRENT_SCAN_VERSION && root.getBoolean(DATA_FLAG)) {
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
        root.putInt(DATA_SCAN_VERSION_FLAG, CURRENT_SCAN_VERSION);
        data.put(DATA_ROOT, root);
    }

    public static int getPendingScanCount() {
        return CHUNK_SCAN_PROGRESS.size() + DEFERRED_CHUNK_SCANS.size();
    }

    /** Mark one Lost Cities output chunk for a bounded post-generation audit. */
    public static void noteGeneratedChunk(ResourceKey<Level> dimension, int chunkX, int chunkZ) {
        if (dimension == null) {
            return;
        }
        if (ConfigManager.CITY_BLEND_ENABLED && GENERATED_SURFACE_RECONCILIATION.size() < 8192) {
            GENERATED_SURFACE_RECONCILIATION.add(chunkKey(dimension.location(), chunkX, chunkZ));
        }
        if (ConfigManager.ENABLE_FLOATING_VEGETATION_REMOVAL && ENABLE_FLOATING_SCAN
            && GENERATED_AUDIT_CHUNKS.size() < 8192) {
            ChunkScanKey key = chunkKey(dimension.location(), chunkX, chunkZ);
            GENERATED_AUDIT_CHUNKS.add(key);
            DEFERRED_CHUNK_SCANS.add(key);
        }
    }

    private static void queueGeneratedVegetationChecks(ServerLevel level, LevelChunk chunk) {
        if (level == null || chunk == null) {
            return;
        }
        int minBuild = level.getMinBuildHeight();
        int maxBuild = level.getMaxBuildHeight() - 1;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int localX = 0; localX < 16; localX++) {
            for (int localZ = 0; localZ < 16; localZ++) {
                int surface = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, localX, localZ);
                int fromY = Math.max(minBuild, surface - 32);
                int toY = Math.min(maxBuild, surface + 12);
                for (int y = fromY; y <= toY; y++) {
                    BlockState state = chunk.getBlockState(pos.set(
                        chunk.getPos().getMinBlockX() + localX, y,
                        chunk.getPos().getMinBlockZ() + localZ));
                    if (shouldWatchFloatingCandidate(state)) {
                        enqueueFloatingCheck(level, pos.immutable());
                    }
                }
            }
        }
    }

    /** Reconcile snow on grass columns after Lost Cities moves the surface. */
    private static void reconcileGeneratedSnow(ServerLevel level, LevelChunk chunk) {
        if (level == null || chunk == null || !ConfigManager.CITY_BLEND_ENABLED) {
            return;
        }
        int minY = level.getMinBuildHeight();
        int maxY = level.getMaxBuildHeight() - 1;
        BlockPos.MutableBlockPos top = new BlockPos.MutableBlockPos();
        int placed = 0;
        for (int localX = 0; localX < 16; localX++) {
            for (int localZ = 0; localZ < 16; localZ++) {
                int surfaceY = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, localX, localZ);
                if (surfaceY <= minY || surfaceY > maxY) {
                    continue;
                }
                top.set(chunk.getPos().getMinBlockX() + localX, surfaceY,
                    chunk.getPos().getMinBlockZ() + localZ);
                BlockState above = level.getBlockState(top);
                if (!above.isAir() && !above.is(Blocks.SNOW)) {
                    continue;
                }
                SNOW_SURFACE_COLUMNS.incrementAndGet();
                BlockPos.MutableBlockPos belowPos = new BlockPos.MutableBlockPos().set(top).move(Direction.DOWN);
                BlockState below = level.getBlockState(belowPos);
                if (!below.is(Blocks.GRASS_BLOCK)) {
                    int foundGrassY = -1;
                    int fromY = Math.max(minY + 1, surfaceY - 32);
                    int toY = Math.min(maxY - 1, surfaceY + 32);
                    for (int candidateY = toY; candidateY >= fromY; candidateY--) {
                        BlockPos candidateBelow = belowPos.set(
                            chunk.getPos().getMinBlockX() + localX, candidateY,
                            chunk.getPos().getMinBlockZ() + localZ);
                        BlockState candidate = level.getBlockState(candidateBelow);
                        if (!candidate.is(Blocks.GRASS_BLOCK)) {
                            continue;
                        }
                        BlockState candidateAbove = level.getBlockState(candidateBelow.above());
                        if (candidateAbove.isAir() || candidateAbove.is(Blocks.SNOW)) {
                            foundGrassY = candidateY;
                            break;
                        }
                    }
                    if (foundGrassY < 0) {
                        continue;
                    }
                    SNOW_HEIGHTMAP_FALLBACKS.incrementAndGet();
                    top.set(chunk.getPos().getMinBlockX() + localX, foundGrassY + 1,
                        chunk.getPos().getMinBlockZ() + localZ);
                    above = level.getBlockState(top);
                    belowPos.set(top).move(Direction.DOWN);
                    below = level.getBlockState(belowPos);
                }
                SNOW_GRASS_COLUMNS.incrementAndGet();
                if (!level.getBiome(top).value().shouldSnow(level, top)) {
                    continue;
                }
                SNOW_BIOME_COLUMNS.incrementAndGet();
                if (above.isAir()) {
                    chunk.setBlockState(top.immutable(), Blocks.SNOW.defaultBlockState(), false);
                    chunk.setUnsaved(true);
                    level.getChunkSource().blockChanged(top);
                    placed++;
                }
                if (below.hasProperty(SnowyDirtBlock.SNOWY)) {
                    chunk.setBlockState(belowPos.immutable(),
                        below.setValue(SnowyDirtBlock.SNOWY, Boolean.TRUE), false);
                    chunk.setUnsaved(true);
                    level.getChunkSource().blockChanged(belowPos);
                }
            }
        }
        SNOW_RECONCILED_CHUNKS.incrementAndGet();
        SNOW_PLACED.addAndGet(placed);
        if (placed > 0) {
            LC2H.LOGGER.debug("[LC2H] Reconciled {} snow columns in generated chunk {},{}",
                placed, chunk.getPos().x, chunk.getPos().z);
        }
    }

    public static String snowDiagnostics() {
        return "reconciledChunks=" + SNOW_RECONCILED_CHUNKS.get()
            + ", placed=" + SNOW_PLACED.get()
            + ", surfaceColumns=" + SNOW_SURFACE_COLUMNS.get()
            + ", grassColumns=" + SNOW_GRASS_COLUMNS.get()
            + ", biomeColumns=" + SNOW_BIOME_COLUMNS.get()
            + ", heightmapFallbacks=" + SNOW_HEIGHTMAP_FALLBACKS.get()
            + ", failures=" + SNOW_RECONCILE_FAILURES.get()
            + ", pending=" + GENERATED_SURFACE_RECONCILIATION.size();
    }

    public static String floatingDiagnostics() {
        int pending = 0;
        int overflowChunks = 0;
        for (PendingCheckQueue bucket : PENDING_FLOATING.values()) {
            if (bucket != null) {
                pending += Math.max(0, bucket.size.get()) + Math.max(0, bucket.overflowSize.get());
                overflowChunks += bucket.overflowChunks.size();
            }
        }
        return "pending=" + pending
            + ", overflowChunks=" + overflowChunks
            + ", chunkScans=" + CHUNK_SCAN_PROGRESS.size()
            + ", deferredChunkScans=" + DEFERRED_CHUNK_SCANS.size()
            + ", generatedAudits=" + GENERATED_AUDIT_CHUNKS.size()
            + ", scanDeferrals=" + CHUNK_SCAN_DEFERRALS.get()
            + ", fixed=" + FIXED_FLOATING
            + ", retries=" + FLOATING_CHECK_RETRIES.get()
            + ", retryDrops=" + FLOATING_CHECK_RETRY_DROPS.get()
            + ", anchorMemoHits=" + FLOATING_ANCHOR_MEMO_HITS.get()
            + ", requested=" + FLOATING_DRAIN_REQUESTED.get();
    }

    private static void drainGeneratedSnow(ServerLevel level) {
        if (level == null || !ConfigManager.CITY_BLEND_ENABLED
            || GENERATED_SURFACE_RECONCILIATION.isEmpty()) {
            return;
        }
        ResourceLocation dimension = level.dimension().location();
        int processed = 0;
        for (ChunkScanKey key : GENERATED_SURFACE_RECONCILIATION) {
            if (processed >= MAX_SURFACE_RECONCILES_PER_TICK) {
                break;
            }
            if (!dimension.equals(key.dimension())) {
                continue;
            }
            LevelChunk chunk = level.getChunkSource().getChunkNow(key.chunkX(), key.chunkZ());
            if (chunk == null) {
                continue;
            }
            if (chunk.getStatus() != ChunkStatus.FULL) {
                continue;
            }
            try {
                reconcileGeneratedSnow(level, chunk);
                GENERATED_SURFACE_RECONCILIATION.remove(key);
                processed++;
            } catch (Throwable failure) {
                // Retry after a transient block or biome failure.
                long failures = SNOW_RECONCILE_FAILURES.incrementAndGet();
                if (failures <= 3 || (failures & 255L) == 0L) {
                    LC2H.LOGGER.warn("[LC2H] Retaining snow reconciliation for chunk {},{} after failure: {}",
                        key.chunkX(), key.chunkZ(), failure.toString());
                }
            }
        }
    }

    @SubscribeEvent
    public static void onLevelTick(TickEvent.LevelTickEvent event) {
        boolean floatingScanEnabled = ConfigManager.ENABLE_FLOATING_VEGETATION_REMOVAL && ENABLE_FLOATING_SCAN;
        boolean surfaceReconciliationEnabled = ConfigManager.CITY_BLEND_ENABLED
            && !GENERATED_SURFACE_RECONCILIATION.isEmpty();
        if (!floatingScanEnabled && !ConfigManager.ENABLE_ASYNC_DOUBLE_BLOCK_BATCHER
            && FORCED_CLEANUP_SCANS.isEmpty() && !surfaceReconciliationEnabled) return;
        if (!(event.level instanceof ServerLevel level) || event.phase != TickEvent.Phase.END) return;

        // Keep bounded cleanup queues moving while worldgen is busy.
        boolean overloadedTick = ServerTickLoad.shouldPauseNonCritical(level.getServer());
        long tick = level.getServer().getTickCount();
        // Reconcile surfaces after the terrain pass, even during a busy tick.
        drainGeneratedSnow(level);
        if (!overloadedTick) {
            drainDeferredChunkScans(level);
        }
        if (overloadedTick) {
            if (FLOATING_DRAIN_REQUESTED.get() && LAST_FLOATING_DRAIN_TICK != tick) {
                LAST_FLOATING_DRAIN_TICK = tick;
                drainFloatingChecks(level.getServer());
            }
            if (SHADOW_REMOVAL_DRAIN_REQUESTED.get() && LAST_SHADOW_REMOVAL_DRAIN_TICK != tick) {
                LAST_SHADOW_REMOVAL_DRAIN_TICK = tick;
                drainShadowRemovals(level.getServer());
            }
            return;
        }

        if (FLOATING_DRAIN_REQUESTED.get() && LAST_FLOATING_DRAIN_TICK != tick) {
            LAST_FLOATING_DRAIN_TICK = tick;
            drainFloatingChecks(level.getServer());
        }
        if (SHADOW_REMOVAL_DRAIN_REQUESTED.get() && LAST_SHADOW_REMOVAL_DRAIN_TICK != tick) {
            LAST_SHADOW_REMOVAL_DRAIN_TICK = tick;
            drainShadowRemovals(level.getServer());
        }
        boolean canAutoRescan = ConfigManager.ENABLE_AUTOMATIC_CHUNK_SCANS && AUTO_RESCAN_STARTUP;
        if (!RESCAN_TRIGGERED && canAutoRescan && tick > 100) {
            RESCAN_TRIGGERED = true;
            RESCAN_IN_PROGRESS = true;
            RESCAN_START_TICK = tick;
            COMPLETED_CHUNKS.clear();
            enqueueLoadedChunks(level);
            LCLogger.info("LC2H ChunkPostProcessor rescan triggered (auto after 5s)");
        }

        double avgTick = level.getServer().getAverageTickTime();

        boolean useBatchDrain = !ENABLE_THREADED_SCAN && (ENABLE_BATCH_DRAIN
            || (CHUNK_SCAN_PROGRESS.size() >= QUEUE_ALERT_THRESHOLD && avgTick < (TICK_TIME_BUDGET_MS * 0.65D)));
        if (useBatchDrain) {
            if (!CHUNK_SCAN_PROGRESS.isEmpty() && BATCH_IN_FLIGHT.compareAndSet(false, true)) {
                submitBatch(level);
            }
            if (BATCH_IN_FLIGHT.get()) {
                return; // The batch drains this work, so skip the incremental pass.
            }
        }

        if (avgTick > TICK_TIME_BUDGET_MS) {
            return;
        }

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

            LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
            if (chunk == null) {
                continue;
            }
            ScanCursor cursor = entry.getValue();
            boolean forcedCleanup = FORCED_CLEANUP_SCANS.contains(key);
            if (ENABLE_THREADED_SCAN) {
                if (INFLIGHT_CHUNK_SCANS.add(key)) {
                    boolean allowFloating = forcedCleanup || (floatingScanEnabled && shouldScanFloatingInChunk(level, chunkX, chunkZ));
                    submitAsyncScan(level, key, chunk, cursor, allowFloating, forcedCleanup, scanSamplesPerTask);
                    processed++;
                }
                if (processed >= MAX_ASYNC_CHUNKS_PER_TICK) {
                    break;
                }
                continue;
            }
            boolean allowFloating = forcedCleanup || (floatingScanEnabled && shouldScanFloatingInChunk(level, chunkX, chunkZ));
            ScanCursor next = processChunk(level, chunk, cursor, deadlineNs, allowFloating, scanSamplesPerTask);
            if (next == null) {
                markChunkComplete(chunk);
                GENERATED_AUDIT_CHUNKS.remove(key);
                FORCED_CLEANUP_SCANS.remove(key);
                clearFloatingOverflowChunk(key);
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
        long startNs = System.nanoTime();
        int minY = level.getMinBuildHeight();
        int maxY = level.getMaxBuildHeight() - 1;
        int baseX = chunk.getPos().getMinBlockX();
        int baseZ = chunk.getPos().getMinBlockZ();
        LevelChunkSection[] sections = chunk.getSections();
        boolean doubleBlockEnabled = ConfigManager.ENABLE_ASYNC_DOUBLE_BLOCK_BATCHER;
        int budget = Math.max(MIN_SAMPLES_PER_TASK, Math.min(SAMPLES_PER_CHUNK, sampleBudget));

        ScanCursor current = cursor;
        int samples = 0;
        try {
        while (samples < budget) {
            if (System.nanoTime() > deadlineNs) {
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

            BlockState state = getSectionState(section, current.x, current.y, current.z);
            if (state == null) {
                samples++;
                current = current.advance(maxY);
                continue;
            }
            BlockPos.MutableBlockPos pos = null;

            if (floatingScanEnabled && shouldWatchFloatingCandidate(state)) {
                pos = LOCAL_SCAN_POS.get();
                pos.set(baseX + current.x, current.y, baseZ + current.z);
                long floatingStartNs = System.nanoTime();
                boolean remove = shouldRemoveFloatingCandidate(level, pos, state);
                TIMING_FLOATING_CANDIDATE_CHECK.record(System.nanoTime() - floatingStartNs);
                if (remove) {
                    FIXED_FLOATING += removeFloatingCandidate(level, pos, state);
                    LCLogger.debug("Removed floating vegetation on chunk scan at {}", pos);
                } else if (!hasLoadedNeighborhood(level, pos)) {
                    enqueueFloatingCheck(level, pos.immutable());
                }
            }

            if (doubleBlockEnabled && hasDoubleHalf(state)) {
                if (pos == null) {
                    pos = LOCAL_SCAN_POS.get();
                    pos.set(baseX + current.x, current.y, baseZ + current.z);
                }
                long repairStartNs = System.nanoTime();
                repairDoubleHalf(level, pos, state);
                TIMING_DOUBLE_BLOCK_REPAIR.record(System.nanoTime() - repairStartNs);
            }

            samples++;
            current = current.advance(maxY);
        }

        return current;
        } finally {
            TIMING_PROCESS_CHUNK.record(System.nanoTime() - startNs);
        }
    }

    private static void submitAsyncScan(ServerLevel level, ChunkScanKey key, LevelChunk chunk, ScanCursor cursor,
                                        boolean floatingScanEnabled, boolean forcedCleanup, int sampleBudget) {
        int budget = Math.max(MIN_SAMPLES_PER_TASK, Math.min(SAMPLES_PER_CHUNK, sampleBudget));
        CpuBatchScheduler.submit("chunk_post_scan", () -> {
            long startNs = System.nanoTime();
            try {
                ChunkScanResult result = scanChunkAsync(key, chunk, cursor, floatingScanEnabled, forcedCleanup, budget);
                ServerRescheduler.runOnServer(() -> applyScanResult(level, result));
            } catch (Throwable t) {
                INFLIGHT_CHUNK_SCANS.remove(key);
            } finally {
                TIMING_SUBMIT_ASYNC_SCAN.record(System.nanoTime() - startNs);
            }
        });
    }

    private static ChunkScanResult scanChunkAsync(ChunkScanKey key, LevelChunk chunk, ScanCursor cursor,
                                                  boolean floatingScanEnabled, boolean forcedCleanup, int sampleBudget) {
        long startNs = System.nanoTime();
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

        try {
        while (samples < budget) {
            if (current.y < minY) {
                current = new ScanCursor(current.x, minY, current.z);
            }
            if (current.y > maxY) {
                return new ChunkScanResult(key, null, floating == null ? List.of() : floating,
                    doubleBlocks == null ? List.of() : doubleBlocks, forcedCleanup);
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

            BlockState state;
            try {
                state = getSectionState(section, current.x, current.y, current.z);
                if (state == null) {
                    current = current.advance(maxY);
                    samples++;
                    continue;
                }
            } catch (Throwable ignored) {
                current = current.advance(maxY);
                samples++;
                continue;
            }
            BlockPos.MutableBlockPos pos = null;

            if (floatingScanEnabled && shouldWatchFloatingCandidate(state)) {
                pos = LOCAL_SCAN_POS.get();
                pos.set(baseX + current.x, current.y, baseZ + current.z);
                BlockPos.MutableBlockPos below = LOCAL_BELOW_POS.get();
                below.set(pos.getX(), pos.getY() - 1, pos.getZ());
                try {
                    long candidateStartNs = System.nanoTime();
                    BlockState belowState = getChunkLocalState(chunk, sections, current.x, current.y - 1, current.z);
                    if (belowState == null) {
                        belowState = chunk.getBlockState(below);
                    }
                    boolean unsupported = isUnsupportedSupport(levelFromChunk(chunk), below, belowState);
                    boolean queueCandidate = isDecayMarkedLeaf(state)
                        || isAttachmentDecoration(state)
                        || isModdedTreeDecoration(state)
                        || (unsupported && !hasTrackedPlantSelfSupport(state, belowState));
                    TIMING_ASYNC_FLOATING_CANDIDATE.record(System.nanoTime() - candidateStartNs);
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
                if (pos == null) {
                    pos = LOCAL_SCAN_POS.get();
                    pos.set(baseX + current.x, current.y, baseZ + current.z);
                }
                if (doubleBlocks == null) {
                    doubleBlocks = new java.util.ArrayList<>();
                }
                doubleBlocks.add(pos.asLong());
            }

            samples++;
            current = current.advance(maxY);
        }

        return new ChunkScanResult(key, current, floating == null ? List.of() : floating,
            doubleBlocks == null ? List.of() : doubleBlocks, forcedCleanup);
        } finally {
            TIMING_SCAN_CHUNK_ASYNC.record(System.nanoTime() - startNs);
        }
    }

    private static ServerLevel levelFromChunk(LevelChunk chunk) {
        if (chunk == null || !(chunk.getLevel() instanceof ServerLevel level)) {
            return null;
        }
        return level;
    }

    private static void applyScanResult(ServerLevel level, ChunkScanResult result) {
        long startNs = System.nanoTime();
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

        LevelChunk chunk = level.getChunkSource().getChunkNow(key.chunkX(), key.chunkZ());
        if (chunk == null) {
            return;
        }

        ArtifactAnchorMemo anchorMemo = new ArtifactAnchorMemo();
        for (Long posLong : result.floating()) {
            if (posLong == null) {
                continue;
            }
            BlockPos pos = BlockPos.of(posLong);
            BlockState state = getLoadedState(level, pos);
            if (state == null || !hasLoadedNeighborhood(level, pos)) {
                enqueueFloatingCheck(level, pos);
                continue;
            }
            long floatingStartNs = System.nanoTime();
            boolean remove = shouldRemoveFloatingCandidate(level, pos, state, anchorMemo);
            TIMING_APPLY_FLOATING_CANDIDATE.record(System.nanoTime() - floatingStartNs);
            if (remove) {
                FIXED_FLOATING += removeFloatingCandidate(level, pos, state);
            }
        }

        for (Long posLong : result.doubleBlocks()) {
            if (posLong == null) {
                continue;
            }
            BlockPos pos = BlockPos.of(posLong);
            BlockState state = getLoadedState(level, pos);
            if (state == null) {
                continue;
            }
            if (hasDoubleHalf(state)) {
                long repairStartNs = System.nanoTime();
                repairDoubleHalf(level, pos, state);
                TIMING_APPLY_DOUBLE_BLOCK_REPAIR.record(System.nanoTime() - repairStartNs);
            }
        }

        if (result.next() == null) {
            markChunkComplete(chunk);
            GENERATED_AUDIT_CHUNKS.remove(key);
            CHUNK_SCAN_PROGRESS.remove(key);
            FORCED_CLEANUP_SCANS.remove(key);
            clearFloatingOverflowChunk(key);
        } else {
            CHUNK_SCAN_PROGRESS.put(key, result.next());
        }

        maybeFinishRescan(level);
        TIMING_APPLY_SCAN_RESULT.record(System.nanoTime() - startNs);
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
        int byBudget = (int) Math.ceil(workBudgetMs / 0.75D);
        int value = Math.max(1, Math.min(8, Math.max(MAX_CHUNKS_PER_TICK, byBudget)));
        int deferredTrees = DeferredTreeQueue.pendingCountAll() + DeferredTreeQueue.readyCountAll();
        if (CHUNK_SCAN_PROGRESS.size() >= QUEUE_ALERT_THRESHOLD || deferredTrees >= 96) {
            value = Math.max(1, value / 2);
        }
        return value;
    }

    private static int computeScanSamplesPerTask(double workBudgetMs, double avgTickMs, int playerCount, boolean threaded) {
        int samples = threaded ? Math.max(MIN_SAMPLES_PER_TASK, SAMPLES_PER_CHUNK / 2) : SAMPLES_PER_CHUNK;
        int deferredTrees = DeferredTreeQueue.pendingCountAll() + DeferredTreeQueue.readyCountAll();
        int backlog = CHUNK_SCAN_PROGRESS.size();

        if (avgTickMs >= TICK_TIME_BUDGET_MS * 0.95D || workBudgetMs <= 0.5D) {
            samples = Math.max(MIN_SAMPLES_PER_TASK, samples / 2);
        } else if (avgTickMs >= TICK_TIME_BUDGET_MS * 0.85D || workBudgetMs <= 1.0D) {
            samples = Math.max(MIN_SAMPLES_PER_TASK, (samples * 2) / 3);
        } else if (playerCount <= 0 && workBudgetMs >= 2.0D) {
            samples = Math.min(SAMPLES_PER_CHUNK, samples + 16);
        }
        if (backlog >= QUEUE_ALERT_THRESHOLD) {
            samples = Math.max(MIN_SAMPLES_PER_TASK, (samples * 3) / 4);
        }
        if (deferredTrees >= 96) {
            samples = Math.max(MIN_SAMPLES_PER_TASK, samples / 2);
        } else if (deferredTrees >= 48) {
            samples = Math.max(MIN_SAMPLES_PER_TASK, (samples * 3) / 4);
        }

        return Math.max(MIN_SAMPLES_PER_TASK, Math.min(SAMPLES_PER_CHUNK, samples));
    }

    private static double computeWorkBudgetMs(double avgTickMs, int playerCount, int backlog) {
        if (OVERRIDE_MAX_WORK_TIME_PER_TICK_MS > 0.0D) {
            return Math.max(MIN_WORK_TIME_PER_TICK_MS, OVERRIDE_MAX_WORK_TIME_PER_TICK_MS);
        }

        double cap = (playerCount <= 0) ? MAX_WORK_TIME_PER_TICK_MS_STARTUP : MAX_WORK_TIME_PER_TICK_MS_PLAYERS;
        double target = Math.min(TARGET_TICK_MS, TICK_TIME_BUDGET_MS);
        double slack = target - avgTickMs;

        double next = ADAPTIVE_WORK_BUDGET_MS + (slack * 0.05D);

        if (avgTickMs > target * 0.90D) {
            next *= 0.85D;
        }
        if (avgTickMs > target) {
            next *= 0.50D;
        }

        if (backlog >= QUEUE_ALERT_THRESHOLD && slack > 3.0D) {
            next += 0.5D;
        }
        int deferredTrees = DeferredTreeQueue.pendingCountAll() + DeferredTreeQueue.readyCountAll();
        if (deferredTrees >= 96) {
            next *= 0.60D;
        } else if (deferredTrees >= 48) {
            next *= 0.80D;
        }

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
        long startNs = System.nanoTime();
        int processed = 0;
        ResourceLocation dimension = level.dimension().location();
        while (!CHUNK_SCAN_PROGRESS.isEmpty()) {
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
                LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
                if (chunk == null) {
                    processedEntry = true;
                    break;
                }
                long deadlineNs = System.nanoTime() + (long) (Math.max(2.0D, ADAPTIVE_WORK_BUDGET_MS * 4.0D) * 1_000_000.0);
                boolean allowFloating = ConfigManager.ENABLE_FLOATING_VEGETATION_REMOVAL
                    && ENABLE_FLOATING_SCAN
                    && shouldScanFloatingInChunk(level, chunkX, chunkZ);
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
                    GENERATED_AUDIT_CHUNKS.remove(key);
                    clearFloatingOverflowChunk(key);
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
        TIMING_DRAIN_ALL.record(System.nanoTime() - startNs);
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
                        boolean cityChunk = floatingScanEnabled && shouldScanFloatingInChunk(level, chunk.getPos().x, chunk.getPos().z);
                        boolean effectiveFloating = floatingScanEnabled && cityChunk;
                        if (!chunkHasInterestingBlocks(chunk, effectiveFloating, doubleBlockEnabled)) {
                            ChunkScanKey key = chunkKey(level.dimension().location(), chunk.getPos().x, chunk.getPos().z);
                            COMPLETED_CHUNKS.add(key);
                            continue;
                        }
                        ChunkScanKey key = chunkKey(level.dimension().location(), chunk.getPos().x, chunk.getPos().z);
                        if (!shouldEnqueueChunkScan(key)) {
                            continue;
                        }
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

    private static BlockState getSectionState(LevelChunkSection section, int localX, int worldY, int localZ) {
        if (section == null) {
            return null;
        }
        try {
            return section.getStates().get(localX & 15, worldY & 15, localZ & 15);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static BlockState getChunkLocalState(LevelChunk chunk,
                                                 LevelChunkSection[] sections,
                                                 int localX,
                                                 int worldY,
                                                 int localZ) {
        if (chunk == null || sections == null) {
            return null;
        }
        int sectionIndex;
        try {
            sectionIndex = chunk.getSectionIndex(worldY);
        } catch (Throwable ignored) {
            return null;
        }
        if (sectionIndex < 0 || sectionIndex >= sections.length) {
            return null;
        }
        return getSectionState(sections[sectionIndex], localX, worldY, localZ);
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
                    BlockState newLower = copyHalf(state, halfProp, DoubleBlockHalf.LOWER);
                    if (!supportState.isCollisionShapeFullBlock(level, support) || !newLower.canSurvive(level, lowerPos)) {
                        return;
                    }
                    level.setBlock(lowerPos, newLower, SAFE_SET_FLAGS);
                    level.setBlock(pos, copyHalf(state, halfProp, DoubleBlockHalf.UPPER), SAFE_SET_FLAGS);
                } else if (canReplace(level, lowerPos) && state.canSurvive(level, lowerPos)) {
                    BlockState newLower = copyHalf(state, halfProp, DoubleBlockHalf.LOWER);
                    level.setBlock(lowerPos, newLower, SAFE_SET_FLAGS);
                } else {
                    if (!isDoublePlant) {
                        level.setBlock(pos, Blocks.AIR.defaultBlockState(), SAFE_SET_FLAGS);
                    }
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
        RECENT_SCAN_ENQUEUE_MS.put(key, System.currentTimeMillis());
    }

    public static void forceRescanChunk(ServerLevel level, net.minecraft.world.level.ChunkPos pos) {
        ChunkScanKey key = chunkKey(level.dimension().location(), pos.x, pos.z);
        COMPLETED_CHUNKS.remove(key);
        GENERATED_AUDIT_CHUNKS.remove(key);
        RECENT_SCAN_ENQUEUE_MS.remove(key);
        int backlog = CHUNK_SCAN_PROGRESS.size();
        if (backlog >= MAX_QUEUE) {
            if (LOGGED_QUEUE_HARD_LIMIT.compareAndSet(false, true)) {
                LCLogger.warn(
                    "ChunkPostProcessor backlog hard limit reached ({} queued chunks >= max_queue {}). Deferring forced rescan enqueue.",
                    backlog,
                    MAX_QUEUE
                );
            }
            FORCED_CLEANUP_SCANS.add(key);
            deferChunkScan(key);
            RESCAN_IN_PROGRESS = true;
            RESCAN_START_TICK = level.getServer().getTickCount();
            return;
        }
        FORCED_CLEANUP_SCANS.add(key);
        int minY = level.getMinBuildHeight();
        CHUNK_SCAN_PROGRESS.put(key, new ScanCursor(0, minY, 0));
        RESCAN_IN_PROGRESS = true;
        RESCAN_START_TICK = level.getServer().getTickCount();
        LCLogger.info("ChunkPostProcessor: forced rescan queued for chunk ({}, {})", pos.x, pos.z);
    }

    public static int forceRescanArea(ServerLevel level, net.minecraft.world.level.ChunkPos center, int radius) {
        if (level == null || center == null) {
            return 0;
        }
        int boundedRadius = Math.max(0, Math.min(8, radius));
        int queued = 0;
        for (int dx = -boundedRadius; dx <= boundedRadius; dx++) {
            for (int dz = -boundedRadius; dz <= boundedRadius; dz++) {
                int cx = center.x + dx;
                int cz = center.z + dz;
                if (level.getChunkSource().getChunkNow(cx, cz) == null) {
                    continue;
                }
                forceRescanChunk(level, new ChunkPos(cx, cz));
                queued++;
            }
        }
        return queued;
    }

    private static ChunkScanKey chunkKey(ResourceLocation dimension, int chunkX, int chunkZ) {
        return new ChunkScanKey(dimension, chunkX, chunkZ);
    }

    private static boolean shouldEnqueueChunkScan(ChunkScanKey key) {
        if (key == null) {
            return false;
        }
        if (CHUNK_SCAN_PROGRESS.containsKey(key) || INFLIGHT_CHUNK_SCANS.contains(key)) {
            return false;
        }
        long now = System.currentTimeMillis();
        Long last = RECENT_SCAN_ENQUEUE_MS.get(key);
        if (last != null && (now - last) < CHUNK_SCAN_ENQUEUE_COOLDOWN_MS) {
            return false;
        }
        RECENT_SCAN_ENQUEUE_MS.put(key, now);
        return true;
    }

    private static boolean tryQueueChunkScan(ServerLevel level,
                                             LevelChunk chunk,
                                             ChunkScanKey key,
                                             boolean generatedAudit) {
        if (level == null || chunk == null || key == null) {
            return false;
        }
        if (CHUNK_SCAN_PROGRESS.containsKey(key) || INFLIGHT_CHUNK_SCANS.contains(key)) {
            return true;
        }

        int backlog = CHUNK_SCAN_PROGRESS.size();
        if (backlog >= MAX_QUEUE) {
            if (LOGGED_QUEUE_HARD_LIMIT.compareAndSet(false, true)) {
                LCLogger.warn(
                    "ChunkPostProcessor backlog hard limit reached ({} queued chunks >= max_queue {}). Deferring new chunk scans until backlog reduces.",
                    backlog,
                    MAX_QUEUE
                );
            }
            return false;
        }

        if (backlog >= QUEUE_ALERT_THRESHOLD) {
            if (LOGGED_QUEUE_ALERT.compareAndSet(false, true)) {
                LCLogger.warn(
                    "ChunkPostProcessor backlog elevated ({} queued chunks >= alert threshold {}). Deferring new work until it drains.",
                    backlog,
                    QUEUE_ALERT_THRESHOLD
                );
            }
            try {
                if (ServerTickLoad.shouldPauseNonCritical(level.getServer())) {
                    return false;
                }
            } catch (Throwable ignored) {
            }

        } else if (backlog < QUEUE_ALERT_THRESHOLD / 2) {
            LOGGED_QUEUE_ALERT.set(false);
            LOGGED_QUEUE_HARD_LIMIT.set(false);
        }

        try {
            if (level.getServer().getPlayerCount() == 0 && backlog >= QUEUE_ALERT_THRESHOLD) {
                return false;
            }
        } catch (Throwable ignored) {
        }

        if (!shouldEnqueueChunkScan(key)) {
            return false;
        }

        int minY = level.getMinBuildHeight();
        if (CHUNK_SCAN_PROGRESS.putIfAbsent(key, new ScanCursor(0, minY, 0)) == null) {
            if (generatedAudit) {
                FORCED_CLEANUP_SCANS.add(key);
            }
        }
        return true;
    }

    private static void deferChunkScan(ChunkScanKey key) {
        if (key == null) {
            return;
        }
        if (DEFERRED_CHUNK_SCANS.size() < MAX_DEFERRED_CHUNK_SCANS
            || DEFERRED_CHUNK_SCANS.contains(key)) {
            if (DEFERRED_CHUNK_SCANS.add(key)) {
                CHUNK_SCAN_DEFERRALS.incrementAndGet();
            }
            return;
        }
        if (LOGGED_QUEUE_HARD_LIMIT.compareAndSet(false, true)) {
            LCLogger.warn(
                "ChunkPostProcessor deferred scan limit reached ({}). Further chunk audits will wait for a later load.",
                MAX_DEFERRED_CHUNK_SCANS
            );
        }
    }

    private static void drainDeferredChunkScans(ServerLevel level) {
        if (level == null || DEFERRED_CHUNK_SCANS.isEmpty()
            || CHUNK_SCAN_PROGRESS.size() >= MAX_QUEUE) {
            return;
        }
        ResourceLocation dimension = level.dimension().location();
        int processed = 0;
        for (ChunkScanKey key : DEFERRED_CHUNK_SCANS) {
            if (processed >= MAX_DEFERRED_CHUNK_SCANS_PER_TICK) {
                break;
            }
            if (!dimension.equals(key.dimension())) {
                continue;
            }
            LevelChunk chunk = level.getChunkSource().getChunkNow(key.chunkX(), key.chunkZ());
            if (chunk == null) {
                DEFERRED_CHUNK_SCANS.remove(key);
                continue;
            }
            boolean generatedAudit = GENERATED_AUDIT_CHUNKS.contains(key);
            boolean floatingEnabled = ConfigManager.ENABLE_FLOATING_VEGETATION_REMOVAL && ENABLE_FLOATING_SCAN;
            boolean doubleBlockEnabled = ConfigManager.ENABLE_AUTOMATIC_CHUNK_SCANS
                && ConfigManager.ENABLE_ASYNC_DOUBLE_BLOCK_BATCHER;
            if (generatedAudit && VineClusterCleaner.isFloatingCleanupEnabled()) {
                try {
                    VineClusterCleaner.cleanVinesOnFirstLoad(level, chunk);
                } catch (Throwable failure) {
                    LCLogger.debug("ChunkPostProcessor: generated vegetation retry deferred: {}", failure.toString());
                }
            }
            if (!chunkHasInterestingBlocks(chunk, floatingEnabled, doubleBlockEnabled)) {
                GENERATED_AUDIT_CHUNKS.remove(key);
                FORCED_CLEANUP_SCANS.remove(key);
                COMPLETED_CHUNKS.add(key);
                DEFERRED_CHUNK_SCANS.remove(key);
                processed++;
                continue;
            }
            if (tryQueueChunkScan(level, chunk, key, generatedAudit)) {
                DEFERRED_CHUNK_SCANS.remove(key);
                processed++;
            }
        }
    }

    private static long packChunk(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) ^ (chunkZ & 0xffffffffL);
    }

    private static boolean queueFloatingOverflowChunk(PendingCheckQueue bucket, BlockPos pos) {
        if (bucket == null || pos == null) {
            return false;
        }
        long packedChunk = packChunk(pos.getX() >> 4, pos.getZ() >> 4);
        if (bucket.overflowChunks.contains(packedChunk)) {
            return true;
        }
        if (bucket.overflowChunks.size() >= MAX_PENDING_FLOATING_OVERFLOW_CHUNKS) {
            return false;
        }
        return bucket.overflowChunks.add(packedChunk);
    }

    private static void clearFloatingOverflowChunk(ChunkScanKey key) {
        if (key == null) {
            return;
        }
        long packedChunk = packChunk(key.chunkX(), key.chunkZ());
        for (PendingCheckQueue bucket : PENDING_FLOATING.values()) {
            ResourceKey<Level> dimension = bucket.dimensionKey;
            if (dimension != null && key.dimension().equals(dimension.location())) {
                bucket.overflowChunks.remove(packedChunk);
            }
        }
    }

    private static int drainFloatingOverflowChunks(MinecraftServer server,
                                                    PendingCheckQueue bucket) {
        if (server == null || bucket == null || bucket.overflowChunks.isEmpty()) {
            return 0;
        }
        ServerLevel level = bucket.level;
        if (level == null && bucket.dimensionKey != null) {
            level = server.getLevel(bucket.dimensionKey);
            bucket.level = level;
        }
        if (level == null) {
            return 0;
        }
        int scheduled = 0;
        ResourceLocation dimension = level.dimension().location();
        for (Long packedChunk : bucket.overflowChunks) {
            if (scheduled >= MAX_FLOATING_OVERFLOW_SCANS_PER_TICK) {
                break;
            }
            if (packedChunk == null) {
                continue;
            }
            int chunkX = (int) (packedChunk.longValue() >> 32);
            int chunkZ = (int) packedChunk.longValue();
            ChunkScanKey key = chunkKey(dimension, chunkX, chunkZ);
            if (CHUNK_SCAN_PROGRESS.containsKey(key) || INFLIGHT_CHUNK_SCANS.contains(key)) {
                FORCED_CLEANUP_SCANS.add(key);
                bucket.overflowChunks.remove(packedChunk);
                continue;
            }
            LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
            if (chunk == null) {
                continue;
            }
            COMPLETED_CHUNKS.remove(key);
            boolean doubleBlockEnabled = ConfigManager.ENABLE_ASYNC_DOUBLE_BLOCK_BATCHER;
            if (!chunkHasInterestingBlocks(chunk, true, doubleBlockEnabled)) {
                bucket.overflowChunks.remove(packedChunk);
                COMPLETED_CHUNKS.add(key);
                continue;
            }
            if (tryQueueChunkScan(level, chunk, key, true)) {
                bucket.overflowChunks.remove(packedChunk);
                scheduled++;
            }
        }
        return scheduled;
    }

    private static void enqueueFloatingCheck(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null) {
            return;
        }
        if (!ConfigManager.ENABLE_FLOATING_VEGETATION_REMOVAL || !ENABLE_FLOATING_SCAN) {
            return;
        }
        ResourceKey<Level> dimension = level.dimension();
        PendingCheckQueue bucket = PENDING_FLOATING.computeIfAbsent(dimension, k -> new PendingCheckQueue());
        bucket.dimensionKey = dimension;
        bucket.level = level;
        // Keep events queued during worldgen; both queues are deduplicated.
        long packed = pos.asLong();
        if (bucket.dedupe.putIfAbsent(packed, Boolean.TRUE) != null) {
            return;
        }
        int pending = bucket.size.incrementAndGet();
        long nowTick = level.getServer() == null ? 0L : level.getServer().getTickCount();
        if (pending > MAX_PENDING_FLOATING_CHECKS) {
            bucket.size.decrementAndGet();
            if (queueFloatingOverflowChunk(bucket, pos)) {
                bucket.dedupe.remove(packed);
                FLOATING_DRAIN_REQUESTED.set(true);
                return;
            }
            int overflow = bucket.overflowSize.incrementAndGet();
            if (overflow > MAX_PENDING_FLOATING_OVERFLOW) {
                bucket.overflowSize.decrementAndGet();
                bucket.dedupe.remove(packed);
                FLOATING_CHECK_RETRY_DROPS.incrementAndGet();
                if (LOGGED_FLOATING_QUEUE_HARD_LIMIT.compareAndSet(false, true)) {
                    LCLogger.warn(
                        "ChunkPostProcessor floating check queues are full ({} main + {} overflow + {} overflow chunks). Dropping new checks until backlog reduces.",
                        pending - 1,
                        overflow - 1,
                        bucket.overflowChunks.size()
                    );
                }
                return;
            }
            bucket.overflow.add(new PendingFloatingCheck(packed, 0, nowTick));
            FLOATING_DRAIN_REQUESTED.set(true);
            return;
        }
        bucket.queue.add(new PendingFloatingCheck(packed, 0, nowTick));
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
        int remainingBudget = MAX_FLOATING_CHECKS_PER_TICK;
        if (server != null) {
            double avgTick = server.getAverageTickTime();
            if (avgTick > TICK_TIME_BUDGET_MS * 1.5D) {
                remainingBudget = Math.max(4, remainingBudget / 8);
            } else if (avgTick > TICK_TIME_BUDGET_MS) {
                remainingBudget = Math.max(8, remainingBudget / 4);
            } else if (avgTick > TARGET_TICK_MS) {
                remainingBudget = Math.max(8, remainingBudget / 2);
            }
        }
        int deferredTrees = DeferredTreeQueue.pendingCountAll() + DeferredTreeQueue.readyCountAll();
        if (deferredTrees >= 96) {
            remainingBudget = Math.max(8, remainingBudget / 4);
        } else if (deferredTrees >= 48) {
            remainingBudget = Math.max(8, remainingBudget / 2);
        }
        boolean pending = false;
        long currentTick = server == null ? 0L : server.getTickCount();
        ArtifactAnchorMemo anchorMemo = new ArtifactAnchorMemo();
        for (PendingCheckQueue bucket : PENDING_FLOATING.values()) {
            drainFloatingOverflowChunks(server, bucket);
            promoteFloatingOverflow(bucket);
            if (remainingBudget <= 0) {
                pending = pending || bucket.size.get() > 0 || bucket.overflowSize.get() > 0
                    || !bucket.overflowChunks.isEmpty();
                continue;
            }
            ServerLevel level = bucket.level;
            if (level == null && server != null && bucket.dimensionKey != null) {
                level = server.getLevel(bucket.dimensionKey);
                bucket.level = level;
            }
            if (level == null) {
                pending = pending || bucket.size.get() > 0 || bucket.overflowSize.get() > 0
                    || !bucket.overflowChunks.isEmpty();
                continue;
            }
            int examined = Math.max(0, bucket.size.get());
            while (remainingBudget > 0 && examined-- > 0) {
                PendingFloatingCheck check = bucket.queue.poll();
                if (check == null) {
                    break;
                }
                if (check.notBeforeTick > currentTick) {
                    bucket.queue.add(check);
                    continue;
                }
                long posLong = check.packed;
                bucket.dedupe.remove(posLong);
                if (bucket.size.decrementAndGet() < 0) {
                    bucket.size.set(0);
                }
                BlockPos pos = BlockPos.of(posLong);
                BlockState state = getLoadedState(level, pos);
                if (state == null || !hasLoadedNeighborhood(level, pos)) {
                    requeueFloatingCheck(bucket, check, currentTick);
                    continue;
                }
                if (!shouldScanFloatingInChunk(level, pos.getX() >> 4, pos.getZ() >> 4)) {
                    continue;
                }
                boolean retry = false;
                try {
                    if (shouldRemoveFloatingCandidate(level, pos, state, anchorMemo)) {
                        FIXED_FLOATING += removeFloatingCandidate(level, pos, state);
                    }
                } catch (Throwable failure) {
                    // Keep the block when a third-party state check fails.
                    if (LOGGED_FLOATING_CHECK_FAILURE.compareAndSet(false, true)) {
                        LCLogger.warn("ChunkPostProcessor: retained floating candidate after block-state failure: {}",
                            failure.toString());
                    }
                    retry = true;
                }
                if (retry) {
                    requeueFloatingCheck(bucket, check, currentTick);
                }
                remainingBudget--;
            }
            if (bucket.size.get() > 0) {
                pending = true;
            }
            if (bucket.overflowSize.get() > 0) {
                pending = true;
            }
            if (!bucket.overflowChunks.isEmpty()) {
                pending = true;
            }
        }
        if (pending) {
            FLOATING_DRAIN_REQUESTED.set(true);
            return;
        }
        FLOATING_DRAIN_REQUESTED.set(false);
    }

    private static void requeueFloatingCheck(PendingCheckQueue bucket,
                                              PendingFloatingCheck previous,
                                              long currentTick) {
        if (bucket == null || previous == null) {
            return;
        }
        int nextAttempt = previous.attempt + 1;
        if (nextAttempt > MAX_FLOATING_CHECK_RETRIES) {
            nextAttempt = 0;
        }
        long delay = Math.min(MAX_FLOATING_RETRY_DELAY_TICKS,
            1L << Math.min(6, Math.max(0, nextAttempt - 1)));
        queueFloatingRetry(bucket,
            new PendingFloatingCheck(previous.packed, nextAttempt, 0L),
            currentTick,
            delay);
    }

    private static void promoteFloatingOverflow(PendingCheckQueue bucket) {
        if (bucket == null) {
            return;
        }
        while (bucket.size.get() < MAX_PENDING_FLOATING_CHECKS) {
            PendingFloatingCheck check = bucket.overflow.poll();
            if (check == null) {
                return;
            }
            bucket.overflowSize.decrementAndGet();
            if (!bucket.dedupe.containsKey(check.packed)) {
                continue;
            }
            int pending = bucket.size.incrementAndGet();
            if (pending > MAX_PENDING_FLOATING_CHECKS) {
                bucket.size.decrementAndGet();
                bucket.overflowSize.incrementAndGet();
                bucket.overflow.add(check);
                return;
            }
            bucket.queue.add(check);
        }
    }

    private static boolean queueFloatingRetry(PendingCheckQueue bucket,
                                               PendingFloatingCheck check,
                                               long currentTick,
                                               long delay) {
        if (bucket == null || check == null) {
            return false;
        }
        if (bucket.dedupe.putIfAbsent(check.packed, Boolean.TRUE) != null) {
            return false;
        }
        int pending = bucket.size.incrementAndGet();
        if (pending > MAX_PENDING_FLOATING_CHECKS) {
            bucket.size.decrementAndGet();
            if (queueFloatingOverflowChunk(bucket, BlockPos.of(check.packed))) {
                bucket.dedupe.remove(check.packed);
                FLOATING_CHECK_RETRIES.incrementAndGet();
                FLOATING_DRAIN_REQUESTED.set(true);
                return true;
            }
            int overflow = bucket.overflowSize.incrementAndGet();
            if (overflow > MAX_PENDING_FLOATING_OVERFLOW) {
                bucket.overflowSize.decrementAndGet();
                bucket.dedupe.remove(check.packed);
                FLOATING_CHECK_RETRY_DROPS.incrementAndGet();
                return false;
            }
            bucket.overflow.add(new PendingFloatingCheck(check.packed, check.attempt, currentTick + delay));
        } else {
            bucket.queue.add(new PendingFloatingCheck(check.packed, check.attempt, currentTick + delay));
        }
        FLOATING_CHECK_RETRIES.incrementAndGet();
        FLOATING_DRAIN_REQUESTED.set(true);
        return true;
    }

    private static void drainShadowRemovals(MinecraftServer server) {
        if (!ConfigManager.ENABLE_FLOATING_VEGETATION_REMOVAL) {
            SHADOW_REMOVAL_DRAIN_REQUESTED.set(false);
            return;
        }
        int remainingBudget = MAX_SHADOW_REMOVALS_PER_TICK;
        if (server != null) {
            double avgTick = server.getAverageTickTime();
            if (avgTick > TICK_TIME_BUDGET_MS * 1.5D) {
                remainingBudget = Math.max(8, remainingBudget / 8);
            } else if (avgTick > TICK_TIME_BUDGET_MS) {
                remainingBudget = Math.max(16, remainingBudget / 4);
            } else if (avgTick > TARGET_TICK_MS) {
                remainingBudget = Math.max(16, remainingBudget / 2);
            }
        }
        int deferredTrees = DeferredTreeQueue.pendingCountAll() + DeferredTreeQueue.readyCountAll();
        if (deferredTrees >= 96) {
            remainingBudget = Math.max(16, remainingBudget / 4);
        } else if (deferredTrees >= 48) {
            remainingBudget = Math.max(16, remainingBudget / 2);
        }

        boolean pending = false;
        while (remainingBudget > 0) {
            ShadowRemovalBatch batch = SHADOW_REMOVAL_QUEUE.poll();
            if (batch == null) {
                break;
            }
            ServerLevel level = batch.level;
            if (level == null || !level.hasChunk(batch.chunkPos.x, batch.chunkPos.z)) {
                pending = pending || batch.hasRemaining();
                continue;
            }
            LevelChunk chunk = level.getChunkSource().getChunkNow(batch.chunkPos.x, batch.chunkPos.z);
            if (chunk == null) {
                pending = true;
                SHADOW_REMOVAL_QUEUE.add(batch);
                break;
            }

            BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
            while (remainingBudget > 0 && batch.hasRemaining()) {
                unpackShadowRemoval(batch.chunkPos, batch.packedPositions[batch.cursor++], cursor);
                try {
                    BlockState state = chunk.getBlockState(cursor);
                    if (!state.isAir()) {
                        level.removeBlockEntity(cursor);
                        level.setBlock(cursor, AIR_STATE, SAFE_SET_FLAGS);
                    }
                } catch (Throwable ignored) {
                }
                remainingBudget--;
            }

            if (batch.hasRemaining()) {
                pending = true;
                SHADOW_REMOVAL_QUEUE.add(batch);
                break;
            }
        }

        SHADOW_REMOVAL_DRAIN_REQUESTED.set(pending || !SHADOW_REMOVAL_QUEUE.isEmpty());
    }

    private static boolean shouldScanFloatingInChunk(ServerLevel level, int cx, int cz) {
        if (level == null) {
            return false;
        }
        if (!ConfigManager.ENABLE_FLOATING_VEGETATION_REMOVAL || !ENABLE_FLOATING_SCAN) {
            return false;
        }
        return level.getChunkSource().getChunkNow(cx, cz) != null;
    }

    private static void enqueueShadowRemovalBatch(ServerLevel level, ChunkPos chunkPos, List<Integer> packedPositions) {
        if (level == null || chunkPos == null || packedPositions == null || packedPositions.isEmpty()) {
            return;
        }
        ChunkShadowMutationPlan.Builder builder = ChunkShadowMutationPlan.builder(
            level,
            new mcjty.lostcities.varia.ChunkCoord(level.dimension(), chunkPos.x, chunkPos.z));
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (Integer packed : packedPositions) {
            if (packed == null) {
                continue;
            }
            unpackShadowRemoval(chunkPos, packed, cursor);
            builder.add(cursor, AIR_STATE, SAFE_SET_FLAGS, false);
        }
        ChunkShadowMutationPlan plan = builder.build();
        if (plan.size() > 0) {
            ShadowBlockMutationApplier.enqueue(plan);
        }
        SHADOW_REMOVAL_DRAIN_REQUESTED.set(false);
    }

    private static int packShadowRemoval(BlockPos pos) {
        int localX = pos.getX() & 15;
        int localZ = pos.getZ() & 15;
        int y = pos.getY() + SHADOW_Y_OFFSET;
        return (y << 8) | (localX << 4) | localZ;
    }

    private static void unpackShadowRemoval(ChunkPos chunkPos, int packed, BlockPos.MutableBlockPos target) {
        int localZ = packed & 15;
        int localX = (packed >>> 4) & 15;
        int y = (packed >>> 8) - SHADOW_Y_OFFSET;
        target.set(chunkPos.getMinBlockX() + localX, y, chunkPos.getMinBlockZ() + localZ);
    }

    private static boolean isCleanupRelevantChunkCached(ServerLevel level, int cx, int cz) {
        if (level == null) {
            return false;
        }
        if (isCityChunkCached(level, cx, cz)) {
            return true;
        }
        return isCityChunkCached(level, cx + 1, cz)
            || isCityChunkCached(level, cx - 1, cz)
            || isCityChunkCached(level, cx, cz + 1)
            || isCityChunkCached(level, cx, cz - 1);
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
        ServerLevel level = resolveWorldgenServerLevel(region);
        if (level == null) {
            level = resolveServerLevel(region);
        }
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

    public static void markTreePlacement(ServerLevel level, BlockPos pos, BlockState state) {
        if (!ConfigManager.CITY_BLEND_TREE_SEAM_FIX) {
            return;
        }
        if (level == null || pos == null || state == null) {
            return;
        }
        if (!isTreeProtectedBlock(state)) {
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
        ServerLevel level = resolveWorldgenServerLevel(region);
        if (level == null) {
            level = resolveServerLevel(region);
        }
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
        return protectedSet != null && protectedSet.containsKey(pos.asLong());
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
        if (!protectedTree) {
            protectedTree = isRegistryNamedTreeBlock(state);
        }
        TREE_PROTECTED_BLOCK_CACHE.put(block, protectedTree);
        return protectedTree;
    }

    private static boolean isRegistryNamedTreeBlock(BlockState state) {
        if (state == null) {
            return false;
        }
        ResourceLocation key = ForgeRegistries.BLOCKS.getKey(state.getBlock());
        if (key == null || "minecraft".equals(key.getNamespace())) {
            return false;
        }
        String path = key.getPath().toLowerCase(java.util.Locale.ROOT);
        return path.contains("log")
            || path.contains("trunk")
            || path.contains("stem")
            || path.contains("branch")
            || path.contains("bark")
            || path.endsWith("_wood")
            || path.equals("wood")
            || path.contains("leaves")
            || path.contains("leaf")
            || path.contains("foliage")
            || path.contains("needles")
            || path.contains("frond");
    }

    private static boolean isProtectedTreeBlock(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null) {
            return false;
        }
        ChunkScanKey key = chunkKey(level.dimension().location(), pos.getX() >> 4, pos.getZ() >> 4);
        java.util.concurrent.ConcurrentHashMap<Long, Boolean> protectedSet = PROTECTED_TREE_BLOCKS.get(key);
        return protectedSet != null && protectedSet.containsKey(pos.asLong());
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
        if (dimInfo == null) {
            return false;
        }

        mcjty.lostcities.varia.ChunkCoord coord =
            new mcjty.lostcities.varia.ChunkCoord(level.dimension(), cx, cz);
        try {
            mcjty.lostcities.api.LostChunkCharacteristics snapshot =
                ChunkRoleProbe.peekCharacteristics(coord);
            if (snapshot != null) {
                boolean isCity = snapshot.isCity;
                CITY_CHUNK_CACHE.put(key, isCity);
                return isCity;
            }
            ChunkRoleProbe.Probe stable = ChunkRoleProbe.peekStableTerrainProbe(
                dimInfo, level.dimension(), cx, cz);
            if (stable == null) {
                ChunkRoleProbe.requestStableTerrainProbe(
                    dimInfo, level.dimension(), cx, cz);
                return false;
            }
            boolean isCity = stable.isCity();
            CITY_CHUNK_CACHE.put(key, isCity);
            return isCity;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
