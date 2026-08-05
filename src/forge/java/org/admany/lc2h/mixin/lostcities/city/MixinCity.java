package org.admany.lc2h.mixin.lostcities.city;

import mcjty.lostcities.config.LostCityProfile;
import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.ChunkHeightmap;
import mcjty.lostcities.worldgen.IDimensionInfo;
import mcjty.lostcities.worldgen.lost.BuildingInfo;
import mcjty.lostcities.worldgen.lost.City;
import mcjty.lostcities.worldgen.lost.CityRarityMap;
import mcjty.lostcities.worldgen.lost.CitySphere;
import mcjty.lostcities.worldgen.lost.cityassets.AssetRegistries;
import mcjty.lostcities.worldgen.lost.cityassets.CityStyle;
import mcjty.lostcities.worldgen.lost.cityassets.WorldStyle;
import mcjty.lostcities.worldgen.lost.cityassets.PredefinedCity;
import mcjty.lostcities.worldgen.lost.regassets.data.PredefinedBuilding;
import mcjty.lostcities.worldgen.lost.regassets.data.PredefinedStreet;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.CommonLevelAccessor;
import org.admany.lc2h.data.cache.LostCitiesCacheBridge;
import org.admany.lc2h.data.cache.LostCitiesCacheBudgetManager;
import org.admany.lc2h.worldgen.lostcities.PlannerHotPath;
import org.admany.lc2h.worldgen.lostcities.PredefinedCityCoordinateIndex;
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

    private static final Map<Object, CityStyle> LC2H_CITY_STYLE_CACHE = new ConcurrentHashMap<>();
    @Unique
    private static final ConcurrentHashMap<Object, ConcurrentHashMap<Long, Boolean>> LC2H_CITY_CENTER_CACHE = new ConcurrentHashMap<>();
    @Unique
    private static final ConcurrentHashMap<Object, ConcurrentHashMap<Long, Float>> LC2H_CITY_RADIUS_CACHE = new ConcurrentHashMap<>();
    /**
     * Provider instances are stable for a loaded dimension. Keep their full
     * world/profile scope here so City hot paths do not rebuild a long string
     * key for every candidate cell during BuildingInfo construction.
     */
    @Unique
    private static final ConcurrentHashMap<IDimensionInfo, Object> LC2H_PROVIDER_SCOPE_KEYS = new ConcurrentHashMap<>();
    /**
     * Predefined cities are normally absent and, when present, form a tiny
     * immutable registry. Index them once by primitive coordinates so the
     * normal city-factor scan does not allocate a ChunkCoord merely to prove
     * that no predefined city exists.
     */
    @Unique
    private static final ConcurrentHashMap<IDimensionInfo, PredefinedCityCoordinateIndex> LC2H_PREDEFINED_CITY_INDEX =
        new ConcurrentHashMap<>();
    @Unique
    private static final LostCitiesCacheBudgetManager.CacheGroup LC2H_CITY_STYLE_BUDGET =
        LostCitiesCacheBudgetManager.register("lc_city_style", 128, 512, key -> LC2H_CITY_STYLE_CACHE.remove(key) != null);
    @Unique
    private static final LostCitiesCacheBudgetManager.CacheGroup LC2H_CITY_CENTER_BUDGET =
        LostCitiesCacheBudgetManager.register("lc_city_center", 16, 2048, MixinCity::lc2h$evictCityCenter);
    @Unique
    private static final LostCitiesCacheBudgetManager.CacheGroup LC2H_CITY_RADIUS_BUDGET =
        LostCitiesCacheBudgetManager.register("lc_city_radius", 16, 2048, MixinCity::lc2h$evictCityRadius);
    @Unique
    private static final Object LC2H_NULL_DIMENSION_KEY = new Object();
    @Unique
    private static final Object LC2H_NULL_LEVEL_KEY = new Object();
    @Unique
    private static final java.util.concurrent.ConcurrentHashMap<Object, CityRarityMap> LC2H_CITY_RARITY_CACHE = new java.util.concurrent.ConcurrentHashMap<>();
    @Unique
    private static final Object LC2H_OCCUPIED_LOCK = new Object();
    @Unique
    private static volatile boolean LC2H_OCCUPIED_READY = false;
    @Unique
    private static volatile Object LC2H_OCCUPIED_READY_KEY = null;
    @Unique
    private static volatile boolean LC2H_PREDEFINED_READY = false;
    @Unique
    private static volatile Object LC2H_PREDEFINED_READY_KEY = null;
    @Unique
    private static final Object LC2H_PREDEFINED_CITY_LOCK = new Object();
    @Unique
    private static volatile boolean LC2H_PREDEFINED_CITY_READY = false;

    @Shadow private static CityStyle getCityStyleInt(ChunkCoord coord, IDimensionInfo provider, LostCityProfile profile) { return null; }
    @Shadow private static Map<ChunkCoord, PredefinedCity> predefinedCityMap;
    @Shadow private static Map<ChunkCoord, PredefinedBuilding> predefinedBuildingMap;
    @Shadow private static Map<ChunkCoord, PredefinedStreet> predefinedStreetMap;
    @Shadow private static Map<ChunkCoord, ?> OCCUPIED_CHUNKS_BUILDING;
    @Shadow private static Map<ChunkCoord, PredefinedStreet> OCCUPIED_CHUNKS_STREET;

    @Invoker("calculateOccupied")
    private static void lc2h$calculateOccupied(IDimensionInfo provider) { throw new AssertionError(); }

    @Invoker("calculateMap")
    private static void lc2h$calculateMap(CommonLevelAccessor level) { throw new AssertionError(); }

    /**
     * This removes the synchronized computeIfAbsent and employs a concurrent cache. It allows parallel warmup without a global City lock.
     *
     * @author Admany
     * @reason Allow parallel warmup without global City lock
     */
    @Overwrite
    public static CityStyle getCityStyle(ChunkCoord coord, IDimensionInfo provider, LostCityProfile profile) {
        Object cacheKey = lc2h$cityStyleKey(coord, provider, profile);
        CityStyle cached = LC2H_CITY_STYLE_CACHE.get(cacheKey);
        if (cached != null) {
            LostCitiesCacheBudgetManager.recordAccess(LC2H_CITY_STYLE_BUDGET, cacheKey);
            return cached;
        }
        if (!PlannerHotPath.isActive()) {
            CityStyle disk = LostCitiesCacheBridge.getDisk("city_style", cacheKey, CityStyle.class);
            if (disk != null) {
                CityStyle prev = LC2H_CITY_STYLE_CACHE.putIfAbsent(cacheKey, disk);
                LostCitiesCacheBudgetManager.recordPut(LC2H_CITY_STYLE_BUDGET, cacheKey, LC2H_CITY_STYLE_BUDGET.defaultEntryBytes(), prev == null);
                return prev != null ? prev : disk;
            }
        }
        CityStyle style = getCityStyleInt(coord, provider, profile);
        CityStyle prev = LC2H_CITY_STYLE_CACHE.putIfAbsent(cacheKey, style);
        LostCitiesCacheBudgetManager.recordPut(LC2H_CITY_STYLE_BUDGET, cacheKey, LC2H_CITY_STYLE_BUDGET.defaultEntryBytes(), prev == null);
        if (!PlannerHotPath.isActive()) {
            LostCitiesCacheBridge.putDisk("city_style", cacheKey, style);
        }
        return style;
    }

    /**
     * @author Admany
     * @reason Remove per-query Random/AtomicLong allocation from the city-center hot path
     */
    @Overwrite
    public static boolean isCityCenter(ChunkCoord coord, IDimensionInfo provider) {
        if (coord == null || provider == null) {
            return false;
        }
        LostCityProfile activeProfile = provider.getProfile();
        if (!activeProfile.isSpace() && !activeProfile.isSpheres()) {
            PredefinedCityCoordinateIndex predefined = lc2h$predefinedCityIndex(provider, coord);
            return lc2h$isNormalCityCenterAt(
                coord.chunkX(), coord.chunkZ(), activeProfile, predefined);
        }
        Object dimensionKey = lc2h$dimensionKey(provider);
        long packedKey = lc2h$packedChunkKey(coord.chunkX(), coord.chunkZ());
        ConcurrentHashMap<Long, Boolean> dimensionCache = LC2H_CITY_CENTER_CACHE.get(dimensionKey);
        Boolean cached = dimensionCache == null ? null : dimensionCache.get(packedKey);
        if (cached != null) {
            return cached;
        }
        boolean result = lc2h$isCityCenterUncached(coord, provider);
        dimensionCache = LC2H_CITY_CENTER_CACHE.computeIfAbsent(dimensionKey, ignored -> new ConcurrentHashMap<>());
        Boolean prev = dimensionCache.putIfAbsent(packedKey, result);
        LostCitiesCacheBudgetManager.recordPut(
            LC2H_CITY_CENTER_BUDGET,
            new java.util.AbstractMap.SimpleImmutableEntry<>(dimensionKey, packedKey),
            LC2H_CITY_CENTER_BUDGET.defaultEntryBytes(),
            prev == null
        );
        return prev != null ? prev : result;
    }

    /**
     * @author Admany
     * @reason Remove per-query Random/AtomicLong allocation from city radius lookups
     */
    @Overwrite
    public static float getCityRadius(ChunkCoord coord, IDimensionInfo provider) {
        if (coord == null || provider == null) {
            return 0.0F;
        }
        LostCityProfile activeProfile = provider.getProfile();
        if (!activeProfile.isSpace() && !activeProfile.isSpheres()) {
            PredefinedCityCoordinateIndex predefined = lc2h$predefinedCityIndex(provider, coord);
            return lc2h$getNormalCityRadiusAt(
                coord.chunkX(), coord.chunkZ(), activeProfile, predefined);
        }
        Object dimensionKey = lc2h$dimensionKey(provider);
        long packedKey = lc2h$packedChunkKey(coord.chunkX(), coord.chunkZ());
        ConcurrentHashMap<Long, Float> dimensionCache = LC2H_CITY_RADIUS_CACHE.get(dimensionKey);
        Float cached = dimensionCache == null ? null : dimensionCache.get(packedKey);
        if (cached != null) {
            return cached;
        }
        float result = lc2h$getCityRadiusUncached(coord, provider);
        dimensionCache = LC2H_CITY_RADIUS_CACHE.computeIfAbsent(dimensionKey, ignored -> new ConcurrentHashMap<>());
        Float prev = dimensionCache.putIfAbsent(packedKey, result);
        LostCitiesCacheBudgetManager.recordPut(
            LC2H_CITY_RADIUS_BUDGET,
            new java.util.AbstractMap.SimpleImmutableEntry<>(dimensionKey, packedKey),
            LC2H_CITY_RADIUS_BUDGET.defaultEntryBytes(),
            prev == null
        );
        return prev != null ? prev : result;
    }

    /**
     * @author Admany
     * @reason Keep LC semantics while using the cheaper city-center/radius path in the inner search loop
     */
    @Overwrite
    public static float getCityFactor(ChunkCoord coord, IDimensionInfo provider, LostCityProfile profile) {
        if (coord == null || provider == null || profile == null) {
            return 0.0F;
        }
        CommonLevelAccessor world = provider.getWorld();
        PredefinedBuilding building = City.getPredefinedBuildingAtTopLeft(world, coord);
        if (building != null) {
            return 1.0F;
        }
        PredefinedStreet street = City.getPredefinedStreet(world, coord);
        if (street != null) {
            return 1.0F;
        }
        building = City.getPredefinedBuildingAtTopLeft(world, new ChunkCoord(provider.getType(), coord.chunkX() - 1, coord.chunkZ()));
        if (building != null && building.multi()) {
            return 1.0F;
        }
        building = City.getPredefinedBuildingAtTopLeft(world, new ChunkCoord(provider.getType(), coord.chunkX() - 1, coord.chunkZ() - 1));
        if (building != null && building.multi()) {
            return 1.0F;
        }
        building = City.getPredefinedBuildingAtTopLeft(world, new ChunkCoord(provider.getType(), coord.chunkX(), coord.chunkZ() - 1));
        if (building != null && building.multi()) {
            return 1.0F;
        }

        int chunkX = coord.chunkX();
        int chunkZ = coord.chunkZ();
        float factor = 0.0F;
        if (profile.CITY_CHANCE < 0.0D) {
            CityRarityMap rarityMap = getCityRarityMap(
                provider.dimension(),
                provider.getSeed(),
                profile.CITY_PERLIN_SCALE,
                profile.CITY_PERLIN_OFFSET,
                profile.CITY_PERLIN_INNERSCALE
            );
            factor = rarityMap.getCityFactor(chunkX, chunkZ);
        } else {
            int radiusChunks = (profile.CITY_MAXRADIUS + 15) / 16;
            ResourceKey<Level> dimension = provider.getType();
            int blockX = chunkX << 4;
            int blockZ = chunkZ << 4;
            if (!profile.isSpace() && !profile.isSpheres()) {
                PredefinedCityCoordinateIndex predefined = lc2h$predefinedCityIndex(provider, coord);
                for (int cx = chunkX - radiusChunks; cx <= chunkX + radiusChunks; cx++) {
                    int dx = (cx - chunkX) << 4;
                    int dx2 = dx * dx;
                    for (int cz = chunkZ - radiusChunks; cz <= chunkZ + radiusChunks; cz++) {
                        if (!lc2h$isNormalCityCenterAt(cx, cz, profile, predefined)) {
                            continue;
                        }
                        float radius = lc2h$getNormalCityRadiusAt(cx, cz, profile, predefined);
                        int dz = (cz - chunkZ) << 4;
                        float sqdist = dx2 + (dz * dz);
                        float radiusSq = radius * radius;
                        if (sqdist < radiusSq) {
                            float dist = (float) Math.sqrt(sqdist);
                            factor += (radius - dist) / radius;
                        }
                    }
                }
            } else {
                // Sphere profiles can select the outside profile per candidate.
                // Keep the exact Lost Cities compatibility path for them.
                for (int cx = chunkX - radiusChunks; cx <= chunkX + radiusChunks; cx++) {
                    int dx = (cx * 16) - blockX;
                    int dx2 = dx * dx;
                    for (int cz = chunkZ - radiusChunks; cz <= chunkZ + radiusChunks; cz++) {
                        ChunkCoord candidate = new ChunkCoord(dimension, cx, cz);
                        LostCityProfile candidateProfile = BuildingInfo.getProfile(candidate, provider);
                        if (candidateProfile != profile || !isCityCenter(candidate, provider)) {
                            continue;
                        }
                        float radius = getCityRadius(candidate, provider);
                        int dz = (cz * 16) - blockZ;
                        float sqdist = dx2 + (dz * dz);
                        float radiusSq = radius * radius;
                        if (sqdist < radiusSq) {
                            float dist = (float) Math.sqrt(sqdist);
                            factor += (radius - dist) / radius;
                        }
                    }
                }
            }
        }

        if (factor > 0.0001D && world != null) {
            ChunkHeightmap heightmap = provider.getHeightmap(coord);
            if (heightmap == null) {
                return 0.0F;
            }
            int height = heightmap.getHeight();
            if (height < profile.CITY_MINHEIGHT || height > profile.CITY_MAXHEIGHT) {
                return 0.0F;
            }
        }
        if (factor > 0.0001D && world != null) {
            WorldStyle worldStyle = (WorldStyle) AssetRegistries.WORLDSTYLES.get(world, profile.getWorldStyle());
            if (worldStyle != null) {
                factor *= worldStyle.getCityChanceMultiplier(provider, coord);
            }
        }
        if (profile.CITY_SPAWN_DISTANCE2 > 0) {
            float dist = (float) Math.sqrt(((chunkX << 4) * (chunkX << 4)) + ((chunkZ << 4) * (chunkZ << 4)));
            double multiplier;
            if (dist <= profile.CITY_SPAWN_DISTANCE1) {
                multiplier = profile.CITY_SPAWN_MULTIPLIER1;
            } else if (dist >= profile.CITY_SPAWN_DISTANCE2) {
                multiplier = profile.CITY_SPAWN_MULTIPLIER2;
            } else {
                float pct = (dist - profile.CITY_SPAWN_DISTANCE1) / (float) (profile.CITY_SPAWN_DISTANCE2 - profile.CITY_SPAWN_DISTANCE1);
                multiplier = profile.CITY_SPAWN_MULTIPLIER1 + (pct * (profile.CITY_SPAWN_MULTIPLIER2 - profile.CITY_SPAWN_MULTIPLIER1));
            }
            factor *= (float) multiplier;
        }
        return Math.min(1.0F, Math.max(0.0F, factor));
    }

    /**
     * This makes the CityRarityMap cache safe for parallel worldgen.
     *
     * @author Admany
     * @reason Prevent HashMap corruption and cross-chunk nondeterminism
     */
    @Overwrite
    public static CityRarityMap getCityRarityMap(ResourceKey<Level> level, long seed, double scale, double offset, double innerScale) {
        Object cacheKey = lc2h$cityRarityKey(level, seed, scale, offset, innerScale);
        return LC2H_CITY_RARITY_CACHE.computeIfAbsent(cacheKey, k -> new CityRarityMap(seed, scale, offset, innerScale));
    }

    @Inject(method = "cleanCache", at = @At("HEAD"))
    private static void lc2h$clearLc2hCaches(CallbackInfo ci) {
        LC2H_CITY_STYLE_CACHE.clear();
        LC2H_CITY_CENTER_CACHE.clear();
        LC2H_CITY_RADIUS_CACHE.clear();
        LC2H_PROVIDER_SCOPE_KEYS.clear();
        LC2H_PREDEFINED_CITY_INDEX.clear();
        LC2H_CITY_RARITY_CACHE.clear();
        LostCitiesCacheBudgetManager.clear(LC2H_CITY_STYLE_BUDGET);
        LostCitiesCacheBudgetManager.clear(LC2H_CITY_CENTER_BUDGET);
        LostCitiesCacheBudgetManager.clear(LC2H_CITY_RADIUS_BUDGET);
        LC2H_OCCUPIED_READY = false;
        LC2H_OCCUPIED_READY_KEY = null;
        LC2H_PREDEFINED_READY = false;
        LC2H_PREDEFINED_READY_KEY = null;
        LC2H_PREDEFINED_CITY_READY = false;
    }

    /**
     * This makes predefined city map initialization safe for parallel access.
     *
     * @author Admany
     * @reason Prevent NPE when cache is cleared during async generation
     */
    @Overwrite
    public static PredefinedCity getPredefinedCity(CommonLevelAccessor level, ChunkCoord coord) {
        if (level == null || coord == null) {
            return null;
        }
        AssetRegistries.loadPredefinedStuff(level);
        Map<ChunkCoord, PredefinedCity> map = predefinedCityMap;
        if (map == null || !LC2H_PREDEFINED_CITY_READY) {
            synchronized (LC2H_PREDEFINED_CITY_LOCK) {
                map = predefinedCityMap;
                if (map == null || !LC2H_PREDEFINED_CITY_READY) {
                    java.util.HashMap<ChunkCoord, PredefinedCity> created = new java.util.HashMap<>();
                    for (PredefinedCity city : AssetRegistries.PREDEFINED_CITIES.getIterable()) {
                        if (city != null) {
                            created.put(new ChunkCoord(city.getDimension(), city.getChunkX(), city.getChunkZ()), city);
                        }
                    }
                    predefinedCityMap = created;
                    LC2H_PREDEFINED_CITY_READY = true;
                    map = created;
                }
            }
        }
        if (map == null || map.isEmpty()) {
            return null;
        }
        return map.get(coord);
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
        Object readyKey = occupiedKey(provider);
        if (readyKey == null) {
            return;
        }
        if (LC2H_OCCUPIED_READY && readyKey.equals(LC2H_OCCUPIED_READY_KEY)) {
            return;
        }
        if (provider == null || provider.getWorld() == null) {
            return;
        }
        synchronized (LC2H_OCCUPIED_LOCK) {
            if (LC2H_OCCUPIED_READY && readyKey.equals(LC2H_OCCUPIED_READY_KEY)) {
                return;
            }
            lc2h$calculateOccupied(provider);
            LC2H_OCCUPIED_READY = OCCUPIED_CHUNKS_BUILDING != null && OCCUPIED_CHUNKS_STREET != null;
            LC2H_OCCUPIED_READY_KEY = LC2H_OCCUPIED_READY ? readyKey : null;
        }
    }

    private static void ensurePredefinedReady(CommonLevelAccessor level) {
        Object readyKey = predefinedKey(level);
        if (readyKey == null) {
            return;
        }
        if (LC2H_PREDEFINED_READY && readyKey.equals(LC2H_PREDEFINED_READY_KEY)) {
            return;
        }
        if (level == null) {
            return;
        }
        synchronized (LC2H_OCCUPIED_LOCK) {
            if (LC2H_PREDEFINED_READY && readyKey.equals(LC2H_PREDEFINED_READY_KEY)) {
                return;
            }
            lc2h$calculateMap(level);
            LC2H_PREDEFINED_READY = predefinedBuildingMap != null && predefinedStreetMap != null;
            LC2H_PREDEFINED_READY_KEY = LC2H_PREDEFINED_READY ? readyKey : null;
        }
    }

    @Unique
    private static Object occupiedKey(IDimensionInfo provider) {
        if (provider == null || provider.getWorld() == null) {
            return null;
        }
        // WorldGenLevel in this mapping does not expose dimension(); use stable object identity.
        return provider.getWorld();
    }

    @Unique
    private static Object predefinedKey(CommonLevelAccessor level) {
        if (level == null) {
            return null;
        }
        // CommonLevelAccessor in this mapping does not expose dimension(); identity is sufficient.
        return level;
    }

    @Unique
    private static boolean lc2h$isCityCenterUncached(ChunkCoord coord, IDimensionInfo provider) {
        PredefinedCity predefined = lc2h$predefinedCityIndex(provider, coord)
            .get(coord.chunkX(), coord.chunkZ());
        if (predefined != null) {
            return true;
        }
        int chunkX = coord.chunkX();
        int chunkZ = coord.chunkZ();
        long seed = ((long) chunkZ * 797003437L) + ((long) chunkX * 295075153L);
        LostCityProfile activeProfile = provider.getProfile();
        if (activeProfile.isSpace() || activeProfile.isSpheres()) {
            CitySphere sphere = CitySphere.getCitySphere(coord, provider);
            if (!sphere.isEnabled()) {
                return lc2h$firstRandomDouble(seed) < provider.getOutsideProfile().CITY_CHANCE;
            }
            ChunkCoord center = sphere.getCenter();
            return center.chunkX() == chunkX
                && center.chunkZ() == chunkZ
                && lc2h$firstRandomDouble(seed) < activeProfile.CITY_CHANCE;
        }
        return lc2h$firstRandomDouble(seed) < activeProfile.CITY_CHANCE;
    }

    @Unique
    private static float lc2h$getCityRadiusUncached(ChunkCoord coord, IDimensionInfo provider) {
        PredefinedCity predefined = lc2h$predefinedCityIndex(provider, coord)
            .get(coord.chunkX(), coord.chunkZ());
        if (predefined != null) {
            return predefined.getRadius();
        }
        int chunkX = coord.chunkX();
        int chunkZ = coord.chunkZ();
        long seed = ((long) chunkZ * 100001653L) + ((long) chunkX * 295075153L);
        LostCityProfile profile = provider.getProfile();
        int range = profile.CITY_MAXRADIUS - profile.CITY_MINRADIUS;
        if (range < 1) {
            range = 1;
        }
        if (profile.isSpace() || profile.isSpheres()) {
            if (CitySphere.intersectsWithCitySphere(coord, provider)) {
                return profile.CITY_MINRADIUS + lc2h$firstRandomInt(seed, range);
            }
            LostCityProfile outsideProfile = provider.getOutsideProfile();
            int outsideRange = outsideProfile.CITY_MAXRADIUS - outsideProfile.CITY_MINRADIUS;
            if (outsideRange < 1) {
                outsideRange = 1;
            }
            return outsideProfile.CITY_MINRADIUS + lc2h$firstRandomInt(seed, outsideRange);
        }
        return profile.CITY_MINRADIUS + lc2h$firstRandomInt(seed, range);
    }

    @Unique
    private static boolean lc2h$isNormalCityCenterAt(int chunkX,
                                                      int chunkZ,
                                                      LostCityProfile profile,
                                                      PredefinedCityCoordinateIndex predefined) {
        if (predefined.get(chunkX, chunkZ) != null) {
            return true;
        }
        long seed = ((long) chunkZ * 797003437L) + ((long) chunkX * 295075153L);
        return lc2h$firstRandomDouble(seed) < profile.CITY_CHANCE;
    }

    @Unique
    private static float lc2h$getNormalCityRadiusAt(int chunkX,
                                                     int chunkZ,
                                                     LostCityProfile profile,
                                                     PredefinedCityCoordinateIndex predefined) {
        PredefinedCity city = predefined.get(chunkX, chunkZ);
        if (city != null) {
            return city.getRadius();
        }
        long seed = ((long) chunkZ * 100001653L) + ((long) chunkX * 295075153L);
        int range = Math.max(1, profile.CITY_MAXRADIUS - profile.CITY_MINRADIUS);
        return profile.CITY_MINRADIUS + lc2h$firstRandomInt(seed, range);
    }

    @Unique
    private static PredefinedCityCoordinateIndex lc2h$predefinedCityIndex(IDimensionInfo provider,
                                                                          ChunkCoord probe) {
        PredefinedCityCoordinateIndex cached = LC2H_PREDEFINED_CITY_INDEX.get(provider);
        if (cached != null) {
            return cached;
        }

        // This initializes Lost Cities' registry-backed map once. The supplied
        // probe already exists at every caller, so the initialization path
        // introduces no extra hot-loop coordinate allocation.
        City.getPredefinedCity(provider.getWorld(), probe);
        Map<ChunkCoord, PredefinedCity> source = predefinedCityMap;
        PredefinedCityCoordinateIndex created =
            PredefinedCityCoordinateIndex.create(source, provider.getType());
        PredefinedCityCoordinateIndex previous = LC2H_PREDEFINED_CITY_INDEX.putIfAbsent(provider, created);
        return previous != null ? previous : created;
    }

    @Unique
    private static Object lc2h$dimensionKey(IDimensionInfo provider) {
        Object cached = LC2H_PROVIDER_SCOPE_KEYS.get(provider);
        if (cached != null) {
            return cached;
        }
        ResourceKey<Level> type = provider.getType();
        String dimension = type != null && type.location() != null ? type.location().toString() : "unknown";
        LostCityProfile profile = provider.getProfile();
        LostCityProfile outside = provider.getOutsideProfile();
        Object scope = new CityScopeKey(
            dimension,
            lc2h$safeSeed(provider),
            lc2h$profileSignature(profile),
            lc2h$profileSignature(outside)
        );
        Object previous = LC2H_PROVIDER_SCOPE_KEYS.putIfAbsent(provider, scope);
        return previous != null ? previous : scope;
    }

    @Unique
    private record CityScopeKey(String dimension, long seed, String profile, String outsideProfile) {
    }

    @Unique
    private static Object lc2h$cityStyleKey(ChunkCoord coord, IDimensionInfo provider, LostCityProfile profile) {
        String dimension = "unknown";
        if (coord != null && coord.dimension() != null && coord.dimension().location() != null) {
            dimension = coord.dimension().location().toString();
        } else if (provider != null && provider.getType() != null && provider.getType().location() != null) {
            dimension = provider.getType().location().toString();
        }
        int chunkX = coord == null ? Integer.MIN_VALUE : coord.chunkX();
        int chunkZ = coord == null ? Integer.MIN_VALUE : coord.chunkZ();
        return "schema=2|dimension=" + dimension
            + "|seed=" + lc2h$safeSeed(provider)
            + "|profile=" + lc2h$profileSignature(profile != null ? profile : (provider == null ? null : provider.getProfile()))
            + "|chunk=" + chunkX + "," + chunkZ;
    }

    @Unique
    private static Object lc2h$cityRarityKey(ResourceKey<Level> level, long seed, double scale, double offset, double innerScale) {
        String dimension = level != null && level.location() != null ? level.location().toString() : "unknown";
        return "schema=2|dimension=" + dimension
            + "|seed=" + seed
            + "|scale=" + Double.doubleToLongBits(scale)
            + "|offset=" + Double.doubleToLongBits(offset)
            + "|innerScale=" + Double.doubleToLongBits(innerScale);
    }

    @Unique
    private static long lc2h$safeSeed(IDimensionInfo provider) {
        if (provider == null) {
            return Long.MIN_VALUE;
        }
        try {
            return provider.getSeed();
        } catch (Throwable ignored) {
            return Long.MIN_VALUE;
        }
    }

    @Unique
    private static String lc2h$profileSignature(LostCityProfile profile) {
        if (profile == null) {
            return "unknown";
        }
        String name;
        try {
            name = profile.getName();
        } catch (Throwable ignored) {
            name = "unknown";
        }
        String worldStyle;
        try {
            worldStyle = profile.getWorldStyle();
        } catch (Throwable ignored) {
            worldStyle = "unknown";
        }
        return name
            + ":style=" + worldStyle
            + ":chance=" + Double.doubleToLongBits(profile.CITY_CHANCE)
            + ":minR=" + profile.CITY_MINRADIUS
            + ":maxR=" + profile.CITY_MAXRADIUS
            + ":threshold=" + Float.floatToIntBits(profile.CITY_THRESHOLD)
            + ":minH=" + profile.CITY_MINHEIGHT
            + ":maxH=" + profile.CITY_MAXHEIGHT
            + ":perlin=" + Double.doubleToLongBits(profile.CITY_PERLIN_SCALE)
            + "," + Double.doubleToLongBits(profile.CITY_PERLIN_OFFSET)
            + "," + Double.doubleToLongBits(profile.CITY_PERLIN_INNERSCALE)
            + ":altStyle=" + profile.CITY_STYLE_ALTERNATIVE
            + ":styleThreshold=" + Float.floatToIntBits(profile.CITY_STYLE_THRESHOLD);
    }

    @Unique
    private static long lc2h$packedChunkKey(int chunkX, int chunkZ) {
        /*
         * Do not box the raw packed coordinate directly. Long.hashCode() folds
         * the upper and lower halves together, so raw (x,z) keys hash to
         * effectively x^z. A square worldgen window then puts whole diagonals
         * in the same ConcurrentHashMap bucket and eventually treeifies it.
         *
         * SplitMix64's finalizer is a bijection over all 64-bit values: it
         * preserves the collision-free packed-coordinate identity while
         * thoroughly mixing the bits before Long.hashCode() sees them. The
         * same mixed value is also used by the cache-budget key, fixing that
         * map's identical diagonal-collision pattern without another object.
         */
        long key = ((long) chunkX << 32) ^ (chunkZ & 0xffffffffL);
        key = (key ^ (key >>> 30)) * 0xbf58476d1ce4e5b9L;
        key = (key ^ (key >>> 27)) * 0x94d049bb133111ebL;
        return key ^ (key >>> 31);
    }

    @Unique
    @SuppressWarnings("unchecked")
    private static boolean lc2h$evictCityCenter(Object key) {
        if (!(key instanceof Map.Entry<?, ?> entry) || !(entry.getValue() instanceof Long packedChunk)) {
            return false;
        }
        Object dimensionKey = entry.getKey();
        ConcurrentHashMap<Long, Boolean> dimensionCache = LC2H_CITY_CENTER_CACHE.get(dimensionKey);
        if (dimensionCache == null) {
            return false;
        }
        boolean removed = dimensionCache.remove(packedChunk) != null;
        if (removed && dimensionCache.isEmpty()) {
            LC2H_CITY_CENTER_CACHE.remove(dimensionKey, dimensionCache);
        }
        return removed;
    }

    @Unique
    @SuppressWarnings("unchecked")
    private static boolean lc2h$evictCityRadius(Object key) {
        if (!(key instanceof Map.Entry<?, ?> entry) || !(entry.getValue() instanceof Long packedChunk)) {
            return false;
        }
        Object dimensionKey = entry.getKey();
        ConcurrentHashMap<Long, Float> dimensionCache = LC2H_CITY_RADIUS_CACHE.get(dimensionKey);
        if (dimensionCache == null) {
            return false;
        }
        boolean removed = dimensionCache.remove(packedChunk) != null;
        if (removed && dimensionCache.isEmpty()) {
            LC2H_CITY_RADIUS_CACHE.remove(dimensionKey, dimensionCache);
        }
        return removed;
    }

    @Unique
    private static final long LC2H_RANDOM_MULTIPLIER = 0x5DEECE66DL;
    @Unique
    private static final long LC2H_RANDOM_ADDEND = 0xBL;
    @Unique
    private static final long LC2H_RANDOM_MASK = (1L << 48) - 1L;

    @Unique
    private static long lc2h$randomSeed(long seed) {
        return (seed ^ LC2H_RANDOM_MULTIPLIER) & LC2H_RANDOM_MASK;
    }

    @Unique
    private static long lc2h$nextRandomSeed(long state) {
        return (state * LC2H_RANDOM_MULTIPLIER + LC2H_RANDOM_ADDEND) & LC2H_RANDOM_MASK;
    }

    @Unique
    private static double lc2h$firstRandomDouble(long seed) {
        long state = lc2h$randomSeed(seed);
        state = lc2h$nextRandomSeed(state);
        long high = state >>> (48 - 26);
        state = lc2h$nextRandomSeed(state);
        long low = state >>> (48 - 27);
        return ((high << 27) + low) * 0x1.0p-53;
    }

    @Unique
    private static int lc2h$firstRandomInt(long seed, int bound) {
        if (bound <= 1) {
            return 0;
        }
        long state = lc2h$nextRandomSeed(lc2h$randomSeed(seed));
        int bits = (int) (state >>> (48 - 31));
        if ((bound & -bound) == bound) {
            return (int) ((bound * (long) bits) >> 31);
        }
        int value = bits % bound;
        while (bits - value + (bound - 1) < 0) {
            state = lc2h$nextRandomSeed(state);
            bits = (int) (state >>> (48 - 31));
            value = bits % bound;
        }
        return value;
    }
}
