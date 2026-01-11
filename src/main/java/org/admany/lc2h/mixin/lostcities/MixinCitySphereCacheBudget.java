package org.admany.lc2h.mixin.lostcities;

import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.lost.CitySphere;
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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Mixin(value = CitySphere.class, remap = false)
public class MixinCitySphereCacheBudget {

    @Shadow @Final @Mutable
    private static Map<ChunkCoord, CitySphere> CITY_SPHERE_CACHE;

    private static final LostCitiesCacheBudgetManager.CacheGroup LC2H_CITY_SPHERE_BUDGET =
        LostCitiesCacheBudgetManager.register("lc_city_sphere", 512, 2048, key -> CITY_SPHERE_CACHE.remove(key) != null);

    @Inject(method = "<clinit>", at = @At("TAIL"))
    private static void lc2h$makeCitySphereCacheConcurrent(CallbackInfo ci) {
        CITY_SPHERE_CACHE = new ConcurrentHashMap<>();
    }

    @Redirect(
        method = "getCitySphere",
        at = @At(
            value = "INVOKE",
            target = "Ljava/util/Map;get(Ljava/lang/Object;)Ljava/lang/Object;"
        )
    )
    private static Object lc2h$trackSphereCacheGet(Map<ChunkCoord, CitySphere> cache, Object key) {
        Object value = cache.get(key);
        if (value == null && key instanceof ChunkCoord coord) {
            CitySphere disk = LostCitiesCacheBridge.getDisk("city_sphere", coord, CitySphere.class);
            if (disk != null) {
                Object prev = cache.put(coord, disk);
                LostCitiesCacheBudgetManager.recordPut(LC2H_CITY_SPHERE_BUDGET, coord, LC2H_CITY_SPHERE_BUDGET.defaultEntryBytes(), prev == null);
                value = disk;
            }
        }
        if (value != null) {
            LostCitiesCacheBudgetManager.recordAccess(LC2H_CITY_SPHERE_BUDGET, key);
        }
        return value;
    }

    @Redirect(
        method = "updateCache",
        at = @At(
            value = "INVOKE",
            target = "Ljava/util/Map;put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"
        )
    )
    private static Object lc2h$trackSphereCachePut(Map<ChunkCoord, CitySphere> cache, Object key, Object value) {
        Object prev = cache.put((ChunkCoord) key, (CitySphere) value);
        LostCitiesCacheBudgetManager.recordPut(LC2H_CITY_SPHERE_BUDGET, key, LC2H_CITY_SPHERE_BUDGET.defaultEntryBytes(), prev == null);
        if (key instanceof ChunkCoord coord && value instanceof CitySphere sphere) {
            LostCitiesCacheBridge.putDisk("city_sphere", coord, sphere);
        }
        return prev;
    }

    @Inject(method = "cleanCache", at = @At("HEAD"))
    private static void lc2h$clearCitySphereBudget(CallbackInfo ci) {
        LostCitiesCacheBudgetManager.clear(LC2H_CITY_SPHERE_BUDGET);
    }
}
