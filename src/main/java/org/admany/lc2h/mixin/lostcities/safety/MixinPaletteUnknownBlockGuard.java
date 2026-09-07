package org.admany.lc2h.mixin.lostcities.safety;

import mcjty.lostcities.worldgen.lost.cityassets.Palette;
import mcjty.lostcities.varia.Tools;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.admany.lc2h.LC2H;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Prevents chunk generation from failing when a palette references an unregistered block.
 * Unknown blocks are silently substituted with air and logged once per block name.
 */
@Mixin(value = Palette.class, remap = false)
public class MixinPaletteUnknownBlockGuard {

    @Unique
    private static final Set<String> LC2H_WARNED_PALETTE_BLOCKS =
        Collections.newSetFromMap(new ConcurrentHashMap<>());

    @Redirect(
        method = "parsePaletteArray",
        at = @At(value = "INVOKE", target = "Lmcjty/lostcities/varia/Tools;stringToState(Ljava/lang/String;)Lnet/minecraft/world/level/block/state/BlockState;"),
        remap = false
    )
    private BlockState lc2h$safeStringToState(String s) {
        try {
            return Tools.stringToState(s);
        } catch (RuntimeException e) {
            if (LC2H_WARNED_PALETTE_BLOCKS.add(s)) {
                LC2H.LOGGER.warn("[LC2H] Ignoring unregistered block '{}' in Lost Cities palette; substituting air. ({})", s, e.getMessage());
            }
            return Blocks.AIR.defaultBlockState();
        }
    }
}
