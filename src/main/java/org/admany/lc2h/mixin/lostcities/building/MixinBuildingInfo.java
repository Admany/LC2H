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
import org.admany.lc2h.worldgen.async.planner.AsyncMultiChunkPlanner;
import org.objectweb.asm.Opcodes;
import org.admany.lc2h.dev.diagnostics.ChunkGenTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Mixin(value = BuildingInfo.class, remap = false)
public abstract class MixinBuildingInfo {

    private static final ConcurrentMap<ChunkCoord, LostChunkCharacteristics> LC2H_CITY_INFO_MAP = new ConcurrentHashMap<>();
    private static final ConcurrentMap<ChunkCoord, BuildingInfo> LC2H_BUILDING_INFO_MAP = new ConcurrentHashMap<>();
    private static final ConcurrentMap<ChunkCoord, Integer> LC2H_CITY_LEVEL_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentMap<ChunkCoord, Boolean> LC2H_IS_CITY_RAW_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentMap<String, Integer> LC2H_CITY_REGION_LEVEL_CACHE = new ConcurrentHashMap<>();

    private static final ConcurrentMap<ChunkCoord, Object> LC2H_BUILDING_INFO_LOCKS = new ConcurrentHashMap<>();
    @Unique
    private static final LostCitiesCacheBudgetManager.CacheGroup LC2H_CITY_INFO_BUDGET =
        LostCitiesCacheBudgetManager.register("lc_city_info", 256, 1024, key -> LC2H_CITY_INFO_MAP.remove(key) != null);
    @Unique
    private static final LostCitiesCacheBudgetManager.CacheGroup LC2H_BUILDING_INFO_BUDGET =
        LostCitiesCacheBudgetManager.register("lc_building_info", 8192, 1024, key -> LC2H_BUILDING_INFO_MAP.remove(key) != null);
    @Unique
    private static final LostCitiesCacheBudgetManager.CacheGroup LC2H_CITY_LEVEL_BUDGET =
        LostCitiesCacheBudgetManager.register("lc_city_level", 64, 2048, key -> LC2H_CITY_LEVEL_CACHE.remove(key) != null);
    @Unique
    private static final LostCitiesCacheBudgetManager.CacheGroup LC2H_CITY_RAW_BUDGET =
        LostCitiesCacheBudgetManager.register("lc_city_raw", 32, 2048, key -> LC2H_IS_CITY_RAW_CACHE.remove(key) != null);
    @Unique
    private static final String LC2H_CITY_LEVEL_DISK_NAMESPACE = "city_level_v4";
    @Unique
    private static final int LC2H_CITY_REGION_MAX_MULTIS = Math.max(1, Integer.getInteger("lc2h.cityRegion.maxMultis", 8));
    @Unique
    private static final int LC2H_CITY_REGION_MAX_LEVEL_DELTA = Math.max(0, Integer.getInteger("lc2h.cityRegion.maxLevelDelta", 1));
    @Unique
    private static final int LC2H_CITY_REGION_MAX_HOPS = Math.max(0, Integer.getInteger("lc2h.cityRegion.maxHops", 1));
    @Unique
    private static final int LC2H_CITY_REGION_MAX_LOCAL_DELTA = Math.max(0, Integer.getInteger("lc2h.cityRegion.maxLocalDelta", 1));

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
        require = 0
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
        require = 0
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

        ChunkCoord multiCoord = lc2h$toMultiCoord(coord, multiAreaSize, provider);

        long[] ownStats = lc2h$computeMultiHeightStats(multiCoord, multiAreaSize, provider, profile);
        int multiOwnLevel = (ownStats[1] > 0)
                ? (int) ownStats[2]
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
                                                        ChunkCoord startMulti,
                                                        int multiAreaSize,
                                                        IDimensionInfo provider,
                                                        LostCityProfile profile) {
        Set<ChunkCoord> visited = new HashSet<>();
        ArrayDeque<ChunkCoord> queue = new ArrayDeque<>();
        ArrayDeque<Integer> depthQueue = new ArrayDeque<>();
        queue.add(startMulti);
        depthQueue.add(0);

        ChunkCoord rootMulti = startMulti;
        long heightSum = 0L;
        int heightCount = 0;

        while (!queue.isEmpty()) {
            if (visited.size() >= LC2H_CITY_REGION_MAX_MULTIS) {
                break;
            }
            ChunkCoord multi = queue.removeFirst();
            int depth = depthQueue.removeFirst();
            if (!visited.add(multi)) {
                continue;
            }
            if (lc2h$compareCoords(multi, rootMulti) < 0) {
                rootMulti = multi;
            }

            long[] currentStats = lc2h$computeMultiHeightStats(multi, multiAreaSize, provider, profile);
            int currentCount = (int) currentStats[1];
            int currentLevel = (int) currentStats[2];
            if (currentCount > 0) {
                heightCount += currentCount;
                heightSum += currentStats[0];
            }

            if (depth >= LC2H_CITY_REGION_MAX_HOPS) {
                continue;
            }

            for (ChunkCoord neighbor : lc2h$getNeighborMultis(multi, provider)) {
                if (visited.contains(neighbor)) {
                    continue;
                }
                if (visited.size() >= LC2H_CITY_REGION_MAX_MULTIS) {
                    break;
                }
                long[] neighborStats = lc2h$computeMultiHeightStats(neighbor, multiAreaSize, provider, profile);
                int neighborCount = (int) neighborStats[1];
                int neighborLevel = (int) neighborStats[2];
                if (currentCount <= 0 || neighborCount <= 0) {
                    continue;
                }
                if (!visited.contains(neighbor)
                    && lc2h$multisShareCityBoundary(multi, neighbor, multiAreaSize, provider, profile)
                    && Math.abs(currentLevel - neighborLevel) <= LC2H_CITY_REGION_MAX_LEVEL_DELTA) {
                    queue.addLast(neighbor);
                    depthQueue.addLast(depth + 1);
                }
            }
        }

        if (heightCount <= 0) {
            return lc2h$getSampleAnchoredCityLevel(coord, provider, profile);
        }

        String regionKey = scopedCacheKey(LC2H_CITY_LEVEL_DISK_NAMESPACE, rootMulti, provider, profile);
        if (regionKey != null) {
            Integer cachedRegionLevel = LC2H_CITY_REGION_LEVEL_CACHE.get(regionKey);
            if (cachedRegionLevel != null) {
                return cachedRegionLevel;
            }
            Integer diskRegionLevel = LostCitiesCacheBridge.getDisk(LC2H_CITY_LEVEL_DISK_NAMESPACE, regionKey, Integer.class);
            if (diskRegionLevel != null) {
                Integer prev = LC2H_CITY_REGION_LEVEL_CACHE.putIfAbsent(regionKey, diskRegionLevel);
                return prev != null ? prev : diskRegionLevel;
            }
        }

        int averageHeight = (int) (heightSum / heightCount);
        int resolvedLevel = lc2h$levelBasedOnHeight(averageHeight, profile);
        if (regionKey != null) {
            Integer prev = LC2H_CITY_REGION_LEVEL_CACHE.putIfAbsent(regionKey, resolvedLevel);
            resolvedLevel = prev != null ? prev : resolvedLevel;
            LostCitiesCacheBridge.putDisk(LC2H_CITY_LEVEL_DISK_NAMESPACE, regionKey, resolvedLevel);
        }
        return resolvedLevel;
    }

    @Unique
    private static long[] lc2h$computeMultiHeightStats(ChunkCoord multi,
                                                       int multiAreaSize,
                                                       IDimensionInfo provider,
                                                       LostCityProfile profile) {
        int baseX = multi.chunkX() * multiAreaSize;
        int baseZ = multi.chunkZ() * multiAreaSize;
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
        if (count <= 0) {
            return new long[]{0L, 0L, 0L};
        }
        int level = lc2h$levelBasedOnHeight((int) (sum / count), profile);
        return new long[]{sum, count, level};
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
    private static ChunkCoord lc2h$toMultiCoord(ChunkCoord coord, int multiAreaSize, IDimensionInfo provider) {
        return new ChunkCoord(provider.dimension(),
            Math.floorDiv(coord.chunkX(), multiAreaSize),
            Math.floorDiv(coord.chunkZ(), multiAreaSize));
    }

    @Unique
    private static List<ChunkCoord> lc2h$getNeighborMultis(ChunkCoord multi, IDimensionInfo provider) {
        List<ChunkCoord> neighbors = new ArrayList<>(4);
        neighbors.add(new ChunkCoord(provider.dimension(), multi.chunkX() - 1, multi.chunkZ()));
        neighbors.add(new ChunkCoord(provider.dimension(), multi.chunkX() + 1, multi.chunkZ()));
        neighbors.add(new ChunkCoord(provider.dimension(), multi.chunkX(), multi.chunkZ() - 1));
        neighbors.add(new ChunkCoord(provider.dimension(), multi.chunkX(), multi.chunkZ() + 1));
        return neighbors;
    }

    @Unique
    private static boolean lc2h$multisShareCityBoundary(ChunkCoord leftMulti,
                                                        ChunkCoord rightMulti,
                                                        int multiAreaSize,
                                                        IDimensionInfo provider,
                                                        LostCityProfile profile) {
        int dx = rightMulti.chunkX() - leftMulti.chunkX();
        int dz = rightMulti.chunkZ() - leftMulti.chunkZ();
        if (Math.abs(dx) + Math.abs(dz) != 1) {
            return false;
        }

        int leftBaseX = leftMulti.chunkX() * multiAreaSize;
        int leftBaseZ = leftMulti.chunkZ() * multiAreaSize;
        int rightBaseX = rightMulti.chunkX() * multiAreaSize;
        int rightBaseZ = rightMulti.chunkZ() * multiAreaSize;

        if (dx != 0) {
            int leftEdgeX = dx > 0 ? leftBaseX + multiAreaSize - 1 : leftBaseX;
            int rightEdgeX = dx > 0 ? rightBaseX : rightBaseX + multiAreaSize - 1;
            for (int offset = 0; offset < multiAreaSize; offset++) {
                int z = leftBaseZ + offset;
                ChunkCoord a = new ChunkCoord(provider.dimension(), leftEdgeX, z);
                ChunkCoord b = new ChunkCoord(provider.dimension(), rightEdgeX, rightBaseZ + offset);
                if (isCityRaw(a, provider, profile) && isCityRaw(b, provider, profile)) {
                    return true;
                }
            }
            return false;
        }

        int leftEdgeZ = dz > 0 ? leftBaseZ + multiAreaSize - 1 : leftBaseZ;
        int rightEdgeZ = dz > 0 ? rightBaseZ : rightBaseZ + multiAreaSize - 1;
        for (int offset = 0; offset < multiAreaSize; offset++) {
            int x = leftBaseX + offset;
            ChunkCoord a = new ChunkCoord(provider.dimension(), x, leftEdgeZ);
            ChunkCoord b = new ChunkCoord(provider.dimension(), rightBaseX + offset, rightEdgeZ);
            if (isCityRaw(a, provider, profile) && isCityRaw(b, provider, profile)) {
                return true;
            }
        }
        return false;
    }

    @Unique
    private static int lc2h$compareCoords(ChunkCoord left, ChunkCoord right) {
        int x = Integer.compare(left.chunkX(), right.chunkX());
        if (x != 0) {
            return x;
        }
        return Integer.compare(left.chunkZ(), right.chunkZ());
    }

    @Redirect(
        method = "<init>",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/WorldGenLevel;getBiome(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/core/Holder;"
        ),
        require = 0
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
        require = 0
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
        LostChunkCharacteristics cached = LC2H_CITY_INFO_MAP.get(key);
        if (cached != null) {
            LostCitiesCacheBudgetManager.recordAccess(LC2H_CITY_INFO_BUDGET, key);
            return cached;
        }
        int chunkX = key.chunkX();
        int chunkZ = key.chunkZ();
        LostCityProfile profile = getProfile(key, provider);
        String cityInfoDiskKey = scopedCacheKey("city_info", key, provider, profile);
        LostChunkCharacteristics disk = LostCitiesCacheBridge.getDisk("city_info", cityInfoDiskKey, LostChunkCharacteristics.class);
        if (disk != null) {
            LostChunkCharacteristics prev = LC2H_CITY_INFO_MAP.putIfAbsent(key, disk);
            LostCitiesCacheBudgetManager.recordPut(LC2H_CITY_INFO_BUDGET, key, LC2H_CITY_INFO_BUDGET.defaultEntryBytes(), prev == null);
            return prev != null ? prev : disk;
        }
        LostChunkCharacteristics characteristics = new LostChunkCharacteristics();

        characteristics.isCity = isCityRaw(key, provider, profile);
        characteristics.cityLevel = getCityLevel(key, provider);
        Random rand = getBuildingRandom(chunkX, chunkZ, provider.getSeed());
        characteristics.couldHaveBuilding = characteristics.isCity && rand.nextFloat() < profile.BUILDING_CHANCE;

        LostChunkCharacteristics prev = LC2H_CITY_INFO_MAP.putIfAbsent(key, characteristics);
        LostCitiesCacheBudgetManager.recordPut(LC2H_CITY_INFO_BUDGET, key, LC2H_CITY_INFO_BUDGET.defaultEntryBytes(), prev == null);
        LostCitiesCacheBridge.putDisk("city_info", cityInfoDiskKey, characteristics);
        return characteristics;
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

        LostChunkCharacteristics cached = LC2H_CITY_INFO_MAP.get(coord);
        if (cached != null) {
            LostCitiesCacheBudgetManager.recordAccess(LC2H_CITY_INFO_BUDGET, coord);
            return cached;
        }
        int chunkX = coord.chunkX();
        int chunkZ = coord.chunkZ();
        LostCityProfile profile = getProfile(coord, provider);
        String cityInfoDiskKey = scopedCacheKey("city_info", coord, provider, profile);
        LostChunkCharacteristics disk = LostCitiesCacheBridge.getDisk("city_info", cityInfoDiskKey, LostChunkCharacteristics.class);
        if (disk != null) {
            LostChunkCharacteristics prev = LC2H_CITY_INFO_MAP.putIfAbsent(coord, disk);
            LostCitiesCacheBudgetManager.recordPut(LC2H_CITY_INFO_BUDGET, coord, LC2H_CITY_INFO_BUDGET.defaultEntryBytes(), prev == null);
            return prev != null ? prev : disk;
        }
        LostChunkCharacteristics characteristics = new LostChunkCharacteristics();

        WorldGenLevel world = provider.getWorld();
        characteristics.isCity = isCityRaw(coord, provider, profile);

        if (!characteristics.isCity) {
            characteristics.multiPos = MultiPos.SINGLE;
            characteristics.multiBuilding = null;
        } else {
            initMultiBuildingSection(characteristics, coord, provider, profile);
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
                        LostChunkCharacteristics prev = LC2H_CITY_INFO_MAP.putIfAbsent(coord, characteristics);
                        LostCitiesCacheBudgetManager.recordPut(LC2H_CITY_INFO_BUDGET, coord, LC2H_CITY_INFO_BUDGET.defaultEntryBytes(), prev == null);
                        LostCitiesCacheBridge.putDisk("city_info", cityInfoDiskKey, characteristics);
                        return characteristics;
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
                            LostChunkCharacteristics prev = LC2H_CITY_INFO_MAP.putIfAbsent(coord, characteristics);
                            LostCitiesCacheBudgetManager.recordPut(LC2H_CITY_INFO_BUDGET, coord, LC2H_CITY_INFO_BUDGET.defaultEntryBytes(), prev == null);
                            LostCitiesCacheBridge.putDisk("city_info", cityInfoDiskKey, characteristics);
                            return characteristics;
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

        LostChunkCharacteristics prev = LC2H_CITY_INFO_MAP.putIfAbsent(coord, characteristics);
        LostCitiesCacheBudgetManager.recordPut(LC2H_CITY_INFO_BUDGET, coord, LC2H_CITY_INFO_BUDGET.defaultEntryBytes(), prev == null);
        LostCitiesCacheBridge.putDisk("city_info", cityInfoDiskKey, characteristics);
        return characteristics;
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

        BuildingInfo cached = LC2H_BUILDING_INFO_MAP.get(key);
        if (cached != null) {
            LostCitiesCacheBudgetManager.recordAccess(LC2H_BUILDING_INFO_BUDGET, key);
            return cached;
        }

        // BuildingInfo construction touches shared registries/caches; serialize by chunk key
        // to avoid global contention and fork-join managedBlock explosions.
        Object lock = LC2H_BUILDING_INFO_LOCKS.computeIfAbsent(key, k -> new Object());
        synchronized (lock) {
            cached = LC2H_BUILDING_INFO_MAP.get(key);
            if (cached != null) {
                LostCitiesCacheBudgetManager.recordAccess(LC2H_BUILDING_INFO_BUDGET, key);
                LC2H_BUILDING_INFO_LOCKS.remove(key, lock);
                return cached;
            }
            try {
                BuildingInfo created = lc2h$create(key, provider);
                BuildingInfo prev = LC2H_BUILDING_INFO_MAP.put(key, created);
                LostCitiesCacheBudgetManager.recordPut(LC2H_BUILDING_INFO_BUDGET, key, LC2H_BUILDING_INFO_BUDGET.defaultEntryBytes(), prev == null);
                try {
                    ChunkGenTracker.recordBuildingInfo(created);
                } catch (Throwable ignored) {
                }
                return created;
            } catch (Throwable t) {
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
                LC2H_BUILDING_INFO_LOCKS.remove(key, lock);
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
        Integer cached = LC2H_CITY_LEVEL_CACHE.get(key);
        if (cached != null) {
            LostCitiesCacheBudgetManager.recordAccess(LC2H_CITY_LEVEL_BUDGET, key);
            return cached;
        }
        String cityLevelDiskKey = scopedCacheKey(LC2H_CITY_LEVEL_DISK_NAMESPACE, key, provider, provider != null ? provider.getProfile() : null);
        Integer disk = LostCitiesCacheBridge.getDisk(LC2H_CITY_LEVEL_DISK_NAMESPACE, cityLevelDiskKey, Integer.class);
        if (disk != null) {
            Integer prev = LC2H_CITY_LEVEL_CACHE.putIfAbsent(key, disk);
            LostCitiesCacheBudgetManager.recordPut(LC2H_CITY_LEVEL_BUDGET, key, LC2H_CITY_LEVEL_BUDGET.defaultEntryBytes(), prev == null);
            return prev != null ? prev : disk;
        }
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
        Integer prev = LC2H_CITY_LEVEL_CACHE.putIfAbsent(key, result);
        LostCitiesCacheBudgetManager.recordPut(LC2H_CITY_LEVEL_BUDGET, key, LC2H_CITY_LEVEL_BUDGET.defaultEntryBytes(), prev == null);
        LostCitiesCacheBridge.putDisk(LC2H_CITY_LEVEL_DISK_NAMESPACE, cityLevelDiskKey, result);
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
        LC2H_BUILDING_INFO_MAP.clear();
        LC2H_CITY_INFO_MAP.clear();
        LC2H_CITY_LEVEL_CACHE.clear();
        LC2H_IS_CITY_RAW_CACHE.clear();
        LC2H_CITY_REGION_LEVEL_CACHE.clear();
        LostCitiesCacheBudgetManager.clear(LC2H_BUILDING_INFO_BUDGET);
        LostCitiesCacheBudgetManager.clear(LC2H_CITY_INFO_BUDGET);
        LostCitiesCacheBudgetManager.clear(LC2H_CITY_LEVEL_BUDGET);
        LostCitiesCacheBudgetManager.clear(LC2H_CITY_RAW_BUDGET);
    }

    @org.spongepowered.asm.mixin.injection.Inject(method = "isCityRaw", at = @org.spongepowered.asm.mixin.injection.At("HEAD"), cancellable = true)
    private static void lc2h$cachedIsCityRawHead(ChunkCoord coord, IDimensionInfo provider, LostCityProfile profile,
                                                 org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> cir) {
        if (coord == null) {
            return;
        }
        Boolean cached = LC2H_IS_CITY_RAW_CACHE.get(coord);
        if (cached != null) {
            LostCitiesCacheBudgetManager.recordAccess(LC2H_CITY_RAW_BUDGET, coord);
            cir.setReturnValue(cached);
            return;
        }
        String cityRawDiskKey = scopedCacheKey("city_raw", coord, provider, profile);
        Boolean disk = LostCitiesCacheBridge.getDisk("city_raw", cityRawDiskKey, Boolean.class);
        if (disk != null) {
            Boolean prev = LC2H_IS_CITY_RAW_CACHE.putIfAbsent(coord, disk);
            LostCitiesCacheBudgetManager.recordPut(LC2H_CITY_RAW_BUDGET, coord, LC2H_CITY_RAW_BUDGET.defaultEntryBytes(), prev == null);
            cir.setReturnValue(prev != null ? prev : disk);
        }
    }

    @org.spongepowered.asm.mixin.injection.Inject(method = "isCityRaw", at = @org.spongepowered.asm.mixin.injection.At("RETURN"))
    private static void lc2h$cachedIsCityRawReturn(ChunkCoord coord, IDimensionInfo provider, LostCityProfile profile,
                                                   org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> cir) {
        if (coord == null) {
            return;
        }
        Boolean prev = LC2H_IS_CITY_RAW_CACHE.putIfAbsent(coord, cir.getReturnValue());
        LostCitiesCacheBudgetManager.recordPut(LC2H_CITY_RAW_BUDGET, coord, LC2H_CITY_RAW_BUDGET.defaultEntryBytes(), prev == null);
        String cityRawDiskKey = scopedCacheKey("city_raw", coord, provider, profile);
        LostCitiesCacheBridge.putDisk("city_raw", cityRawDiskKey, cir.getReturnValue());
    }
}
