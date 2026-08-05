package org.admany.lc2h.mixin.accessor.minecraft;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.blending.BlendingData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Reaches Blender's package-private constructor without adding a class to its package. */
@Mixin(Blender.class)
public interface BlenderFactory {

    @Invoker("<init>")
    static Blender lc2h$create(Long2ObjectOpenHashMap<BlendingData> heightAndBiomeBlendingData,
                               Long2ObjectOpenHashMap<BlendingData> densityBlendingData) {
        throw new UnsupportedOperationException("Mixin invoker not applied");
    }
}
