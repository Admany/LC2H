package org.admany.lc2h.mixin.minecraft.worldgen;

import net.minecraft.core.RegistryAccess;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.ServerLevelAccessor;
import org.admany.lc2h.config.ConfigManager;
import org.admany.lc2h.dev.diagnostics.CriticalMixinHookValidator;
import org.admany.lc2h.mixin.accessor.minecraft.StructureManagerAccessor;
import org.admany.lc2h.util.server.DimensionInfoAccessor;
import org.admany.lc2h.worldgen.terrain.CityShiftField;
import org.admany.lc2h.worldgen.terrain.NaturalHeightSampler;
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
        lc2h$prewarmTerrainBlend(structureManager, chunk);
        /*
         * Leave discovery alone. The complete StructureStart is checked later,
         * once its real bounding box is available.
         */
    }

    private static void lc2h$prewarmTerrainBlend(StructureManager structureManager,
                                                  ChunkAccess chunk) {
        if (!ConfigManager.CITY_BLEND_ENABLED || structureManager == null || chunk == null) {
            return;
        }
        try {
            LevelAccessor rawLevel = ((StructureManagerAccessor) (Object) structureManager).lc2h$getLevel();
            ServerLevel level = rawLevel instanceof ServerLevel serverLevel
                ? serverLevel
                : rawLevel instanceof WorldGenRegion region
                    ? ((ServerLevelAccessor) region).getLevel()
                    : null;
            if (level == null) {
                return;
            }
            var provider = DimensionInfoAccessor.getForLevel(level);
            if (provider == null || provider.getProfile() == null) {
                return;
            }
            NaturalHeightSampler.LevelSampler heights = NaturalHeightSampler.forLevel(level);
            CityShiftField.Context context = CityShiftField.context(
                provider, provider.getProfile(), heights);
            if (context == null || !context.settings().enabled()) {
                return;
            }
            int chunkX = chunk.getPos().x;
            int chunkZ = chunk.getPos().z;
            CityShiftField.prewarm(context, chunkX, chunkZ);
            /* A block interpolation at a 32-chunk region edge samples the
             * neighbouring immutable plan.  Request only those boundary
             * regions; ordinary chunks remain one deduplicated prewarm. */
            int localX = Math.floorMod(chunkX, 32);
            int localZ = Math.floorMod(chunkZ, 32);
            if (localX == 0) CityShiftField.prewarm(context, chunkX - 1, chunkZ);
            if (localX == 31) CityShiftField.prewarm(context, chunkX + 1, chunkZ);
            if (localZ == 0) CityShiftField.prewarm(context, chunkX, chunkZ - 1);
            if (localZ == 31) CityShiftField.prewarm(context, chunkX, chunkZ + 1);
        } catch (Throwable ignored) {
            // Prewarming is advisory. Vanilla generation remains authoritative.
        }
    }
}
