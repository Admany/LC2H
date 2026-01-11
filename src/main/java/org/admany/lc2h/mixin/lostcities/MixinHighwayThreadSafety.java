package org.admany.lc2h.mixin.lostcities;

import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.config.LostCityProfile;
import mcjty.lostcities.worldgen.IDimensionInfo;
import mcjty.lostcities.worldgen.lost.Highway;
import mcjty.lostcities.worldgen.lost.Orientation;
import org.admany.lc2h.data.cache.LostCitiesCacheBridge;
import org.admany.lc2h.data.cache.LostCitiesCacheBudgetManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

@Mixin(value = Highway.class, remap = false)
public abstract class MixinHighwayThreadSafety {

    @Shadow @Final @Mutable
    private static Map<ChunkCoord, Integer> X_HIGHWAY_LEVEL_CACHE;

    @Shadow @Final @Mutable
    private static Map<ChunkCoord, Integer> Z_HIGHWAY_LEVEL_CACHE;

    private static final LostCitiesCacheBudgetManager.CacheGroup LC2H_HIGHWAY_X_BUDGET =
        LostCitiesCacheBudgetManager.register("lc_highway_x", 64, 4096, key -> X_HIGHWAY_LEVEL_CACHE.remove(key) != null);
    private static final LostCitiesCacheBudgetManager.CacheGroup LC2H_HIGHWAY_Z_BUDGET =
        LostCitiesCacheBudgetManager.register("lc_highway_z", 64, 4096, key -> Z_HIGHWAY_LEVEL_CACHE.remove(key) != null);

    @Inject(method = "<clinit>", at = @At("TAIL"))
    private static void lc2h$makeHighwayCachesConcurrent(CallbackInfo ci) {
        X_HIGHWAY_LEVEL_CACHE = new ConcurrentHashMap<>();
        Z_HIGHWAY_LEVEL_CACHE = new ConcurrentHashMap<>();
    }

    @Inject(method = "getHighwayLevel", at = @At("HEAD"), cancellable = true)
    private static void lc2h$fastCacheHit(IDimensionInfo provider, LostCityProfile profile,
                                          Map<ChunkCoord, Integer> cache, Function<ChunkCoord, Boolean> hasHighway,
                                          Orientation orientation, ChunkCoord cp,
                                          CallbackInfoReturnable<Integer> cir) {
        Integer cached = cache.get(cp);
        if (cached != null) {
            LostCitiesCacheBudgetManager.recordAccess(selectBudget(cache), cp);
            cir.setReturnValue(cached);
            return;
        }
        String diskName = cacheName(cache);
        Integer disk = LostCitiesCacheBridge.getDisk(diskName, cp, Integer.class);
        if (disk != null) {
            Integer prev = cache.putIfAbsent(cp, disk);
            LostCitiesCacheBudgetManager.recordPut(selectBudget(cache), cp, selectBudget(cache).defaultEntryBytes(), prev == null);
            cir.setReturnValue(prev != null ? prev : disk);
        }
    }

    @Redirect(
        method = "getHighwayLevel",
        at = @At(
            value = "INVOKE",
            target = "Ljava/util/Map;put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"
        )
    )
    private static Object lc2h$trackHighwayCachePut(Map<ChunkCoord, Integer> cache, Object key, Object value) {
        Object prev = cache.put((ChunkCoord) key, (Integer) value);
        LostCitiesCacheBudgetManager.CacheGroup budget = selectBudget(cache);
        LostCitiesCacheBudgetManager.recordPut(budget, key, budget.defaultEntryBytes(), prev == null);
        if (key instanceof ChunkCoord coord && value instanceof Integer level) {
            LostCitiesCacheBridge.putDisk(cacheName(cache), coord, level);
        }
        return prev;
    }

    @Inject(method = "cleanCache", at = @At("HEAD"))
    private static void lc2h$clearBudget(CallbackInfo ci) {
        LostCitiesCacheBudgetManager.clear(LC2H_HIGHWAY_X_BUDGET);
        LostCitiesCacheBudgetManager.clear(LC2H_HIGHWAY_Z_BUDGET);
    }

    private static LostCitiesCacheBudgetManager.CacheGroup selectBudget(Map<ChunkCoord, Integer> cache) {
        return cache == X_HIGHWAY_LEVEL_CACHE ? LC2H_HIGHWAY_X_BUDGET : LC2H_HIGHWAY_Z_BUDGET;
    }

    private static String cacheName(Map<ChunkCoord, Integer> cache) {
        return cache == X_HIGHWAY_LEVEL_CACHE ? "highway_x" : "highway_z";
    }
}
