package org.admany.lc2h.mixin.lostcities.spawn;

import mcjty.lostcities.setup.ForgeEventHandlers;
import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.IDimensionInfo;
import mcjty.lostcities.worldgen.lost.BuildingInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.util.function.Predicate;

@Mixin(value = ForgeEventHandlers.class, remap = false)
public abstract class MixinForgeEventHandlersSpawnPartsFix {

    @Shadow
    private boolean isValidStandingPosition(Level world, BlockPos pos) {
        throw new IllegalStateException("Shadowed");
    }

    /** Evaluates spawn suitability at the real Y level instead of a fixed 128. */
    @Overwrite
    private BlockPos findSafeSpawnPointAtColumn(Level world, IDimensionInfo provider, Predicate<BlockPos> isSuitable, int x, int z) {
        ChunkCoord coord = new ChunkCoord(provider.getType(), x >> 4, z >> 4);
        int y = BuildingInfo.getProfile(coord, provider).GROUNDLEVEL - 5;
        while (y < 125) {
            BlockPos standing = new BlockPos(x, y, z);
            if (isValidStandingPosition(world, standing)) {
                BlockPos spawn = standing.above();
                if (isSuitable.test(spawn)) {
                    return spawn;
                }
            }
            y++;
        }
        return null;
    }
}
