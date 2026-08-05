package org.admany.lc2h.mixin.lostcities.worldgen;

import mcjty.lostcities.api.ILostCityAsset;
import mcjty.lostcities.config.LostCityProfile;
import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.IDimensionInfo;
import mcjty.lostcities.worldgen.lost.MultiChunk;
import mcjty.lostcities.worldgen.lost.Railway;
import mcjty.lostcities.worldgen.lost.cityassets.CityStyle;
import mcjty.lostcities.worldgen.lost.cityassets.RegistryAssetRegistry;
import net.minecraft.world.level.CommonLevelAccessor;

import org.admany.lc2h.data.cache.LostCitiesCacheBudgetManager;
import org.admany.lc2h.worldgen.async.planner.AsyncMultiChunkPlanner;
import org.admany.lc2h.worldgen.lostcities.FastMultiChunkPlanner;
import org.admany.lc2h.worldgen.lostcities.MultiChunkPlanningCache;
import org.admany.lc2h.util.lostcities.MultiChunkCacheAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = MultiChunk.class, remap = false)
public class MixinMultiChunk {

    @Unique
    private static final int LC2H_MULTICHUNK_MIN_RETAIN = Math.max(64,
        Integer.getInteger("lc2h.multichunk.cacheMinRetain", 192));

    @Unique
    private static final LostCitiesCacheBudgetManager.CacheGroup LC2H_MULTICHUNK_BUDGET =
        LostCitiesCacheBudgetManager.register("lc_multichunk", 4096, LC2H_MULTICHUNK_MIN_RETAIN, MultiChunkCacheAccess::remove);

    @Inject(method = "calculateBuildings", at = @At("HEAD"), cancellable = true)
    private void lc2h$beginPlanningCache(IDimensionInfo provider, CallbackInfoReturnable<MultiChunk> cir) {
        MultiChunk self = (MultiChunk) (Object) this;
        if (FastMultiChunkPlanner.tryPlan(self, provider)) {
            cir.setReturnValue(self);
            cir.cancel();
            return;
        }
        MultiChunkPlanningCache.begin();
    }

    @Inject(method = "calculateBuildings", at = @At("RETURN"))
    private void lc2h$endPlanningCache(IDimensionInfo provider, CallbackInfoReturnable<MultiChunk> cir) {
        MultiChunkPlanningCache.end();
    }

    @Redirect(
        method = {"calculateBuildings", "canPlaceBuilding"},
        at = @At(
            value = "INVOKE",
            target = "Lmcjty/lostcities/worldgen/lost/City;getCityStyle(Lmcjty/lostcities/varia/ChunkCoord;Lmcjty/lostcities/worldgen/IDimensionInfo;Lmcjty/lostcities/config/LostCityProfile;)Lmcjty/lostcities/worldgen/lost/cityassets/CityStyle;"
        ),
        require = 0, expect = 0
    )
    private CityStyle lc2h$cachedCityStyle(ChunkCoord coord, IDimensionInfo provider, LostCityProfile profile) {
        return MultiChunkPlanningCache.cityStyle(coord, provider, profile);
    }

    @Redirect(
        method = "canPlaceBuilding",
        at = @At(
            value = "INVOKE",
            target = "Lmcjty/lostcities/worldgen/lost/City;isChunkOccupied(Lmcjty/lostcities/worldgen/IDimensionInfo;Lmcjty/lostcities/varia/ChunkCoord;)Z"
        ),
        require = 0, expect = 0
    )
    private boolean lc2h$cachedOccupied(IDimensionInfo provider, ChunkCoord coord) {
        return MultiChunkPlanningCache.isChunkOccupied(provider, coord);
    }

    @Redirect(
        method = "canPlaceBuilding",
        at = @At(
            value = "INVOKE",
            target = "Lmcjty/lostcities/worldgen/lost/Railway;getRailChunkType(Lmcjty/lostcities/varia/ChunkCoord;Lmcjty/lostcities/worldgen/IDimensionInfo;Lmcjty/lostcities/config/LostCityProfile;)Lmcjty/lostcities/worldgen/lost/Railway$RailChunkInfo;"
        ),
        require = 0, expect = 0
    )
    private Railway.RailChunkInfo lc2h$cachedRail(ChunkCoord coord, IDimensionInfo provider, LostCityProfile profile) {
        return MultiChunkPlanningCache.railChunkType(coord, provider, profile);
    }

    @Redirect(
        method = "canPlaceBuilding",
        at = @At(
            value = "INVOKE",
            target = "Lmcjty/lostcities/worldgen/lost/BuildingInfo;isCityRaw(Lmcjty/lostcities/varia/ChunkCoord;Lmcjty/lostcities/worldgen/IDimensionInfo;Lmcjty/lostcities/config/LostCityProfile;)Z"
        ),
        require = 0, expect = 0
    )
    private boolean lc2h$cachedCityRaw(ChunkCoord coord, IDimensionInfo provider, LostCityProfile profile) {
        return MultiChunkPlanningCache.isCityRaw(coord, provider, profile);
    }

    @Redirect(
        method = "canPlaceBuilding",
        at = @At(
            value = "INVOKE",
            target = "Lmcjty/lostcities/worldgen/lost/BuildingInfo;hasHighway(Lmcjty/lostcities/varia/ChunkCoord;Lmcjty/lostcities/worldgen/IDimensionInfo;Lmcjty/lostcities/config/LostCityProfile;)Z"
        ),
        require = 0, expect = 0
    )
    private boolean lc2h$cachedHighway(ChunkCoord coord, IDimensionInfo provider, LostCityProfile profile) {
        return MultiChunkPlanningCache.hasHighway(coord, provider, profile);
    }

    @Redirect(
        method = "calculateBuildings",
        at = @At(
            value = "INVOKE",
            target = "Lmcjty/lostcities/worldgen/lost/cityassets/RegistryAssetRegistry;get(Lnet/minecraft/world/level/CommonLevelAccessor;Ljava/lang/String;)Lmcjty/lostcities/api/ILostCityAsset;"
        ),
        require = 0, expect = 0
    )
    private ILostCityAsset lc2h$cachedAsset(RegistryAssetRegistry<?, ?> registry, CommonLevelAccessor world, String name) {
        return MultiChunkPlanningCache.asset(registry, world, name);
    }

    @Inject(method = "getOrCreate", at = @At("HEAD"), cancellable = true)
    private static void lc2h$asyncGetOrCreate(IDimensionInfo provider, ChunkCoord coord, CallbackInfoReturnable<MultiChunk> cir) {
        if (AsyncMultiChunkPlanner.isInternalComputation()) {
            return;
        }

        if (provider == null || coord == null) {
            return;
        }

        String threadName = Thread.currentThread().getName();
        boolean isServerThread = "Server thread".equals(threadName);
        boolean holdsMultiChunkLock = Thread.holdsLock(MultiChunk.class);

        if (isServerThread || holdsMultiChunkLock) {
            MultiChunk prepared = AsyncMultiChunkPlanner.tryConsumePrepared(provider, coord);
            if (prepared != null) {
                cir.setReturnValue(prepared);
                cir.cancel();
                return;
            }
            MultiChunk raced = AsyncMultiChunkPlanner.claimSynchronousFallback(provider, coord);
            if (raced != null) {
                cir.setReturnValue(raced);
                cir.cancel();
            }
            return;
        }

        MultiChunk resolved = AsyncMultiChunkPlanner.resolve(provider, coord);
        if (resolved != null) {
            cir.setReturnValue(resolved);
            cir.cancel();
        }
    }

    @Inject(method = "getOrCreate", at = @At("RETURN"))
    private static void lc2h$afterGetOrCreate(IDimensionInfo provider, ChunkCoord coord, CallbackInfoReturnable<MultiChunk> cir) {
        AsyncMultiChunkPlanner.onSynchronousResult(provider, coord, cir.getReturnValue());
    }

    @Inject(method = "cleanCache", at = @At("HEAD"))
    private static void lc2h$clearMultiChunkBudget(org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        LostCitiesCacheBudgetManager.clear(LC2H_MULTICHUNK_BUDGET);
    }
}
