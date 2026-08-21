package org.admany.lc2h.mixin.minecraft.worldgen;

import net.minecraft.core.RegistryAccess;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.admany.lc2h.dev.diagnostics.CriticalMixinHookValidator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChunkGenerator.class)
public class MixinChunkGeneratorSkipStructures {
    @Inject(
            method = "createStructures(Lnet/minecraft/core/RegistryAccess;Lnet/minecraft/world/level/chunk/ChunkGeneratorStructureState;Lnet/minecraft/world/level/StructureManager;Lnet/minecraft/world/level/chunk/ChunkAccess;Lnet/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplateManager;)V",
            at = @At("HEAD")
    )
    private void lc2h$skipStructuresInCityChunks(RegistryAccess registryAccess,
                                                 ChunkGeneratorStructureState placementCalculator,
                                                 StructureManager structureManager,
                                                 ChunkAccess chunk,
                                                 StructureTemplateManager templateManager,
                                                 CallbackInfo ci) {
        CriticalMixinHookValidator.markObserved(CriticalMixinHookValidator.CHUNK_GENERATOR_STRUCTURE_GUARD);
        /*
         * Leave discovery alone. The complete StructureStart is checked later,
         * once its real bounding box is available.
         */
    }
}
