package org.admany.lc2h.mixin.lostcities;

import mcjty.lostcities.config.LostCityProfile;
import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.IDimensionInfo;
import mcjty.lostcities.worldgen.lost.City;
import mcjty.lostcities.worldgen.lost.CityRarityMap;
import mcjty.lostcities.worldgen.lost.cityassets.CityStyle;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
@Mixin(value = City.class, remap = false)
public abstract class MixinCity {

    private static final Map<ChunkCoord, CityStyle> LC2H_CITY_STYLE_CACHE = new ConcurrentHashMap<>();
    @Unique
    private static final Object LC2H_NULL_LEVEL_KEY = new Object();
    @Unique
    private static final java.util.concurrent.ConcurrentHashMap<Object, CityRarityMap> LC2H_CITY_RARITY_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    @Shadow private static CityStyle getCityStyleInt(ChunkCoord coord, IDimensionInfo provider, LostCityProfile profile) { return null; }

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
            return cached;
        }
        CityStyle style = getCityStyleInt(coord, provider, profile);
        LC2H_CITY_STYLE_CACHE.putIfAbsent(coord, style);
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
    }
}
