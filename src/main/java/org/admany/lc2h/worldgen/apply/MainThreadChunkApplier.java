package org.admany.lc2h.worldgen.apply;

import mcjty.lostcities.varia.ChunkCoord;
import net.minecraft.server.MinecraftServer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.server.ServerLifecycleHooks;
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
        }
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
                if (!ChunkPriorityManager.isChunkWithinViewDistance(chunk.dimension().location(), chunk.chunkX(), chunk.chunkZ())) {
                    ViewCullingStats.recordMainThreadApply(1);
                    continue;
                }
            }

            try {
                task.run();
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

 
    public static boolean isChunkApplied(ChunkCoord chunk) {
        return APPLIED_CHUNKS.containsKey(chunk);
    }


    public static void removeAppliedChunk(ChunkCoord chunk) {
        APPLIED_CHUNKS.remove(chunk);
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
}
