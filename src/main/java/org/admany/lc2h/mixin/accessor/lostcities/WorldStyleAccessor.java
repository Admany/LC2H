package org.admany.lc2h.mixin.accessor.lostcities;

import mcjty.lostcities.worldgen.lost.cityassets.WorldStyle;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import org.apache.commons.lang3.tuple.Pair;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;
import java.util.function.Predicate;

@Mixin(value = WorldStyle.class, remap = false)
public interface WorldStyleAccessor {
    @Accessor("cityStyleSelector")
    List<Pair<Predicate<Holder<Biome>>, Pair<Float, String>>> lc2h$getCityStyleSelector();
}
