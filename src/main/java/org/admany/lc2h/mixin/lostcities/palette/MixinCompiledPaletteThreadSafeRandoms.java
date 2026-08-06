package org.admany.lc2h.mixin.lostcities.palette;

import mcjty.lostcities.worldgen.lost.cityassets.CompiledPalette;
import net.minecraft.world.level.block.state.BlockState;
import org.admany.lc2h.runtime.Lc2hParityRandoms;
import org.admany.lc2h.runtime.Lc2hRuntimeModes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.util.Map;
import java.util.Random;

@Mixin(value = CompiledPalette.class, remap = false)
public abstract class MixinCompiledPaletteThreadSafeRandoms {
    @Shadow @Final private Map<Character, Object> palette;

    private static final ThreadLocal<Random> LC2H_PALETTE_RANDOM = ThreadLocal.withInitial(Random::new);

    @Overwrite
    public BlockState get(char c) {
        try {
            Object value = palette.get(Character.valueOf(c));
            if (value instanceof BlockState state) {
                return state;
            }
            if (value == null) {
                return null;
            }
            BlockState[] states = (BlockState[]) value;
            return states[lc2h$nextPaletteIndex()];
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static int lc2h$nextPaletteIndex() {
        if (!Lc2hRuntimeModes.worldParityAuto()) {
            return lc2h$compatFastRand128();
        }
        return Lc2hParityRandoms.withLostCitiesSeedLock(MixinCompiledPaletteThreadSafeRandoms::lc2h$compatFastRand128);
    }
    private static int lc2h$compatFastRand128() {
        Random random = LC2H_PALETTE_RANDOM.get();
        return random.nextInt(128);
    }
}
