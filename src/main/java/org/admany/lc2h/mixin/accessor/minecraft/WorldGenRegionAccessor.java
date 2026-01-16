package org.admany.lc2h.mixin.accessor.minecraft;

import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(WorldGenRegion.class)
public interface WorldGenRegionAccessor {
    @Accessor("firstPos")
    ChunkPos lc2h$getFirstPos();

    @Accessor("lastPos")
    ChunkPos lc2h$getLastPos();
}
