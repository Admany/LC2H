package org.admany.lc2h.mixin.accessor.lostcities;

import mcjty.lostcities.worldgen.IDimensionInfo;
import mcjty.lostcities.worldgen.lost.MultiChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(value = MultiChunk.class, remap = false)
public interface MultiChunkInvoker {

    @Invoker("calculateBuildings")
    MultiChunk lc2h$calculateBuildings(IDimensionInfo provider);
}
