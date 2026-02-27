package org.admany.lc2h.worldgen.seams;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

public record SeamWriteIntent(
    BlockPos pos,
    BlockState state,
    int flags,
    long createdAtMs,
    int sourceChunkX,
    int sourceChunkZ
) {
}

