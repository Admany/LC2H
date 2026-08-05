package org.admany.lc2h.mixin.lostcities.building;

import mcjty.lostcities.api.LostChunkCharacteristics;
import mcjty.lostcities.api.MultiPos;
import mcjty.lostcities.api.ILostCityBuilding;
import mcjty.lostcities.config.LostCityProfile;
import mcjty.lostcities.setup.Config;
import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.ChunkHeightmap;
import mcjty.lostcities.worldgen.IDimensionInfo;
import mcjty.lostcities.worldgen.lost.BuildingInfo;
import mcjty.lostcities.worldgen.lost.City;
import mcjty.lostcities.worldgen.lost.cityassets.CityStyle;
import mcjty.lostcities.worldgen.lost.cityassets.AssetRegistries;
import mcjty.lostcities.worldgen.lost.cityassets.Building;
import mcjty.lostcities.worldgen.lost.cityassets.ConditionContext;
import mcjty.lostcities.worldgen.lost.regassets.data.PredefinedBuilding;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.CommonLevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.WorldGenLevel;
import org.admany.lc2h.data.cache.LostCitiesCacheBridge;
import org.admany.lc2h.data.cache.LostCitiesCacheBudgetManager;
import org.admany.lc2h.data.cache.BuildingInfoCacheScope;
import org.admany.lc2h.dev.diagnostics.BuildingInfoDiagnostics;
import org.admany.lc2h.worldgen.async.planner.AsyncMultiChunkPlanner;
import org.admany.lc2h.worldgen.MountainCityReservationPlanner;
import org.admany.lc2h.worldgen.lostcities.ChunkRoleProbe;
import org.admany.lc2h.worldgen.lostcities.MultiChunkBoundaryRegistry;
import org.admany.lc2h.worldgen.lostcities.PlannerHotPath;
import org.objectweb.asm.Opcodes;
import org.admany.lc2h.dev.diagnostics.ChunkGenTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Random;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Mixin(value = BuildingInfo.class, remap = false)
public abstract class MixinBuildingInfo {

    private static final ConcurrentMap<String, Integer> LC2H_CITY_REGION_LEVEL_CACHE = new ConcurrentHashMap<>();
    private static final ThreadLocal<Boolean> LC2H_CITY_RAW_COMPUTE_FLAG = ThreadLocal.withInitial(() -> Boolean.FALSE);
    // Every world/provider gets an isolated cache. ChunkCoord contains a dimension,
    // but not a seed, datapack epoch, or server lifecycle identity, so a single
    // global map can leak data between worlds that reuse the same coordinates.
    private static final ConcurrentMap<IDimensionInfo, BuildingInfoCacheScope> LC2H_SCOPES = new ConcurrentHashMap<>();
    private static final BuildingInfoCacheScope LC2H_FALLBACK_SCOPE = new BuildingInfoCacheScope();
    @Unique
    private static final LostCitiesCacheBudgetManager.CacheGroup LC2H_CITY_INFO_BUDGET =
        LostCitiesCacheBudgetManager.register("lc_city_info", 256, 256, MixinBuildingInfo::lc2h$evictCityInfo);
    @Unique
    private static final LostCitiesCacheBudgetManager.CacheGroup LC2H_BUILDING_INFO_BUDGET =
        LostCitiesCacheBudgetManager.register("lc_building_info", 8192, 256, MixinBuildingInfo::lc2h$evictBuildingInfo);
    @Unique
    private static final LostCitiesCacheBudgetManager.CacheGroup LC2H_CITY_LEVEL_BUDGET =
        LostCitiesCacheBudgetManager.register("lc_city_level", 64, 512, MixinBuildingInfo::lc2h$evictCityLevel);
    @Unique
    private static final LostCitiesCacheBudgetManager.CacheGroup LC2H_CITY_RAW_BUDGET =
        LostCitiesCacheBudgetManager.register("lc_city_raw", 32, 512, MixinBuildingInfo::lc2h$evictCityRaw);
    @Unique
    private static final LostCitiesCacheBudgetManager.CacheGroup LC2H_HIGHWAY_BUDGET =
        LostCitiesCacheBudgetManager.register("lc_building_highway", 32, 256, MixinBuildingInfo::lc2h$evictHighway);
    @Unique
    private static final LostCitiesCacheBudgetManager.CacheGroup LC2H_MULTI_HEIGHT_STATS_BUDGET =
        LostCitiesCacheBudgetManager.register("lc_multi_height_stats", 64, 1024, MixinBuildingInfo::lc2h$evictMultiHeightStats);
    @Unique
    private static final LostCitiesCacheBudgetManager.CacheGroup LC2H_MULTI_BOUNDARY_BUDGET =
        LostCitiesCacheBudgetManager.register("lc_multi_boundary", 64, 1024, MixinBuildingInfo::lc2h$evictMultiBoundary);
    @Unique
    private static final String LC2H_CITY_LEVEL_DISK_NAMESPACE = "city_level_v4";
    @Unique
    private static final String LC2H_CITY_INFO_DISK_NAMESPACE = "city_info_v2_boundary";
    @Unique
    private static final String LC2H_CITY_RAW_DISK_NAMESPACE = "city_raw_v3_compact_mountain_envelope";
    @Unique
    private static final int LC2H_CITY_REGION_MAX_MULTIS = Math.max(1, Integer.getInteger("lc2h.cityRegion.maxMultis", 8));
    @Unique
    private static final int LC2H_CITY_REGION_MAX_LEVEL_DELTA = Math.max(0, Integer.getInteger("lc2h.cityRegion.maxLevelDelta", 1));
    @Unique
    private static final int LC2H_CITY_REGION_MAX_HOPS = Math.max(0, Integer.getInteger("lc2h.cityRegion.maxHops", 1));
    @Unique
    private static final int LC2H_CITY_REGION_MAX_LOCAL_DELTA = Math.max(0, Integer.getInteger("lc2h.cityRegion.maxLocalDelta", 1));

    @Unique
    private static BuildingInfoCacheScope lc2h$scope(IDimensionInfo provider) {
        return provider == null ? LC2H_FALLBACK_SCOPE : LC2H_SCOPES.computeIfAbsent(provider, ignored -> new BuildingInfoCacheScope());
    }

    @Unique
    private static boolean lc2h$evictCityInfo(Object key) {
        if (!(key instanceof ChunkCoord coord)) {
            return false;
        }
        boolean evicted = LC2H_FALLBACK_SCOPE.cityInfo.remove(coord) != null;
        return LC2H_SCOPES.values().stream().map(scope -> scope.cityInfo.remove(coord)).anyMatch(Objects::nonNull) || evicted;
    }

    @Unique
    private static boolean lc2h$evictBuildingInfo(Object key) {
        if (!(key instanceof ChunkCoord coord)) {
            return false;
        }
        boolean evicted = LC2H_FALLBACK_SCOPE.buildingInfo.remove(coord) != null;
        return LC2H_SCOPES.values().stream().map(scope -> scope.buildingInfo.remove(coord)).anyMatch(Objects::nonNull) || evicted;
    }

    @Unique
    private static boolean lc2h$evictCityLevel(Object key) {
        if (!(key instanceof ChunkCoord coord)) {
            return false;
        }
        boolean evicted = LC2H_FALLBACK_SCOPE.cityLevel.remove(coord) != null;
        return LC2H_SCOPES.values().stream().map(scope -> scope.cityLevel.remove(coord)).anyMatch(Objects::nonNull) || evicted;
    }

    @Unique
    private static boolean lc2h$evictCityRaw(Object key) {
        if (!(key instanceof ChunkCoord coord)) {
            return false;
        }
        boolean evicted = LC2H_FALLBACK_SCOPE.cityRaw.remove(coord) != null;
        return LC2H_SCOPES.values().stream().map(scope -> scope.cityRaw.remove(coord)).anyMatch(Objects::nonNull) || evicted;
    }

    @Unique
    private static boolean lc2h$evictHighway(Object key) {
        if (!(key instanceof ChunkCoord coord)) {
            return false;
        }
        boolean evicted = LC2H_FALLBACK_SCOPE.highway.remove(coord) != null;
        return LC2H_SCOPES.values().stream().map(scope -> scope.highway.remove(coord)).anyMatch(Objects::nonNull) || evicted;
    }

    @Unique
    private static boolean lc2h$evictMultiHeightStats(Object key) {
        if (!(key instanceof ChunkCoord coord)) {
            return false;
        }
        boolean evicted = LC2H_FALLBACK_SCOPE.multiHeightStats.remove(coord) != null;
        return LC2H_SCOPES.values().stream().map(scope -> scope.multiHeightStats.remove(coord)).anyMatch(Objects::nonNull) || evicted;
    }

    @Unique
    private static boolean lc2h$evictMultiBoundary(Object key) {
        if (!(key instanceof Map.Entry<?, ?> entry)) {
            return false;
        }
        boolean evicted = LC2H_FALLBACK_SCOPE.multiBoundary.remove(entry) != null;
        return LC2H_SCOPES.values().stream().map(scope -> scope.multiBoundary.remove(entry)).anyMatch(Objects::nonNull) || evicted;
    }

    @Unique
    private static LostChunkCharacteristics lc2h$rememberCharacteristics(ChunkCoord coord, LostChunkCharacteristics characteristics) {
        if (coord != null && characteristics != null) {
            ChunkRoleProbe.rememberCharacteristics(coord, characteristics);
        }
        return characteristics;
    }

    @Shadow public ChunkCoord coord;
    @Shadow public IDimensionInfo provider;
    @Shadow public LostCityProfile profile;
    @Shadow public ILostCityBuilding buildingType;
    @Shadow private int floors;
    @Shadow public int cellars;
    @Shadow public int cityLevel;

    /**
     * This is a Lost Cities bugfix. Some CityStyles specify a minimum cellar count, and vanilla Lost Cities can apply that after clamping to a building's maxcellars, effectively overriding the building constraint. This causes buildings with maxcellars=0 to still generate cellars, leading to crashes when selecting cellar parts since none exist. We ensure building maxcellars is always respected over CityStyle minimum cellars.
     *
     * @author Admany
     * @reason Ensure building maxcellars is always respected over CityStyle min cellars.
     */
    @Overwrite
    private int getMaxcellars(CityStyle cs) {
        int maxcellars = profile.BUILDING_MAXCELLARS + cityLevel;

        if (buildingType == null) {
            if (cs.getMaxCellarCount() != null) {
                maxcellars = Math.min(maxcellars, cs.getMaxCellarCount());
            }
            if (cs.getMinCellarCount() != null) {
                maxcellars = Math.max(maxcellars, cs.getMinCellarCount());
            }
            return maxcellars;
        }

        // Keep Lost Cities' override behavior intact.
        if (buildingType.getMaxCellars() != -1 && buildingType.getOverrideFloors()) {
            return buildingType.getMaxCellars();
        }
        if (buildingType.getMinCellars() != -1 && buildingType.getOverrideFloors()) {
            return buildingType.getMinCellars();
        }

        // Apply CityStyle constraints first...
        if (cs.getMaxCellarCount() != null) {
            maxcellars = Math.min(maxcellars, cs.getMaxCellarCount());
        }
        if (cs.getMinCellarCount() != null) {
            maxcellars = Math.max(maxcellars, cs.getMinCellarCount());
        }

        // ...then clamp to building constraints last so buildings can't be forced into impossible cellars.
        if (buildingType.getMaxCellars() != -1) {
            maxcellars = Math.min(maxcellars, buildingType.getMaxCellars());
        }
        if (buildingType.getMinCellars() != -1) {
            maxcellars = Math.max(maxcellars, buildingType.getMinCellars());
        }

        return maxcellars;
    }

    /**
     * This is a Lost Cities bugfix. We ensure building floor constraints are always respected, even when the pack does not set overrideFloors=true. This prevents short buildings and their parts2 decorations from being forced up to the profile minimum floors.
     *
     * @author Admany
     * @reason Respect building min floors regardless of overrideFloors to avoid mis-sized builds.
     */
    @Overwrite
    private int getMinfloors(CityStyle cs) {
        int minfloors = profile.BUILDING_MINFLOORS + 1;
        if (cs.getMinFloorCount() != null) {
            minfloors = Math.max(minfloors, cs.getMinFloorCount());
        }

        if (buildingType != null) {
            int buildingMin = buildingType.getMinFloors();
            if (buildingMin >= 0) {
                minfloors = buildingMin;
            }
            int buildingMax = buildingType.getMaxFloors();
            if (buildingMax >= 0) {
                minfloors = Math.min(minfloors, buildingMax);
            }
        }

        return minfloors;
    }

    /**
     * This is a Lost Cities bugfix. We ensure building floor constraints are always respected, even when overrideFloors is false.
     *
     * @author Admany
     * @reason Respect building max floors regardless of overrideFloors to avoid mis-sized builds.
     */
    @Overwrite
    private int getMaxfloors(CityStyle cs) {
        int maxfloors = profile.BUILDING_MAXFLOORS;
        if (cs.getMaxFloorCount() != null) {
            maxfloors = Math.min(maxfloors, cs.getMaxFloorCount());
        }

        if (buildingType != null) {
            int buildingMax = buildingType.getMaxFloors();
            if (buildingMax >= 0) {
                maxfloors = buildingMax;
            }
            int buildingMin = buildingType.getMinFloors();
            if (buildingMin >= 0) {
                maxfloors = Math.max(maxfloors, buildingMin);
            }
        }

        return maxfloors;
    }

    @Redirect(
        method = "<init>",
        at = @At(
            value = "FIELD",
            target = "Lmcjty/lostcities/worldgen/lost/BuildingInfo;cellars:I",
            opcode = Opcodes.PUTFIELD
        ),
        require = 0, expect = 0
    )
    private void lc2h$clampCellars(BuildingInfo self, int value) {
        int clamped = value;
        try {
            ILostCityBuilding bt = self.buildingType;
            if (bt != null) {
                int max = bt.getMaxCellars();
                if (max >= 0) {
                    clamped = Math.min(clamped, max);
                }
                int min = bt.getMinCellars();
                if (min >= 0) {
                    clamped = Math.max(clamped, min);
                }
            }
        } catch (Throwable ignored) {
            // Never break worldgen if a modded building behaves oddly.
        }
        self.cellars = clamped;
    }

    @Redirect(
        method = "<init>",
        at = @At(
            value = "FIELD",
            target = "Lmcjty/lostcities/worldgen/lost/BuildingInfo;floors:I",
            opcode = Opcodes.PUTFIELD
        ),
        require = 0, expect = 0
    )
    private void lc2h$clampFloors(BuildingInfo self, int value) {
        int clamped = value;
        try {
            ILostCityBuilding bt = self.buildingType;
            if (bt != null) {
                int max = bt.getMaxFloors();
                if (max >= 0) {
                    clamped = Math.min(clamped, max);
                }
                int min = bt.getMinFloors();
                if (min >= 0) {
                    clamped = Math.max(clamped, min);
                }
            }
        } catch (Throwable ignored) {
            // Never break worldgen if a modded building behaves oddly.
        }
        try {
            ((org.admany.lc2h.mixin.accessor.lostcities.BuildingInfoAccessor) self).lc2h$setFloors(clamped);
        } catch (Throwable ignored) {
            // Fallback: if accessor fails, leave floors as-is.
        }
    }

    /**
     * Lost Cities 1.20-7.4.13 directly calls getMinCellars() in the
     * BuildingInfo constructor even for valid non-building chunks where
     * buildingType is null. Vanilla generation rarely reaches that ordering,
     * while DH's parallel feature batches reproduce it reliably. Treat null as
     * the asset API's normal "no override" value.
     */
    @Redirect(
        method = "<init>",
        at = @At(
            value = "INVOKE",
            target = "Lmcjty/lostcities/api/ILostCityBuilding;getMinCellars()I"
        ),
        require = 0
    )
    private int lc2h$safeNullBuildingMinCellars(ILostCityBuilding building) {
        return building == null ? -1 : building.getMinCellars();
    }

    @Shadow public static boolean isCityRaw(ChunkCoord coord, IDimensionInfo provider, LostCityProfile profile) { return false; }
    @Shadow private static void initMultiBuildingSection(LostChunkCharacteristics characteristics, ChunkCoord coord, IDimensionInfo provider, LostCityProfile profile) {}
    @Shadow private static int getAverageCityLevel(LostChunkCharacteristics thisone, ChunkCoord coord, IDimensionInfo provider) { return 0; }
    @Shadow private static int getTopLeftCityLevel(LostChunkCharacteristics thisone, ChunkCoord coord, IDimensionInfo provider) { return 0; }
    @Shadow private static LostChunkCharacteristics getTopLeftCityInfo(LostChunkCharacteristics thisone, ChunkCoord coord, IDimensionInfo provider) { return null; }
    @Shadow private static boolean checkBuildingPossibility(ChunkCoord coord, IDimensionInfo provider, LostCityProfile profile, MultiPos multiPos, int cityLevel, Random rand) { return false; }
    @Shadow public static LostCityProfile getProfile(ChunkCoord coord, IDimensionInfo provider) { return null; }
    @Shadow private static int getCityLevelSpace(ChunkCoord coord, IDimensionInfo provider) { return 0; }
    @Shadow private static int getCityLevelFloating(ChunkCoord coord, IDimensionInfo provider) { return 0; }
    @Shadow private static int getCityLevelCavern(ChunkCoord coord, IDimensionInfo provider) { return 0; }
    @Shadow private static int getCityLevelNormal(ChunkCoord coord, IDimensionInfo provider, LostCityProfile profile) { return 0; }
    @Shadow public static Random getBuildingRandom(int chunkX, int chunkZ, long seed) { return null; }
    @Invoker("<init>")
    static BuildingInfo lc2h$create(ChunkCoord key, IDimensionInfo provider) { throw new AssertionError(); }

    @Unique
    private static int lc2h$levelBasedOnHeight(int height, LostCityProfile profile) {
        if (height < profile.CITY_LEVEL0_HEIGHT) {
            return 0;
        } else if (height < profile.CITY_LEVEL1_HEIGHT) {
            return 1;
        } else if (height < profile.CITY_LEVEL2_HEIGHT) {
            return 2;
        } else if (height < profile.CITY_LEVEL3_HEIGHT) {
            return 3;
        } else if (height < profile.CITY_LEVEL4_HEIGHT) {
            return 4;
        } else if (height < profile.CITY_LEVEL5_HEIGHT) {
            return 5;
        } else if (height < profile.CITY_LEVEL6_HEIGHT) {
            return 6;
        } else if (height < profile.CITY_LEVEL7_HEIGHT) {
            return 7;
        }
        return 8;
    }

    @Unique
    private static int lc2h$getCityLevelNormalFixed(ChunkCoord coord, IDimensionInfo provider, LostCityProfile profile) {
        if (!profile.USE_AVG_HEIGHTMAP || Config.HEIGHT_SAMPLE_SIZE.get() <= 2) {
            return getCityLevelNormal(coord, provider, profile);
        }

        int multiAreaSize = lc2h$getMultiAreaSize(provider);
        if (multiAreaSize <= 1) {
            return lc2h$getSampleAnchoredCityLevel(coord, provider, profile);
        }

        long multiCoord = lc2h$packMulti(Math.floorDiv(coord.chunkX(), multiAreaSize), Math.floorDiv(coord.chunkZ(), multiAreaSize));

        long ownStats = lc2h$computeMultiHeightStatsPacked(multiCoord, multiAreaSize, provider, profile);
        int multiOwnLevel = (lc2h$statsCount(ownStats) > 0)
                ? lc2h$statsLevel(ownStats)
                : lc2h$getSampleAnchoredCityLevel(coord, provider, profile);

        int regionLevel = lc2h$getConnectedRegionCityLevel(coord, multiCoord, multiAreaSize, provider, profile);
        return lc2h$clampLevelToLocal(regionLevel, multiOwnLevel);
    }

    @Unique
    private static int lc2h$clampLevelToLocal(int regionLevel, int localLevel) {
        if (LC2H_CITY_REGION_MAX_LOCAL_DELTA <= 0) {
            return localLevel;
        }
        int min = localLevel - LC2H_CITY_REGION_MAX_LOCAL_DELTA;
        int max = localLevel + LC2H_CITY_REGION_MAX_LOCAL_DELTA;
        if (regionLevel < min) {
            return min;
        }
        if (regionLevel > max) {
            return max;
        }
        return regionLevel;
    }

    @Unique
    private static int lc2h$getConnectedRegionCityLevel(ChunkCoord coord,
                                                        long startMulti,
                                                        int multiAreaSize,
                                                        IDimensionInfo provider,
                                                        LostCityProfile profile) {
        int maxVisited = Math.max(1, LC2H_CITY_REGION_MAX_MULTIS);
        int queueCapacity = Math.max(8, maxVisited * 4 + 4);
        long[] visited = new long[maxVisited];
        int visitedCount = 0;
        long[] queue = new long[queueCapacity];
        int[] depthQueue = new int[queueCapacity];
        int head = 0;
        int tail = 0;
        queue[tail] = startMulti;
        depthQueue[tail] = 0;
        tail++;

        long rootMulti = startMulti;
        long heightSum = 0L;
        int heightCount = 0;

        while (head < tail) {
            if (visitedCount >= maxVisited) {
                break;
            }
            long multi = queue[head];
            int depth = depthQueue[head];
            head++;
            if (lc2h$containsLong(visited, visitedCount, multi)) {
                continue;
            }
            visited[visitedCount++] = multi;
            if (lc2h$comparePackedMulti(multi, rootMulti) < 0) {
                rootMulti = multi;
            }

            long currentStats = lc2h$computeMultiHeightStatsPacked(multi, multiAreaSize, provider, profile);
            int currentCount = lc2h$statsCount(currentStats);
            int currentLevel = lc2h$statsLevel(currentStats);
            if (currentCount > 0) {
                heightCount += currentCount;
                heightSum += lc2h$statsSum(currentStats);
            }

            if (depth >= LC2H_CITY_REGION_MAX_HOPS) {
                continue;
            }

            int multiX = lc2h$multiX(multi);
            int multiZ = lc2h$multiZ(multi);
            for (int i = 0; i < 4; i++) {
                if (visitedCount >= maxVisited || tail >= queue.length) {
                    break;
                }
                long neighbor = switch (i) {
                    case 0 -> lc2h$packMulti(multiX - 1, multiZ);
                    case 1 -> lc2h$packMulti(multiX + 1, multiZ);
                    case 2 -> lc2h$packMulti(multiX, multiZ - 1);
                    default -> lc2h$packMulti(multiX, multiZ + 1);
                };
                if (lc2h$containsLong(visited, visitedCount, neighbor)
                    || lc2h$containsLong(queue, head, tail, neighbor)) {
                    continue;
                }
                long neighborStats = lc2h$computeMultiHeightStatsPacked(neighbor, multiAreaSize, provider, profile);
                int neighborCount = lc2h$statsCount(neighborStats);
                int neighborLevel = lc2h$statsLevel(neighborStats);
                if (currentCount <= 0 || neighborCount <= 0) {
                    continue;
                }
                if (lc2h$multisShareCityBoundaryPacked(multi, neighbor, multiAreaSize, provider, profile)
                    && Math.abs(currentLevel - neighborLevel) <= LC2H_CITY_REGION_MAX_LEVEL_DELTA) {
                    queue[tail] = neighbor;
                    depthQueue[tail] = depth + 1;
                    tail++;
                }
            }
        }

        if (heightCount <= 0) {
            return lc2h$getSampleAnchoredCityLevel(coord, provider, profile);
        }

        ChunkCoord rootCoord = new ChunkCoord(provider.dimension(), lc2h$multiX(rootMulti), lc2h$multiZ(rootMulti));
        String regionKey = scopedCacheKey(LC2H_CITY_LEVEL_DISK_NAMESPACE, rootCoord, provider, profile);
        if (regionKey != null) {
            Integer cachedRegionLevel = LC2H_CITY_REGION_LEVEL_CACHE.get(regionKey);
            if (cachedRegionLevel != null) {
                BuildingInfoDiagnostics.recordRegionLevelMemoryHit();
                return cachedRegionLevel;
            }
            if (!PlannerHotPath.isActive()) {
                Integer diskRegionLevel = LostCitiesCacheBridge.getDisk(LC2H_CITY_LEVEL_DISK_NAMESPACE, regionKey, Integer.class);
                if (diskRegionLevel != null) {
                    BuildingInfoDiagnostics.recordRegionLevelDiskHit();
                    Integer prev = LC2H_CITY_REGION_LEVEL_CACHE.putIfAbsent(regionKey, diskRegionLevel);
                    return prev != null ? prev : diskRegionLevel;
                }
            }
        }

        int averageHeight = (int) (heightSum / heightCount);
        int resolvedLevel = lc2h$levelBasedOnHeight(averageHeight, profile);
        BuildingInfoDiagnostics.recordRegionLevelCompute();
        if (regionKey != null) {
            Integer prev = LC2H_CITY_REGION_LEVEL_CACHE.putIfAbsent(regionKey, resolvedLevel);
            resolvedLevel = prev != null ? prev : resolvedLevel;
            if (!PlannerHotPath.isActive()) {
                LostCitiesCacheBridge.putDisk(LC2H_CITY_LEVEL_DISK_NAMESPACE, regionKey, resolvedLevel);
            }
        }
        return resolvedLevel;
    }

    @Unique
    private static long lc2h$computeMultiHeightStatsPacked(long multi,
                                                           int multiAreaSize,
                                                           IDimensionInfo provider,
                                                           LostCityProfile profile) {
        int multiX = lc2h$multiX(multi);
        int multiZ = lc2h$multiZ(multi);
        ChunkCoord statsKey = new ChunkCoord(provider.dimension(), multiX, multiZ);
        BuildingInfoCacheScope scope = lc2h$scope(provider);
        Long cached = scope.multiHeightStats.get(statsKey);
        if (cached != null) {
            BuildingInfoDiagnostics.recordMultiHeightStatsHit();
            LostCitiesCacheBudgetManager.recordAccess(LC2H_MULTI_HEIGHT_STATS_BUDGET, statsKey);
            return lc2h$resolvePackedStats(cached, profile);
        }
        BuildingInfoDiagnostics.recordMultiHeightStatsMiss();
        int baseX = multiX * multiAreaSize;
        int baseZ = multiZ * multiAreaSize;
        long sum = 0L;
        int count = 0;
        for (int dx = 0; dx < multiAreaSize; dx++) {
            for (int dz = 0; dz < multiAreaSize; dz++) {
                ChunkCoord chunk = new ChunkCoord(provider.dimension(), baseX + dx, baseZ + dz);
                if (!isCityRaw(chunk, provider, profile)) {
                    continue;
                }
                sum += provider.getHeightmap(chunk).getHeight();
                count++;
            }
        }
        long rawStats = count <= 0 ? 0L : lc2h$packStats(sum, count, 0);
        Long prev = scope.multiHeightStats.putIfAbsent(statsKey, rawStats);
        LostCitiesCacheBudgetManager.recordPut(LC2H_MULTI_HEIGHT_STATS_BUDGET, statsKey,
            LC2H_MULTI_HEIGHT_STATS_BUDGET.defaultEntryBytes(), prev == null);
        return lc2h$resolvePackedStats(prev != null ? prev : rawStats, profile);
    }

    @Unique
    private static int lc2h$getSampleAnchoredCityLevel(ChunkCoord coord, IDimensionInfo provider, LostCityProfile profile) {
        ChunkHeightmap heightmap = provider.getHeightmap(coord);
        int height = heightmap.getHeight();

        int sampleSize = Config.HEIGHT_SAMPLE_SIZE.get();
        int gridX = Math.floorDiv(coord.chunkX(), sampleSize);
        int gridZ = Math.floorDiv(coord.chunkZ(), sampleSize);
        int centerOffset = sampleSize / 2;

        int chunkBaseX = (gridX * sampleSize) + centerOffset;
        int chunkBaseZ = (gridZ * sampleSize) + centerOffset;
        int chunkLeft = ((gridX - 1) * sampleSize) + centerOffset;
        int chunkRight = ((gridX + 1) * sampleSize) + centerOffset;
        int chunkUp = ((gridZ - 1) * sampleSize) + centerOffset;
        int chunkDown = ((gridZ + 1) * sampleSize) + centerOffset;

        ChunkCoord left = new ChunkCoord(provider.dimension(), chunkLeft, chunkBaseZ);
        ChunkCoord right = new ChunkCoord(provider.dimension(), chunkRight, chunkBaseZ);
        ChunkCoord up = new ChunkCoord(provider.dimension(), chunkBaseX, chunkUp);
        ChunkCoord down = new ChunkCoord(provider.dimension(), chunkBaseX, chunkDown);

        int avgHeightmap = height;
        int counter = 1;
        if (isCityRaw(left, provider, profile)) {
            avgHeightmap += provider.getHeightmap(left).getHeight();
            counter++;
        }
        if (isCityRaw(right, provider, profile)) {
            avgHeightmap += provider.getHeightmap(right).getHeight();
            counter++;
        }
        if (isCityRaw(up, provider, profile)) {
            avgHeightmap += provider.getHeightmap(up).getHeight();
            counter++;
        }
        if (isCityRaw(down, provider, profile)) {
            avgHeightmap += provider.getHeightmap(down).getHeight();
            counter++;
        }

        avgHeightmap /= counter;
        return lc2h$levelBasedOnHeight(avgHeightmap, profile);
    }

    @Unique
    private static int lc2h$getMultiAreaSize(IDimensionInfo provider) {
        try {
            int areaSize = provider.getWorldStyle().getMultiSettings().areasize();
            return Math.max(1, areaSize);
        } catch (Throwable ignored) {
            return 1;
        }
    }

    @Unique
    private static boolean lc2h$multisShareCityBoundaryPacked(long leftMulti,
                                                             long rightMulti,
                                                             int multiAreaSize,
                                                             IDimensionInfo provider,
                                                             LostCityProfile profile) {
        ChunkCoord leftKey = new ChunkCoord(provider.dimension(), lc2h$multiX(leftMulti), lc2h$multiZ(leftMulti));
        ChunkCoord rightKey = new ChunkCoord(provider.dimension(), lc2h$multiX(rightMulti), lc2h$multiZ(rightMulti));
        Map.Entry<ChunkCoord, ChunkCoord> boundaryKey = lc2h$comparePackedMulti(leftMulti, rightMulti) <= 0
            ? Map.entry(leftKey, rightKey)
            : Map.entry(rightKey, leftKey);
        BuildingInfoCacheScope scope = lc2h$scope(provider);
        Boolean cached = scope.multiBoundary.get(boundaryKey);
        if (cached != null) {
            BuildingInfoDiagnostics.recordMultiBoundaryHit();
            LostCitiesCacheBudgetManager.recordAccess(LC2H_MULTI_BOUNDARY_BUDGET, boundaryKey);
            return cached;
        }
        BuildingInfoDiagnostics.recordMultiBoundaryMiss();
        int leftMultiX = lc2h$multiX(leftMulti);
        int leftMultiZ = lc2h$multiZ(leftMulti);
        int rightMultiX = lc2h$multiX(rightMulti);
        int rightMultiZ = lc2h$multiZ(rightMulti);
        int dx = rightMultiX - leftMultiX;
        int dz = rightMultiZ - leftMultiZ;
        if (Math.abs(dx) + Math.abs(dz) != 1) {
            return false;
        }

        int leftBaseX = leftMultiX * multiAreaSize;
        int leftBaseZ = leftMultiZ * multiAreaSize;
        int rightBaseX = rightMultiX * multiAreaSize;
        int rightBaseZ = rightMultiZ * multiAreaSize;

        if (dx != 0) {
            int leftEdgeX = dx > 0 ? leftBaseX + multiAreaSize - 1 : leftBaseX;
            int rightEdgeX = dx > 0 ? rightBaseX : rightBaseX + multiAreaSize - 1;
            for (int offset = 0; offset < multiAreaSize; offset++) {
                int z = leftBaseZ + offset;
                ChunkCoord a = new ChunkCoord(provider.dimension(), leftEdgeX, z);
                ChunkCoord b = new ChunkCoord(provider.dimension(), rightEdgeX, rightBaseZ + offset);
                if (isCityRaw(a, provider, profile) && isCityRaw(b, provider, profile)) {
                    lc2h$rememberBoundary(provider, boundaryKey, true);
                    return true;
                }
            }
            lc2h$rememberBoundary(provider, boundaryKey, false);
            return false;
        }

        int leftEdgeZ = dz > 0 ? leftBaseZ + multiAreaSize - 1 : leftBaseZ;
        int rightEdgeZ = dz > 0 ? rightBaseZ : rightBaseZ + multiAreaSize - 1;
        for (int offset = 0; offset < multiAreaSize; offset++) {
            int x = leftBaseX + offset;
            ChunkCoord a = new ChunkCoord(provider.dimension(), x, leftEdgeZ);
            ChunkCoord b = new ChunkCoord(provider.dimension(), rightBaseX + offset, rightEdgeZ);
            if (isCityRaw(a, provider, profile) && isCityRaw(b, provider, profile)) {
                lc2h$rememberBoundary(provider, boundaryKey, true);
                return true;
            }
        }
        lc2h$rememberBoundary(provider, boundaryKey, false);
        return false;
    }

    @Unique
    private static long lc2h$packMulti(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    @Unique
    private static int lc2h$multiX(long packed) {
        return (int) (packed >> 32);
    }

    @Unique
    private static int lc2h$multiZ(long packed) {
        return (int) packed;
    }

    @Unique
    private static boolean lc2h$containsLong(long[] values, int size, long value) {
        for (int i = 0; i < size; i++) {
            if (values[i] == value) {
                return true;
            }
        }
        return false;
    }

    @Unique
    private static boolean lc2h$containsLong(long[] values, int startInclusive, int endExclusive, long value) {
        for (int i = startInclusive; i < endExclusive; i++) {
            if (values[i] == value) {
                return true;
            }
        }
        return false;
    }

    @Unique
    private static long lc2h$packStats(long sum, int count, int level) {
        long safeSum = Math.max(0L, Math.min(sum, (1L << 40) - 1L));
        return (safeSum << 24) | ((long) (count & 0xFFFF) << 8) | (long) (level & 0xFF);
    }

    @Unique
    private static long lc2h$resolvePackedStats(long packed, LostCityProfile profile) {
        int count = lc2h$statsCount(packed);
        if (count <= 0) {
            return 0L;
        }
        long sum = lc2h$statsSum(packed);
        int level = lc2h$levelBasedOnHeight((int) (sum / count), profile);
        return lc2h$packStats(sum, count, level);
    }

    @Unique
    private static long lc2h$statsSum(long packed) {
        return packed >>> 24;
    }

    @Unique
    private static int lc2h$statsCount(long packed) {
        return (int) ((packed >>> 8) & 0xFFFFL);
    }

    @Unique
    private static int lc2h$statsLevel(long packed) {
        return (int) (packed & 0xFFL);
    }

    @Unique
    private static int lc2h$comparePackedMulti(long left, long right) {
        int x = Integer.compare(lc2h$multiX(left), lc2h$multiX(right));
        if (x != 0) {
            return x;
        }
        return Integer.compare(lc2h$multiZ(left), lc2h$multiZ(right));
    }

    @Unique
    private static void lc2h$rememberBoundary(IDimensionInfo provider, Map.Entry<ChunkCoord, ChunkCoord> boundaryKey, boolean connected) {
        Boolean prev = lc2h$scope(provider).multiBoundary.putIfAbsent(boundaryKey, connected);
        LostCitiesCacheBudgetManager.recordPut(LC2H_MULTI_BOUNDARY_BUDGET, boundaryKey,
            LC2H_MULTI_BOUNDARY_BUDGET.defaultEntryBytes(), prev == null);
    }

    @Redirect(
        method = "<init>",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/WorldGenLevel;getBiome(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/core/Holder;"
        ),
        require = 0, expect = 0
    )
    private Holder<Biome> lc2h$useProviderBiome(WorldGenLevel world, BlockPos pos) {
        try {
            return provider != null ? provider.getBiome(pos) : world.getBiome(pos);
        } catch (Throwable t) {
            return world.getBiome(pos);
        }
    }

    @Redirect(
        method = "<init>",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/LevelReader;getBiome(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/core/Holder;"
        ),
        require = 0, expect = 0
    )
    private Holder<Biome> lc2h$useProviderBiomeFallback(LevelReader world, BlockPos pos) {
        // Some builds compile the getBiome call against LevelReader instead of WorldGenLevel.
        try {
            return provider != null ? provider.getBiome(pos) : world.getBiome(pos);
        } catch (Throwable t) {
            return world.getBiome(pos);
        }
    }

    @Redirect(
        method = "<init>",
        at = @At(
            value = "INVOKE",
            target = "Lmcjty/lostcities/worldgen/lost/cityassets/Building;getRandomPart(Ljava/util/Random;Lmcjty/lostcities/worldgen/lost/cityassets/ConditionContext;)Ljava/lang/String;"
        )
    )
    private String lc2h$debugRandomPart(Building building, Random rand, ConditionContext ctx) {
        String part = building.getRandomPart(rand, ctx);
        if (part == null) {
            String biomeStr;
            boolean isSphere;
            try {
                biomeStr = String.valueOf(ctx.getBiome());
            } catch (Throwable t) {
                biomeStr = "<biome-error:" + t.getClass().getSimpleName() + ">";
            }
            try {
                isSphere = ctx.isSphere();
            } catch (Throwable t) {
                isSphere = false;
            }

            org.admany.lc2h.LC2H.LOGGER.error(
                "[LC2H] LostCities BuildingInfo part selection returned null (building='{}', coord={}, profile={}, cityLevel={}, floors={}, cellars={}, ctxLevel={}, ctxFloor={}, below={}, above={}, cellar={}, ground={}, top={}, sphere={}, biome={})",
                safeBuildingName(building),
                coord,
                safeProfileName(profile),
                cityLevel,
                floors,
                cellars,
                ctx.getLevel(),
                ctx.getFloor(),
                ctx.getFloorsBelowGround(),
                ctx.getFloorsAboveGround(),
                ctx.isCellar(),
                ctx.isGroundFloor(),
                ctx.isTopOfBuilding(),
                isSphere,
                biomeStr
            );
        }
        return part;
    }

    private static String safeProfileName(LostCityProfile profile) {
        try {
            if (profile == null) {
                return "<null>";
            }
            return profile.getName();
        } catch (Throwable t) {
            return "<error:" + t.getClass().getSimpleName() + ">";
        }
    }

    private static String safeBuildingName(Building building) {
        try {
            if (building == null) {
                return "<null>";
            }
            return building.getName();
        } catch (Throwable t) {
            return "<error:" + t.getClass().getSimpleName() + ">";
        }
    }

    @Unique
    private static String scopedCacheKey(String cacheName, ChunkCoord coord, IDimensionInfo provider, LostCityProfile profile) {
        String dimension = "<unknown-dim>";
        String profileName = safeProfileName(profile);
        long seed = 0L;

        try {
            if (provider != null) {
                seed = provider.getSeed();
                if (provider.getType() != null) {
                    dimension = provider.getType().toString();
                }
                if ((profileName == null || profileName.startsWith("<")) && provider.getProfile() != null) {
                    profileName = safeProfileName(provider.getProfile());
                }
            }
        } catch (Throwable ignored) {
        }

        return cacheName + '|' + dimension + '|' + profileName + '|' + seed + '|' + coord;
    }

    /**
     * This removes global sync and uses a concurrent cache for GUI characteristics.
     *
     * @author Admany
     * @reason Make cache concurrent and non blocking
     */
    @Overwrite
    public static LostChunkCharacteristics getChunkCharacteristicsGui(ChunkCoord key, IDimensionInfo provider) {
        BuildingInfoCacheScope scope = lc2h$scope(provider);
        LostChunkCharacteristics cached = scope.cityInfo.get(key);
        if (cached != null) {
            LostCitiesCacheBudgetManager.recordAccess(LC2H_CITY_INFO_BUDGET, key);
            return lc2h$rememberCharacteristics(key, cached);
        }
        LostChunkCharacteristics snapshot = ChunkRoleProbe.peekCharacteristics(key);
        if (snapshot != null) {
            LostChunkCharacteristics prev = scope.cityInfo.putIfAbsent(key, snapshot);
            LostCitiesCacheBudgetManager.recordPut(LC2H_CITY_INFO_BUDGET, key, LC2H_CITY_INFO_BUDGET.defaultEntryBytes(), prev == null);
            return lc2h$rememberCharacteristics(key, prev != null ? prev : snapshot);
        }
        int chunkX = key.chunkX();
        int chunkZ = key.chunkZ();
        LostCityProfile profile = getProfile(key, provider);
        String cityInfoDiskKey = scopedCacheKey(LC2H_CITY_INFO_DISK_NAMESPACE + "_gui", key, provider, profile);
        if (!PlannerHotPath.isActive()) {
            LostChunkCharacteristics disk = LostCitiesCacheBridge.getDisk(LC2H_CITY_INFO_DISK_NAMESPACE + "_gui", cityInfoDiskKey, LostChunkCharacteristics.class);
            if (disk != null) {
                LostChunkCharacteristics prev = scope.cityInfo.putIfAbsent(key, disk);
                LostCitiesCacheBudgetManager.recordPut(LC2H_CITY_INFO_BUDGET, key, LC2H_CITY_INFO_BUDGET.defaultEntryBytes(), prev == null);
                return lc2h$rememberCharacteristics(key, prev != null ? prev : disk);
            }
        }
        LostChunkCharacteristics characteristics = new LostChunkCharacteristics();

        characteristics.isCity = isCityRaw(key, provider, profile);
        characteristics.cityLevel = getCityLevel(key, provider);
        Random rand = getBuildingRandom(chunkX, chunkZ, provider.getSeed());
        characteristics.couldHaveBuilding = characteristics.isCity && rand.nextFloat() < profile.BUILDING_CHANCE;

        LostChunkCharacteristics prev = scope.cityInfo.putIfAbsent(key, characteristics);
        LostCitiesCacheBudgetManager.recordPut(LC2H_CITY_INFO_BUDGET, key, LC2H_CITY_INFO_BUDGET.defaultEntryBytes(), prev == null);
        if (!PlannerHotPath.isActive()) {
            LostCitiesCacheBridge.putDisk(LC2H_CITY_INFO_DISK_NAMESPACE + "_gui", cityInfoDiskKey, characteristics);
        }
        return lc2h$rememberCharacteristics(key, characteristics);
    }

    /**
     * This removes global sync and uses a concurrent cache for characteristics.
     *
     * @author Admany
     * @reason Make cache concurrent and non blocking
     */
    @Overwrite
    public static LostChunkCharacteristics getChunkCharacteristics(ChunkCoord coord, IDimensionInfo provider) {
        AsyncMultiChunkPlanner.ensureIntegrated(provider, coord);
        BuildingInfoCacheScope scope = lc2h$scope(provider);

        LostChunkCharacteristics cached = scope.cityInfo.get(coord);
        if (cached != null) {
            BuildingInfoDiagnostics.recordCharacteristicsMemoryHit();
            LostCitiesCacheBudgetManager.recordAccess(LC2H_CITY_INFO_BUDGET, coord);
            return lc2h$rememberCharacteristics(coord, cached);
        }
        LostChunkCharacteristics snapshot = ChunkRoleProbe.peekCharacteristics(coord);
        if (snapshot != null) {
            BuildingInfoDiagnostics.recordCharacteristicsSnapshotHit();
            LostChunkCharacteristics prev = scope.cityInfo.putIfAbsent(coord, snapshot);
            LostCitiesCacheBudgetManager.recordPut(LC2H_CITY_INFO_BUDGET, coord, LC2H_CITY_INFO_BUDGET.defaultEntryBytes(), prev == null);
            return lc2h$rememberCharacteristics(coord, prev != null ? prev : snapshot);
        }
        int chunkX = coord.chunkX();
        int chunkZ = coord.chunkZ();
        LostCityProfile profile = getProfile(coord, provider);
        String cityInfoDiskKey = scopedCacheKey(LC2H_CITY_INFO_DISK_NAMESPACE, coord, provider, profile);
        if (!PlannerHotPath.isActive()) {
            LostChunkCharacteristics disk = LostCitiesCacheBridge.getDisk(LC2H_CITY_INFO_DISK_NAMESPACE, cityInfoDiskKey, LostChunkCharacteristics.class);
            if (disk != null) {
                BuildingInfoDiagnostics.recordCharacteristicsDiskHit();
                LostChunkCharacteristics prev = scope.cityInfo.putIfAbsent(coord, disk);
                LostCitiesCacheBudgetManager.recordPut(LC2H_CITY_INFO_BUDGET, coord, LC2H_CITY_INFO_BUDGET.defaultEntryBytes(), prev == null);
                return lc2h$rememberCharacteristics(coord, prev != null ? prev : disk);
            }
        }
        BuildingInfoDiagnostics.recordCharacteristicsCompute();
        LostChunkCharacteristics characteristics = new LostChunkCharacteristics();

        WorldGenLevel world = provider.getWorld();
        characteristics.isCity = isCityRaw(coord, provider, profile);

        if (!characteristics.isCity) {
            characteristics.multiPos = MultiPos.SINGLE;
            characteristics.multiBuilding = null;
        } else {
            initMultiBuildingSection(characteristics, coord, provider, profile);
            if (MultiChunkBoundaryRegistry.shouldReserveBoundaryCorridor(provider, coord)) {
                characteristics.multiPos = MultiPos.SINGLE;
                characteristics.multiBuilding = null;
                characteristics.multiBuildingId = null;
            }
        }

        if (characteristics.multiPos.isSingle()) {
            characteristics.cityLevel = getCityLevel(coord, provider);
        } else {
            characteristics.cityLevel = profile.MULTI_USE_CORNER
                ? getTopLeftCityLevel(characteristics, coord, provider)
                : getAverageCityLevel(characteristics, coord, provider);
        }

        Random rand = getBuildingRandom(chunkX, chunkZ, provider.getSeed());
        characteristics.couldHaveBuilding = characteristics.isCity &&
            checkBuildingPossibility(coord, provider, profile, characteristics.multiPos, characteristics.cityLevel, rand);
        if (characteristics.isCity && MultiChunkBoundaryRegistry.shouldReserveBoundaryCorridor(provider, coord)) {
            characteristics.couldHaveBuilding = false;
        }
        if ((profile.isSpace() || profile.isSpheres()) && characteristics.multiPos.isSingle()) {
            float dist = mcjty.lostcities.worldgen.lost.CitySphere.getRelativeDistanceToCityCenter(coord, provider);
            if (dist > .7f) {
                characteristics.couldHaveBuilding = false;
            }
        }

        CityStyle cityStyle;
        if (characteristics.isCity && !characteristics.couldHaveBuilding) {
            mcjty.lostcities.varia.Counter<String> counter = new mcjty.lostcities.varia.Counter<>();
            for (int cx = -1; cx <= 1; cx++) {
                for (int cz = -1; cz <= 1; cz++) {
                    ChunkCoord key = coord.offset(cx, cz);
                    cityStyle = City.getCityStyle(key, provider, profile);
                    counter.add(cityStyle.getName());
                    if (cx == 0 && cz == 0) {
                        counter.add(cityStyle.getName());
                    }
                }
            }
            cityStyle = AssetRegistries.CITYSTYLES.get(world, counter.getMostOccuring());
        } else {
            cityStyle = City.getCityStyle(coord, provider, profile);
        }
        characteristics.cityStyle = cityStyle;

        if (characteristics.multiPos.isMulti() && !characteristics.multiPos.isTopLeft()) {
            LostChunkCharacteristics topleft = getTopLeftCityInfo(characteristics, coord, provider);
            if (characteristics.multiBuilding != null) {
                String b = characteristics.multiBuilding.getBuilding(characteristics.multiPos.x(), characteristics.multiPos.z());
                Building bt = resolveBuildingWithFallback(world, b);
                characteristics.buildingType = bt != null ? bt : topleft.buildingType;
            } else {
                characteristics.buildingType = topleft.buildingType;
                if (characteristics.buildingType == null) {
                    throw new RuntimeException("Topleft building type is not set!");
                }
            }
        } else {
            PredefinedBuilding predefinedBuilding = City.getPredefinedBuildingAtTopLeft(world, coord);
            if (characteristics.multiPos.isTopLeft()) {
                if (characteristics.multiBuilding != null) {
                    // Respect the multichunk plan; don't re-roll a different multi building.
                    String b = characteristics.multiBuilding.getBuilding(0, 0);
                    characteristics.buildingType = resolveBuildingWithFallback(world, b);
                } else if (predefinedBuilding != null && predefinedBuilding.multi()) {
                    characteristics.multiBuilding = AssetRegistries.MULTI_BUILDINGS.getOrWarn(world, predefinedBuilding.building());
                    if (characteristics.multiBuilding == null) {
                        // Multi-building definition is missing; fall back to single-building selection.
                        String fallbackName = City.getCityStyle(coord, provider, profile).getRandomBuilding(rand, coord);
                        characteristics.buildingType = fallbackName != null ? resolveBuildingWithFallback(world, fallbackName) : null;
                        LostChunkCharacteristics prev = scope.cityInfo.putIfAbsent(coord, characteristics);
                        LostCitiesCacheBudgetManager.recordPut(LC2H_CITY_INFO_BUDGET, coord, LC2H_CITY_INFO_BUDGET.defaultEntryBytes(), prev == null);
                        if (!PlannerHotPath.isActive()) {
                            LostCitiesCacheBridge.putDisk(LC2H_CITY_INFO_DISK_NAMESPACE, cityInfoDiskKey, characteristics);
                        }
                        return lc2h$rememberCharacteristics(coord, characteristics);
                    }
                    characteristics.multiPos = new MultiPos(predefinedBuilding.relChunkX(), predefinedBuilding.relChunkZ(), characteristics.multiBuilding.getDimX(), characteristics.multiBuilding.getDimZ());
                    String b = characteristics.multiBuilding.getBuilding(0, 0);
                    characteristics.buildingType = resolveBuildingWithFallback(world, b);
                } else {
                    String name = cityStyle.getRandomMultiBuilding(rand, coord);
                    if (predefinedBuilding != null && predefinedBuilding.building() != null) {
                        name = predefinedBuilding.building();
                    }
                    if (name == null) {
                        String buildingName = cityStyle.getRandomBuilding(rand, coord);
                        characteristics.buildingType = buildingName != null ? resolveBuildingWithFallback(world, buildingName) : null;
                        if (predefinedBuilding != null && predefinedBuilding.building() != null) {
                            characteristics.buildingType = resolveBuildingWithFallback(world, predefinedBuilding.building());
                        }
                    } else {
                        characteristics.multiBuilding = AssetRegistries.MULTI_BUILDINGS.getOrWarn(world, name);
                        if (characteristics.multiBuilding == null) {
                            // Multi-building definition is missing; treat this as a single-building chunk.
                            String buildingName = cityStyle.getRandomBuilding(rand, coord);
                            characteristics.buildingType = buildingName != null ? resolveBuildingWithFallback(world, buildingName) : null;
                            LostChunkCharacteristics prev = scope.cityInfo.putIfAbsent(coord, characteristics);
                            LostCitiesCacheBudgetManager.recordPut(LC2H_CITY_INFO_BUDGET, coord, LC2H_CITY_INFO_BUDGET.defaultEntryBytes(), prev == null);
                            if (!PlannerHotPath.isActive()) {
                                LostCitiesCacheBridge.putDisk(LC2H_CITY_INFO_DISK_NAMESPACE, cityInfoDiskKey, characteristics);
                            }
                            return lc2h$rememberCharacteristics(coord, characteristics);
                        }
                        characteristics.multiPos = new MultiPos(0, 0, characteristics.multiBuilding.getDimX(), characteristics.multiBuilding.getDimZ());
                        String b = characteristics.multiBuilding.getBuilding(0, 0);
                        characteristics.buildingType = resolveBuildingWithFallback(world, b);
                    }
                }
            } else {
                String buildingName = cityStyle.getRandomBuilding(rand, coord);
                if (predefinedBuilding != null && predefinedBuilding.building() != null) {
                    buildingName = predefinedBuilding.building();
                }
                if (buildingName != null) {
                    characteristics.buildingType = resolveBuildingWithFallback(world, buildingName);
                } else {
                    characteristics.buildingType = null;
                }
            }
        }

        // A missing registry entry or an incomplete multichunk characteristic must not
        // reach native BuildingInfo construction as a buildable chunk. Native LC code
        // assumes buildingType is non-null whenever this flag is true and dereferences
        // it while resolving cellar limits.
        if (characteristics.couldHaveBuilding && characteristics.buildingType == null) {
            characteristics.couldHaveBuilding = false;
            characteristics.multiBuilding = null;
            characteristics.multiBuildingId = null;
            characteristics.multiPos = MultiPos.SINGLE;
        }

        LostChunkCharacteristics prev = scope.cityInfo.putIfAbsent(coord, characteristics);
        LostCitiesCacheBudgetManager.recordPut(LC2H_CITY_INFO_BUDGET, coord, LC2H_CITY_INFO_BUDGET.defaultEntryBytes(), prev == null);
        if (!PlannerHotPath.isActive()) {
            LostCitiesCacheBridge.putDisk(LC2H_CITY_INFO_DISK_NAMESPACE, cityInfoDiskKey, characteristics);
        }
        return lc2h$rememberCharacteristics(coord, characteristics);
    }

    private static Building resolveBuildingWithFallback(CommonLevelAccessor world, String name) {
        if (name == null || name.isBlank()) {
            return null;
        }

        // Fast path
        Building direct = AssetRegistries.BUILDINGS.getOrWarn(world, name);
        if (direct != null) {
            return direct;
        }

        // Common typos / suffixes seen in user asset packs.
        String cleaned = name.trim();
        if (cleaned.endsWith("!")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }
        cleaned = cleaned.replace("structurebundel", "structurebundle");

        // Try stripping trailing coordinate suffixes like _0_1_1 or _3_0_0
        String ns = null;
        String path = cleaned;
        int colon = cleaned.indexOf(':');
        if (colon > 0) {
            ns = cleaned.substring(0, colon);
            path = cleaned.substring(colon + 1);
        }

        String[] candidates = new String[] {
            cleaned,
            withNs(ns, stripTrailingNumbers(path, 3)),
            withNs(ns, stripTrailingNumbers(path, 2)),
            withNs(ns, stripTrailingNumbers(path, 1)),
        };

        for (String candidate : candidates) {
            if (candidate == null || candidate.isBlank() || candidate.equals(name)) {
                continue;
            }
            Building b = AssetRegistries.BUILDINGS.getOrWarn(world, candidate);
            if (b != null) {
                return b;
            }
        }

        return null;
    }

    private static String withNs(String ns, String path) {
        if (path == null) {
            return null;
        }
        return ns == null || ns.isBlank() ? path : ns + ":" + path;
    }

    private static String stripTrailingNumbers(String path, int count) {
        if (path == null || count <= 0) {
            return path;
        }
        String result = path;
        for (int i = 0; i < count; i++) {
            int idx = result.lastIndexOf('_');
            if (idx < 0) {
                return result;
            }
            String tail = result.substring(idx + 1);
            if (tail.isEmpty() || !tail.chars().allMatch(Character::isDigit)) {
                return result;
            }
            result = result.substring(0, idx);
        }
        return result;
    }

    /**
     * This removes global sync and uses a concurrent cache for building info.
     *
     * @author Admany
     * @reason Make cache concurrent and non blocking
     */
    @Overwrite
    public static BuildingInfo getBuildingInfo(ChunkCoord key, IDimensionInfo provider) {
        AsyncMultiChunkPlanner.ensureIntegrated(provider, key);
        BuildingInfoCacheScope scope = lc2h$scope(provider);

        BuildingInfo cached = scope.buildingInfo.get(key);
        if (cached != null) {
            BuildingInfoDiagnostics.recordBuildingInfoMemoryHit();
            LostCitiesCacheBudgetManager.recordAccess(LC2H_BUILDING_INFO_BUDGET, key);
            return cached;
        }

        // BuildingInfo construction touches shared registries/caches; serialize by chunk key
        // to avoid global contention and fork-join managedBlock explosions.
        Object lock = scope.buildingLocks.computeIfAbsent(key, k -> new Object());
        long lockWaitStartNs = System.nanoTime();
        synchronized (lock) {
            BuildingInfoDiagnostics.recordBuildingInfoLockWait(System.nanoTime() - lockWaitStartNs);
            cached = scope.buildingInfo.get(key);
            if (cached != null) {
                BuildingInfoDiagnostics.recordBuildingInfoMemoryHit();
                LostCitiesCacheBudgetManager.recordAccess(LC2H_BUILDING_INFO_BUDGET, key);
                scope.buildingLocks.remove(key, lock);
                return cached;
            }
            try {
                BuildingInfo created = lc2h$create(key, provider);
                BuildingInfoDiagnostics.recordBuildingInfoCreate();
                BuildingInfo prev = scope.buildingInfo.put(key, created);
                LostCitiesCacheBudgetManager.recordPut(LC2H_BUILDING_INFO_BUDGET, key, LC2H_BUILDING_INFO_BUDGET.defaultEntryBytes(), prev == null);
                try {
                    ChunkGenTracker.recordBuildingInfo(created);
                } catch (Throwable ignored) {
                }
                return created;
            } catch (Throwable t) {
                BuildingInfoDiagnostics.recordBuildingInfoFailure();
                // Don't cache failures. Log with coords/dim for easier diagnosis.
                try {
                    org.admany.lc2h.LC2H.LOGGER.error(
                        "[LC2H] BuildingInfo construction failed for {} (seed={})",
                        key,
                        provider != null ? provider.getSeed() : 0L,
                        t
                    );
                } catch (Throwable ignored) {
                }
                throw t;
            } finally {
                scope.buildingLocks.remove(key, lock);
            }
        }
    }

    /**
     * This removes global sync and uses a concurrent cache for city level.
     *
     * @author Admany
     * @reason Make cache concurrent and non blocking
     */
    @Overwrite
    public static int getCityLevel(ChunkCoord key, IDimensionInfo provider) {
        BuildingInfoCacheScope scope = lc2h$scope(provider);
        Integer cached = scope.cityLevel.get(key);
        if (cached != null) {
            BuildingInfoDiagnostics.recordCityLevelMemoryHit();
            LostCitiesCacheBudgetManager.recordAccess(LC2H_CITY_LEVEL_BUDGET, key);
            return cached;
        }
        String cityLevelDiskKey = scopedCacheKey(LC2H_CITY_LEVEL_DISK_NAMESPACE, key, provider, provider != null ? provider.getProfile() : null);
        if (!PlannerHotPath.isActive()) {
            Integer disk = LostCitiesCacheBridge.getDisk(LC2H_CITY_LEVEL_DISK_NAMESPACE, cityLevelDiskKey, Integer.class);
            if (disk != null) {
                BuildingInfoDiagnostics.recordCityLevelDiskHit();
                Integer prev = scope.cityLevel.putIfAbsent(key, disk);
                LostCitiesCacheBudgetManager.recordPut(LC2H_CITY_LEVEL_BUDGET, key, LC2H_CITY_LEVEL_BUDGET.defaultEntryBytes(), prev == null);
                return prev != null ? prev : disk;
            }
        }
        BuildingInfoDiagnostics.recordCityLevelCompute();
        int result;
        if ((provider.getProfile().isSpace() || provider.getProfile().isVoidSpheres())) {
            result = getCityLevelSpace(key, provider);
        } else if (provider.getProfile().isFloating()) {
            result = getCityLevelFloating(key, provider);
        } else if (provider.getProfile().isCavern()) {
            result = getCityLevelCavern(key, provider);
        } else {
            result = lc2h$getCityLevelNormalFixed(key, provider, provider.getProfile());
        }
        Integer prev = scope.cityLevel.putIfAbsent(key, result);
        LostCitiesCacheBudgetManager.recordPut(LC2H_CITY_LEVEL_BUDGET, key, LC2H_CITY_LEVEL_BUDGET.defaultEntryBytes(), prev == null);
        if (!PlannerHotPath.isActive()) {
            LostCitiesCacheBridge.putDisk(LC2H_CITY_LEVEL_DISK_NAMESPACE, cityLevelDiskKey, result);
        }
        return prev != null ? prev : result;
    }

    /**
     * This clears the concurrent caches.
     *
     * @author Admany
     * @reason Make cache concurrent and non blocking
     */
    @Overwrite
    public static void cleanCache() {
        LC2H_FALLBACK_SCOPE.clear();
        LC2H_SCOPES.values().forEach(BuildingInfoCacheScope::clear);
        LC2H_SCOPES.clear();
        LC2H_CITY_REGION_LEVEL_CACHE.clear();
        ChunkRoleProbe.clear();
        MountainCityReservationPlanner.clear();
        LostCitiesCacheBudgetManager.clear(LC2H_BUILDING_INFO_BUDGET);
        LostCitiesCacheBudgetManager.clear(LC2H_CITY_INFO_BUDGET);
        LostCitiesCacheBudgetManager.clear(LC2H_CITY_LEVEL_BUDGET);
        LostCitiesCacheBudgetManager.clear(LC2H_CITY_RAW_BUDGET);
        LostCitiesCacheBudgetManager.clear(LC2H_HIGHWAY_BUDGET);
        LostCitiesCacheBudgetManager.clear(LC2H_MULTI_HEIGHT_STATS_BUDGET);
        LostCitiesCacheBudgetManager.clear(LC2H_MULTI_BOUNDARY_BUDGET);
    }

    @org.spongepowered.asm.mixin.injection.Inject(method = "isCityRaw", at = @org.spongepowered.asm.mixin.injection.At("HEAD"), cancellable = true)
    private static void lc2h$cachedIsCityRawHead(ChunkCoord coord, IDimensionInfo provider, LostCityProfile profile,
                                                 org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> cir) {
        if (coord == null) {
            return;
        }
        if (MountainCityReservationPlanner.isPlanning()) {
            LC2H_CITY_RAW_COMPUTE_FLAG.set(Boolean.FALSE);
            return;
        }
        BuildingInfoCacheScope scope = lc2h$scope(provider);
        Boolean cached = scope.cityRaw.get(coord);
        if (cached != null) {
            LC2H_CITY_RAW_COMPUTE_FLAG.set(Boolean.FALSE);
            BuildingInfoDiagnostics.recordCityRawMemoryHit();
            LostCitiesCacheBudgetManager.recordAccess(LC2H_CITY_RAW_BUDGET, coord);
            cir.setReturnValue(cached
                && !MountainCityReservationPlanner.removesBuildingCell(provider, coord, profile));
            return;
        }
        if (!PlannerHotPath.isActive()) {
            String cityRawDiskKey = scopedCacheKey("city_raw", coord, provider, profile);
            Boolean disk = LostCitiesCacheBridge.getDisk(LC2H_CITY_RAW_DISK_NAMESPACE, cityRawDiskKey, Boolean.class);
            if (disk != null) {
                LC2H_CITY_RAW_COMPUTE_FLAG.set(Boolean.FALSE);
                BuildingInfoDiagnostics.recordCityRawDiskHit();
                boolean effectiveCity = disk
                    && !MountainCityReservationPlanner.removesBuildingCell(provider, coord, profile);
                Boolean prev = scope.cityRaw.putIfAbsent(coord, effectiveCity);
                LostCitiesCacheBudgetManager.recordPut(LC2H_CITY_RAW_BUDGET, coord, LC2H_CITY_RAW_BUDGET.defaultEntryBytes(), prev == null);
                cir.setReturnValue(prev != null ? prev : effectiveCity);
                return;
            }
        }
        LC2H_CITY_RAW_COMPUTE_FLAG.set(Boolean.TRUE);
    }

    @org.spongepowered.asm.mixin.injection.Inject(
        method = "isCityRaw",
        at = @org.spongepowered.asm.mixin.injection.At("RETURN"),
        cancellable = true
    )
    private static void lc2h$cachedIsCityRawReturn(ChunkCoord coord, IDimensionInfo provider, LostCityProfile profile,
                                                   org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> cir) {
        if (coord == null) {
            return;
        }
        if (MountainCityReservationPlanner.isPlanning()) {
            LC2H_CITY_RAW_COMPUTE_FLAG.set(Boolean.FALSE);
            return;
        }
        BuildingInfoCacheScope scope = lc2h$scope(provider);
        if (Boolean.TRUE.equals(LC2H_CITY_RAW_COMPUTE_FLAG.get())) {
            BuildingInfoDiagnostics.recordCityRawCompute();
        }
        LC2H_CITY_RAW_COMPUTE_FLAG.set(Boolean.FALSE);
        boolean effectiveCity = cir.getReturnValue();
        if (effectiveCity && MountainCityReservationPlanner.removesBuildingCell(provider, coord, profile)) {
            effectiveCity = false;
            cir.setReturnValue(false);
        }
        Boolean prev = scope.cityRaw.putIfAbsent(coord, effectiveCity);
        LostCitiesCacheBudgetManager.recordPut(LC2H_CITY_RAW_BUDGET, coord, LC2H_CITY_RAW_BUDGET.defaultEntryBytes(), prev == null);
        if (!PlannerHotPath.isActive()) {
            String cityRawDiskKey = scopedCacheKey("city_raw", coord, provider, profile);
            LostCitiesCacheBridge.putDisk(LC2H_CITY_RAW_DISK_NAMESPACE, cityRawDiskKey, effectiveCity);
        }
    }

    @org.spongepowered.asm.mixin.injection.Inject(method = "hasHighway", at = @org.spongepowered.asm.mixin.injection.At("HEAD"), cancellable = true)
    private static void lc2h$cachedHasHighwayHead(ChunkCoord coord, IDimensionInfo provider, LostCityProfile profile,
                                                  org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> cir) {
        if (coord == null) {
            return;
        }
        Boolean cached = lc2h$scope(provider).highway.get(coord);
        if (cached != null) {
            LostCitiesCacheBudgetManager.recordAccess(LC2H_HIGHWAY_BUDGET, coord);
            cir.setReturnValue(cached);
        }
    }

    @org.spongepowered.asm.mixin.injection.Inject(method = "hasHighway", at = @org.spongepowered.asm.mixin.injection.At("RETURN"))
    private static void lc2h$cachedHasHighwayReturn(ChunkCoord coord, IDimensionInfo provider, LostCityProfile profile,
                                                    org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> cir) {
        if (coord == null) {
            return;
        }
        Boolean previous = lc2h$scope(provider).highway.putIfAbsent(coord, cir.getReturnValue());
        LostCitiesCacheBudgetManager.recordPut(LC2H_HIGHWAY_BUDGET, coord,
            LC2H_HIGHWAY_BUDGET.defaultEntryBytes(), previous == null);
    }
}
