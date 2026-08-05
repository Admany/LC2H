package org.admany.lc2h.mixin.minecraft.worldgen;

import mcjty.lostcities.worldgen.IDimensionInfo;
import mcjty.lostcities.setup.Registration;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.admany.lc2h.mixin.accessor.minecraft.StructureManagerAccessor;
import org.admany.lc2h.config.ConfigManager;
import org.admany.lc2h.worldgen.lostcities.ChunkRoleProbe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChunkGenerator.class)
public class MixinChunkGeneratorSkipStructures {
    @Inject(
            method = "createStructures(Lnet/minecraft/core/RegistryAccess;Lnet/minecraft/world/level/chunk/ChunkGeneratorStructureState;Lnet/minecraft/world/level/StructureManager;Lnet/minecraft/world/level/chunk/ChunkAccess;Lnet/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplateManager;)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void lc2h$skipStructuresInCityChunks(RegistryAccess registryAccess,
                                                 ChunkGeneratorStructureState placementCalculator,
                                                 StructureManager structureManager,
                                                 ChunkAccess chunk,
                                                 StructureTemplateManager templateManager,
                                                 CallbackInfo ci) {
        if (!ConfigManager.REJECT_STRUCTURES_IN_CITY_CHUNKS) {
            return;
        }
        if (!(structureManager instanceof StructureManagerAccessor accessor)) {
            return;
        }
        if (!(accessor.lc2h$getLevel() instanceof ServerLevel level)) {
            return;
        }
        try {
            IDimensionInfo dimInfo = Registration.LOSTCITY_FEATURE.get().getDimensionInfo(level);
            if (dimInfo == null) {
                return;
            }
            ResourceKey<Level> dim = level.dimension();
            int cx = chunk.getPos().x;
            int cz = chunk.getPos().z;
            int buffer = ConfigManager.CITY_STRUCTURE_REJECTION_BUFFER_CHUNKS;
            ChunkRoleProbe.RoleGrid roleGrid = ChunkRoleProbe.getGrid(dimInfo, dim, cx, cz, Math.max(1, buffer));
            boolean intersectsCity = false;
            for (int dx = -buffer; dx <= buffer && !intersectsCity; dx++) {
                for (int dz = -buffer; dz <= buffer; dz++) {
                    if (roleGrid.isCity(cx + dx, cz + dz)) {
                        intersectsCity = true;
                        break;
                    }
                }
            }
            if (intersectsCity) {
                ci.cancel();
            }
        } catch (Throwable ignored) {
        }
    }
}
