package org.admany.lc2h.mixin.accessor.minecraft;

import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.NoiseChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ChunkAccess.class)
public interface ChunkAccessNoiseChunkAccessor {
    @Accessor("noiseChunk")
    NoiseChunk lc2h$getNoiseChunk();
}
