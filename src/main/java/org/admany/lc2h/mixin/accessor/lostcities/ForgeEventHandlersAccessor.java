package org.admany.lc2h.mixin.accessor.lostcities;

import mcjty.lostcities.setup.ForgeEventHandlers;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

@Mixin(value = ForgeEventHandlers.class, remap = false)
public interface ForgeEventHandlersAccessor {
    @Accessor("spawnPositions")
    Map<ResourceKey<Level>, BlockPos> lc2h$getSpawnPositions();
}
