package org.admany.lc2h.mixin.accessor.lostcities;

import mcjty.lostcities.worldgen.lost.BiomeInfo;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = BiomeInfo.class, remap = false)
public interface BiomeInfoAccessor {
    @Accessor("mainBiome")
    void lc2h$setMainBiome(Holder<Biome> biome);
}
