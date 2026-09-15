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
import net.minecraft.world.level.WorldGenLevel;
import org.admany.lc2h.data.cache.LostCitiesCacheBridge;
import org.admany.lc2h.data.cache.LostCitiesCacheBudgetManager;
import org.admany.lc2h.data.cache.NormalCityCenterRadiusCache;
import org.admany.lc2h.data.cache.NormalCityFactorTileCache;
import org.admany.lc2h.worldgen.lostcities.PlannerHotPath;
import org.admany.lc2h.worldgen.lostcities.LostCitiesGuiPreviewGuard;
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
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
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
    /**
     * City factor is a pure function of the loaded dimension, profile and
     * chunk coordinate. Lost Cities calls it from several independent
     * planners, and the terrain shift field can ask for the same 32 by 32
     * window repeatedly while neighbouring regions are built. Keep the
     * result once per lifecycle instead of re-running the radius scan and
     * height/style checks for every consumer.
     */
    @Unique
    private static final ConcurrentHashMap<Object,
        ConcurrentHashMap<LostCityProfile, ConcurrentHashMap<Long, Float>>>
        LC2H_CITY_FACTOR_CACHE = new ConcurrentHashMap<>();
    /** Share one cold factor calculation across overlapping worldgen workers. */
    @Unique
    private static final ConcurrentHashMap<CityFactorKey, CompletableFuture<Float>>
        LC2H_CITY_FACTOR_FLIGHTS = new ConcurrentHashMap<>();
    @Unique
    private static final LostCitiesCacheBudgetManager.CacheGroup LC2H_CITY_FACTOR_BUDGET =
        LostCitiesCacheBudgetManager.register("lc_city_factor", 8, 2048,
            MixinCity::lc2h$evictCityFactor);
    /**
     * Planner callers must not enter Lost Cities' synchronous heightmap
     * builder.  A missing sampled height is treated as unknown for that
     * planner pass and is deliberately not retained in the factor cache.
     * Vanilla Lost Cities callers keep the exact heightmap gate.
     */
    @Unique
    private static final ThreadLocal<Boolean> LC2H_FACTOR_HEIGHT_UNKNOWN =
        ThreadLocal.withInitial(() -> Boolean.FALSE);
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

    /** Uses the concurrent scoped cache so warmup does not hold a global City lock. */
    @Overwrite
    public static CityStyle getCityStyle(ChunkCoord coord, IDimensionInfo provider, LostCityProfile profile) {
        Object cacheKey = lc2h$cityStyleKey(coord, provider, profile);
        CityStyle cached = LC2H_CITY_STYLE_CACHE.get(cacheKey);
        if (cached != null) {
            LostCitiesCacheBudgetManager.recordAccess(LC2H_CITY_STYLE_BUDGET, cacheKey);
            return cached;
        }
        if (!PlannerHotPath.shouldAvoidBlocking()) {
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
        if (!PlannerHotPath.shouldAvoidBlocking()) {
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
            return lc2h$normalCenterRadiusAt(provider, activeProfile, predefined,
                coord.chunkX(), coord.chunkZ()) > 0.0F;
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
            return lc2h$normalCenterRadiusAt(provider, activeProfile, predefined,
                coord.chunkX(), coord.chunkZ());
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
        // Space and sphere profiles can resolve a different profile per
        // candidate coordinate. Keep their original path until a scoped
        // cache can be proven safe for that topology.
        if (profile.isSpace() || profile.isSpheres()) {
            return lc2h$getCityFactorUncached(coord, provider, profile);
        }
        Object cacheScope = LostCitiesGuiPreviewGuard.cacheScope(provider, profile);
        ConcurrentHashMap<LostCityProfile, ConcurrentHashMap<Long, Float>> byProfile =
            LC2H_CITY_FACTOR_CACHE.computeIfAbsent(cacheScope, ignored -> new ConcurrentHashMap<>());
        ConcurrentHashMap<Long, Float> factors =
            byProfile.computeIfAbsent(profile, ignored -> new ConcurrentHashMap<>());
        long packed = lc2h$packedChunkKey(coord.chunkX(), coord.chunkZ());
        Float cached = factors.get(packed);
        if (cached != null) {
            LostCitiesCacheBudgetManager.recordAccess(LC2H_CITY_FACTOR_BUDGET,
                new CityFactorKey(cacheScope, profile, packed));
            return cached;
        }
        CityFactorKey key = new CityFactorKey(cacheScope, profile, packed);
        CompletableFuture<Float> created = new CompletableFuture<>();
        CompletableFuture<Float> existing = LC2H_CITY_FACTOR_FLIGHTS.putIfAbsent(key, created);
        if (existing != null) {
            if (PlannerHotPath.shouldAvoidBlocking()) {
                Float ready = existing.getNow(null);
                if (ready != null) {
                    return ready;
                }
                // A vanilla caller may own an exact heightmap flight. Never
                // park a planner worker behind it. Recompute the cheap radius
                // walk with the non blocking height gate instead.
                LC2H_FACTOR_HEIGHT_UNKNOWN.set(Boolean.TRUE);
                try {
                    return lc2h$getCityFactorUncached(coord, provider, profile);
                } finally {
                    LC2H_FACTOR_HEIGHT_UNKNOWN.remove();
                }
            }
            try {
                return existing.join();
            } catch (CancellationException | CompletionException failure) {
                /* A lifecycle reset can cancel a cold flight. Keep the exact
                 * fallback instead of publishing a stale decision. */
                return lc2h$getCityFactorUncached(coord, provider, profile);
            }
        }
        try {
            LC2H_FACTOR_HEIGHT_UNKNOWN.set(Boolean.FALSE);
            float calculated = lc2h$getCityFactorUncached(coord, provider, profile);
            boolean heightUnknown = Boolean.TRUE.equals(LC2H_FACTOR_HEIGHT_UNKNOWN.get());
            Float previous = factors.putIfAbsent(packed, calculated);
            if (!heightUnknown) {
                LostCitiesCacheBudgetManager.recordPut(LC2H_CITY_FACTOR_BUDGET,
                    key, LC2H_CITY_FACTOR_BUDGET.defaultEntryBytes(), previous == null);
            } else {
                // The planner used a non-blocking height approximation. Do
                // not publish a value which could outlive the sampler's
                // eventual exact height for this coordinate.
                factors.remove(packed, calculated);
            }
            float result = previous != null ? previous : calculated;
            created.complete(result);
            return result;
        } catch (RuntimeException | Error failure) {
            created.completeExceptionally(failure);
            throw failure;
        } finally {
            LC2H_FACTOR_HEIGHT_UNKNOWN.remove();
            LC2H_CITY_FACTOR_FLIGHTS.remove(key, created);
        }
    }

    @Unique
    private static float lc2h$getCityFactorUncached(ChunkCoord coord,
                                                     IDimensionInfo provider,
                                                     LostCityProfile profile) {
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
        if (!profile.isSpace() && !profile.isSpheres()) {
            float factor = NormalCityFactorTileCache.get(provider, profile,
                chunkX, chunkZ, !PlannerHotPath.shouldAvoidBlocking(),
                (originX, originZ, side) -> lc2h$buildNormalFactorTile(
                    provider, profile, originX, originZ, side));
            return lc2h$finishNormalCityFactor(
                coord, provider, profile, world, factor);
        }

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
                        float radius = lc2h$normalCenterRadiusAt(
                            provider, profile, predefined, cx, cz);
                        if (radius <= 0.0F) {
                            continue;
                        }
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
            Integer sampledHeight = null;
            if (PlannerHotPath.shouldAvoidBlocking()) {
                org.admany.lc2h.worldgen.terrain.NaturalHeightSampler.LevelSampler sampler =
                    world instanceof WorldGenLevel genWorld
                        ? org.admany.lc2h.worldgen.terrain.NaturalHeightSampler.forLevel(genWorld)
                        : null;
                if (sampler != null) {
                    sampledHeight = sampler.cachedChunkHeight(chunkX, chunkZ);
                }
                if (sampledHeight == null) {
                    // Never call provider.getHeightmap from a planner worker:
                    // that path constructs NoiseChunk and evaluates the full
                    // vanilla density graph. The bounded natural sampler will
                    // publish the exact value for a later pass.
                    LC2H_FACTOR_HEIGHT_UNKNOWN.set(Boolean.TRUE);
                }
            } else {
                ChunkHeightmap heightmap = provider.getHeightmap(coord);
                if (heightmap == null) {
                    return 0.0F;
                }
                sampledHeight = heightmap.getHeight();
            }
            if (sampledHeight != null
                && (sampledHeight < profile.CITY_MINHEIGHT || sampledHeight > profile.CITY_MAXHEIGHT)) {
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

    @Unique
    private static float lc2h$finishNormalCityFactor(ChunkCoord coord,
                                                      IDimensionInfo provider,
                                                      LostCityProfile profile,
                                                      CommonLevelAccessor world,
                                                      float factor) {
        int chunkX = coord.chunkX();
        int chunkZ = coord.chunkZ();
        if (factor > 0.0001F && world != null) {
            Integer sampledHeight = null;
            if (PlannerHotPath.shouldAvoidBlocking()) {
                org.admany.lc2h.worldgen.terrain.NaturalHeightSampler.LevelSampler sampler =
                    world instanceof WorldGenLevel genWorld
                        ? org.admany.lc2h.worldgen.terrain.NaturalHeightSampler.forLevel(genWorld)
                        : null;
                if (sampler != null) {
                    sampledHeight = sampler.cachedChunkHeight(chunkX, chunkZ);
                }
                if (sampledHeight == null) {
                    LC2H_FACTOR_HEIGHT_UNKNOWN.set(Boolean.TRUE);
                }
            } else {
                ChunkHeightmap heightmap = provider.getHeightmap(coord);
                if (heightmap == null) {
                    return 0.0F;
                }
                sampledHeight = heightmap.getHeight();
            }
            if (sampledHeight != null
                && (sampledHeight < profile.CITY_MINHEIGHT
                || sampledHeight > profile.CITY_MAXHEIGHT)) {
                return 0.0F;
            }
        }
        if (factor > 0.0001F && world != null) {
            WorldStyle worldStyle = (WorldStyle) AssetRegistries.WORLDSTYLES.get(
                world, profile.getWorldStyle());
            if (worldStyle != null) {
                factor *= worldStyle.getCityChanceMultiplier(provider, coord);
            }
        }
        if (profile.CITY_SPAWN_DISTANCE2 > 0) {
            int blockX = chunkX << 4;
            int blockZ = chunkZ << 4;
            float dist = (float) Math.sqrt((blockX * blockX) + (blockZ * blockZ));
            double multiplier;
            if (dist <= profile.CITY_SPAWN_DISTANCE1) {
                multiplier = profile.CITY_SPAWN_MULTIPLIER1;
            } else if (dist >= profile.CITY_SPAWN_DISTANCE2) {
                multiplier = profile.CITY_SPAWN_MULTIPLIER2;
            } else {
                float pct = (dist - profile.CITY_SPAWN_DISTANCE1)
                    / (float) (profile.CITY_SPAWN_DISTANCE2 - profile.CITY_SPAWN_DISTANCE1);
                multiplier = profile.CITY_SPAWN_MULTIPLIER1
                    + pct * (profile.CITY_SPAWN_MULTIPLIER2
                    - profile.CITY_SPAWN_MULTIPLIER1);
            }
            factor *= (float) multiplier;
        }
        return Math.min(1.0F, Math.max(0.0F, factor));
    }

    @Unique
    private static NormalCityFactorTileCache.BuildResult lc2h$buildNormalFactorTile(
            IDimensionInfo provider,
            LostCityProfile profile,
            int originX,
            int originZ,
            int side) {
        CommonLevelAccessor world = provider.getWorld();
        ResourceKey<Level> dimension = provider.getType();
        int cells = side * side;
        float[] factors = new float[cells];

        if (profile.CITY_CHANCE < 0.0D) {
            CityRarityMap rarityMap = getCityRarityMap(
                provider.dimension(), provider.getSeed(), profile.CITY_PERLIN_SCALE,
                profile.CITY_PERLIN_OFFSET, profile.CITY_PERLIN_INNERSCALE);
            for (int localZ = 0; localZ < side; localZ++) {
                for (int localX = 0; localX < side; localX++) {
                    int index = localZ * side + localX;
                    factors[index] = rarityMap.getCityFactor(
                        originX + localX, originZ + localZ);
                }
            }
        } else {
            int radiusChunks = (profile.CITY_MAXRADIUS + 15) / 16;
            ChunkCoord probe = new ChunkCoord(dimension, originX, originZ);
            PredefinedCityCoordinateIndex predefined = lc2h$predefinedCityIndex(provider, probe);
            int endX = originX + side - 1;
            int endZ = originZ + side - 1;
            for (int centerZ = originZ - radiusChunks;
                 centerZ <= endZ + radiusChunks; centerZ++) {
                for (int centerX = originX - radiusChunks;
                     centerX <= endX + radiusChunks; centerX++) {
                    float radius = lc2h$normalCenterRadiusAt(
                        provider, profile, predefined, centerX, centerZ);
                    if (radius <= 0.0F) {
                        continue;
                    }
                    float radiusSq = radius * radius;
                    int minX = Math.max(originX, centerX - radiusChunks);
                    int maxX = Math.min(endX, centerX + radiusChunks);
                    int minZ = Math.max(originZ, centerZ - radiusChunks);
                    int maxZ = Math.min(endZ, centerZ + radiusChunks);
                    for (int chunkZ = minZ; chunkZ <= maxZ; chunkZ++) {
                        int dz = (centerZ - chunkZ) << 4;
                        int dz2 = dz * dz;
                        int row = (chunkZ - originZ) * side;
                        for (int chunkX = minX; chunkX <= maxX; chunkX++) {
                            int index = row + chunkX - originX;
                            int dx = (centerX - chunkX) << 4;
                            float sqdist = (dx * dx) + dz2;
                            if (sqdist < radiusSq) {
                                factors[index] += (radius - (float) Math.sqrt(sqdist)) / radius;
                            }
                        }
                    }
                }
            }
        }
        return new NormalCityFactorTileCache.BuildResult(factors, true);
    }

    /** Keeps the rarity map safe during parallel worldgen. */
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
        NormalCityCenterRadiusCache.clear();
        NormalCityFactorTileCache.clear();
        LC2H_CITY_FACTOR_CACHE.clear();
        LC2H_CITY_FACTOR_FLIGHTS.values().forEach(flight -> flight.cancel(false));
        LC2H_CITY_FACTOR_FLIGHTS.clear();
        LC2H_PROVIDER_SCOPE_KEYS.clear();
        LC2H_PREDEFINED_CITY_INDEX.clear();
        LC2H_CITY_RARITY_CACHE.clear();
        LostCitiesCacheBudgetManager.clear(LC2H_CITY_STYLE_BUDGET);
        LostCitiesCacheBudgetManager.clear(LC2H_CITY_CENTER_BUDGET);
        LostCitiesCacheBudgetManager.clear(LC2H_CITY_RADIUS_BUDGET);
        LostCitiesCacheBudgetManager.clear(LC2H_CITY_FACTOR_BUDGET);
        LC2H_OCCUPIED_READY = false;
        LC2H_OCCUPIED_READY_KEY = null;
        LC2H_PREDEFINED_READY = false;
        LC2H_PREDEFINED_READY_KEY = null;
        LC2H_PREDEFINED_CITY_READY = false;
    }

    /** Keeps predefined city map initialization safe during async generation. */
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
        // This WorldGenLevel mapping has no dimension accessor. Use object identity.
        return provider.getWorld();
    }

    @Unique
    private static Object predefinedKey(CommonLevelAccessor level) {
        if (level == null) {
            return null;
        }
        // This accessor has no dimension method. Object identity is enough here.
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
    private static float lc2h$normalCenterRadiusAt(IDimensionInfo provider,
                                                    LostCityProfile profile,
                                                    PredefinedCityCoordinateIndex predefined,
                                                    int chunkX,
                                                    int chunkZ) {
        float cached = NormalCityCenterRadiusCache.get(provider, profile, chunkX, chunkZ);
        if (!Float.isNaN(cached)) {
            return cached;
        }

        float radius = lc2h$isNormalCityCenterAt(chunkX, chunkZ, profile, predefined)
            ? lc2h$getNormalCityRadiusAt(chunkX, chunkZ, profile, predefined)
            : 0.0F;
        return NormalCityCenterRadiusCache.publish(provider, profile, chunkX, chunkZ, radius);
    }

    @Unique
    private static PredefinedCityCoordinateIndex lc2h$predefinedCityIndex(IDimensionInfo provider,
                                                                          ChunkCoord probe) {
        PredefinedCityCoordinateIndex cached = LC2H_PREDEFINED_CITY_INDEX.get(provider);
        if (cached != null) {
            return cached;
        }

        // Initialize LC's registry map once. The caller already has the probe,
        // so this does not add a coordinate allocation to the hot loop.
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
    private static boolean lc2h$evictCityFactor(Object key) {
        if (!(key instanceof CityFactorKey factorKey)) {
            return false;
        }
        ConcurrentHashMap<LostCityProfile, ConcurrentHashMap<Long, Float>> byProfile =
            LC2H_CITY_FACTOR_CACHE.get(factorKey.scope());
        if (byProfile == null) {
            return false;
        }
        ConcurrentHashMap<Long, Float> factors = byProfile.get(factorKey.profile());
        if (factors == null) {
            return false;
        }
        boolean removed = factors.remove(factorKey.packed()) != null;
        if (removed && factors.isEmpty()) {
            byProfile.remove(factorKey.profile(), factors);
        }
        if (byProfile.isEmpty()) {
            LC2H_CITY_FACTOR_CACHE.remove(factorKey.scope(), byProfile);
        }
        return removed;
    }

    @Unique
    private record CityFactorKey(Object scope,
                                 LostCityProfile profile,
                                 long packed) {
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
