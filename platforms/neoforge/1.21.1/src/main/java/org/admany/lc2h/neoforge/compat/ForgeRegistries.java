package org.admany.lc2h.neoforge.compat;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.sounds.SoundEvent;

/** Registry names used by the Forge-side sources, backed by vanilla 1.21 registries. */
public final class ForgeRegistries {
    public static final Registry<Block> BLOCKS = BuiltInRegistries.BLOCK;
    public static final Registry<Fluid> FLUIDS = BuiltInRegistries.FLUID;
    public static final Registry<BlockEntityType<?>> BLOCK_ENTITY_TYPES = BuiltInRegistries.BLOCK_ENTITY_TYPE;
    public static final Registry<SoundEvent> SOUND_EVENTS = BuiltInRegistries.SOUND_EVENT;
    public static final Registry<EntityType<?>> ENTITY_TYPES = BuiltInRegistries.ENTITY_TYPE;

    private ForgeRegistries() {
    }
}
