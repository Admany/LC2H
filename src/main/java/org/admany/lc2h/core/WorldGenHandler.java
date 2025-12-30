package org.admany.lc2h.core;

import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraft.server.level.ServerLevel;
import org.admany.lc2h.world.VineClusterCleaner;
import org.admany.lc2h.LC2H;

public class WorldGenHandler {

    @SubscribeEvent
    public void onChunkLoad(ChunkEvent.Load event) {
        LC2H.LOGGER.debug("Chunk loaded at " + event.getChunk().getPos() + " - checking for async tasks");


        if (event.getLevel() instanceof ServerLevel serverLevel) {
            VineClusterCleaner.cleanVinesOnFirstLoad(serverLevel, event.getChunk().getPos());
        }
    }
}