package org.admany.lc2h.mixin.lostcities.worldgen.feature;

import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.IDimensionInfo;
import mcjty.lostcities.worldgen.LostCityFeature;
import mcjty.lostcities.worldgen.LostCityTerrainFeature;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.ServerLevelAccessor;
import org.admany.lc2h.dev.diagnostics.CriticalMixinHookValidator;
import org.admany.lc2h.dev.debug.WorldParityObservedChunkTracker;
import org.admany.lc2h.runtime.Lc2hRuntimeModes;
import org.admany.lc2h.worldgen.async.warmup.AsyncChunkWarmup;
import org.admany.lc2h.worldgen.lostcities.LostCityFeatureGuards;
import org.admany.lc2h.worldgen.lostcities.LostCityGenerationHotPath;
import org.admany.lc2h.worldgen.lostcities.LostCitiesGenerationLocks;
import org.admany.lc2h.worldgen.seams.SeamOwnershipJournal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

@Mixin(value = LostCityFeature.class, remap = false)
public class MixinLostCityFeature {
    private static final ThreadLocal<Boolean> LC2H_TERRAIN_PATH_REACHED = ThreadLocal.withInitial(() -> Boolean.FALSE);
    private static final ThreadLocal<Boolean> LC2H_GENERATE_REDIRECT_REACHED = ThreadLocal.withInitial(() -> Boolean.FALSE);
    private static final AtomicBoolean LC2H_LOGGED_NON_REGION_SKIP = new AtomicBoolean(false);
    private static final AtomicBoolean LC2H_LOGGED_NULL_PROVIDER_SKIP = new AtomicBoolean(false);
    private static final AtomicBoolean LC2H_LOGGED_VOID_BIOME_SKIP = new AtomicBoolean(false);
    private static final AtomicBoolean LC2H_LOGGED_POST_FEATURE_GAP = new AtomicBoolean(false);

    @Shadow @Final @Mutable
    private Map<ResourceKey<Level>, IDimensionInfo> dimensionInfo;

    @Inject(method = "<init>", at = @At("RETURN"), remap = false)
    private void lc2h$initConcurrentDimensionInfo(org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        if (!(dimensionInfo instanceof java.util.concurrent.ConcurrentHashMap)) {
            dimensionInfo = new java.util.concurrent.ConcurrentHashMap<>();
        }
    }

    @Inject(method = "cleanUp", at = @At("HEAD"), remap = false)
    private void lc2h$resetLc2hGuards(org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        try {
            mcjty.lostcities.worldgen.lost.City.cleanCache();
        } catch (Throwable ignored) {
        }
    }

    @Inject(method = "place(Lnet/minecraft/world/level/levelgen/feature/FeaturePlaceContext;)Z",
            at = @At("HEAD"), remap = true, cancellable = true)
    private void lc2h$warmupFeature(FeaturePlaceContext<?> context, CallbackInfoReturnable<Boolean> cir) {
        CriticalMixinHookValidator.markObserved(CriticalMixinHookValidator.LOST_CITY_FEATURE_PLACE_HEAD);
        CriticalMixinHookValidator.markWorldgenOpportunity();
        LC2H_TERRAIN_PATH_REACHED.set(Boolean.FALSE);
        LC2H_GENERATE_REDIRECT_REACHED.set(Boolean.FALSE);
        WorldGenLevel level = context.level();
        if (!(level instanceof WorldGenRegion region)) return;

        LostCityFeature self = (LostCityFeature) (Object) this;
        IDimensionInfo provider = self.getDimensionInfo(level);
        if (provider == null) return;

        provider.setWorld(level);
        ChunkPos center = region.getCenter();
        ChunkCoord coord = new ChunkCoord(provider.getType(), center.x, center.z);
        WorldParityObservedChunkTracker.record(coord);
        long now = System.currentTimeMillis();

        if (LostCityFeatureGuards.isPlacedRecently(coord, now) && LostCityFeatureGuards.TRACE_PLACE) {
            org.admany.lc2h.LC2H.LOGGER.debug(
                "[LC2H] LostCityFeature.place seen as already placed recently (continuing) coord={} thread={}",
                coord, Thread.currentThread().getName()
            );
        }

        Long inFlight = LostCityFeatureGuards.IN_FLIGHT_PLACE_MS.putIfAbsent(coord, now);
        if (inFlight != null) {
            if ((now - inFlight) < LostCityFeatureGuards.PLACE_GUARD_MS) {
                if (LostCityFeatureGuards.TRACE_PLACE) {
                    org.admany.lc2h.LC2H.LOGGER.debug("[LC2H] LostCityFeature.place duplicate in-flight (continuing) coord={} thread={}",
                        coord, Thread.currentThread().getName());
                }
            } else {
                LostCityFeatureGuards.IN_FLIGHT_PLACE_MS.put(coord, now);
            }
        }
        if (LostCityFeatureGuards.TRACE_PLACE) {
            org.admany.lc2h.LC2H.LOGGER.debug("[LC2H] LostCityFeature.place begin coord={} thread={}",
                coord, Thread.currentThread().getName());
        }

        if (AsyncChunkWarmup.shouldWarmupFromCurrentThread()
            && AsyncChunkWarmup.shouldAcceptPreschedule()
            && !AsyncChunkWarmup.isPreScheduled(coord)) {
            AsyncChunkWarmup.preSchedule(provider, coord);
        }

        try {
            SeamOwnershipJournal.beginLostCityPass(region);
        } catch (Throwable ignored) {
        }
    }

    @Redirect(
            method = "place(Lnet/minecraft/world/level/levelgen/feature/FeaturePlaceContext;)Z",
            at = @At(
                    value = "INVOKE",
                    target = "Lmcjty/lostcities/worldgen/IDimensionInfo;getFeature()Lmcjty/lostcities/worldgen/LostCityTerrainFeature;",
                    remap = false
            ),
            remap = true
    )
    private LostCityTerrainFeature lc2h$markTerrainPathAtFeatureFetch(IDimensionInfo provider) {
        CriticalMixinHookValidator.markObserved(CriticalMixinHookValidator.LOST_CITY_FEATURE_TERRAIN_PATH);
        LC2H_TERRAIN_PATH_REACHED.set(Boolean.TRUE);
        return provider.getFeature();
    }

    @Redirect(
            method = "place(Lnet/minecraft/world/level/levelgen/feature/FeaturePlaceContext;)Z",
            at = @At(
                    value = "INVOKE",
                    target = "Lmcjty/lostcities/worldgen/LostCityTerrainFeature;generate(Lnet/minecraft/server/level/WorldGenRegion;Lnet/minecraft/world/level/chunk/ChunkAccess;)V",
                    remap = false
            ),
            remap = true
    )
    private void lc2h$wrapGenerateWithStripeLock(LostCityTerrainFeature feature, WorldGenRegion region, ChunkAccess chunk) {
        CriticalMixinHookValidator.markObserved(CriticalMixinHookValidator.LOST_CITY_FEATURE_GENERATE_REDIRECT);
        LC2H_GENERATE_REDIRECT_REACHED.set(Boolean.TRUE);
        if (feature == null || region == null || chunk == null) return;
        final ResourceKey<Level> dim = ((ServerLevelAccessor) region).getLevel().dimension();
        final int cx = chunk.getPos().x;
        final int cz = chunk.getPos().z;

        LostCitiesGenerationLocks.withChunkStripeLock(dim, cx, cz,
            () -> LostCityGenerationHotPath.run(() -> feature.generate(region, chunk)));
    }

    @Inject(method = "place(Lnet/minecraft/world/level/levelgen/feature/FeaturePlaceContext;)Z",
            at = @At("RETURN"), remap = true)
    private void lc2h$markPlace(FeaturePlaceContext<?> context, CallbackInfoReturnable<Boolean> cir) {
        boolean terrainPathReached = Boolean.TRUE.equals(LC2H_TERRAIN_PATH_REACHED.get());
        boolean generateRedirectReached = Boolean.TRUE.equals(LC2H_GENERATE_REDIRECT_REACHED.get());
        try {
            CriticalMixinHookValidator.markObserved(CriticalMixinHookValidator.LOST_CITY_FEATURE_PLACE_RETURN);
            WorldGenLevel level = context.level();
            if (!(level instanceof WorldGenRegion region)) {
                if (!generateRedirectReached) {
                    lc2h$logMissingRedirectReason(
                        LC2H_LOGGED_NON_REGION_SKIP,
                        "level was not a WorldGenRegion"
                    );
                }
                return;
            }

            LostCityFeature self = (LostCityFeature) (Object) this;
            IDimensionInfo provider = self.getDimensionInfo(level);
            if (provider == null) {
                if (!generateRedirectReached) {
                    lc2h$logMissingRedirectReason(
                        LC2H_LOGGED_NULL_PROVIDER_SKIP,
                        "dimension info/profile was null"
                    );
                }
                return;
            }

            if (!generateRedirectReached) {
                if (!terrainPathReached) {
                    lc2h$logMissingRedirectReason(
                        LC2H_LOGGED_VOID_BIOME_SKIP,
                        "place returned before IDimensionInfo#getFeature(); on Lost Cities 1.20-7.4.11 this branch is the center-biome IS_VOID short-circuit"
                    );
                } else {
                    lc2h$logMissingRedirectReason(
                        LC2H_LOGGED_POST_FEATURE_GAP,
                        "terrain path was entered but LostCityTerrainFeature.generate redirect was not reached"
                    );
                }
            }

            ChunkPos center = region.getCenter();
            ChunkCoord coord = new ChunkCoord(provider.getType(), center.x, center.z);

            LostCityFeatureGuards.IN_FLIGHT_PLACE_MS.remove(coord);
            boolean placed = Boolean.TRUE.equals(cir.getReturnValue());
            if (placed) {
                LostCityFeatureGuards.markPlaced(coord, System.currentTimeMillis());
            }
            if (LostCityFeatureGuards.TRACE_PLACE) {
                org.admany.lc2h.LC2H.LOGGER.debug("[LC2H] LostCityFeature.place end coord={} placed={} terrainPathReached={} generateRedirectReached={} thread={}",
                    coord, placed, terrainPathReached, generateRedirectReached, Thread.currentThread().getName());
            }

            try {
            } catch (Throwable ignored) {
            }

            try {
                if (provider instanceof org.admany.lc2h.util.lostcities.ThreadLocalDimensionInfo tl) {
                    tl.lc2h$clearThreadContext();
                }
            } catch (Throwable ignored) {
            }

            try {
                SeamOwnershipJournal.endLostCityPass(region);
            } catch (Throwable ignored) {
            }
        } finally {
            LC2H_TERRAIN_PATH_REACHED.remove();
            LC2H_GENERATE_REDIRECT_REACHED.remove();
        }
    }

    private static void lc2h$logMissingRedirectReason(AtomicBoolean guard, String reason) {
        if (!LostCityFeatureGuards.TRACE_PLACE || !guard.compareAndSet(false, true)) {
            return;
        }
        org.admany.lc2h.LC2H.LOGGER.debug(
            "[LC2H] LostCityFeature.place completed without observing lostcity_feature.terrain_generate.redirect: {}",
            reason
        );
    }
}
