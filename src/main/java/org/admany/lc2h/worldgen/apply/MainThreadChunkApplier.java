package org.admany.lc2h.worldgen.apply;

import mcjty.lostcities.varia.ChunkCoord;
import net.minecraft.server.MinecraftServer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.server.ServerLifecycleHooks;
import net.minecraftforge.fml.ModList;
import org.admany.lc2h.LC2H;
import org.admany.lc2h.client.frustum.ChunkPriorityManager;
import org.admany.lc2h.util.server.ServerTickLoad;
import org.admany.lc2h.util.server.ServerRescheduler;
import org.admany.lc2h.dev.diagnostics.ViewCullingStats;

import java.util.Comparator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;


@Mod.EventBusSubscriber(modid = LC2H.MODID)
public class MainThreadChunkApplier {

    private record PriorityAnchors(
        double spawnX,
        double spawnZ,
        double[] playerXs,
        double[] playerZs
    ) {
    }

    private static volatile PriorityAnchors PRIORITY_ANCHORS = new PriorityAnchors(0.0, 0.0, new double[0], new double[0]);

    private record ChunkQueueEntry(
        ChunkCoord chunk,
        double priorityDistanceSq
    ) {}

    private static final PriorityBlockingQueue<ChunkQueueEntry> APPLICATION_QUEUE =
        new PriorityBlockingQueue<>(256, Comparator.comparingDouble(ChunkQueueEntry::priorityDistanceSq));

    private static final ConcurrentHashMap<ChunkCoord, Boolean> APPLIED_CHUNKS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<ChunkCoord, Runnable> PENDING_TASKS = new ConcurrentHashMap<>();

    private static final AtomicBoolean DRAIN_SCHEDULED = new AtomicBoolean(false);

    private static final AtomicLong LAST_DRAIN_TICK = new AtomicLong(-1L);
    private static final AtomicInteger DRAIN_PASSES_THIS_TICK = new AtomicInteger(0);

    private static final long DEFAULT_TICK_BUDGET_NS = TimeUnit.MILLISECONDS.toNanos(1);
    private static final long STARTUP_TICK_BUDGET_NS = TimeUnit.MILLISECONDS.toNanos(6);
    private static final int DEFAULT_MAX_TASKS_PER_TICK = 4;
    private static final int STARTUP_MAX_TASKS_PER_TICK = 64;
    private static final int MAX_CREATE_CHUNK_REFRESH_PER_TICK = 4;
    private static final int MAX_CREATE_BLOCK_REFRESH_PER_TICK = 4;
    private static final int MAX_TICKER_REREGISTER_PER_TICK = 16;
    private static final long AUX_DRAIN_BUDGET_NS_PLAYERS = TimeUnit.MILLISECONDS.toNanos(1);
    private static final long AUX_DRAIN_BUDGET_NS_STARTUP = TimeUnit.MILLISECONDS.toNanos(3);
    private static final boolean CREATE_PRESENT = ModList.get().isLoaded("create");
    private static final ResourceLocation CREATE_WHEEL_BE_ID = ResourceLocation.fromNamespaceAndPath("create", "crushing_wheel");
    private static final ResourceLocation CREATE_WHEEL_BLOCK_ID = ResourceLocation.fromNamespaceAndPath("create", "crushing_wheel");
    private static final ResourceLocation CREATE_WHEEL_CONTROLLER_BLOCK_ID =
        ResourceLocation.fromNamespaceAndPath("create", "crushing_wheel_controller");
    private static volatile net.minecraft.world.level.block.entity.BlockEntityType<?> CREATE_WHEEL_TYPE_CACHE;
    private static volatile Block CREATE_WHEEL_BLOCK_CACHE;
    private static volatile Block CREATE_WHEEL_CONTROLLER_BLOCK_CACHE;
    private record CreateRefreshTask(ChunkCoord chunk, BlockPos pos, BlockState state, long readyTick) {}
    private record CreateChunkRefreshTask(ChunkCoord chunk, long readyTick) {}
    private static final java.util.concurrent.PriorityBlockingQueue<CreateChunkRefreshTask> CREATE_CHUNK_REFRESH_TASKS =
        new java.util.concurrent.PriorityBlockingQueue<>(64, Comparator.comparingLong(CreateChunkRefreshTask::readyTick));
    private static final java.util.concurrent.ConcurrentHashMap<ChunkCoord, Boolean> CREATE_REFRESH_PENDING =
        new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.PriorityBlockingQueue<CreateRefreshTask> CREATE_REFRESH_TASKS =
        new java.util.concurrent.PriorityBlockingQueue<>(64, Comparator.comparingLong(CreateRefreshTask::readyTick));

    private record TickerReRegisterTask(ChunkCoord chunk, long readyTick) {}
    private static final java.util.concurrent.PriorityBlockingQueue<TickerReRegisterTask> TICKER_REREGISTER_TASKS =
        new java.util.concurrent.PriorityBlockingQueue<>(128, Comparator.comparingLong(TickerReRegisterTask::readyTick));
    private static final java.util.concurrent.ConcurrentHashMap<ChunkCoord, Boolean> TICKER_REREGISTER_PENDING =
        new java.util.concurrent.ConcurrentHashMap<>();


    public static void enqueueChunkApplication(ChunkCoord chunk, Runnable applicationTask) {
        if (chunk == null || applicationTask == null) {
            return;
        }
        if (APPLIED_CHUNKS.containsKey(chunk)) {
            return;
        }

        if (!ServerRescheduler.isServerAvailable() && shouldRunInlineClient()) {
            try {
                applicationTask.run();
                APPLIED_CHUNKS.put(chunk, Boolean.TRUE);
            } catch (Throwable t) {
                LC2H.LOGGER.error("[LC2H] Failed to apply chunk {} on client thread: {}", chunk, t.getMessage());
                LC2H.LOGGER.debug("[LC2H] Apply error", t);
            } finally {
                PENDING_TASKS.remove(chunk);
            }
            return;
        }

        // Coalesce by chunk: keep only the latest task per chunk and queue each chunk once.
        if (PENDING_TASKS.putIfAbsent(chunk, applicationTask) != null) {
            PENDING_TASKS.put(chunk, applicationTask);
            return;
        }

        double priorityDistanceSq = calculatePriorityDistanceSq(chunk);
        APPLICATION_QUEUE.offer(new ChunkQueueEntry(chunk, priorityDistanceSq));

        // Fast path - we apply prepared work ASAP on the server thread instead of waiting for tick END.
        // This still uses the same drain() budget logic, and remains main-thread only for better performance.
        scheduleDrain();
    }

    private static void scheduleDrain() {
        MinecraftServer server = ServerRescheduler.getServer();
        if (server == null) {
            return;
        }
        if (!DRAIN_SCHEDULED.compareAndSet(false, true)) {
            return;
        }

        try {
            ServerRescheduler.runOnServer(() -> {
                DRAIN_SCHEDULED.set(false);
                drain(server);

                if (!APPLICATION_QUEUE.isEmpty()) {
                    int passes = DRAIN_PASSES_THIS_TICK.get();
                    double avg = ServerTickLoad.getAverageTickMs(server, 50.0D);
                    double elapsed = ServerTickLoad.getElapsedMsInCurrentTick();
                    if (passes < 2 && avg < 30.0D && elapsed < 12.0D) {
                        scheduleDrain();
                    }
                }
            });
        } catch (Throwable ignored) {
            DRAIN_SCHEDULED.set(false);
        }
    }

    private static double calculatePriorityDistanceSq(ChunkCoord chunk) {
        double cx = chunk.chunkX() * 16.0 + 8.0;
        double cz = chunk.chunkZ() * 16.0 + 8.0;

        PriorityAnchors anchors = PRIORITY_ANCHORS;

        double minDistSq = Double.MAX_VALUE;
        double[] xs = anchors.playerXs;
        double[] zs = anchors.playerZs;
        int count = Math.min(xs.length, zs.length);
        for (int i = 0; i < count; i++) {
            double dx = cx - xs[i];
            double dz = cz - zs[i];
            double distSq = dx * dx + dz * dz;
            if (distSq < minDistSq) {
                minDistSq = distSq;
            }
        }

        if (minDistSq != Double.MAX_VALUE) {
            return minDistSq;
        }

        double dx = cx - anchors.spawnX;
        double dz = cz - anchors.spawnZ;
        return dx * dx + dz * dz;
    }

    private static void updatePriorityAnchors(MinecraftServer server) {
        if (server == null) {
            return;
        }

        double spawnX = 0.0;
        double spawnZ = 0.0;
        try {
            BlockPos spawn = server.overworld().getSharedSpawnPos();
            spawnX = spawn.getX();
            spawnZ = spawn.getZ();
        } catch (Throwable ignored) {
        }

        double[] xs = new double[0];
        double[] zs = new double[0];
        try {
            var playerList = server.getPlayerList();
            if (playerList != null) {
                int total = playerList.getPlayerCount();
                int cap = Math.min(total, 8);
                if (cap > 0) {
                    xs = new double[cap];
                    zs = new double[cap];
                    int i = 0;
                    for (Player player : playerList.getPlayers()) {
                        if (i >= cap) {
                            break;
                        }
                        xs[i] = player.getX();
                        zs[i] = player.getZ();
                        i++;
                    }
                    if (i != cap) {
                        double[] trimmedX = new double[i];
                        double[] trimmedZ = new double[i];
                        System.arraycopy(xs, 0, trimmedX, 0, i);
                        System.arraycopy(zs, 0, trimmedZ, 0, i);
                        xs = trimmedX;
                        zs = trimmedZ;
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        PRIORITY_ANCHORS = new PriorityAnchors(spawnX, spawnZ, xs, zs);
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        MinecraftServer server = event.getServer();
        if (event.phase == TickEvent.Phase.START) {
            updatePriorityAnchors(server);
            return;
        }
        if (event.phase == TickEvent.Phase.END) {
            drain(server);
            drainTickerReRegister(server);
            drainCreateRefresh(server);
        }
    }

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getChunk() instanceof LevelChunk levelChunk)) {
            return;
        }
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        MinecraftServer server = level.getServer();
        if (server == null) {
            return;
        }
        ChunkCoord coord = new ChunkCoord(level.dimension(), levelChunk.getPos().x, levelChunk.getPos().z);
        // Defer ticker re-registration by 1 tick so modded BEs (notably Create) are fully constructed.
        enqueueTickerReRegister(server, coord);
    }

    @SubscribeEvent
    public static void onChunkUnload(ChunkEvent.Unload event) {
        if (!(event.getChunk() instanceof LevelChunk chunk)) {
            return;
        }
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        ChunkCoord coord = new ChunkCoord(level.dimension(), chunk.getPos().x, chunk.getPos().z);
        APPLIED_CHUNKS.remove(coord);
        PENDING_TASKS.remove(coord);
    }

    private static void drain(MinecraftServer server) {
        if (APPLICATION_QUEUE.isEmpty()) {
            return;
        }

        long tick = 0L;
        try {
            tick = server.getTickCount();
        } catch (Throwable ignored) {
        }
        long prev = LAST_DRAIN_TICK.getAndSet(tick);
        if (prev != tick) {
            DRAIN_PASSES_THIS_TICK.set(0);
        }
        DRAIN_PASSES_THIS_TICK.incrementAndGet();

        int playerCount = 0;
        try {
            if (server.getPlayerList() != null) {
                playerCount = server.getPlayerList().getPlayerCount();
            }
        } catch (Throwable ignored) {
        }
        final boolean cullOutOfView = playerCount > 0;

        // getAverageTickTime() returns ms-per-tick - higher values mean more overloaded.
        double avgTickMs = 50.0;
        try {
            avgTickMs = server.getAverageTickTime();
        } catch (Throwable ignored) {
        }

        final double elapsedMs = ServerTickLoad.getElapsedMsInCurrentTick();

        final boolean startup = playerCount == 0;
        final long budgetNs;
        final int maxTasks;
        if (startup) {
            budgetNs = STARTUP_TICK_BUDGET_NS;
            maxTasks = STARTUP_MAX_TASKS_PER_TICK;
        } else if (avgTickMs >= 70.0 || elapsedMs >= 45.0D) {
            budgetNs = TimeUnit.MILLISECONDS.toNanos(0); // This is effectively 1 task
            maxTasks = 1;
        } else if (avgTickMs >= 45.0 || elapsedMs >= 30.0D) {
            budgetNs = TimeUnit.MILLISECONDS.toNanos(0); // This is effectively 1 task
            maxTasks = 1;
        } else if (avgTickMs > 30.0) {
            budgetNs = DEFAULT_TICK_BUDGET_NS;
            maxTasks = 2;
        } else {
            budgetNs = DEFAULT_TICK_BUDGET_NS;
            maxTasks = DEFAULT_MAX_TASKS_PER_TICK;
        }

        long start = System.nanoTime();
        int applied = 0;
        while (applied < maxTasks) {
            ChunkQueueEntry entry = APPLICATION_QUEUE.poll();
            if (entry == null) {
                break;
            }
            ChunkCoord chunk = entry.chunk;
            Runnable task = chunk == null ? null : PENDING_TASKS.remove(chunk);
            if (task == null) {
                continue;
            }
            if (cullOutOfView && chunk != null && chunk.dimension() != null) {
                boolean inView = ChunkPriorityManager.isChunkWithinViewDistance(
                    chunk.dimension().location(), chunk.chunkX(), chunk.chunkZ());
                if (!inView) {
                    boolean hasCreate = chunkHasCreate(server, chunk);
                    if (!hasCreate) {
                        ViewCullingStats.recordMainThreadApply(1);
                        continue;
                    }
                }
            }

            try {
                task.run();
                enqueueTickerReRegister(server, chunk);
                APPLIED_CHUNKS.put(chunk, Boolean.TRUE);
            } catch (Throwable t) {
                LC2H.LOGGER.error("[LC2H] Failed to apply chunk {} on main thread: {}", chunk, t.getMessage());
                LC2H.LOGGER.debug("[LC2H] Apply error", t);
            }

            applied++;
            if (budgetNs > 0 && (System.nanoTime() - start) >= budgetNs) {
                break;
            }
        }

    }

    private static void enqueueCreateRefresh(ChunkCoord chunk) {
        if (chunk == null) {
            return;
        }
        if (!CREATE_PRESENT) {
            return;
        }
        MinecraftServer server = ServerRescheduler.getServer();
        long nowTick = 0L;
        try {
            if (server != null) {
                nowTick = server.getTickCount();
            }
        } catch (Throwable ignored) {
        }
        if (CREATE_REFRESH_PENDING.putIfAbsent(chunk, Boolean.TRUE) != null) {
            return;
        }
        CREATE_CHUNK_REFRESH_TASKS.offer(new CreateChunkRefreshTask(chunk, nowTick + 1L));
    }

    public static void requestCreateRefresh(ServerLevel level, net.minecraft.world.level.ChunkPos pos) {
        if (level == null || pos == null) {
            return;
        }
        enqueueCreateRefresh(new ChunkCoord(level.dimension(), pos.x, pos.z));
    }

    private static void drainCreateRefresh(MinecraftServer server) {
        if (server == null) {
            return;
        }
        long budgetNs = computeAuxDrainBudgetNs(server);
        long startNs = System.nanoTime();
        int maxChunkRefresh = MAX_CREATE_CHUNK_REFRESH_PER_TICK;
        int maxBlockRefresh = MAX_CREATE_BLOCK_REFRESH_PER_TICK;
        int processed = 0;
        long nowTick = 0L;
        try {
            nowTick = server.getTickCount();
        } catch (Throwable ignored) {
        }

        while (processed < maxChunkRefresh) {
            if (budgetNs > 0 && (System.nanoTime() - startNs) >= budgetNs) {
                break;
            }
            CreateChunkRefreshTask task = CREATE_CHUNK_REFRESH_TASKS.peek();
            if (task == null || task.readyTick() > nowTick) {
                break;
            }
            CREATE_CHUNK_REFRESH_TASKS.poll();
            ChunkCoord coord = task.chunk();
            if (coord == null) {
                continue;
            }

            boolean ready = false;
            try {
                ServerLevel level = server.getLevel(coord.dimension());
                if (level != null) {
                    LevelChunk levelChunk = level.getChunkSource().getChunkNow(coord.chunkX(), coord.chunkZ());
                    if (levelChunk != null && isChunkTicking(level, levelChunk)) {
                        ready = true;
                    }
                }
            } catch (Throwable ignored) {
            }

            if (!ready) {
                CREATE_CHUNK_REFRESH_TASKS.offer(new CreateChunkRefreshTask(coord, nowTick + 10L));
                processed++;
                continue;
            }

            CREATE_REFRESH_PENDING.remove(coord);
            refreshCreateCrushingControllers(server, coord);
            processed++;
        }

        int applied = 0;
        while (applied < maxBlockRefresh) {
            if (budgetNs > 0 && (System.nanoTime() - startNs) >= budgetNs) {
                break;
            }
            CreateRefreshTask task = CREATE_REFRESH_TASKS.peek();
            if (task == null || task.readyTick() > nowTick) {
                break;
            }
            CREATE_REFRESH_TASKS.poll();
            ServerLevel level = server.getLevel(task.chunk().dimension());
            if (level != null && level.isLoaded(task.pos())) {
                try {
                    BlockPos pos = task.pos();
                    BlockState state = task.state();
                    level.sendBlockUpdated(pos, state, state, Block.UPDATE_ALL);
                    level.updateNeighborsAt(pos, state.getBlock());
                    applied++;
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static void reRegisterBlockEntityTickers(MinecraftServer server, ChunkCoord chunk) {
        if (server == null || chunk == null || chunk.dimension() == null) {
            return;
        }
        ServerLevel level;
        try {
            level = server.getLevel(chunk.dimension());
        } catch (Throwable t) {
            return;
        }
        if (level == null) {
            return;
        }
        LevelChunk levelChunk = level.getChunkSource().getChunkNow(chunk.chunkX(), chunk.chunkZ());
        if (levelChunk == null) {
            return;
        }
        reRegisterBlockEntityTickers(level, levelChunk, chunk);
    }

    private static void reRegisterBlockEntityTickers(ServerLevel level, LevelChunk levelChunk, ChunkCoord chunk) {
        if (level == null || levelChunk == null || chunk == null) {
            return;
        }
        boolean hasCreate = chunkHasCreate(levelChunk);
        if (hasCreate) {
            enqueueCreateRefresh(chunk);
            return;
        }
        try {
            levelChunk.registerAllBlockEntitiesAfterLevelLoad();
        } catch (Throwable t) {
        }
    }

    private static void enqueueTickerReRegister(MinecraftServer server, ChunkCoord chunk) {
        if (server == null || chunk == null) {
            return;
        }
        if (TICKER_REREGISTER_PENDING.putIfAbsent(chunk, Boolean.TRUE) != null) {
            return;
        }
        long nowTick = 0L;
        try {
            nowTick = server.getTickCount();
        } catch (Throwable ignored) {
        }
        TICKER_REREGISTER_TASKS.offer(new TickerReRegisterTask(chunk, nowTick + 1L));
    }

    private static void drainTickerReRegister(MinecraftServer server) {
        if (server == null) {
            return;
        }
        long budgetNs = computeAuxDrainBudgetNs(server);
        long startNs = System.nanoTime();
        long nowTick = 0L;
        try {
            nowTick = server.getTickCount();
        } catch (Throwable ignored) {
        }

        int max = MAX_TICKER_REREGISTER_PER_TICK;
        int processed = 0;
        while (processed < max) {
            if (budgetNs > 0 && (System.nanoTime() - startNs) >= budgetNs) {
                break;
            }
            TickerReRegisterTask task = TICKER_REREGISTER_TASKS.peek();
            if (task == null || task.readyTick() > nowTick) {
                break;
            }
            TICKER_REREGISTER_TASKS.poll();
            ChunkCoord chunk = task.chunk();
            if (chunk != null) {
                try {
                    reRegisterBlockEntityTickers(server, chunk);
                } catch (Throwable ignored) {
                }
                TICKER_REREGISTER_PENDING.remove(chunk);
            }
            processed++;
        }
    }

    private static long computeAuxDrainBudgetNs(MinecraftServer server) {
        if (server == null) {
            return AUX_DRAIN_BUDGET_NS_PLAYERS;
        }
        int players = 0;
        try {
            if (server.getPlayerList() != null) {
                players = server.getPlayerList().getPlayerCount();
            }
        } catch (Throwable ignored) {
        }
        if (players <= 0) {
            return AUX_DRAIN_BUDGET_NS_STARTUP;
        }
        double avgTickMs = 50.0D;
        try {
            avgTickMs = server.getAverageTickTime();
        } catch (Throwable ignored) {
        }
        if (avgTickMs >= 45.0D) {
            return TimeUnit.MILLISECONDS.toNanos(0);
        }
        if (avgTickMs >= 35.0D) {
            return TimeUnit.MILLISECONDS.toNanos(1);
        }
        return AUX_DRAIN_BUDGET_NS_PLAYERS;
    }

    private static void refreshCreateCrushingControllers(MinecraftServer server, ChunkCoord chunk) {
        if (server == null || chunk == null || chunk.dimension() == null) {
            return;
        }
        if (!CREATE_PRESENT) {
            return;
        }
        ServerLevel level;
        try {
            level = server.getLevel(chunk.dimension());
        } catch (Throwable t) {
            return;
        }
        if (level == null) {
            return;
        }
        LevelChunk levelChunk = level.getChunkSource().getChunkNow(chunk.chunkX(), chunk.chunkZ());
        if (levelChunk == null) {
            return;
        }
        refreshCreateCrushingControllers(level, levelChunk, chunk);
    }

    private static void refreshCreateCrushingControllers(ServerLevel level, LevelChunk levelChunk, ChunkCoord chunk) {
        if (level == null || levelChunk == null || chunk == null) {
            return;
        }
        if (!CREATE_PRESENT) {
            return;
        }
        java.util.HashSet<Long> scheduledPositions = new java.util.HashSet<>(8);
        java.util.ArrayList<CreateRefreshTask> updates = new java.util.ArrayList<>(4);
        try {
            net.minecraft.world.level.block.entity.BlockEntityType<?> wheelType = getCreateWheelType();
            Block wheelBlock = getCreateWheelBlock();
            Block controllerBlock = getCreateWheelControllerBlock();
            long nowTick = level.getServer().getTickCount();
            for (BlockEntity be : levelChunk.getBlockEntities().values()) {
                if (be == null) continue;
                if (wheelType != null) {
                    if (be.getType() != wheelType) continue;
                } else {
                    ResourceLocation key = ForgeRegistries.BLOCK_ENTITY_TYPES.getKey(be.getType());
                    if (!CREATE_WHEEL_BE_ID.equals(key)) {
                        continue;
                    }
                }
                var state = be.getBlockState();
                if (!isCreateWheelBlock(state, wheelBlock)) {
                    continue;
                }
                BlockPos wheelPos = be.getBlockPos();
                if (wheelPos != null) {
                    if (scheduledPositions.add(wheelPos.asLong())) {
                        updates.add(new CreateRefreshTask(chunk, wheelPos, state, nowTick + 1L));
                    }
                    
                    for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.Plane.HORIZONTAL) {
                        BlockPos otherWheel = wheelPos.relative(dir, 2);
                        int ocx = otherWheel.getX() >> 4;
                        int ocz = otherWheel.getZ() >> 4;
                        if (level.getChunkSource().getChunkNow(ocx, ocz) == null) {
                            continue;
                        }
                        BlockState otherState = level.getBlockState(otherWheel);
                        if (!isCreateWheelBlock(otherState, wheelBlock)) {
                            continue;
                        }

                        BlockPos middle = wheelPos.relative(dir, 1);
                        int mcx = middle.getX() >> 4;
                        int mcz = middle.getZ() >> 4;
                        if (level.getChunkSource().getChunkNow(mcx, mcz) == null) {
                            continue;
                        }
                        BlockState middleState = level.getBlockState(middle);
                        if (!isAirOrCreateWheelController(middleState, controllerBlock)) {
                            continue;
                        }

                        if (scheduledPositions.add(middle.asLong())) {
                            updates.add(new CreateRefreshTask(chunk, middle, middleState, nowTick + 1L));
                        }
                        if (scheduledPositions.add(otherWheel.asLong())) {
                            updates.add(new CreateRefreshTask(chunk, otherWheel, otherState, nowTick + 1L));
                        }

                        break;
                    }
                }

                if (updates.size() >= 4) {
                    break;
                }
            }
        } catch (Throwable t) {
        }
        if (!updates.isEmpty()) {
            updates.sort(Comparator.comparingLong(t -> t.pos().asLong()));
            for (CreateRefreshTask t : updates) {
                CREATE_REFRESH_TASKS.offer(t);
            }
        }
    }

    public static int getQueueSize() {
        return APPLICATION_QUEUE.size();
    }

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

    private static boolean chunkHasCreate(MinecraftServer server, ChunkCoord chunk) {
        if (server == null || chunk == null || chunk.dimension() == null || !CREATE_PRESENT) {
            return false;
        }
        ServerLevel level = server.getLevel(chunk.dimension());
        if (level == null) {
            return false;
        }
        LevelChunk levelChunk = level.getChunkSource().getChunkNow(chunk.chunkX(), chunk.chunkZ());
        if (levelChunk == null) {
            return false;
        }
        return chunkHasCreate(levelChunk);
    }

    private static boolean chunkHasCreate(LevelChunk levelChunk) {
        if (!CREATE_PRESENT || levelChunk == null) {
            return false;
        }
        net.minecraft.world.level.block.entity.BlockEntityType<?> wheelType = getCreateWheelType();
        for (BlockEntity be : levelChunk.getBlockEntities().values()) {
            if (be == null) continue;
            if (wheelType != null) {
                if (be.getType() == wheelType) {
                    return true;
                }
            } else {
                ResourceLocation key = ForgeRegistries.BLOCK_ENTITY_TYPES.getKey(be.getType());
                if (CREATE_WHEEL_BE_ID.equals(key)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static net.minecraft.world.level.block.entity.BlockEntityType<?> getCreateWheelType() {
        net.minecraft.world.level.block.entity.BlockEntityType<?> cached = CREATE_WHEEL_TYPE_CACHE;
        if (cached != null) {
            return cached;
        }
        net.minecraft.world.level.block.entity.BlockEntityType<?> resolved =
            ForgeRegistries.BLOCK_ENTITY_TYPES.getValue(CREATE_WHEEL_BE_ID);
        if (resolved != null) {
            CREATE_WHEEL_TYPE_CACHE = resolved;
        }
        return resolved;
    }

    private static Block getCreateWheelBlock() {
        Block cached = CREATE_WHEEL_BLOCK_CACHE;
        if (cached != null) {
            return cached;
        }
        Block resolved = ForgeRegistries.BLOCKS.getValue(CREATE_WHEEL_BLOCK_ID);
        if (resolved != null && resolved != Blocks.AIR) {
            CREATE_WHEEL_BLOCK_CACHE = resolved;
        }
        return resolved;
    }

    private static Block getCreateWheelControllerBlock() {
        Block cached = CREATE_WHEEL_CONTROLLER_BLOCK_CACHE;
        if (cached != null) {
            return cached;
        }
        Block resolved = ForgeRegistries.BLOCKS.getValue(CREATE_WHEEL_CONTROLLER_BLOCK_ID);
        if (resolved != null && resolved != Blocks.AIR) {
            CREATE_WHEEL_CONTROLLER_BLOCK_CACHE = resolved;
        }
        return resolved;
    }

    private static boolean isCreateWheelBlock(BlockState state, Block cachedWheelBlock) {
        if (state == null) {
            return false;
        }
        Block block = state.getBlock();
        if (cachedWheelBlock != null) {
            return block == cachedWheelBlock;
        }
        ResourceLocation key = ForgeRegistries.BLOCKS.getKey(block);
        return CREATE_WHEEL_BLOCK_ID.equals(key);
    }

    private static boolean isAirOrCreateWheelController(BlockState state, Block cachedControllerBlock) {
        if (state == null) {
            return false;
        }
        if (state.isAir()) {
            return true;
        }
        if (cachedControllerBlock != null) {
            return state.getBlock() == cachedControllerBlock;
        }
        ResourceLocation key = ForgeRegistries.BLOCKS.getKey(state.getBlock());
        return CREATE_WHEEL_CONTROLLER_BLOCK_ID.equals(key);
    }

    private static boolean isChunkTicking(ServerLevel level, LevelChunk levelChunk) {
        if (level == null || levelChunk == null) {
            return false;
        }
        net.minecraft.world.level.ChunkPos pos = levelChunk.getPos();
        BlockPos probe = new BlockPos((pos.x << 4) + 8, 64, (pos.z << 4) + 8);
        return level.shouldTickBlocksAt(probe);
    }
}
