package org.admany.lc2h.mixin.accessor.lostcities;

import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.varia.TimedCache;
import mcjty.lostcities.worldgen.lost.MultiChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = MultiChunk.class, remap = false)
public interface MultiChunkAccessor {

    @Accessor("areasize")
    int lc2h$getAreaSize();

    @Accessor("topleft")
    ChunkCoord lc2h$getTopLeft();

    @Accessor("MULTICHUNKS")
    TimedCache<ChunkCoord, MultiChunk> lc2h$getCache();

}
