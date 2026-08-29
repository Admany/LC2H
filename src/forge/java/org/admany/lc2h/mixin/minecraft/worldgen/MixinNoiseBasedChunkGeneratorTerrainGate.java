package org.admany.lc2h.mixin.minecraft.worldgen;

import mcjty.lostcities.config.LostCityProfile;
import mcjty.lostcities.worldgen.IDimensionInfo;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.admany.lc2h.config.ConfigManager;
import org.admany.lc2h.compat.DHCompat;
import org.admany.lc2h.mixin.accessor.minecraft.StructureManagerAccessor;
import org.admany.lc2h.mixin.accessor.minecraft.ChunkAccessNoiseChunkAccessor;
import org.admany.lc2h.util.server.DimensionInfoAccessor;
import org.admany.lc2h.worldgen.terrain.CityDensityTransform;
import org.admany.lc2h.worldgen.terrain.CityDensityTransformBinding;
import org.admany.lc2h.worldgen.terrain.CityShiftField;
import org.admany.lc2h.worldgen.terrain.MountainCityBlendDiagnostics;
import org.admany.lc2h.worldgen.terrain.NaturalHeightSampler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** Make the published city-shift plan a dependency of native noise. */
@Mixin(NoiseBasedChunkGenerator.class)
public abstract class MixinNoiseBasedChunkGeneratorTerrainGate {

    @Unique
    private static final ThreadLocal<Boolean> LC2H_BYPASS =
        ThreadLocal.withInitial(() -> Boolean.FALSE);

    @Inject(
        method = "fillFromNoise(Ljava/util/concurrent/Executor;Lnet/minecraft/world/level/levelgen/blending/Blender;Lnet/minecraft/world/level/levelgen/RandomState;Lnet/minecraft/world/level/StructureManager;Lnet/minecraft/world/level/chunk/ChunkAccess;)Ljava/util/concurrent/CompletableFuture;",
        at = @At("HEAD"),
        cancellable = true)
    private void lc2h$awaitTerrainPlanBeforeNoise(Executor executor,
                                                   Blender blender,
                                                   RandomState randomState,
                                                   StructureManager structureManager,
                                                   ChunkAccess chunk,
                                                   CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        if (LC2H_BYPASS.get() || !ConfigManager.CITY_BLEND_ENABLED
            || executor == null || structureManager == null || chunk == null) {
            return;
        }
        MountainCityBlendDiagnostics.gateSeen();
        try {
            LevelAccessor rawLevel = ((StructureManagerAccessor) (Object) structureManager).lc2h$getLevel();
            WorldGenRegion worldGenRegion = rawLevel instanceof WorldGenRegion region ? region : null;
            ServerLevel level = rawLevel instanceof ServerLevel serverLevel
                ? serverLevel
                : worldGenRegion != null
                    ? ((ServerLevelAccessor) worldGenRegion).getLevel()
                    : null;
            if (level == null) {
                return;
            }
            int targetChunkX = chunk.getPos().x;
            int targetChunkZ = chunk.getPos().z;
            MountainCityBlendDiagnostics.gateSeen(level.dimension(), targetChunkX, targetChunkZ);
            IDimensionInfo provider = DimensionInfoAccessor.getForLevel(level);
            if (provider == null) {
                MountainCityBlendDiagnostics.gateDecision(level.dimension(), targetChunkX, targetChunkZ, "NO_PROVIDER");
                return;
            }
            LostCityProfile profile = provider.getProfile();
            NaturalHeightSampler.LevelSampler heights = NaturalHeightSampler.forLevel(level);
            CityShiftField.Context context = CityShiftField.context(provider, profile, heights);
            if (context == null || !context.settings().enabled()) {
                MountainCityBlendDiagnostics.gateDecision(level.dimension(), targetChunkX, targetChunkZ,
                    context == null ? "NO_CONTEXT" : "FIELD_DISABLED");
                return;
            }

            CompletableFuture<Void> plan = CityShiftField.readyForChunk(
                context, targetChunkX, targetChunkZ);
            boolean distantWorldgen = DHCompat.isDistantWorldgenThread();
            /* DH requires this future to complete synchronously. Rebuild the
             * Blender after the field is ready so no pre-publication samples
             * survive. */
            if (!plan.isDone() && distantWorldgen) {
                plan.join();
            }
            if (plan.isDone()) {
                /* Blender.of(region) may have run before the field published.
                 * Resolve it again before the native pass. */
                Blender readyBlender = worldGenRegion == null ? blender : Blender.of(worldGenRegion);
                if (!lc2h$isActive(readyBlender)) {
                    MountainCityBlendDiagnostics.gateNoRegion();
                    MountainCityBlendDiagnostics.gateDecision(level.dimension(), targetChunkX, targetChunkZ, "NO_REGION");
                    return;
                }
                if (lc2h$isActive(blender)) {
                    MountainCityBlendDiagnostics.gateAlreadyActive();
                }
                boolean rebound = lc2h$bindExistingNoiseChunk(chunk, readyBlender);
                MountainCityBlendDiagnostics.gateReadyRebuild();
                MountainCityBlendDiagnostics.gateDecision(level.dimension(), targetChunkX, targetChunkZ,
                    (distantWorldgen ? "DH_SYNC_READY_REBUILD" : "READY_REBUILD")
                        + (rebound ? "_NOISE_REBOUND" : ""));
                MountainCityBlendDiagnostics.targetPlan(level.dimension(), targetChunkX, targetChunkZ,
                    CityShiftField.cachedShiftAtChunk(context, targetChunkX, targetChunkZ));
                cir.setReturnValue(lc2h$fillWithReadyBlender(executor, readyBlender,
                    randomState, structureManager, chunk));
                return;
            }
            NoiseBasedChunkGenerator self = (NoiseBasedChunkGenerator) (Object) this;
            MountainCityBlendDiagnostics.gateDeferred();
            MountainCityBlendDiagnostics.gateDecision(level.dimension(), targetChunkX, targetChunkZ, "DEFERRED");
            cir.setReturnValue(plan.thenComposeAsync(ignored -> {
                LC2H_BYPASS.set(Boolean.TRUE);
                try {
                    // Resolve Blender again after the field dependency completes.
                    Blender readyBlender = worldGenRegion == null ? blender : Blender.of(worldGenRegion);
                    if (lc2h$isActive(readyBlender)) {
                        boolean rebound = lc2h$bindExistingNoiseChunk(chunk, readyBlender);
                        MountainCityBlendDiagnostics.gateResumed();
                        MountainCityBlendDiagnostics.gateDecision(level.dimension(), targetChunkX, targetChunkZ,
                            rebound ? "RESUMED_NOISE_REBOUND" : "RESUMED");
                        MountainCityBlendDiagnostics.targetPlan(level.dimension(), targetChunkX, targetChunkZ,
                            CityShiftField.cachedShiftAtChunk(context, targetChunkX, targetChunkZ));
                    } else {
                        MountainCityBlendDiagnostics.gateNoRegion();
                        MountainCityBlendDiagnostics.gateDecision(level.dimension(), targetChunkX, targetChunkZ, "NO_REGION");
                    }
                    return self.fillFromNoise(executor, readyBlender, randomState, structureManager, chunk);
                } finally {
                    LC2H_BYPASS.remove();
                }
            }, executor));
        } catch (Throwable ignored) {
            MountainCityBlendDiagnostics.gateFailure();
            // If the Lost Cities context is unavailable, native noise remains authoritative.
        }
    }

    @Unique
    private CompletableFuture<ChunkAccess> lc2h$fillWithReadyBlender(Executor executor,
                                                                     Blender blender,
                                                                     RandomState randomState,
                                                                     StructureManager structureManager,
                                                                     ChunkAccess chunk) {
        LC2H_BYPASS.set(Boolean.TRUE);
        try {
            return ((NoiseBasedChunkGenerator) (Object) this).fillFromNoise(
                executor, blender, randomState, structureManager, chunk);
        } finally {
            LC2H_BYPASS.remove();
        }
    }

    @Unique
    private static boolean lc2h$isActive(Blender blender) {
        return blender instanceof CityDensityTransform transform
            && transform.lc2h$isDensityTransformActive();
    }

    @Unique
    private static boolean lc2h$bindExistingNoiseChunk(ChunkAccess chunk, Blender blender) {
        if (chunk == null || !lc2h$isActive(blender)) {
            return false;
        }
        NoiseChunk noiseChunk = ((ChunkAccessNoiseChunkAccessor) (Object) chunk).lc2h$getNoiseChunk();
        if (!(noiseChunk instanceof CityDensityTransformBinding binding)) {
            return false;
        }
        binding.lc2h$bindDensityTransform(blender);
        return true;
    }
}
