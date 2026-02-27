package org.admany.lc2h.worldgen.seams;

import net.minecraft.resources.ResourceLocation;

public record SeamChunkKey(ResourceLocation dimension, int chunkX, int chunkZ) {
}

