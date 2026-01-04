package org.admany.lc2h.mixin.accessor;

import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.lost.MultiChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

@Mixin(value = MultiChunk.class, remap = false)
public interface MultiChunkAccessor {

    @Accessor("MULTICHUNKS")
    static Map<ChunkCoord, MultiChunk> lc2h$getCache() {
        throw new IllegalStateException("Mixin not applied");
    }

    @Accessor("areasize")
    int lc2h$getAreaSize();

    @Accessor("topleft")
    ChunkCoord lc2h$getTopLeft();

}
