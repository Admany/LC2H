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
import org.admany.lc2h.worldgen.async.warmup.AsyncChunkWarmup;
import org.admany.lc2h.worldgen.lostcities.LostCityFeatureGuards;
import org.admany.lc2h.worldgen.lostcities.LostCitiesGenerationLocks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;

@Mixin(value = LostCityFeature.class, remap = false)
public class MixinLostCityFeature {

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

    @Inject(method = "m_142674_(Lnet/minecraft/world/level/levelgen/feature/FeaturePlaceContext;)Z",
            at = @At("HEAD"), remap = false, cancellable = true)
    private void lc2h$warmupFeature(FeaturePlaceContext<?> context, CallbackInfoReturnable<Boolean> cir) {
        WorldGenLevel level = context.level();
        if (!(level instanceof WorldGenRegion region)) return;

        LostCityFeature self = (LostCityFeature) (Object) this;
        IDimensionInfo provider = self.getDimensionInfo(level);
        if (provider == null) return;

        provider.setWorld(level);

        ChunkPos center = region.getCenter();
        ChunkCoord coord = new ChunkCoord(provider.getType(), center.x, center.z);

        long now = System.currentTimeMillis();

        if (LostCityFeatureGuards.isPlacedRecently(coord, now)) {
            if (LostCityFeatureGuards.TRACE_PLACE) {
                org.admany.lc2h.LC2H.LOGGER.debug("[LC2H] LostCityFeature.place skipped (already placed) coord={} thread={}",
                    coord, Thread.currentThread().getName());
            }
            cir.setReturnValue(true);
            cir.cancel();
            return;
        }

        Long inFlight = LostCityFeatureGuards.IN_FLIGHT_PLACE_MS.putIfAbsent(coord, now);
        if (inFlight != null) {
            if ((now - inFlight) < LostCityFeatureGuards.PLACE_GUARD_MS) {
                if (LostCityFeatureGuards.TRACE_PLACE) {
                    org.admany.lc2h.LC2H.LOGGER.debug("[LC2H] LostCityFeature.place skipped (in-flight) coord={} thread={}",
                        coord, Thread.currentThread().getName());
                }
                cir.setReturnValue(true);
                cir.cancel();
                return;
            }
            LostCityFeatureGuards.IN_FLIGHT_PLACE_MS.put(coord, now);
        }
        Long last = LostCityFeatureGuards.getLastSuccess(coord, now);
        if (last != null && (now - last) < LostCityFeatureGuards.PLACE_GUARD_MS) {
            LostCityFeatureGuards.IN_FLIGHT_PLACE_MS.remove(coord);
            if (LostCityFeatureGuards.TRACE_PLACE) {
                org.admany.lc2h.LC2H.LOGGER.debug("[LC2H] LostCityFeature.place skipped (recent) coord={} thread={}",
                    coord, Thread.currentThread().getName());
            }
            cir.setReturnValue(true);
            cir.cancel();
            return;
        }
        if (LostCityFeatureGuards.TRACE_PLACE) {
            org.admany.lc2h.LC2H.LOGGER.debug("[LC2H] LostCityFeature.place begin coord={} thread={}",
                coord, Thread.currentThread().getName());
        }

        if (!AsyncChunkWarmup.isPreScheduled(coord)) {
            AsyncChunkWarmup.preSchedule(provider, coord);
        }
    }

    @Redirect(
            method = "m_142674_",
            at = @At(
                    value = "INVOKE",
                    target = "Lmcjty/lostcities/worldgen/LostCityTerrainFeature;generate(Lnet/minecraft/server/level/WorldGenRegion;Lnet/minecraft/world/level/chunk/ChunkAccess;)V"
            ),
            remap = false,
            require = 0
    )
    private void lc2h$wrapGenerateWithStripeLock(LostCityTerrainFeature feature, WorldGenRegion region, ChunkAccess chunk) {
        if (feature == null || region == null || chunk == null) return;

        final ResourceKey<Level> dim = ((ServerLevelAccessor) region).getLevel().dimension();
        final int cx = chunk.getPos().x;
        final int cz = chunk.getPos().z;

        LostCitiesGenerationLocks.withChunkStripeLock(dim, cx, cz, () -> feature.generate(region, chunk));
    }

    @Inject(method = "m_142674_(Lnet/minecraft/world/level/levelgen/feature/FeaturePlaceContext;)Z",
            at = @At("RETURN"), remap = false)
    private void lc2h$markPlace(FeaturePlaceContext<?> context, CallbackInfoReturnable<Boolean> cir) {
        WorldGenLevel level = context.level();
        if (!(level instanceof WorldGenRegion region)) return;

        LostCityFeature self = (LostCityFeature) (Object) this;
        IDimensionInfo provider = self.getDimensionInfo(level);
        if (provider == null) return;

        ChunkPos center = region.getCenter();
        ChunkCoord coord = new ChunkCoord(provider.getType(), center.x, center.z);

        LostCityFeatureGuards.IN_FLIGHT_PLACE_MS.remove(coord);
        LostCityFeatureGuards.markPlaced(coord, System.currentTimeMillis());
        if (LostCityFeatureGuards.TRACE_PLACE) {
            org.admany.lc2h.LC2H.LOGGER.debug("[LC2H] LostCityFeature.place end coord={} thread={}",
                coord, Thread.currentThread().getName());
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
    }
}
