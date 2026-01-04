package org.admany.lc2h.mixin.lostcities;

import mcjty.lostcities.api.LostChunkCharacteristics;
import mcjty.lostcities.api.MultiPos;
import mcjty.lostcities.api.ILostCityBuilding;
import mcjty.lostcities.config.LostCityProfile;
import mcjty.lostcities.varia.ChunkCoord;
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
import org.admany.lc2h.worldgen.async.planner.AsyncMultiChunkPlanner;
import org.objectweb.asm.Opcodes;
import org.admany.lc2h.diagnostics.ChunkGenTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Mixin(value = BuildingInfo.class, remap = false)
public abstract class MixinBuildingInfo {

    private static final ConcurrentMap<ChunkCoord, LostChunkCharacteristics> LC2H_CITY_INFO_MAP = new ConcurrentHashMap<>();
    private static final ConcurrentMap<ChunkCoord, BuildingInfo> LC2H_BUILDING_INFO_MAP = new ConcurrentHashMap<>();
    private static final ConcurrentMap<ChunkCoord, Integer> LC2H_CITY_LEVEL_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentMap<ChunkCoord, Boolean> LC2H_IS_CITY_RAW_CACHE = new ConcurrentHashMap<>();

    private static final ConcurrentMap<ChunkCoord, Object> LC2H_BUILDING_INFO_LOCKS = new ConcurrentHashMap<>();

    @Shadow public ChunkCoord coord;
    @Shadow public IDimensionInfo provider;
    @Shadow public LostCityProfile profile;
    @Shadow public ILostCityBuilding buildingType;
    @Shadow private int floors;
    @Shadow public int cellars;
    @Shadow public int cityLevel;

    /**
     * Lost Cities bugfix.
     * Some CityStyles specify a min cellar count, and vanilla Lost Cities can apply that after
     * clamping to a building's maxcellars, effectively overriding the building constraint.
     * That makes buildings with maxcellars=0 still generate cellars, which then
     * crashes when selecting cellar parts because none exist.
     * Ensure building maxcellars is always respected over CityStyle min cellars.
     *
     * @author LC2H
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
            ((org.admany.lc2h.mixin.accessor.BuildingInfoAccessor) self).lc2h$setFloors(clamped);
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

    /**
     * Remove global sync and use concurrent cache for GUI characteristics.
     *
     * @author LC2H
     * @reason Make cache concurrent and non blocking
     */
    @Overwrite
    public static LostChunkCharacteristics getChunkCharacteristicsGui(ChunkCoord key, IDimensionInfo provider) {
        LostChunkCharacteristics cached = LC2H_CITY_INFO_MAP.get(key);
        if (cached != null) {
            return cached;
        }

        int chunkX = key.chunkX();
        int chunkZ = key.chunkZ();
        LostCityProfile profile = getProfile(key, provider);
        LostChunkCharacteristics characteristics = new LostChunkCharacteristics();

        characteristics.isCity = isCityRaw(key, provider, profile);
        characteristics.cityLevel = getCityLevel(key, provider);
        Random rand = getBuildingRandom(chunkX, chunkZ, provider.getSeed());
        characteristics.couldHaveBuilding = characteristics.isCity && rand.nextFloat() < profile.BUILDING_CHANCE;

        LC2H_CITY_INFO_MAP.putIfAbsent(key, characteristics);
        return characteristics;
    }

    /**
     * Remove global sync and use concurrent cache for characteristics.
     *
     * @author LC2H
     * @reason Make cache concurrent and non blocking
     */
    @Overwrite
    public static LostChunkCharacteristics getChunkCharacteristics(ChunkCoord coord, IDimensionInfo provider) {
        AsyncMultiChunkPlanner.ensureIntegrated(provider, coord);

        LostChunkCharacteristics cached = LC2H_CITY_INFO_MAP.get(coord);
        if (cached != null) {
            return cached;
        }

        int chunkX = coord.chunkX();
        int chunkZ = coord.chunkZ();
        LostCityProfile profile = getProfile(coord, provider);
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
                        LC2H_CITY_INFO_MAP.putIfAbsent(coord, characteristics);
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
                            LC2H_CITY_INFO_MAP.putIfAbsent(coord, characteristics);
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

        LC2H_CITY_INFO_MAP.putIfAbsent(coord, characteristics);
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
     * Remove global sync and use concurrent cache for building info.
     *
     * @author LC2H
     * @reason Make cache concurrent and non blocking
     */
    @Overwrite
    public static BuildingInfo getBuildingInfo(ChunkCoord key, IDimensionInfo provider) {
        AsyncMultiChunkPlanner.ensureIntegrated(provider, key);

        BuildingInfo cached = LC2H_BUILDING_INFO_MAP.get(key);
        if (cached != null) {
            return cached;
        }

        // BuildingInfo construction touches shared registries/caches; serialize by chunk key
        // to avoid global contention and fork-join managedBlock explosions.
        Object lock = LC2H_BUILDING_INFO_LOCKS.computeIfAbsent(key, k -> new Object());
        synchronized (lock) {
            cached = LC2H_BUILDING_INFO_MAP.get(key);
            if (cached != null) {
                LC2H_BUILDING_INFO_LOCKS.remove(key, lock);
                return cached;
            }
            try {
                BuildingInfo created = lc2h$create(key, provider);
                LC2H_BUILDING_INFO_MAP.put(key, created);
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
     * Remove global sync and use concurrent cache for city level.
     *
     * @author LC2H
     * @reason Make cache concurrent and non blocking
     */
    @Overwrite
    public static int getCityLevel(ChunkCoord key, IDimensionInfo provider) {
        Integer cached = LC2H_CITY_LEVEL_CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        int result;
        if ((provider.getProfile().isSpace() || provider.getProfile().isVoidSpheres())) {
            result = getCityLevelSpace(key, provider);
        } else if (provider.getProfile().isFloating()) {
            result = getCityLevelFloating(key, provider);
        } else if (provider.getProfile().isCavern()) {
            result = getCityLevelCavern(key, provider);
        } else {
            result = getCityLevelNormal(key, provider, provider.getProfile());
        }
        Integer prev = LC2H_CITY_LEVEL_CACHE.putIfAbsent(key, result);
        return prev != null ? prev : result;
    }

    /**
     * Clear concurrent caches.
     *
     * @author LC2H
     * @reason Make cache concurrent and non blocking
     */
    @Overwrite
    public static void cleanCache() {
        LC2H_BUILDING_INFO_MAP.clear();
        LC2H_CITY_INFO_MAP.clear();
        LC2H_CITY_LEVEL_CACHE.clear();
        LC2H_IS_CITY_RAW_CACHE.clear();
    }

    @org.spongepowered.asm.mixin.injection.Inject(method = "isCityRaw", at = @org.spongepowered.asm.mixin.injection.At("HEAD"), cancellable = true)
    private static void lc2h$cachedIsCityRawHead(ChunkCoord coord, IDimensionInfo provider, LostCityProfile profile,
                                                 org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> cir) {
        if (coord == null) {
            return;
        }
        Boolean cached = LC2H_IS_CITY_RAW_CACHE.get(coord);
        if (cached != null) {
            cir.setReturnValue(cached);
        }
    }

    @org.spongepowered.asm.mixin.injection.Inject(method = "isCityRaw", at = @org.spongepowered.asm.mixin.injection.At("RETURN"))
    private static void lc2h$cachedIsCityRawReturn(ChunkCoord coord, IDimensionInfo provider, LostCityProfile profile,
                                                   org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> cir) {
        if (coord == null) {
            return;
        }
        LC2H_IS_CITY_RAW_CACHE.putIfAbsent(coord, cir.getReturnValue());
    }
}
