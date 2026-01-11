package org.admany.lc2h.mixin.lostcities;

import mcjty.lostcities.config.LostCityProfile;
import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.IDimensionInfo;
import mcjty.lostcities.worldgen.lost.City;
import mcjty.lostcities.worldgen.lost.CityRarityMap;
import mcjty.lostcities.worldgen.lost.cityassets.CityStyle;
import mcjty.lostcities.worldgen.lost.regassets.data.PredefinedBuilding;
import mcjty.lostcities.worldgen.lost.regassets.data.PredefinedStreet;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.CommonLevelAccessor;
import org.admany.lc2h.data.cache.LostCitiesCacheBridge;
import org.admany.lc2h.data.cache.LostCitiesCacheBudgetManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
@Mixin(value = City.class, remap = false)
public abstract class MixinCity {

    private static final Map<ChunkCoord, CityStyle> LC2H_CITY_STYLE_CACHE = new ConcurrentHashMap<>();
    @Unique
    private static final LostCitiesCacheBudgetManager.CacheGroup LC2H_CITY_STYLE_BUDGET =
        LostCitiesCacheBudgetManager.register("lc_city_style", 128, 2048, key -> LC2H_CITY_STYLE_CACHE.remove(key) != null);
    @Unique
    private static final Object LC2H_NULL_LEVEL_KEY = new Object();
    @Unique
    private static final java.util.concurrent.ConcurrentHashMap<Object, CityRarityMap> LC2H_CITY_RARITY_CACHE = new java.util.concurrent.ConcurrentHashMap<>();
    @Unique
    private static final Object LC2H_OCCUPIED_LOCK = new Object();
    @Unique
    private static volatile boolean LC2H_OCCUPIED_READY = false;
    @Unique
    private static volatile boolean LC2H_PREDEFINED_READY = false;

    @Shadow private static CityStyle getCityStyleInt(ChunkCoord coord, IDimensionInfo provider, LostCityProfile profile) { return null; }
    @Shadow private static Map<ChunkCoord, PredefinedBuilding> predefinedBuildingMap;
    @Shadow private static Map<ChunkCoord, PredefinedStreet> predefinedStreetMap;
    @Shadow private static Map<ChunkCoord, ?> OCCUPIED_CHUNKS_BUILDING;
    @Shadow private static Map<ChunkCoord, PredefinedStreet> OCCUPIED_CHUNKS_STREET;

    @Invoker("calculateOccupied")
    private static void lc2h$calculateOccupied(IDimensionInfo provider) { throw new AssertionError(); }

    @Invoker("calculateMap")
    private static void lc2h$calculateMap(CommonLevelAccessor level) { throw new AssertionError(); }

    /**
     * Remove synchronized computeIfAbsent and use a concurrent cache.
     * Allow parallel warmup without global City lock.
     *
     * @author LC2H
     * @reason Allow parallel warmup without global City lock
     */
    @Overwrite
    public static CityStyle getCityStyle(ChunkCoord coord, IDimensionInfo provider, LostCityProfile profile) {
        CityStyle cached = LC2H_CITY_STYLE_CACHE.get(coord);
        if (cached != null) {
            LostCitiesCacheBudgetManager.recordAccess(LC2H_CITY_STYLE_BUDGET, coord);
            return cached;
        }
        CityStyle disk = LostCitiesCacheBridge.getDisk("city_style", coord, CityStyle.class);
        if (disk != null) {
            CityStyle prev = LC2H_CITY_STYLE_CACHE.putIfAbsent(coord, disk);
            LostCitiesCacheBudgetManager.recordPut(LC2H_CITY_STYLE_BUDGET, coord, LC2H_CITY_STYLE_BUDGET.defaultEntryBytes(), prev == null);
            return prev != null ? prev : disk;
        }
        CityStyle style = getCityStyleInt(coord, provider, profile);
        CityStyle prev = LC2H_CITY_STYLE_CACHE.putIfAbsent(coord, style);
        LostCitiesCacheBudgetManager.recordPut(LC2H_CITY_STYLE_BUDGET, coord, LC2H_CITY_STYLE_BUDGET.defaultEntryBytes(), prev == null);
        LostCitiesCacheBridge.putDisk("city_style", coord, style);
        return style;
    }

    /**
     * Make CityRarityMap cache safe for parallel worldgen.
     *
     * @author LC2H
     * @reason Prevent HashMap corruption and cross-chunk nondeterminism
     */
    @Overwrite
    public static CityRarityMap getCityRarityMap(ResourceKey<Level> level, long seed, double scale, double offset, double innerScale) {
        Object cacheKey = level != null ? level : LC2H_NULL_LEVEL_KEY;
        return LC2H_CITY_RARITY_CACHE.computeIfAbsent(cacheKey, k -> new CityRarityMap(seed, scale, offset, innerScale));
    }

    @Inject(method = "cleanCache", at = @At("HEAD"))
    private static void lc2h$clearLc2hCaches(CallbackInfo ci) {
        LC2H_CITY_STYLE_CACHE.clear();
        LC2H_CITY_RARITY_CACHE.clear();
        LostCitiesCacheBudgetManager.clear(LC2H_CITY_STYLE_BUDGET);
        LC2H_OCCUPIED_READY = false;
        LC2H_PREDEFINED_READY = false;
    }

    @Inject(method = "isChunkOccupied", at = @At("HEAD"))
    private static void lc2h$ensureOccupied(IDimensionInfo provider, ChunkCoord coord, CallbackInfoReturnable<Boolean> cir) {
        ensureOccupiedReady(provider);
    }

    @Inject(method = "getPredefinedBuilding", at = @At("HEAD"))
    private static void lc2h$ensureOccupiedForBuilding(IDimensionInfo provider, ChunkCoord coord, CallbackInfoReturnable<Object> cir) {
        ensureOccupiedReady(provider);
    }

    @Inject(method = "getPredefinedStreet", at = @At("HEAD"))
    private static void lc2h$ensureOccupiedForStreet(IDimensionInfo provider, ChunkCoord coord, CallbackInfoReturnable<PredefinedStreet> cir) {
        ensureOccupiedReady(provider);
    }

    @Inject(method = "getPredefinedBuildingAtTopLeft", at = @At("HEAD"))
    private static void lc2h$ensurePredefinedMap(CommonLevelAccessor level, ChunkCoord coord, CallbackInfoReturnable<PredefinedBuilding> cir) {
        ensurePredefinedReady(level);
    }

    private static void ensureOccupiedReady(IDimensionInfo provider) {
        if (LC2H_OCCUPIED_READY) {
            return;
        }
        if (provider == null || provider.getWorld() == null) {
            return;
        }
        synchronized (LC2H_OCCUPIED_LOCK) {
            if (LC2H_OCCUPIED_READY) {
                return;
            }
            lc2h$calculateOccupied(provider);
            LC2H_OCCUPIED_READY = OCCUPIED_CHUNKS_BUILDING != null && OCCUPIED_CHUNKS_STREET != null;
        }
    }

    private static void ensurePredefinedReady(CommonLevelAccessor level) {
        if (LC2H_PREDEFINED_READY) {
            return;
        }
        if (level == null) {
            return;
        }
        synchronized (LC2H_OCCUPIED_LOCK) {
            if (LC2H_PREDEFINED_READY) {
                return;
            }
            lc2h$calculateMap(level);
            LC2H_PREDEFINED_READY = predefinedBuildingMap != null && predefinedStreetMap != null;
        }
    }
}
