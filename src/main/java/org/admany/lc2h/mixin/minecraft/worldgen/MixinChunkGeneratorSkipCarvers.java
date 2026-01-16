package org.admany.lc2h.mixin.minecraft.worldgen;

import mcjty.lostcities.LostCities;
import mcjty.lostcities.api.ILostChunkInfo;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.ServerLevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(NoiseBasedChunkGenerator.class)
public class MixinChunkGeneratorSkipCarvers {

    @Inject(
        method = "applyCarvers(Lnet/minecraft/server/level/WorldGenRegion;JLnet/minecraft/world/level/levelgen/RandomState;Lnet/minecraft/world/level/biome/BiomeManager;Lnet/minecraft/world/level/StructureManager;Lnet/minecraft/world/level/chunk/ChunkAccess;Lnet/minecraft/world/level/levelgen/GenerationStep$Carving;)V",
        at = @At("HEAD"),
        cancellable = true,
        require = 0
    )
    private void lc2h$skipCarversInCityChunks(WorldGenRegion region,
                                              long seed,
                                              RandomState randomState,
                                              BiomeManager biomeManager,
                                              StructureManager structureManager,
                                              ChunkAccess chunk,
                                              GenerationStep.Carving step,
                                              CallbackInfo ci) {
        if (region == null || chunk == null) {
            return;
        }
        ServerLevel level;
        try {
            level = ((ServerLevelAccessor) region).getLevel();
        } catch (Throwable ignored) {
            return;
        }
        if (level == null) {
            return;
        }
        try {
            var info = LostCities.lostCitiesImp.getLostInfo(level);
            ILostChunkInfo chunkInfo = info.getChunkInfo(chunk.getPos().x, chunk.getPos().z);
            if (chunkInfo != null && chunkInfo.isCity()) {
                ci.cancel();
            }
        } catch (Throwable ignored) {
        }
    }
}
