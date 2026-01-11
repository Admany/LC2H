package org.admany.lc2h.mixin.minecraft;

import mcjty.lostcities.LostCities;
import mcjty.lostcities.api.ILostChunkInfo;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.admany.lc2h.mixin.accessor.StructureManagerAccessor;
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
        if (!(structureManager instanceof StructureManagerAccessor accessor)) {
            return;
        }
        if (!(accessor.lc2h$getLevel() instanceof ServerLevel level)) {
            return;
        }
        try {
            var info = LostCities.lostCitiesImp.getLostInfo(level);
            ILostChunkInfo chunkInfo = info.getChunkInfo(chunk.getPos().x, chunk.getPos().z);
            boolean isCity = chunkInfo != null && chunkInfo.isCity();
            int cx = chunk.getPos().x;
            int cz = chunk.getPos().z;
            boolean neighborCity =
                isCity(info.getChunkInfo(cx + 1, cz)) ||
                isCity(info.getChunkInfo(cx - 1, cz)) ||
                isCity(info.getChunkInfo(cx, cz + 1)) ||
                isCity(info.getChunkInfo(cx, cz - 1)) ||
                isCity(info.getChunkInfo(cx + 1, cz + 1)) ||
                isCity(info.getChunkInfo(cx + 1, cz - 1)) ||
                isCity(info.getChunkInfo(cx - 1, cz + 1)) ||
                isCity(info.getChunkInfo(cx - 1, cz - 1));
            if (isCity || neighborCity) {
                ci.cancel();
            }
        } catch (Throwable ignored) {
        }
    }

    private static boolean isCity(ILostChunkInfo info) {
        return info != null && info.isCity();
    }
}
