package org.admany.lc2h.bootstrap;

import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraft.server.level.ServerLevel;
import org.admany.lc2h.world.cleanup.VineClusterCleaner;
import org.admany.lc2h.LC2H;
import org.admany.lc2h.runtime.Lc2hRuntimeModes;

public class WorldGenHandler {
    private static final boolean TRACE_CHUNK_LOADS = Boolean.getBoolean("lc2h.trace.chunk_loads");

    @SubscribeEvent
    public void onChunkLoad(ChunkEvent.Load event) {
        if (Lc2hRuntimeModes.isolatedWorldParityBaseline()) {
            return;
        }
        if (TRACE_CHUNK_LOADS) {
            LC2H.LOGGER.debug("Chunk loaded at {} - checking for async tasks", event.getChunk().getPos());
        }

        if (VineClusterCleaner.isAutomaticCleanupEnabled()
                && event.getLevel() instanceof ServerLevel serverLevel) {
            VineClusterCleaner.cleanVinesOnFirstLoad(serverLevel, event.getChunk().getPos());
        }
    }
}
