package org.admany.lc2h.mixin.lostcities.worldgen;

import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.ChunkFixer;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Lost Cities 7.5.5 adds a synchronous boundary sweep for vanilla vines.
 * LC2H already performs the configured attachment cleanup through its bounded
 * resident queue, so leave those new scans to LC2H instead of running both.
 * The optional redirects are ignored by Mixin on 7.5.4, where the calls do not
 * exist yet.
 */
@SuppressWarnings("target")
@Mixin(value = ChunkFixer.class, remap = false)
public final class MixinChunkFixerVineCleanup {

    @Redirect(
        method = "generateVines",
        at = @At(
            value = "INVOKE",
            target = "Lmcjty/lostcities/worldgen/ChunkFixer;removeUnsupportedVines(Lnet/minecraft/world/level/LevelAccessor;Lnet/minecraft/world/level/block/Block;IIIIII)V"
        ),
        require = 0,
        expect = 0,
        remap = false
    )
    private static void lc2h$skipSynchronousVineSweep(LevelAccessor world, Block vineBlock,
                                                       int minX, int minZ, int maxX, int maxZ,
                                                       int bottom, int top) {
        // LC2H's configured cleaner handles this on the bounded server queue.
    }

    @Redirect(
        method = "fix",
        at = @At(
            value = "INVOKE",
            target = "Lmcjty/lostcities/worldgen/ChunkFixer;removeUnsupportedBoundaryVines(Lmcjty/lostcities/varia/ChunkCoord;Lnet/minecraft/world/level/LevelAccessor;)V"
        ),
        require = 0,
        expect = 0,
        remap = false
    )
    private static void lc2h$skipSynchronousBoundaryVineSweep(ChunkCoord coord, LevelAccessor world) {
        // LC2H's async resident scan is the single boundary cleanup path.
    }
}
