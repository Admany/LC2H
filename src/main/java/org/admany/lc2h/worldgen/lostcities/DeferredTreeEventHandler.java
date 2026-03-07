package org.admany.lc2h.worldgen.lostcities;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.TreeConfiguration;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.admany.lc2h.LC2H;
import org.admany.lc2h.util.chunk.ChunkPostProcessor;

import java.util.List;
import java.util.Optional;

@Mod.EventBusSubscriber(modid = LC2H.MODID)
public final class DeferredTreeEventHandler {

    private static final int MAX_READY_ENQUEUES_PER_CHUNK_LOAD = 8;
    private static final int MAX_REPLAYS_PER_SERVER_TICK = 4;

    private DeferredTreeEventHandler() {
    }

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        if (event.getChunk() == null) {
            return;
        }

        var dim = level.dimension();
        DeferredTreeQueue.loadFromDisk(level);
        if (DeferredTreeQueue.pendingCount(dim) == 0) {
            return;
        }

        List<DeferredTreeQueue.PendingTree> ready = DeferredTreeQueue.drainReady(dim, level);
        if (ready.isEmpty()) {
            return;
        }

        if (ready.size() > MAX_READY_ENQUEUES_PER_CHUNK_LOAD) {
            DeferredTreeQueue.enqueueReady(dim, ready.subList(0, MAX_READY_ENQUEUES_PER_CHUNK_LOAD));
            for (int i = MAX_READY_ENQUEUES_PER_CHUNK_LOAD; i < ready.size(); i++) {
                DeferredTreeQueue.enqueue(dim, ready.get(i));
            }
            return;
        }

        DeferredTreeQueue.enqueueReady(dim, ready);
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.getServer() == null) {
            return;
        }

        int replayed = 0;
        for (ServerLevel level : event.getServer().getAllLevels()) {
            if (level == null) {
                continue;
            }
            ChunkGenerator generator = level.getChunkSource().getGenerator();
            if (generator == null) {
                continue;
            }

            List<DeferredTreeQueue.PendingTree> ready = DeferredTreeQueue.pollReady(
                level.dimension(),
                MAX_REPLAYS_PER_SERVER_TICK - replayed
            );
            if (ready.isEmpty()) {
                continue;
            }

            for (DeferredTreeQueue.PendingTree pending : ready) {
                replayTree(level, generator, pending);
                replayed++;
                if (replayed >= MAX_REPLAYS_PER_SERVER_TICK) {
                    return;
                }
            }
        }
    }

    private static void replayTree(ServerLevel level, ChunkGenerator generator, DeferredTreeQueue.PendingTree pending) {
        if (level == null || generator == null || pending == null) {
            return;
        }
        if (pending.pos() == null) {
            return;
        }
        if (!level.isLoaded(pending.pos())) {
            // Retry later if this position unloaded between readiness check and replay.
            DeferredTreeQueue.enqueue(pending.dim(), pending);
            return;
        }

        if (pending.hasCapturedBlocks()) {
            applyCapturedTree(level, pending);
            return;
        }

        if (pending.config() == null || pending.feature() == null) {
            return;
        }

        try {
            RandomSource random = RandomSource.create();
            random.setSeed(level.getSeed() ^ pending.pos().asLong());

            FeaturePlaceContext<TreeConfiguration> replayContext = new FeaturePlaceContext<>(
                Optional.empty(),
                level,
                generator,
                random,
                pending.pos(),
                pending.config()
            );

            DeferredTreeQueue.runReplay(() -> {
                try {
                    pending.feature().place(replayContext);
                } catch (Throwable t) {
                    LC2H.LOGGER.debug("[LC2H] Deferred tree replay failed at {}: {}", pending.pos(), t.toString());
                }
            });
        } catch (Throwable t) {
            LC2H.LOGGER.debug("[LC2H] Deferred tree replay setup failed at {}: {}", pending.pos(), t.toString());
        }
    }

    private static void applyCapturedTree(ServerLevel level, DeferredTreeQueue.PendingTree pending) {
        for (DeferredTreeQueue.CapturedBlock block : pending.blocks()) {
            if (block == null || block.pos() == null || block.state() == null) {
                continue;
            }
            if (!level.isLoaded(block.pos())) {
                DeferredTreeQueue.enqueue(pending.dim(), pending);
                return;
            }
        }

        for (DeferredTreeQueue.CapturedBlock block : pending.blocks()) {
            if (shouldApplyCapturedBlock(level, block.pos(), block.state())) {
                try {
                    level.setBlock(block.pos(), block.state(), 2);
                    ChunkPostProcessor.markTreePlacement(level, block.pos(), block.state());
                } catch (Throwable t) {
                    LC2H.LOGGER.debug("[LC2H] Deferred captured tree block apply failed at {}: {}", block.pos(), t.toString());
                }
            }
        }
    }

    private static boolean shouldApplyCapturedBlock(ServerLevel level, net.minecraft.core.BlockPos pos, BlockState planned) {
        if (level == null || pos == null || planned == null) {
            return false;
        }

        BlockState existing = level.getBlockState(pos);
        if (existing.equals(planned)) {
            return false;
        }
        if (existing.isAir() || existing.canBeReplaced()) {
            return true;
        }

        boolean plannedLog = planned.is(BlockTags.LOGS);
        boolean plannedLeaves = planned.is(BlockTags.LEAVES);
        boolean existingTreeish = existing.is(BlockTags.LOGS) || existing.is(BlockTags.LEAVES);

        if (existingTreeish) {
            return true;
        }

        if (plannedLeaves) {
            return false;
        }

        if (plannedLog) {
            return false;
        }

        return false;
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            DeferredTreeQueue.flushToDisk(level);
            DeferredTreeQueue.clearDimension(level.dimension());
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        if (event == null || event.getServer() == null) {
            return;
        }
        for (ServerLevel level : event.getServer().getAllLevels()) {
            if (level != null) {
                DeferredTreeQueue.flushToDisk(level);
            }
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        DeferredTreeQueue.clearAll();
    }
}
