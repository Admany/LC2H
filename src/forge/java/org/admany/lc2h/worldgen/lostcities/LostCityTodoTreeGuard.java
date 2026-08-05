package org.admany.lc2h.worldgen.lostcities;

import mcjty.lostcities.worldgen.GlobalTodo;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.SaplingBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.admany.lc2h.worldgen.scope.WorldGenScope;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class LostCityTodoTreeGuard {

    private static final int REQUIRED_BLOCK_RADIUS = Math.max(2,
        Integer.getInteger("lc2h.todoSapling.requiredBlockRadius", 8));
    private static final int MAX_REQUEUE_ATTEMPTS = Math.max(1,
        Integer.getInteger("lc2h.todoSapling.maxRequeues", 32));

    private static final AtomicLong OBSERVED = new AtomicLong();
    private static final AtomicLong ADVANCED = new AtomicLong();
    private static final AtomicLong REQUEUED = new AtomicLong();
    private static final AtomicLong DEFERRED_DURING_PARITY_PRIME = new AtomicLong();
    private static final AtomicLong DROPPED_STALE = new AtomicLong();
    private static final AtomicLong DROPPED_RETRY_LIMIT = new AtomicLong();
    private static final AtomicLong DROPPED_UNLOADED_ROOT = new AtomicLong();
    private static final ConcurrentHashMap<TodoKey, Integer> RETRIES = new ConcurrentHashMap<>();

    private LostCityTodoTreeGuard() {
    }

    private record TodoKey(WorldGenScope.DimensionKey scope, long rootPos) {
    }

    public static void advanceOrRequeue(SaplingBlock sapling,
                                        ServerLevel level,
                                        BlockPos pos,
                                        BlockState state,
                                        RandomSource random) {
        if (sapling == null || level == null || pos == null || state == null) {
            return;
        }
        OBSERVED.incrementAndGet();
        BlockPos immutablePos = pos.immutable();
        TodoKey key = new TodoKey(WorldGenScope.dimension(level), immutablePos.asLong());
        if (!isNeighborhoodLoaded(level, immutablePos)) {
            int attempts = RETRIES.merge(key, 1, Integer::sum);
            if (attempts > MAX_REQUEUE_ATTEMPTS) {
                RETRIES.remove(key);
                DROPPED_RETRY_LIMIT.incrementAndGet();
                return;
            }
            requeue(level, immutablePos, state, random);
            REQUEUED.incrementAndGet();
            return;
        }

        RETRIES.remove(key);
        ADVANCED.incrementAndGet();
        sapling.advanceTree(level, immutablePos, state, random);
    }

    public static void handleLostCityTodo(BlockPos pos,
                                          BlockState state,
                                          SaplingBlock sapling,
                                          RandomSource random,
                                          ServerLevel level) {
        if (sapling == null || level == null || pos == null || state == null) {
            return;
        }
        BlockPos immutablePos = pos.immutable();
        if (!level.isAreaLoaded(immutablePos, 1)) {
            requeue(level, immutablePos, state, random);
            REQUEUED.incrementAndGet();
            return;
        }
        net.minecraft.world.level.chunk.LevelChunk chunk = level.getChunkSource()
            .getChunkNow(immutablePos.getX() >> 4, immutablePos.getZ() >> 4);
        if (chunk == null) {
            requeue(level, immutablePos, state, random);
            REQUEUED.incrementAndGet();
            return;
        }
        BlockState current = chunk.getBlockState(immutablePos);
        if (!(current.getBlock() instanceof SaplingBlock currentSapling)) {
            RETRIES.remove(new TodoKey(WorldGenScope.dimension(level), immutablePos.asLong()));
            DROPPED_STALE.incrementAndGet();
            return;
        }
        advanceOrRequeue(currentSapling, level, immutablePos, state, random);
    }

    public static void clearDimension(ServerLevel level) {
        if (level == null) {
            return;
        }
        WorldGenScope.DimensionKey scope = WorldGenScope.dimension(level);
        RETRIES.keySet().removeIf(key -> scope.equals(key.scope()));
    }

    public static void clearAll() {
        RETRIES.clear();
    }

    public static String diagnostics() {
        long maxRetry = 0L;
        for (Map.Entry<TodoKey, Integer> entry : RETRIES.entrySet()) {
            if (entry.getValue() != null) {
                maxRetry = Math.max(maxRetry, entry.getValue());
            }
        }
        return "observed=" + OBSERVED.get()
            + ", advanced=" + ADVANCED.get()
            + ", requeued=" + REQUEUED.get()
            + ", deferredDuringParityPrime=" + DEFERRED_DURING_PARITY_PRIME.get()
            + ", droppedStale=" + DROPPED_STALE.get()
            + ", droppedRetryLimit=" + DROPPED_RETRY_LIMIT.get()
            + ", droppedUnloadedRoot=" + DROPPED_UNLOADED_ROOT.get()
            + ", deferredRoots=" + RETRIES.size()
            + ", maxRetry=" + maxRetry
            + ", requiredBlockRadius=" + REQUIRED_BLOCK_RADIUS;
    }

    private static boolean isNeighborhoodLoaded(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null) {
            return false;
        }
        if (!level.isAreaLoaded(pos, 1)) {
            return false;
        }
        int minChunkX = (pos.getX() - REQUIRED_BLOCK_RADIUS) >> 4;
        int maxChunkX = (pos.getX() + REQUIRED_BLOCK_RADIUS) >> 4;
        int minChunkZ = (pos.getZ() - REQUIRED_BLOCK_RADIUS) >> 4;
        int maxChunkZ = (pos.getZ() + REQUIRED_BLOCK_RADIUS) >> 4;
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                if (!level.hasChunk(chunkX, chunkZ)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static void requeue(ServerLevel level, BlockPos pos, BlockState state, RandomSource random) {
        if (level == null || pos == null || state == null) {
            return;
        }
        GlobalTodo.get(level).addTodo(pos, queuedLevel -> {
            if (queuedLevel == null) {
                return;
            }
            if (!queuedLevel.isAreaLoaded(pos, 1)) {
                DROPPED_UNLOADED_ROOT.incrementAndGet();
                requeue(queuedLevel, pos, state, random);
                return;
            }
            net.minecraft.world.level.chunk.LevelChunk chunk = queuedLevel.getChunkSource()
                .getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
            if (chunk == null) {
                DROPPED_UNLOADED_ROOT.incrementAndGet();
                requeue(queuedLevel, pos, state, random);
                return;
            }
            BlockState current = chunk.getBlockState(pos);
            if (!(current.getBlock() instanceof SaplingBlock sapling)) {
                RETRIES.remove(new TodoKey(WorldGenScope.dimension(queuedLevel), pos.asLong()));
                DROPPED_STALE.incrementAndGet();
                return;
            }
            queuedLevel.setBlock(pos, state, 2);
            advanceOrRequeue(sapling, queuedLevel, pos, state, random);
        });
    }
}
