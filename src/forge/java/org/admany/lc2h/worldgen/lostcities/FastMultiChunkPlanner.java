package org.admany.lc2h.worldgen.lostcities;

import mcjty.lostcities.api.RailChunkType;
import mcjty.lostcities.config.HighwayGenerationMode;
import mcjty.lostcities.config.LostCityProfile;
import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.varia.Counter;
import mcjty.lostcities.varia.Tools;
import mcjty.lostcities.worldgen.IDimensionInfo;
import mcjty.lostcities.worldgen.lost.BiomeInfo;
import mcjty.lostcities.worldgen.lost.BuildingInfo;
import mcjty.lostcities.worldgen.lost.City;
import mcjty.lostcities.worldgen.lost.CityRarityMap;
import mcjty.lostcities.worldgen.lost.CitySphere;
import mcjty.lostcities.worldgen.lost.MultiChunk;
import mcjty.lostcities.worldgen.lost.Railway;
import mcjty.lostcities.worldgen.lost.cityassets.AssetRegistries;
import mcjty.lostcities.worldgen.lost.cityassets.Building;
import mcjty.lostcities.worldgen.lost.cityassets.CityStyle;
import mcjty.lostcities.worldgen.lost.cityassets.MultiBuilding;
import mcjty.lostcities.worldgen.lost.cityassets.PredefinedCity;
import mcjty.lostcities.worldgen.lost.cityassets.WorldStyle;
import mcjty.lostcities.worldgen.lost.regassets.data.PredefinedBuilding;
import mcjty.lostcities.worldgen.lost.regassets.data.PredefinedStreet;
import mcjty.lostcities.worldgen.lost.regassets.data.MultiSettings;
import mcjty.lostcities.worldgen.ChunkHeightmap;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.CommonLevelAccessor;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import org.admany.lc2h.LC2H;
import org.admany.lc2h.dev.diagnostics.Lc2hTimingRegistry;
import org.admany.lc2h.mixin.accessor.lostcities.MultiChunkAccessor;
import org.admany.lc2h.mixin.accessor.lostcities.MultiChunkInvoker;
import org.admany.lc2h.mixin.accessor.lostcities.WorldStyleAccessor;
import org.admany.lc2h.worldgen.terrain.MountainCityReservationPlanner;
import org.admany.lc2h.worldgen.terrain.IntercityHighwayIndex;
import org.admany.lc2h.worldgen.gpu.CityCenterGpuCache;
import org.apache.commons.lang3.tuple.Pair;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Predicate;
import java.util.function.Supplier;

public final class FastMultiChunkPlanner {
    /*
     * This replaces Lost Cities' placement algorithm. It is useful for
     * profiling, but it is not the authoritative path until every material
     * and multi building decision is parity proven. Keeping it opt in means a
     * normal LC2H install keeps the exact Lost Cities city layout instead of
     * trading structures for a faster approximation.
     */
    private static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("lc2h.fast_multichunk.enabled", "false"));
    private static final ThreadLocal<Integer> BYPASS_DEPTH = ThreadLocal.withInitial(() -> 0);
    private static final ThreadLocal<FastCityFacts> ACTIVE_RAIL_CITY_FACTS = new ThreadLocal<>();
    private static final Set<ChunkCoord> AUDIT_DISABLED = ConcurrentHashMap.newKeySet();

    private static final byte UNKNOWN = 0;
    private static final byte FALSE = 1;
    private static final byte TRUE = 2;

    private static final LongAdder PLANS = new LongAdder();
    private static final LongAdder FALLBACKS = new LongAdder();
    private static final LongAdder CANDIDATES = new LongAdder();
    private static final LongAdder ATTEMPTS = new LongAdder();
    private static final LongAdder ACCEPTED = new LongAdder();
    private static final LongAdder REJECT_GRID = new LongAdder();
    private static final LongAdder REJECT_OCCUPIED = new LongAdder();
    private static final LongAdder REJECT_RAIL = new LongAdder();
    private static final LongAdder REJECT_HIGHWAY = new LongAdder();
    private static final LongAdder REJECT_STYLE = new LongAdder();
    private static final LongAdder REJECT_INVALID = new LongAdder();
    private static final LongAdder CITY_LEVEL_NS = new LongAdder();
    private static final LongAdder STYLE_GRID_NS = new LongAdder();
    private static final LongAdder STYLE_CENTER_SCAN_NS = new LongAdder();
    private static final LongAdder STYLE_CELL_RESOLVE_NS = new LongAdder();
    private static final LongAdder STYLE_BIOME_RESOLVE_NS = new LongAdder();
    private static final LongAdder STYLE_SELECTOR_RESOLVE_NS = new LongAdder();
    private static final LongAdder STYLE_SELECTOR_CACHE_HITS = new LongAdder();
    private static final LongAdder STYLE_SELECTOR_CACHE_MISSES = new LongAdder();
    private static final LongAdder STYLE_CENTER_CANDIDATES = new LongAdder();
    private static final LongAdder STYLE_CENTERS = new LongAdder();
    private static final LongAdder REGISTRY_NS = new LongAdder();
    private static final LongAdder PLACE_NS = new LongAdder();
    private static final LongAdder TOTAL_NS = new LongAdder();
    private static final LongAdder LAZY_OCCUPIED_NS = new LongAdder();
    private static final LongAdder LAZY_RAIL_NS = new LongAdder();
    private static final LongAdder LAZY_RAW_NS = new LongAdder();
    private static final LongAdder LAZY_HIGHWAY_NS = new LongAdder();
    private static final LongAdder CAN_PLACE_NS = new LongAdder();
    private static final LongAdder FALLBACK_DISABLED = new LongAdder();
    private static final LongAdder FALLBACK_AUDIT_DISABLED = new LongAdder();
    private static final LongAdder FALLBACK_EXCEPTION = new LongAdder();
    private static final LongAdder FALLBACK_INVALID_INPUT = new LongAdder();
    private static final LongAdder SERVER_THREAD_PLANS = new LongAdder();
    private static final LongAdder WORLDGEN_THREAD_PLANS = new LongAdder();
    private static final LongAdder QAPI_THREAD_PLANS = new LongAdder();
    private static final LongAdder OTHER_THREAD_PLANS = new LongAdder();

    private FastMultiChunkPlanner() {
    }

    public static boolean isEnabled() {
        return ENABLED;
    }

    public static boolean isBypassed() {
        return BYPASS_DEPTH.get() > 0;
    }

    public static <T> T runWithBypass(Supplier<T> supplier) {
        BYPASS_DEPTH.set(BYPASS_DEPTH.get() + 1);
        try {
            return supplier.get();
        } finally {
            int next = BYPASS_DEPTH.get() - 1;
            if (next <= 0) {
                BYPASS_DEPTH.remove();
            } else {
                BYPASS_DEPTH.set(next);
            }
        }
    }

    /**
     * Supplies Railway's internal city tests from the exact facts already owned
     * by the active fast multichunk plan. Outside that narrow scope the normal
     * Lost Cities compatible cache remains authoritative.
     */
    public static boolean resolveRailCityRaw(ChunkCoord coord, IDimensionInfo provider, LostCityProfile profile) {
        FastCityFacts facts = ACTIVE_RAIL_CITY_FACTS.get();
        if (facts != null && facts.supports(provider, profile) && coord != null) {
            return facts.isRailCityRaw(coord.chunkX(), coord.chunkZ());
        }
        return MultiChunkPlanningCache.isCityRaw(coord, provider, profile);
    }

    private static <T> T withRailCityFacts(FastCityFacts facts, Supplier<T> supplier) {
        FastCityFacts previous = ACTIVE_RAIL_CITY_FACTS.get();
        ACTIVE_RAIL_CITY_FACTS.set(facts);
        try {
            return supplier.get();
        } finally {
            if (previous == null) {
                ACTIVE_RAIL_CITY_FACTS.remove();
            } else {
                ACTIVE_RAIL_CITY_FACTS.set(previous);
            }
        }
    }

    public static boolean tryPlan(MultiChunk multiChunk, IDimensionInfo provider) {
        if (!ENABLED || isBypassed() || multiChunk == null || provider == null) {
            FALLBACK_DISABLED.increment();
            return false;
        }
        ChunkCoord multiCoord = ((MultiChunkAccessor) multiChunk).lc2h$getMultiCoord();
        if (multiCoord != null && AUDIT_DISABLED.contains(multiCoord)) {
            FALLBACK_AUDIT_DISABLED.increment();
            return false;
        }

        long totalStart = System.nanoTime();
        try {
            boolean planned = PlannerHotPath.run(() -> plan(multiChunk, provider));
            if (planned) {
                PLANS.increment();
                recordPlanThread();
            } else {
                FALLBACKS.increment();
            }
            return planned;
        } catch (Throwable t) {
            FALLBACKS.increment();
            FALLBACK_EXCEPTION.increment();
            LC2H.LOGGER.debug("Fast multichunk planner fell back: {}", t.getMessage());
            return false;
        } finally {
            long elapsed = System.nanoTime() - totalStart;
            TOTAL_NS.add(elapsed);
            Lc2hTimingRegistry.record("fast_multichunk.total", elapsed);
        }
    }

    public static MultiChunk calculateFastForAudit(IDimensionInfo provider, ChunkCoord multiCoord, int areaSize) {
        return PlannerHotPath.run(() -> {
            MultiChunk multiChunk = new MultiChunk(multiCoord, areaSize);
            try {
                return plan(multiChunk, provider) ? multiChunk : null;
            } catch (Throwable ignored) {
                return null;
            }
        });
    }

    public static String auditPlacementTrace(IDimensionInfo provider, ChunkCoord multiCoord, int areaSize, int cellX, int cellZ) {
        return PlannerHotPath.run(() -> {
            if (provider == null || multiCoord == null || areaSize <= 0) {
                return "trace=invalid-input";
            }
            PlacementTrace trace = new PlacementTrace(cellX, cellZ);
            MultiChunk multiChunk = new MultiChunk(multiCoord, areaSize);
            try {
                boolean planned = plan(multiChunk, provider, trace);
                OriginalPlacementTrace original = auditOriginalPlacementTrace(provider, multiCoord, areaSize, cellX, cellZ);
                return trace.summary(planned) + " " + original.summary()
                    + " " + firstListDiff("selectedDiff", trace.selectedEntries, original.selectedEntries)
                    + " " + firstListDiff("pairDiff", trace.actualEntries, original.actualEntries)
                    + " " + firstListDiff("acceptDiff", trace.accepts, original.accepts);
            } catch (Throwable t) {
                return "trace=failed:" + t.getClass().getSimpleName() + ":" + t.getMessage();
            }
        });
    }

    public static void disableForAuditMismatch(ChunkCoord multiCoord) {
        if (multiCoord != null) {
            AUDIT_DISABLED.add(multiCoord);
        }
    }

    public static void resetAuditDisabled() {
        AUDIT_DISABLED.clear();
    }

    public static Set<ChunkCoord> auditDisabledCoords() {
        return Set.copyOf(AUDIT_DISABLED);
    }

    public static int auditDisabledCount() {
        return AUDIT_DISABLED.size();
    }

    public static String auditStyleGridMismatch(IDimensionInfo provider, ChunkCoord multiCoord, int areaSize) {
        return PlannerHotPath.run(() -> {
            if (provider == null || multiCoord == null || areaSize <= 0) {
                return "stage=style_grid invalid audit input";
            }
            LostCityProfile profile = provider.getProfile();
            ChunkCoord topLeft = new ChunkCoord(multiCoord.dimension(), multiCoord.chunkX() * areaSize, multiCoord.chunkZ() * areaSize);
            FastCityFacts facts = new FastCityFacts(provider, profile, topLeft, areaSize);
            for (int x = 0; x < areaSize; x++) {
                for (int z = 0; z < areaSize; z++) {
                    ChunkCoord cell = new ChunkCoord(topLeft.dimension(), topLeft.chunkX() + x, topLeft.chunkZ() + z);
                    CityStyle original = City.getCityStyle(cell, provider, profile);
                    CityStyle fast = facts.cityStyle(cell.chunkX(), cell.chunkZ());
                    String originalName = styleName(original);
                    String fastName = styleName(fast);
                    if (!Objects.equals(originalName, fastName)) {
                        return "stage=style_grid cell=" + x + "," + z
                            + " world=" + cell.chunkX() + "," + cell.chunkZ()
                            + " originalStyle=" + originalName
                            + " fastStyle=" + fastName;
                    }
                }
            }
            return null;
        });
    }

    public static String auditFactGridMismatch(IDimensionInfo provider, ChunkCoord multiCoord, int areaSize) {
        return PlannerHotPath.run(() -> {
            if (provider == null || multiCoord == null || areaSize <= 0) {
                return "stage=facts invalid audit input";
            }
            LostCityProfile profile = provider.getProfile();
            ChunkCoord topLeft = new ChunkCoord(multiCoord.dimension(), multiCoord.chunkX() * areaSize, multiCoord.chunkZ() * areaSize);
            LazyPlan plan = buildStyleGrid(provider, profile, topLeft, areaSize, provider.getWorldStyle().getMultiSettings().correctStyleFactor());
            if (plan == null) {
                return "stage=facts invalid style grid";
            }
            for (int x = 0; x < areaSize; x++) {
                for (int z = 0; z < areaSize; z++) {
                    int index = index(x, z, areaSize);
                    ChunkCoord coord = plan.coord(index);
                    FactSnapshot fast = plan.factSnapshot(index);
                    FactSnapshot original = originalFactSnapshot(provider, profile, coord);
                    if (!fast.equals(original)) {
                        return "stage=facts cell=" + x + "," + z
                            + " world=" + coord.chunkX() + "," + coord.chunkZ()
                            + " fast[" + fast.summary(coord) + "]"
                            + " original[" + original.summary(coord) + "]";
                    }
                }
            }
            return null;
        });
    }

    public static MultiChunk calculateOriginalForAudit(IDimensionInfo provider, ChunkCoord multiCoord, int areaSize) {
        return PlannerHotPath.run(() -> runWithBypass(() -> {
            MultiChunk multiChunk = new MultiChunk(multiCoord, areaSize);
            return ((MultiChunkInvoker) multiChunk).lc2h$calculateBuildings(provider);
        }));
    }

    public static String diagnostics() {
        long plans = PLANS.sum();
        long lazyCount = Math.max(1L, ATTEMPTS.sum());
        return String.format(Locale.ROOT,
            "plans=%d fallback=%d auditDisabled=%d threads[server=%d worldgen=%d qapi=%d other=%d] fallbackReason[disabled=%d auditDisabled=%d exception=%d invalid=%d] candidates=%d attempts=%d accepted=%d reject[grid=%d occupied=%d rail=%d highway=%d style=%d invalid=%d] avgMs[styleGrid=%.3f centers=%.3f styleCells=%.3f styleBiome=%.3f styleSelect=%.3f cityLevel=%.3f registry=%.3f place=%.3f total=%.3f] styleCenters[candidates=%d accepted=%d] styleSelector[hit=%d miss=%d] lazyUs[occupied=%.3f rail=%.3f raw=%.3f highway=%.3f canPlace=%.3f]",
            plans,
            FALLBACKS.sum(),
            AUDIT_DISABLED.size(),
            SERVER_THREAD_PLANS.sum(),
            WORLDGEN_THREAD_PLANS.sum(),
            QAPI_THREAD_PLANS.sum(),
            OTHER_THREAD_PLANS.sum(),
            FALLBACK_DISABLED.sum(),
            FALLBACK_AUDIT_DISABLED.sum(),
            FALLBACK_EXCEPTION.sum(),
            FALLBACK_INVALID_INPUT.sum(),
            CANDIDATES.sum(),
            ATTEMPTS.sum(),
            ACCEPTED.sum(),
            REJECT_GRID.sum(),
            REJECT_OCCUPIED.sum(),
            REJECT_RAIL.sum(),
            REJECT_HIGHWAY.sum(),
            REJECT_STYLE.sum(),
            REJECT_INVALID.sum(),
            avgMs(STYLE_GRID_NS.sum(), plans),
            avgMs(STYLE_CENTER_SCAN_NS.sum(), plans),
            avgMs(STYLE_CELL_RESOLVE_NS.sum(), plans),
            avgMs(STYLE_BIOME_RESOLVE_NS.sum(), plans),
            avgMs(STYLE_SELECTOR_RESOLVE_NS.sum(), plans),
            avgMs(CITY_LEVEL_NS.sum(), plans),
            avgMs(REGISTRY_NS.sum(), plans),
            avgMs(PLACE_NS.sum(), plans),
            avgMs(TOTAL_NS.sum(), Math.max(1L, plans + FALLBACKS.sum())),
            STYLE_CENTER_CANDIDATES.sum(),
            STYLE_CENTERS.sum(),
            STYLE_SELECTOR_CACHE_HITS.sum(),
            STYLE_SELECTOR_CACHE_MISSES.sum(),
            avgUs(LAZY_OCCUPIED_NS.sum(), lazyCount),
            avgUs(LAZY_RAIL_NS.sum(), lazyCount),
            avgUs(LAZY_RAW_NS.sum(), lazyCount),
            avgUs(LAZY_HIGHWAY_NS.sum(), lazyCount),
            avgUs(CAN_PLACE_NS.sum(), lazyCount));
    }

    private static void recordPlanThread() {
        String name = Thread.currentThread().getName();
        if ("Server thread".equals(name)) {
            SERVER_THREAD_PLANS.increment();
        } else if (name != null && (name.startsWith("Worker-Main") || name.contains("worldgen"))) {
            WORLDGEN_THREAD_PLANS.increment();
        } else if (name != null && (name.startsWith("quantified-") || name.startsWith("lc2h-") || name.contains("ForkJoinPool"))) {
            QAPI_THREAD_PLANS.increment();
        } else {
            OTHER_THREAD_PLANS.increment();
        }
    }

    private static boolean plan(MultiChunk multiChunk, IDimensionInfo provider) {
        return plan(multiChunk, provider, null);
    }

    private static boolean plan(MultiChunk multiChunk, IDimensionInfo provider, PlacementTrace trace) {
        MultiChunkAccessor accessor = (MultiChunkAccessor) multiChunk;
        ChunkCoord multiCoord = accessor.lc2h$getMultiCoord();
        int areaSize = accessor.lc2h$getAreaSize();
        if (multiCoord == null || areaSize <= 0) {
            rejectInvalid();
            return false;
        }

        MultiSettings settings = provider.getWorldStyle().getMultiSettings();
        Random random = new Random((long) multiCoord.chunkX() * 797013493L + (long) multiCoord.chunkZ() * 295085213L);
        int minimum = settings.minimum();
        int maximum = settings.maximum();
        if (maximum < minimum) {
            rejectInvalid();
            return false;
        }
        int amount = minimum + random.nextInt(maximum - minimum + 1);
        if (amount <= 0) {
            return true;
        }

        LostCityProfile profile = provider.getProfile();
        ChunkCoord topLeft = new ChunkCoord(multiCoord.dimension(), multiCoord.chunkX() * areaSize, multiCoord.chunkZ() * areaSize);
        CityCenterGpuCache.prepareDirectNeighborhood(provider, multiCoord, areaSize);

        long cityLevelStart = System.nanoTime();
        int cityLevel;
        try {
            cityLevel = BuildingInfo.getCityLevel(topLeft, provider);
        } finally {
            long elapsed = System.nanoTime() - cityLevelStart;
            CITY_LEVEL_NS.add(elapsed);
            Lc2hTimingRegistry.record("fast_multichunk.city_level", elapsed);
        }
        if (trace != null) {
            trace.header(multiCoord, topLeft, areaSize, amount, cityLevel, settings);
        }

        LazyPlan plan = buildStyleGrid(provider, profile, topLeft, areaSize, settings.correctStyleFactor());
        if (plan == null) {
            rejectInvalid();
            return false;
        }
        if (trace != null) {
            trace.targetFacts(plan);
        }

        List<CityStyle> styleList = new ArrayList<>(plan.counter.getMap().keySet());
        styleList.sort(Comparator.comparing(CityStyle::getName));
        if (styleList.isEmpty()) {
            rejectInvalid();
            return false;
        }

        List<MultiBuilding> buildings = new ArrayList<>(amount);
        List<CityStyle> selectedStyles = new ArrayList<>(amount);
        long registryStart = System.nanoTime();
        boolean registryRecorded = false;
        try {
            CommonLevelAccessor world = provider.getWorld();
            for (int i = 0; i < amount; i++) {
                CityStyle style = Tools.getRandomFromList(random, styleList, s -> (float) plan.counter.get(s));
                String name = style.getRandomMultiBuilding(random, topLeft);
                MultiBuilding building = AssetRegistries.MULTI_BUILDINGS.get(world, name);
                if (building == null) {
                    rejectInvalid();
                    return false;
                }
                buildings.add(building);
                selectedStyles.add(style);
                if (trace != null) {
                    trace.selected(i, style, building, false);
                }
            }
            buildings.sort((a, b) -> Integer.compare(b.getDimX() + b.getDimZ(), a.getDimX() + a.getDimZ()));
            if (trace != null) {
                for (int i = 0; i < buildings.size(); i++) {
                    CityStyle pairedStyle = i < selectedStyles.size() ? selectedStyles.get(i) : null;
                    trace.selected(i, pairedStyle, buildings.get(i), true);
                }
            }

            int[] maxCellars = new int[buildings.size()];
            Map<String, Building> buildingCache = new HashMap<>();
            for (int i = 0; i < buildings.size(); i++) {
                MultiBuilding building = buildings.get(i);
                maxCellars[i] = maxCellars(world, building, buildingCache);
                if (building.getDimX() <= 0 || building.getDimZ() <= 0
                    || building.getDimX() > areaSize || building.getDimZ() > areaSize) {
                    rejectInvalid();
                    return false;
                }
            }
            CANDIDATES.add(buildings.size());
            long registryNs = System.nanoTime() - registryStart;
            REGISTRY_NS.add(registryNs);
            Lc2hTimingRegistry.record("fast_multichunk.registry", registryNs);
            registryRecorded = true;

            place(provider, multiChunk, random, settings, plan, selectedStyles, buildings, maxCellars, cityLevel, trace);
            return true;
        } finally {
            if (!registryRecorded) {
                long registryNs = System.nanoTime() - registryStart;
                REGISTRY_NS.add(registryNs);
                Lc2hTimingRegistry.record("fast_multichunk.registry", registryNs);
            }
        }
    }

    private static LazyPlan buildStyleGrid(IDimensionInfo provider,
                                           LostCityProfile profile,
                                           ChunkCoord topLeft,
                                           int areaSize,
                                           float correctStyleFactor) {
        long start = System.nanoTime();
        try {
            int size = areaSize * areaSize;
            CityStyle[] styles = new CityStyle[size];
            Counter<CityStyle> counter = new Counter<>();
            FastCityFacts facts = new FastCityFacts(provider, profile, topLeft, areaSize);
            int baseX = topLeft.chunkX();
            int baseZ = topLeft.chunkZ();

            long cellStart = System.nanoTime();
            for (int x = 0; x < areaSize; x++) {
                for (int z = 0; z < areaSize; z++) {
                    int index = index(x, z, areaSize);
                    CityStyle style = facts.cityStyle(baseX + x, baseZ + z);
                    if (style == null) {
                        return null;
                    }
                    styles[index] = style;
                    counter.add(style);
                }
            }
            STYLE_CELL_RESOLVE_NS.add(System.nanoTime() - cellStart);
            return new LazyPlan(provider, profile, topLeft, areaSize, styles, counter, facts, correctStyleFactor);
        } finally {
            long elapsed = System.nanoTime() - start;
            STYLE_GRID_NS.add(elapsed);
            Lc2hTimingRegistry.record("fast_multichunk.style_grid", elapsed);
            Lc2hTimingRegistry.record("fast_multichunk.precompute", elapsed);
        }
    }

    private static int maxCellars(CommonLevelAccessor world, MultiBuilding multiBuilding, Map<String, Building> buildingCache) {
        int maxCellars = 0;
        for (String name : multiBuilding.getBuildingSet()) {
            Building building = buildingCache.computeIfAbsent(name, key -> AssetRegistries.BUILDINGS.get(world, key));
            if (building == null) {
                rejectInvalid();
                throw new IllegalStateException("Unknown Lost Cities building asset " + name);
            }
            maxCellars = Math.max(maxCellars, building.getMaxCellars());
        }
        return maxCellars;
    }

    private static void place(IDimensionInfo provider,
                              MultiChunk multiChunk,
                              Random random,
                              MultiSettings settings,
                              LazyPlan plan,
                              List<CityStyle> selectedStyles,
                              List<MultiBuilding> buildings,
                              int[] maxCellars,
                              int cityLevel,
                              PlacementTrace trace) {
        long start = System.nanoTime();
        try {
            int railPartHeight6 = provider.getWorldStyle().getWorldSettings().railPartHeight6();

            for (int i = 0; i < buildings.size(); i++) {
                MultiBuilding building = buildings.get(i);
                CityStyle style = selectedStyles.get(i);
                int dimX = building.getDimX();
                int dimZ = building.getDimZ();
                int maxX = plan.areaSize - dimX + 1;
                int maxZ = plan.areaSize - dimZ + 1;
                if (maxX <= 0 || maxZ <= 0) {
                    rejectInvalid();
                    return;
                }
                for (int attempt = 0; attempt < settings.attempts(); attempt++) {
                    ATTEMPTS.increment();
                    int x = random.nextInt(maxX);
                    int z = random.nextInt(maxZ);
                    boolean traceAttempt = trace != null && trace.intersectsTarget(x, z, dimX, dimZ);
                    PlacementDecision decision = timedCanPlaceDecision(plan, style, dimX, dimZ, cityLevel, maxCellars[i], railPartHeight6, x, z, true, traceAttempt);
                    if (traceAttempt) {
                        trace.attempt(i, attempt, building, style, x, z, dimX, dimZ, decision);
                    }
                    if (decision.accepted) {
                        markPlaced(plan, dimX, dimZ, x, z);
                        ((MultiChunkInvoker) multiChunk).lc2h$placeBuilding(building, x, z);
                        if (trace != null) {
                            trace.accept(i, attempt, building, style, x, z, dimX, dimZ);
                        }
                        ACCEPTED.increment();
                        break;
                    }
                }
            }
        } finally {
            long elapsed = System.nanoTime() - start;
            PLACE_NS.add(elapsed);
            Lc2hTimingRegistry.record("fast_multichunk.place", elapsed);
        }
    }

    private static boolean canPlace(LazyPlan plan,
                                    CityStyle style,
                                    int dimX,
                                    int dimZ,
                                    int cityLevel,
                                    int maxCellars,
                                    int railPartHeight6,
                                    int x,
                                    int z) {
        long start = System.nanoTime();
        try {
            return canPlaceDecision(plan, style, dimX, dimZ, cityLevel, maxCellars, railPartHeight6, x, z, true, false).accepted;
        } finally {
            long elapsed = System.nanoTime() - start;
            CAN_PLACE_NS.add(elapsed);
            Lc2hTimingRegistry.record("fast_multichunk.can_place", elapsed);
        }
    }

    private static PlacementDecision timedCanPlaceDecision(LazyPlan plan,
                                                           CityStyle style,
                                                           int dimX,
                                                           int dimZ,
                                                           int cityLevel,
                                                           int maxCellars,
                                                           int railPartHeight6,
                                                           int x,
                                                           int z,
                                                           boolean countRejects,
                                                           boolean includeDetails) {
        long start = System.nanoTime();
        try {
            return canPlaceDecision(plan, style, dimX, dimZ, cityLevel, maxCellars, railPartHeight6, x, z, countRejects, includeDetails);
        } finally {
            long elapsed = System.nanoTime() - start;
            CAN_PLACE_NS.add(elapsed);
            Lc2hTimingRegistry.record("fast_multichunk.can_place", elapsed);
        }
    }

    private static PlacementDecision canPlaceDecision(LazyPlan plan,
                                                      CityStyle style,
                                                      int dimX,
                                                      int dimZ,
                                                      int cityLevel,
                                                      int maxCellars,
                                                    int railPartHeight6,
                                                    int x,
                                                    int z,
                                                    boolean countRejects,
                                                    boolean includeDetails) {
        if (plan.anyPlaced(x, z, dimX, dimZ)) {
            if (countRejects) {
                REJECT_GRID.increment();
            }
            return PlacementDecision.reject("grid", x, z, "placed-overlap");
        }
        for (int dx = 0; dx < dimX; dx++) {
            for (int dz = 0; dz < dimZ; dz++) {
                int lx = x + dx;
                int lz = z + dz;
                int index = index(lx, lz, plan.areaSize);
                if (plan.occupied(index)) {
                    if (countRejects) {
                        REJECT_OCCUPIED.increment();
                    }
                    return PlacementDecision.reject("occupied", lx, lz, includeDetails ? plan.factCompareSummary(index) : "");
                }
                // A non-city cell can never accept a multi-building.  Lost Cities
                // checks rail first, but these queries are pure and its rail route
                // resolver is by far the most expensive fact on a cold planning
                // window.  Rejecting the decisive city fact first preserves the
                // accept/reject result and random stream while avoiding an
                // unnecessary rail traversal for the overwhelming majority of
                // failed candidates.
                if (!plan.cityRaw(index)) {
                    if (countRejects) {
                        REJECT_HIGHWAY.increment();
                    }
                    return PlacementDecision.reject("not_city_raw", lx, lz, includeDetails ? plan.factCompareSummary(index) : "");
                }
                if (plan.highway(index)) {
                    if (countRejects) {
                        REJECT_HIGHWAY.increment();
                    }
                    return PlacementDecision.reject("highway", lx, lz, includeDetails ? plan.factCompareSummary(index) : "");
                }
                Railway.RailChunkInfo rail = plan.rail(index);
                RailChunkType railType = rail == null ? RailChunkType.NONE : rail.getType();
                if (railType == null) {
                    railType = RailChunkType.NONE;
                }
                if (railType.isSurface() || railType.isStation()) {
                    if (countRejects) {
                        REJECT_RAIL.increment();
                    }
                    return PlacementDecision.reject("rail_surface_or_station", lx, lz, includeDetails ? plan.factCompareSummary(index) : "");
                }
                if (railType != RailChunkType.NONE) {
                    int required = Math.min(cityLevel - rail.getLevel() - railPartHeight6, maxCellars);
                    if (required < maxCellars) {
                        if (countRejects) {
                            REJECT_RAIL.increment();
                        }
                        return PlacementDecision.reject("rail_cellar", lx, lz,
                            (includeDetails ? plan.factCompareSummary(index) + " " : "") + "required=" + required + " maxCellars=" + maxCellars);
                    }
                }
            }
        }

        int styleMatches = plan.countStyle(style, x, z, dimX, dimZ);
        float requiredStyle = (float) (dimX * dimZ) * plan.correctStyleFactor;
        if ((float) styleMatches < requiredStyle) {
            if (countRejects) {
                REJECT_STYLE.increment();
            }
            return PlacementDecision.reject("style", x, z,
                "matches=" + styleMatches + " required=" + requiredStyle + " style=" + styleName(style));
        }
        return PlacementDecision.accept(styleMatches);
    }

    private static void markPlaced(LazyPlan plan, int dimX, int dimZ, int x, int z) {
        for (int dx = 0; dx < dimX; dx++) {
            for (int dz = 0; dz < dimZ; dz++) {
                plan.markPlaced(x + dx, z + dz);
            }
        }
    }

    private static int index(int x, int z, int areaSize) {
        return x * areaSize + z;
    }

    private static void rejectInvalid() {
        REJECT_INVALID.increment();
        FALLBACK_INVALID_INPUT.increment();
    }

    private static String styleName(CityStyle style) {
        return style == null ? "<null>" : style.getName();
    }

    private static double avgMs(long ns, long count) {
        if (count <= 0L) {
            return 0.0D;
        }
        return (ns / 1_000_000.0D) / count;
    }

    private static double avgUs(long ns, long count) {
        if (count <= 0L) {
            return 0.0D;
        }
        return (ns / 1_000.0D) / count;
    }

    private static long packedChunk(int chunkX, int chunkZ) {
        return (((long) chunkX) << 32) ^ (chunkZ & 0xffffffffL);
    }

    private static long bitRange(int start, int size) {
        if (size <= 0) {
            return 0L;
        }
        if (size >= Long.SIZE) {
            return -1L;
        }
        return ((1L << size) - 1L) << start;
    }

    private static String assetName(MultiBuilding building) {
        return building == null ? "<null>" : building.getName();
    }

    private static final class PlacementDecision {
        private final boolean accepted;
        private final String reason;
        private final int localX;
        private final int localZ;
        private final String detail;
        private final int styleMatches;

        private PlacementDecision(boolean accepted, String reason, int localX, int localZ, String detail, int styleMatches) {
            this.accepted = accepted;
            this.reason = reason;
            this.localX = localX;
            this.localZ = localZ;
            this.detail = detail == null ? "" : detail;
            this.styleMatches = styleMatches;
        }

        private static PlacementDecision accept(int styleMatches) {
            return new PlacementDecision(true, "accept", -1, -1, "", styleMatches);
        }

        private static PlacementDecision reject(String reason, int localX, int localZ, String detail) {
            return new PlacementDecision(false, reason, localX, localZ, detail, -1);
        }

        private String compact() {
            if (accepted) {
                return "accept styleMatches=" + styleMatches;
            }
            String cell = localX >= 0 && localZ >= 0 ? " cell=" + localX + "," + localZ : "";
            return "reject=" + reason + cell + (detail.isBlank() ? "" : " " + detail);
        }
    }

    private static final class PlacementTrace {
        private static final int MAX_CANDIDATES = 80;
        private static final int MAX_ATTEMPTS = 512;

        private final int targetX;
        private final int targetZ;
        private final StringBuilder header = new StringBuilder();
        private final StringBuilder selected = new StringBuilder();
        private final StringBuilder actualPairs = new StringBuilder();
        private final StringBuilder attempts = new StringBuilder();
        private final StringBuilder accepted = new StringBuilder();
        private final List<String> selectedEntries = new ArrayList<>();
        private final List<String> actualEntries = new ArrayList<>();
        private final List<String> accepts = new ArrayList<>();
        private String facts = "";
        private int selectedCount;
        private int actualCount;
        private int attemptCount;

        private PlacementTrace(int targetX, int targetZ) {
            this.targetX = targetX;
            this.targetZ = targetZ;
        }

        private void header(ChunkCoord multiCoord, ChunkCoord topLeft, int areaSize, int amount, int cityLevel, MultiSettings settings) {
            header.append("multi=").append(multiCoord.chunkX()).append(',').append(multiCoord.chunkZ())
                .append(" topLeft=").append(topLeft.chunkX()).append(',').append(topLeft.chunkZ())
                .append(" area=").append(areaSize)
                .append(" amount=").append(amount)
                .append(" attempts=").append(settings.attempts())
                .append(" cityLevel=").append(cityLevel)
                .append(" correctStyleFactor=").append(settings.correctStyleFactor());
        }

        private void targetFacts(LazyPlan plan) {
            if (targetX < 0 || targetZ < 0 || targetX >= plan.areaSize || targetZ >= plan.areaSize) {
                facts = "targetFacts=out-of-bounds";
                return;
            }
            int idx = index(targetX, targetZ, plan.areaSize);
            ChunkCoord coord = plan.coord(idx);
            facts = "targetFacts cell=" + targetX + "," + targetZ
                + " world=" + coord.chunkX() + "," + coord.chunkZ()
                + " fast[" + plan.factSummary(idx) + "]"
                + " original[" + originalFactSummary(plan.provider, plan.profile, coord) + "]";
        }

        private void selected(int index, CityStyle style, MultiBuilding building, boolean actual) {
            StringBuilder out = actual ? actualPairs : selected;
            List<String> entries = actual ? actualEntries : selectedEntries;
            int count = actual ? actualCount++ : selectedCount++;
            String entry = candidateEntry(index, style, building);
            entries.add(entry);
            if (count >= MAX_CANDIDATES) {
                return;
            }
            if (out.length() > 0) {
                out.append(';');
            }
            out.append(entry);
        }

        private boolean intersectsTarget(int x, int z, int dimX, int dimZ) {
            return targetX >= x && targetX < x + dimX && targetZ >= z && targetZ < z + dimZ;
        }

        private void attempt(int buildingIndex,
                             int attemptIndex,
                             MultiBuilding building,
                             CityStyle style,
                             int x,
                             int z,
                             int dimX,
                             int dimZ,
                             PlacementDecision decision) {
            if (attemptCount++ >= MAX_ATTEMPTS) {
                return;
            }
            if (attempts.length() > 0) {
                attempts.append(';');
            }
            attempts.append("b=").append(buildingIndex)
                .append(':').append(assetName(building))
                .append('[').append(dimX).append('x').append(dimZ).append(']')
                .append("/style=").append(styleName(style))
                .append("/a=").append(attemptIndex)
                .append("/pos=").append(x).append(',').append(z)
                .append('/').append(decision.compact());
        }

        private void accept(int buildingIndex,
                            int attemptIndex,
                            MultiBuilding building,
                            CityStyle style,
                            int x,
                            int z,
                            int dimX,
                            int dimZ) {
            String entry = acceptEntry(buildingIndex, attemptIndex, building, style, x, z, dimX, dimZ);
            accepts.add(entry);
            if (accepted.length() > 0) {
                accepted.append(';');
            }
            accepted.append(entry);
        }

        private String summary(boolean planned) {
            return "trace[planned=" + planned
                + " " + header
                + " " + facts
                + " selected=" + selected
                + " actualPairs=" + actualPairs
                + " accepts=" + accepted
                + " targetAttempts=" + attempts
                + (attemptCount > MAX_ATTEMPTS ? ";attemptsTruncated=" + (attemptCount - MAX_ATTEMPTS) : "")
                + (selectedCount > MAX_CANDIDATES ? ";selectedTruncated=" + (selectedCount - MAX_CANDIDATES) : "")
                + (actualCount > MAX_CANDIDATES ? ";actualTruncated=" + (actualCount - MAX_CANDIDATES) : "")
                + ']';
        }
    }

    private static final class OriginalPlacementTrace {
        private static final int MAX_ATTEMPTS = 512;

        private final int targetX;
        private final int targetZ;
        private final StringBuilder attempts = new StringBuilder();
        private final StringBuilder accepted = new StringBuilder();
        private final List<String> selectedEntries = new ArrayList<>();
        private final List<String> actualEntries = new ArrayList<>();
        private final List<String> accepts = new ArrayList<>();
        private int attemptCount;

        private OriginalPlacementTrace(int targetX, int targetZ) {
            this.targetX = targetX;
            this.targetZ = targetZ;
        }

        private boolean intersectsTarget(int x, int z, int dimX, int dimZ) {
            return targetX >= x && targetX < x + dimX && targetZ >= z && targetZ < z + dimZ;
        }

        private void selected(int index, CityStyle style, MultiBuilding building, boolean actual) {
            String entry = candidateEntry(index, style, building);
            if (actual) {
                actualEntries.add(entry);
            } else {
                selectedEntries.add(entry);
            }
        }

        private void attempt(int buildingIndex,
                             int attemptIndex,
                             MultiBuilding building,
                             CityStyle style,
                             int x,
                             int z,
                             int dimX,
                             int dimZ,
                             PlacementDecision decision) {
            if (attemptCount++ >= MAX_ATTEMPTS) {
                return;
            }
            if (attempts.length() > 0) {
                attempts.append(';');
            }
            attempts.append("b=").append(buildingIndex)
                .append(':').append(assetName(building))
                .append('[').append(dimX).append('x').append(dimZ).append(']')
                .append("/style=").append(styleName(style))
                .append("/a=").append(attemptIndex)
                .append("/pos=").append(x).append(',').append(z)
                .append('/').append(decision.compact());
        }

        private void accept(int buildingIndex,
                            int attemptIndex,
                            MultiBuilding building,
                            CityStyle style,
                            int x,
                            int z,
                            int dimX,
                            int dimZ) {
            String entry = acceptEntry(buildingIndex, attemptIndex, building, style, x, z, dimX, dimZ);
            accepts.add(entry);
            if (accepted.length() > 0) {
                accepted.append(';');
            }
            accepted.append(entry);
        }

        private String summary() {
            return "originalTrace[accepts=" + accepted
                + " targetAttempts=" + attempts
                + (attemptCount > MAX_ATTEMPTS ? ";attemptsTruncated=" + (attemptCount - MAX_ATTEMPTS) : "")
                + ']';
        }
    }

    private static String acceptEntry(int buildingIndex,
                                      int attemptIndex,
                                      MultiBuilding building,
                                      CityStyle style,
                                      int x,
                                      int z,
                                      int dimX,
                                      int dimZ) {
        return "b=" + buildingIndex
            + ':' + assetName(building)
            + '[' + dimX + 'x' + dimZ + ']'
            + "/style=" + styleName(style)
            + "/a=" + attemptIndex
            + "/pos=" + x + ',' + z;
    }

    private static String candidateEntry(int index, CityStyle style, MultiBuilding building) {
        return index
            + ":" + assetName(building)
            + '[' + (building == null ? "?" : building.getDimX())
            + 'x' + (building == null ? "?" : building.getDimZ())
            + "]/style=" + styleName(style);
    }

    private static String originalFactSummary(IDimensionInfo provider, LostCityProfile profile, ChunkCoord coord) {
        try {
            return originalFactSnapshot(provider, profile, coord).summary(coord);
        } catch (Throwable t) {
            return "error=" + t.getClass().getSimpleName() + ':' + t.getMessage();
        }
    }

    private static FactSnapshot originalFactSnapshot(IDimensionInfo provider, LostCityProfile profile, ChunkCoord coord) {
        boolean occupied = City.isChunkOccupied(provider, coord);
        Railway.RailChunkInfo rail = Railway.getRailChunkType(coord, provider, profile);
        boolean cityRaw = BuildingInfo.isCityRaw(coord, provider, profile);
        boolean highway = BuildingInfo.hasHighway(coord, provider, profile);
        RailChunkType railType = rail == null || rail.getType() == null ? RailChunkType.NONE : rail.getType();
        int railLevel = rail == null ? 0 : rail.getLevel();
        return new FactSnapshot(occupied, railType, railLevel, cityRaw, highway);
    }

    private static String factString(ChunkCoord coord, boolean occupied, Railway.RailChunkInfo rail, boolean cityRaw, boolean highway) {
        RailChunkType type = rail == null || rail.getType() == null ? RailChunkType.NONE : rail.getType();
        int level = rail == null ? 0 : rail.getLevel();
        return "coord=" + coord.chunkX() + "," + coord.chunkZ()
            + " occupied=" + occupied
            + " rail=" + type
            + " railLevel=" + level
            + " cityRaw=" + cityRaw
            + " highway=" + highway;
    }

    private record FactSnapshot(boolean occupied,
                                RailChunkType railType,
                                int railLevel,
                                boolean cityRaw,
                                boolean highway) {
        private String summary(ChunkCoord coord) {
            return "coord=" + coord.chunkX() + "," + coord.chunkZ()
                + " occupied=" + occupied
                + " rail=" + (railType == null ? RailChunkType.NONE : railType)
                + " railLevel=" + railLevel
                + " cityRaw=" + cityRaw
                + " highway=" + highway;
        }
    }

    private static OriginalPlacementTrace auditOriginalPlacementTrace(IDimensionInfo provider, ChunkCoord multiCoord, int areaSize, int targetX, int targetZ) {
        return runWithBypass(() -> {
            OriginalPlacementTrace trace = new OriginalPlacementTrace(targetX, targetZ);
            MultiSettings settings = provider.getWorldStyle().getMultiSettings();
            Random random = new Random((long) multiCoord.chunkX() * 797013493L + (long) multiCoord.chunkZ() * 295085213L);
            int amount = settings.minimum() + random.nextInt(settings.maximum() - settings.minimum() + 1);
            if (amount <= 0) {
                return trace;
            }
            LostCityProfile profile = provider.getProfile();
            ChunkCoord topLeft = new ChunkCoord(multiCoord.dimension(), multiCoord.chunkX() * areaSize, multiCoord.chunkZ() * areaSize);
            int cityLevel = BuildingInfo.getCityLevel(topLeft, provider);
            Counter<CityStyle> counter = new Counter<>();
            for (int x = 0; x < areaSize; x++) {
                for (int z = 0; z < areaSize; z++) {
                    counter.add(City.getCityStyle(topLeft.offset(x, z), provider, provider.getProfile()));
                }
            }
            List<CityStyle> styleList = new ArrayList<>(counter.getMap().keySet());
            styleList.sort(Comparator.comparing(CityStyle::getName));
            List<MultiBuilding> buildings = new ArrayList<>(amount);
            List<CityStyle> selectedStyles = new ArrayList<>(amount);
            CommonLevelAccessor world = provider.getWorld();
            for (int i = 0; i < amount; i++) {
                CityStyle style = Tools.getRandomFromList(random, styleList, s -> (float) counter.get(s));
                String name = style.getRandomMultiBuilding(random, topLeft);
                MultiBuilding building = AssetRegistries.MULTI_BUILDINGS.get(world, name);
                buildings.add(building);
                selectedStyles.add(style);
                trace.selected(i, style, building, false);
            }
            buildings.sort((a, b) -> Integer.compare(b.getDimX() + b.getDimZ(), a.getDimX() + a.getDimZ()));
            for (int i = 0; i < buildings.size(); i++) {
                CityStyle pairedStyle = i < selectedStyles.size() ? selectedStyles.get(i) : null;
                trace.selected(i, pairedStyle, buildings.get(i), true);
            }
            boolean[] placed = new boolean[areaSize * areaSize];
            Map<String, Building> buildingCache = new HashMap<>();
            for (int i = 0; i < buildings.size(); i++) {
                MultiBuilding building = buildings.get(i);
                CityStyle style = selectedStyles.get(i);
                int dimX = building.getDimX();
                int dimZ = building.getDimZ();
                int maxCellars = maxCellars(world, building, buildingCache);
                int maxX = areaSize - dimX + 1;
                int maxZ = areaSize - dimZ + 1;
                for (int attempt = 0; attempt < settings.attempts(); attempt++) {
                    int x = random.nextInt(maxX);
                    int z = random.nextInt(maxZ);
                    PlacementDecision decision = canPlaceOriginalDecision(placed, topLeft, provider, provider.getProfile(), style, building, cityLevel, maxCellars, x, z, areaSize);
                    if (trace.intersectsTarget(x, z, dimX, dimZ)) {
                        trace.attempt(i, attempt, building, style, x, z, dimX, dimZ, decision);
                    }
                    if (decision.accepted) {
                        markPlaced(placed, areaSize, dimX, dimZ, x, z);
                        trace.accept(i, attempt, building, style, x, z, dimX, dimZ);
                        break;
                    }
                }
            }
            return trace;
        });
    }

    private static PlacementDecision canPlaceOriginalDecision(boolean[] placed,
                                                             ChunkCoord topLeft,
                                                             IDimensionInfo provider,
                                                             LostCityProfile profile,
                                                             CityStyle style,
                                                             MultiBuilding building,
                                                             int cityLevel,
                                                             int maxCellars,
                                                             int x,
                                                             int z,
                                                             int areaSize) {
        int railPartHeight6 = provider.getWorldStyle().getWorldSettings().railPartHeight6();
        int styleMatches = 0;
        for (int dx = 0; dx < building.getDimX(); dx++) {
            for (int dz = 0; dz < building.getDimZ(); dz++) {
                int lx = x + dx;
                int lz = z + dz;
                if (placed[index(lx, lz, areaSize)]) {
                    return PlacementDecision.reject("grid", lx, lz, "placed-overlap");
                }
                ChunkCoord coord = topLeft.offset(lx, lz);
                if (City.isChunkOccupied(provider, coord)) {
                    return PlacementDecision.reject("occupied", lx, lz, originalFactSummary(provider, profile, coord));
                }
                Railway.RailChunkInfo rail = Railway.getRailChunkType(coord, provider, profile);
                RailChunkType railType = rail == null || rail.getType() == null ? RailChunkType.NONE : rail.getType();
                if (railType.isSurface() || railType.isStation()) {
                    return PlacementDecision.reject("rail_surface_or_station", lx, lz, originalFactSummary(provider, profile, coord));
                }
                if (!BuildingInfo.isCityRaw(coord, provider, profile)) {
                    return PlacementDecision.reject("not_city_raw", lx, lz, originalFactSummary(provider, profile, coord));
                }
                if (BuildingInfo.hasHighway(coord, provider, profile)) {
                    return PlacementDecision.reject("highway", lx, lz, originalFactSummary(provider, profile, coord));
                }
                if (railType != RailChunkType.NONE) {
                    int required = Math.min(cityLevel - rail.getLevel() - railPartHeight6, maxCellars);
                    if (required < maxCellars) {
                        return PlacementDecision.reject("rail_cellar", lx, lz,
                            originalFactSummary(provider, profile, coord) + " required=" + required + " maxCellars=" + maxCellars);
                    }
                }
                CityStyle cellStyle = City.getCityStyle(coord, provider, profile);
                if (Objects.equals(cellStyle, style)) {
                    styleMatches++;
                }
            }
        }
        float requiredStyle = (float) (building.getDimX() * building.getDimZ()) * provider.getWorldStyle().getMultiSettings().correctStyleFactor();
        if ((float) styleMatches < requiredStyle) {
            return PlacementDecision.reject("style", x, z,
                "matches=" + styleMatches + " required=" + requiredStyle + " style=" + styleName(style));
        }
        return PlacementDecision.accept(styleMatches);
    }

    private static void markPlaced(boolean[] placed, int areaSize, int dimX, int dimZ, int x, int z) {
        for (int dx = 0; dx < dimX; dx++) {
            for (int dz = 0; dz < dimZ; dz++) {
                placed[index(x + dx, z + dz, areaSize)] = true;
            }
        }
    }

    private static String firstListDiff(String label, List<String> fast, List<String> original) {
        int max = Math.max(fast.size(), original.size());
        for (int i = 0; i < max; i++) {
            String fastEntry = i < fast.size() ? fast.get(i) : "<none>";
            String originalEntry = i < original.size() ? original.get(i) : "<none>";
            if (!Objects.equals(fastEntry, originalEntry)) {
                return label + "[index=" + i + " fast=" + fastEntry + " original=" + originalEntry + "]";
            }
        }
        return label + "=none";
    }

    private static final class FastCityFacts {
        private final IDimensionInfo provider;
        private final LostCityProfile profile;
        private final ChunkCoord topLeft;
        private final int areaSize;
        private final WorldGenLevel world;
        private final WorldStyle worldStyle;
        private final ResourceKey<Level> dimension;
        private final int radiusChunks;
        private final boolean hasPredefinedCities;
        private final CityRarityMap styleRarityMap;
        private final CityRarityMap cityRarityMap;
        private final CenterInfo[] centers;
        /**
         * Exact, plan-local spatial index for the style grid.  The legacy
         * resolver tested every discovered centre against every cell.  Centres
         * are appended in the original scan order and each bucket uses a
         * conservative block-space envelope, so this only removes impossible
         * distance checks and cannot change weighted selection order.
         */
        private final CenterInfo[][] styleCentersByCell;
        private final Map<Long, ChunkCoord> coords = new HashMap<>();
        private final Map<Long, String> cityStyleNames = new HashMap<>();
        private final Map<Long, CityStyle> cityStyles = new HashMap<>();
        private final Map<Long, Float> cityFactors = new HashMap<>();
        private final Map<Long, Boolean> railCityRaw = new HashMap<>();
        private final List<Pair<Predicate<Holder<Biome>>, Pair<Float, String>>> styleSelectors;
        private final IdentityHashMap<Holder<Biome>, SelectorChoices> selectorChoices = new IdentityHashMap<>();
        private float[] choiceWeights;
        private String[] choiceNames;

        private FastCityFacts(IDimensionInfo provider, LostCityProfile profile, ChunkCoord topLeft, int areaSize) {
            this.provider = provider;
            this.profile = profile;
            this.topLeft = topLeft;
            this.areaSize = areaSize;
            this.world = provider.getWorld();
            this.worldStyle = provider.getWorldStyle();
            this.styleSelectors = ((WorldStyleAccessor) this.worldStyle).lc2h$getCityStyleSelector();
            this.dimension = topLeft.dimension();
            this.radiusChunks = Math.max(0, (profile.CITY_MAXRADIUS + 15) / 16);
            this.hasPredefinedCities = hasPredefinedCities(world);
            this.styleRarityMap = profile.CITY_CHANCE < 0.0D && world != null
                ? City.getCityRarityMap(world.getLevel().dimension(), world.getSeed(), profile.CITY_PERLIN_SCALE, profile.CITY_PERLIN_OFFSET, profile.CITY_PERLIN_INNERSCALE)
                : null;
            this.cityRarityMap = profile.CITY_CHANCE < 0.0D
                ? City.getCityRarityMap(provider.dimension(), provider.getSeed(), profile.CITY_PERLIN_SCALE, profile.CITY_PERLIN_OFFSET, profile.CITY_PERLIN_INNERSCALE)
                : null;
            this.centers = collectCenters();
            this.styleCentersByCell = indexStyleCenters();
            this.choiceWeights = new float[Math.max(1, centers.length)];
            this.choiceNames = new String[Math.max(1, centers.length)];
        }

        private boolean supports(IDimensionInfo provider, LostCityProfile profile) {
            return this.provider == provider && this.profile == profile;
        }

        @SuppressWarnings("unchecked")
        private CenterInfo[][] indexStyleCenters() {
            int cells = areaSize * areaSize;
            CenterInfo[][] indexed = new CenterInfo[cells][];
            if (centers.length == 0 || cells == 0) {
                return indexed;
            }
            ArrayList<CenterInfo>[] collecting = new ArrayList[cells];
            int minChunkX = topLeft.chunkX();
            int minChunkZ = topLeft.chunkZ();
            int maxChunkX = minChunkX + areaSize - 1;
            int maxChunkZ = minChunkZ + areaSize - 1;
            for (CenterInfo center : centers) {
                // The later squared-distance test remains authoritative.  This
                // envelope is deliberately inclusive at both ends so floating
                // point rounding cannot exclude a valid legacy candidate.
                int fromX = Math.max(minChunkX, Math.floorDiv((int) Math.floor(center.blockX - center.radius), 16));
                int toX = Math.min(maxChunkX, Math.floorDiv((int) Math.ceil(center.blockX + center.radius), 16));
                int fromZ = Math.max(minChunkZ, Math.floorDiv((int) Math.floor(center.blockZ - center.radius), 16));
                int toZ = Math.min(maxChunkZ, Math.floorDiv((int) Math.ceil(center.blockZ + center.radius), 16));
                for (int chunkX = fromX; chunkX <= toX; chunkX++) {
                    for (int chunkZ = fromZ; chunkZ <= toZ; chunkZ++) {
                        int cell = index(chunkX - minChunkX, chunkZ - minChunkZ, areaSize);
                        ArrayList<CenterInfo> bucket = collecting[cell];
                        if (bucket == null) {
                            bucket = new ArrayList<>();
                            collecting[cell] = bucket;
                        }
                        bucket.add(center);
                    }
                }
            }
            for (int cell = 0; cell < cells; cell++) {
                ArrayList<CenterInfo> bucket = collecting[cell];
                if (bucket != null && !bucket.isEmpty()) {
                    indexed[cell] = bucket.toArray(CenterInfo[]::new);
                }
            }
            return indexed;
        }

        private CenterInfo[] collectCenters() {
            long start = System.nanoTime();
            try {
            if (profile.CITY_CHANCE < 0.0D || radiusChunks <= 0) {
                return new CenterInfo[0];
            }
            int minX = topLeft.chunkX() - radiusChunks;
            int maxX = topLeft.chunkX() + areaSize - 1 + radiusChunks;
            int minZ = topLeft.chunkZ() - radiusChunks;
            int maxZ = topLeft.chunkZ() + areaSize - 1 + radiusChunks;
            ArrayList<CenterInfo> result = new ArrayList<>();
            STYLE_CENTER_CANDIDATES.add((long) (maxX - minX + 1) * (maxZ - minZ + 1));
            CityCenterGpuCache.PreparedCenters prepared = hasPredefinedCities
                ? null
                : CityCenterGpuCache.get(provider, profile, topLeft, areaSize);
            for (int x = minX; x <= maxX; x++) {
                int blockX = x << 4;
                for (int z = minZ; z <= maxZ; z++) {
                    float radius;
                    if (prepared != null) {
                        radius = prepared.radiusAt(x, z);
                    } else {
                        if (!isCityCenterFast(x, z)) {
                            continue;
                        }
                        radius = cityRadiusFast(x, z);
                    }
                    if (radius <= 0.0F) {
                        continue;
                    }
                    result.add(new CenterInfo(x, z, blockX, z << 4, radius, radius * radius));
                }
            }
            STYLE_CENTERS.add(result.size());
            return result.toArray(CenterInfo[]::new);
            } finally {
                STYLE_CENTER_SCAN_NS.add(System.nanoTime() - start);
            }
        }

        private ChunkCoord coord(int chunkX, int chunkZ) {
            long key = packedChunk(chunkX, chunkZ);
            ChunkCoord cached = coords.get(key);
            if (cached != null) {
                return cached;
            }
            ChunkCoord created = new ChunkCoord(dimension, chunkX, chunkZ);
            coords.put(key, created);
            return created;
        }

        private CityStyle cityStyle(int chunkX, int chunkZ) {
            long key = packedChunk(chunkX, chunkZ);
            CityStyle cached = cityStyles.get(key);
            if (cached != null) {
                return cached;
            }
            ChunkCoord coord = coord(chunkX, chunkZ);
            Random random = new Random(provider.getSeed() + ((long) chunkZ * 593441843L) + ((long) chunkX * 217645177L));
            int choiceCount = 0;

            if (profile.CITY_CHANCE < 0.0D) {
                float factor = styleRarityMap == null ? 0.0F : styleRarityMap.getCityFactor(chunkX, chunkZ);
                choiceWeights[0] = factor;
                choiceNames[0] = factor < profile.CITY_STYLE_THRESHOLD
                    ? profile.CITY_STYLE_ALTERNATIVE
                    : cityStyleName(chunkX, chunkZ);
                choiceCount = 1;
            } else {
                int blockX = chunkX << 4;
                int blockZ = chunkZ << 4;
                String cityStyleName = null;
                int localX = chunkX - topLeft.chunkX();
                int localZ = chunkZ - topLeft.chunkZ();
                CenterInfo[] candidates = localX < 0 || localZ < 0 || localX >= areaSize || localZ >= areaSize
                    ? centers
                    : styleCentersByCell[index(localX, localZ, areaSize)];
                if (candidates == null) {
                    candidates = new CenterInfo[0];
                }
                for (CenterInfo center : candidates) {
                    float dx = center.blockX - blockX;
                    float dz = center.blockZ - blockZ;
                    float distanceSq = (dx * dx) + (dz * dz);
                    if (distanceSq >= center.radiusSq) {
                        continue;
                    }
                    float weight = (center.radius - (float) Math.sqrt(distanceSq)) / center.radius;
                    String name;
                    if (weight < profile.CITY_STYLE_THRESHOLD) {
                        name = profile.CITY_STYLE_ALTERNATIVE;
                    } else {
                        if (cityStyleName == null) {
                            cityStyleName = cityStyleName(chunkX, chunkZ);
                        }
                        name = cityStyleName;
                    }
                    choiceWeights[choiceCount] = weight;
                    choiceNames[choiceCount] = name;
                    choiceCount++;
                }
            }

            String name;
            if (choiceCount == 0) {
                name = randomCityStyle(coord, random);
            } else if (choiceCount == 1) {
                name = choiceNames[0];
            } else {
                float totalWeight = 0.0F;
                for (int i = 0; i < choiceCount; i++) {
                    totalWeight += choiceWeights[i];
                }
                float selectedWeight = random.nextFloat() * totalWeight;
                name = null;
                for (int i = 0; i < choiceCount; i++) {
                    selectedWeight -= choiceWeights[i];
                    if (selectedWeight <= 0.0F) {
                        name = choiceNames[i];
                        break;
                    }
                }
            }
            CityStyle resolved = (CityStyle) AssetRegistries.CITYSTYLES.get(world, name);
            if (resolved != null) {
                cityStyles.put(key, resolved);
            }
            return resolved;
        }

        private String cityStyleName(int chunkX, int chunkZ) {
            long key = packedChunk(chunkX, chunkZ);
            String cached = cityStyleNames.get(key);
            if (cached != null) {
                return cached;
            }
            ChunkCoord coord = coord(chunkX, chunkZ);
            PredefinedCity predefined = hasPredefinedCities ? City.getPredefinedCity(world, coord) : null;
            String resolved;
            if (predefined != null && predefined.getCityStyle() != null) {
                resolved = predefined.getCityStyle();
            } else {
                Random random = new Random(((long) chunkZ * 899809363L) + ((long) chunkX * 256203221L));
                resolved = randomCityStyle(coord, random);
            }
            if (resolved != null) {
                cityStyleNames.put(key, resolved);
            }
            return resolved;
        }

        private String randomCityStyle(ChunkCoord coord, Random random) {
            long biomeStart = System.nanoTime();
            Holder<Biome> biome = BiomeInfo.getBiomeInfo(provider, coord).getMainBiome();
            STYLE_BIOME_RESOLVE_NS.add(System.nanoTime() - biomeStart);

            long selectorStart = System.nanoTime();
            try {
                SelectorChoices choices = selectorChoices.get(biome);
                if (choices == null) {
                    STYLE_SELECTOR_CACHE_MISSES.increment();
                    choices = resolveSelectorChoices(biome);
                    selectorChoices.put(biome, choices);
                } else {
                    STYLE_SELECTOR_CACHE_HITS.increment();
                }
                if (choices.names.length == 0) {
                    return null;
                }

                float selectedWeight = random.nextFloat() * choices.totalWeight;
                for (int i = 0; i < choices.names.length; i++) {
                    selectedWeight -= choices.weights[i];
                    if (selectedWeight <= 0.0F) {
                        return choices.names[i];
                    }
                }
                // Matches Tools.getRandomFromList(Random, ...) exactly.
                return null;
            } finally {
                STYLE_SELECTOR_RESOLVE_NS.add(System.nanoTime() - selectorStart);
            }
        }

        private SelectorChoices resolveSelectorChoices(Holder<Biome> biome) {
            int capacity = styleSelectors.size();
            float[] weights = new float[capacity];
            String[] names = new String[capacity];
            float totalWeight = 0.0F;
            int matched = 0;
            for (Pair<Predicate<Holder<Biome>>, Pair<Float, String>> selector : styleSelectors) {
                if (!selector.getKey().test(biome)) {
                    continue;
                }
                Pair<Float, String> choice = selector.getValue();
                float weight = choice.getLeft();
                weights[matched] = weight;
                names[matched] = choice.getRight();
                totalWeight += weight;
                matched++;
            }
            if (matched == 0) {
                return SelectorChoices.EMPTY;
            }
            if (matched != capacity) {
                weights = Arrays.copyOf(weights, matched);
                names = Arrays.copyOf(names, matched);
            }
            return new SelectorChoices(weights, names, totalWeight);
        }

        private boolean isCityRaw(int chunkX, int chunkZ) {
            // Do not route the planner's per-cell rejection test through
            // BuildingInfo.isCityRaw.  That method is exact, but it rebuilds
            // Lost Cities' full City.getCityFactor path on each cold fact and
            // turns a 16x16 placement window into thousands of expensive
            // worldgen lookups.  The planner already owns the same immutable
            // factor inputs and caches them by coordinate.
            //
            // Spheres/space have extra CitySphere guards which are intentionally
            // delegated to Lost Cities. They are uncommon and correctness wins
            // over the normal-world fast path there.
            if (profile.isSpace() || profile.isSpheres()) {
                return BuildingInfo.isCityRaw(coord(chunkX, chunkZ), provider, profile);
            }
            if (cityFactor(chunkX, chunkZ) <= profile.CITY_THRESHOLD) {
                return false;
            }
            // This predicate runs while Lost Cities walks a multichunk.  Do
            // not synchronously build a cold reservation region here: the
            // reservation planner samples vanilla density and joining that
            // flight can stall every parallel worldgen worker.  A published
            // reservation still applies the exact rejection; before it is
            // published, keep Lost Cities' city-factor result.
            return !MountainCityReservationPlanner.peekRemovesBuildingCell(
                provider, coord(chunkX, chunkZ), profile);
        }

        private boolean isRailCityRaw(int chunkX, int chunkZ) {
            // Railway topology is derived from Lost Cities' base city field.
            // MountainCityReservationPlanner only reserves building cells. It
            // must not recursively expand terrain-component plans while the
            // railway graph walks far outside this multichunk window. Apart
            // from being extremely expensive, applying that building-only
            // reservation here can sever otherwise valid rail routes.
            long key = packedChunk(chunkX, chunkZ);
            Boolean cached = railCityRaw.get(key);
            if (cached != null) {
                return cached;
            }
            boolean value;
            if (profile.isSpace() || profile.isSpheres()) {
                value = BuildingInfo.isCityRaw(coord(chunkX, chunkZ), provider, profile);
            } else {
                value = cityFactor(chunkX, chunkZ) > profile.CITY_THRESHOLD;
            }
            railCityRaw.put(key, value);
            return value;
        }

        private float cityFactor(int chunkX, int chunkZ) {
            long key = packedChunk(chunkX, chunkZ);
            Float cached = cityFactors.get(key);
            if (cached != null) {
                return cached;
            }
            ChunkCoord coord = coord(chunkX, chunkZ);
            float factor = cityFactorUncached(coord, chunkX, chunkZ);
            cityFactors.put(key, factor);
            return factor;
        }

        private float cityFactorUncached(ChunkCoord coord, int chunkX, int chunkZ) {
            /*
             * City.getCityFactor is the single authoritative normal-profile
             * resolver.  LC2H's City mixin gives it a provider/profile scoped
             * cache and a predefined-city index, while this planner used to
             * rebuild the centre walk for every new multichunk plan.  Calling
             * the authority here keeps the exact Lost Cities decision and
             * lets adjacent plans reuse the immutable factor instead of
             * paying the radius scan again.
             *
             * Space and sphere profiles never reach this method from the
             * normal fast path.  Their extra profile-per-coordinate rules
             * remain delegated through isCityRaw above.
             */
            return City.getCityFactor(coord, provider, profile);
        }

        private boolean isWestMultiBuilding(int chunkX, int chunkZ) {
            PredefinedBuilding building = City.getPredefinedBuildingAtTopLeft(world, coord(chunkX - 1, chunkZ));
            return building != null && building.multi();
        }

        private boolean isNorthWestMultiBuilding(int chunkX, int chunkZ) {
            PredefinedBuilding building = City.getPredefinedBuildingAtTopLeft(world, coord(chunkX - 1, chunkZ - 1));
            return building != null && building.multi();
        }

        private boolean isNorthMultiBuilding(int chunkX, int chunkZ) {
            PredefinedBuilding building = City.getPredefinedBuildingAtTopLeft(world, coord(chunkX, chunkZ - 1));
            return building != null && building.multi();
        }

        private boolean isCityCenterFast(int chunkX, int chunkZ) {
            ChunkCoord coord = hasPredefinedCities || profile.isSpace() || profile.isSpheres()
                ? coord(chunkX, chunkZ)
                : null;
            PredefinedCity predefined = hasPredefinedCities ? City.getPredefinedCity(world, coord) : null;
            if (predefined != null) {
                return true;
            }
            long seed = ((long) chunkZ * 797003437L) + ((long) chunkX * 295075153L);
            LostCityProfile activeProfile = provider.getProfile();
            if (activeProfile.isSpace() || activeProfile.isSpheres()) {
                CitySphere sphere = CitySphere.getCitySphere(coord, provider);
                if (!sphere.isEnabled()) {
                    return firstRandomDouble(seed) < provider.getOutsideProfile().CITY_CHANCE;
                }
                ChunkCoord center = sphere.getCenter();
                return center.chunkX() == chunkX
                    && center.chunkZ() == chunkZ
                    && firstRandomDouble(seed) < activeProfile.CITY_CHANCE;
            }
            return firstRandomDouble(seed) < activeProfile.CITY_CHANCE;
        }

        private float cityRadiusFast(int chunkX, int chunkZ) {
            ChunkCoord coord = hasPredefinedCities || profile.isSpace() || profile.isSpheres()
                ? coord(chunkX, chunkZ)
                : null;
            PredefinedCity predefined = hasPredefinedCities ? City.getPredefinedCity(world, coord) : null;
            if (predefined != null) {
                return predefined.getRadius();
            }
            long seed = ((long) chunkZ * 100001653L) + ((long) chunkX * 295075153L);
            LostCityProfile activeProfile = provider.getProfile();
            int range = activeProfile.CITY_MAXRADIUS - activeProfile.CITY_MINRADIUS;
            if (range < 1) {
                range = 1;
            }
            if (activeProfile.isSpace() || activeProfile.isSpheres()) {
                if (CitySphere.intersectsWithCitySphere(coord, provider)) {
                    return activeProfile.CITY_MINRADIUS + firstRandomInt(seed, range);
                }
                LostCityProfile outsideProfile = provider.getOutsideProfile();
                int outsideRange = outsideProfile.CITY_MAXRADIUS - outsideProfile.CITY_MINRADIUS;
                if (outsideRange < 1) {
                    outsideRange = 1;
                }
                return outsideProfile.CITY_MINRADIUS + firstRandomInt(seed, outsideRange);
            }
            return activeProfile.CITY_MINRADIUS + firstRandomInt(seed, range);
        }

        private static boolean hasPredefinedCities(WorldGenLevel world) {
            if (world == null) {
                return true;
            }
            try {
                return AssetRegistries.PREDEFINED_CITIES.getNumAssets(world) > 0;
            } catch (Throwable ignored) {
                return true;
            }
        }
    }

    private record SelectorChoices(float[] weights, String[] names, float totalWeight) {
        private static final SelectorChoices EMPTY = new SelectorChoices(new float[0], new String[0], 0.0F);
    }

    private static final long RANDOM_MULTIPLIER = 0x5DEECE66DL;
    private static final long RANDOM_ADDEND = 0xBL;
    private static final long RANDOM_MASK = (1L << 48) - 1L;

    private static long randomSeed(long seed) {
        return (seed ^ RANDOM_MULTIPLIER) & RANDOM_MASK;
    }

    private static long nextRandomSeed(long state) {
        return (state * RANDOM_MULTIPLIER + RANDOM_ADDEND) & RANDOM_MASK;
    }

    private static double firstRandomDouble(long seed) {
        long state = randomSeed(seed);
        state = nextRandomSeed(state);
        long high = state >>> (48 - 26);
        state = nextRandomSeed(state);
        long low = state >>> (48 - 27);
        return ((high << 27) + low) * 0x1.0p-53;
    }

    private static int firstRandomInt(long seed, int bound) {
        if (bound <= 1) {
            return 0;
        }
        long state = nextRandomSeed(randomSeed(seed));
        int bits = (int) (state >>> (48 - 31));
        if ((bound & -bound) == bound) {
            return (int) ((bound * (long) bits) >> 31);
        }
        int value = bits % bound;
        while (bits - value + (bound - 1) < 0) {
            state = nextRandomSeed(state);
            bits = (int) (state >>> (48 - 31));
            value = bits % bound;
        }
        return value;
    }

    private static final class CenterInfo {
        private final int chunkX;
        private final int chunkZ;
        private final int blockX;
        private final int blockZ;
        private final float radius;
        private final float radiusSq;
        private boolean profileResolved;
        private LostCityProfile profile;

        private CenterInfo(int chunkX, int chunkZ, int blockX, int blockZ, float radius, float radiusSq) {
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.blockX = blockX;
            this.blockZ = blockZ;
            this.radius = radius;
            this.radiusSq = radiusSq;
        }

        private LostCityProfile profile(FastCityFacts facts) {
            if (!profileResolved) {
                profile = BuildingInfo.getProfile(facts.coord(chunkX, chunkZ), facts.provider);
                profileResolved = true;
            }
            return profile;
        }
    }

    private static final class LazyPlan {
        private final IDimensionInfo provider;
        private final LostCityProfile profile;
        private final ChunkCoord topLeft;
        private final int areaSize;
        private final CityStyle[] styles;
        private final Counter<CityStyle> counter;
        private final FastCityFacts facts;
        private final boolean[] placed;
        private final long[] placedRows;
        private final Map<CityStyle, long[]> styleRows;
        private final ChunkCoord[] coords;
        private final byte[] occupied;
        private final Railway.RailChunkInfo[] rails;
        private final byte[] cityRaw;
        private final byte[] highway;
        private final float correctStyleFactor;

        private LazyPlan(IDimensionInfo provider,
                         LostCityProfile profile,
                         ChunkCoord topLeft,
                         int areaSize,
                         CityStyle[] styles,
                         Counter<CityStyle> counter,
                         FastCityFacts facts,
                         float correctStyleFactor) {
            int size = areaSize * areaSize;
            this.provider = provider;
            this.profile = profile;
            this.topLeft = topLeft;
            this.areaSize = areaSize;
            this.styles = styles;
            this.counter = counter;
            this.facts = facts;
            this.placed = new boolean[size];
            this.placedRows = areaSize <= Long.SIZE ? new long[areaSize] : null;
            this.styleRows = buildStyleRows(styles, areaSize);
            this.coords = new ChunkCoord[size];
            this.occupied = new byte[size];
            this.rails = new Railway.RailChunkInfo[size];
            this.cityRaw = new byte[size];
            this.highway = new byte[size];
            this.correctStyleFactor = correctStyleFactor;
        }

        private static Map<CityStyle, long[]> buildStyleRows(CityStyle[] styles, int areaSize) {
            if (areaSize > Long.SIZE) {
                return Map.of();
            }
            IdentityHashMap<CityStyle, long[]> rows = new IdentityHashMap<>();
            for (int x = 0; x < areaSize; x++) {
                for (int z = 0; z < areaSize; z++) {
                    CityStyle style = styles[index(x, z, areaSize)];
                    if (style == null) {
                        continue;
                    }
                    rows.computeIfAbsent(style, ignored -> new long[areaSize])[x] |= 1L << z;
                }
            }
            return rows;
        }

        private boolean anyPlaced(int x, int z, int dimX, int dimZ) {
            if (placedRows != null) {
                long mask = bitRange(z, dimZ);
                for (int dx = 0; dx < dimX; dx++) {
                    if ((placedRows[x + dx] & mask) != 0L) {
                        return true;
                    }
                }
                return false;
            }
            for (int dx = 0; dx < dimX; dx++) {
                for (int dz = 0; dz < dimZ; dz++) {
                    if (placed[index(x + dx, z + dz, areaSize)]) {
                        return true;
                    }
                }
            }
            return false;
        }

        private int countStyle(CityStyle style, int x, int z, int dimX, int dimZ) {
            long[] rows = styleRows.get(style);
            if (rows != null) {
                int count = 0;
                long mask = bitRange(z, dimZ);
                for (int dx = 0; dx < dimX; dx++) {
                    count += Long.bitCount(rows[x + dx] & mask);
                }
                return count;
            }
            int count = 0;
            for (int dx = 0; dx < dimX; dx++) {
                for (int dz = 0; dz < dimZ; dz++) {
                    if (Objects.equals(styles[index(x + dx, z + dz, areaSize)], style)) {
                        count++;
                    }
                }
            }
            return count;
        }

        private void markPlaced(int x, int z) {
            placed[index(x, z, areaSize)] = true;
            if (placedRows != null) {
                placedRows[x] |= 1L << z;
            }
        }

        private String factSummary(int index) {
            ChunkCoord coord = coord(index);
            return factString(coord, occupied(index), rail(index), cityRaw(index), highway(index));
        }

        private String factCompareSummary(int index) {
            ChunkCoord coord = coord(index);
            return "fast[" + factSummary(index) + "] original[" + originalFactSummary(provider, profile, coord) + "]";
        }

        private FactSnapshot factSnapshot(int index) {
            Railway.RailChunkInfo rail = rail(index);
            RailChunkType railType = rail == null || rail.getType() == null ? RailChunkType.NONE : rail.getType();
            int railLevel = rail == null ? 0 : rail.getLevel();
            return new FactSnapshot(occupied(index), railType, railLevel, cityRaw(index), highway(index));
        }

        private ChunkCoord coord(int index) {
            ChunkCoord cached = coords[index];
            if (cached != null) {
                return cached;
            }
            int x = index / areaSize;
            int z = index - (x * areaSize);
            ChunkCoord coord = new ChunkCoord(topLeft.dimension(), topLeft.chunkX() + x, topLeft.chunkZ() + z);
            coords[index] = coord;
            return coord;
        }

        private boolean occupied(int index) {
            byte state = occupied[index];
            if (state != UNKNOWN) {
                return state == TRUE;
            }
            long start = System.nanoTime();
            try {
                boolean value = City.isChunkOccupied(provider, coord(index));
                occupied[index] = value ? TRUE : FALSE;
                return value;
            } finally {
                long elapsed = System.nanoTime() - start;
                LAZY_OCCUPIED_NS.add(elapsed);
                Lc2hTimingRegistry.record("fast_multichunk.lazy_occupied", elapsed);
            }
        }

        private Railway.RailChunkInfo rail(int index) {
            Railway.RailChunkInfo cached = rails[index];
            if (cached != null) {
                return cached;
            }
            long start = System.nanoTime();
            try {
                Railway.RailChunkInfo value = withRailCityFacts(
                    facts,
                    () -> Railway.getRailChunkType(coord(index), provider, profile));
                rails[index] = value == null ? Railway.RailChunkInfo.NOTHING : value;
                return rails[index];
            } finally {
                long elapsed = System.nanoTime() - start;
                LAZY_RAIL_NS.add(elapsed);
                Lc2hTimingRegistry.record("fast_multichunk.lazy_rail", elapsed);
            }
        }

        private boolean cityRaw(int index) {
            byte state = cityRaw[index];
            if (state != UNKNOWN) {
                return state == TRUE;
            }
            long start = System.nanoTime();
            try {
                ChunkCoord coord = coord(index);
                boolean value = facts.isCityRaw(coord.chunkX(), coord.chunkZ());
                cityRaw[index] = value ? TRUE : FALSE;
                return value;
            } finally {
                long elapsed = System.nanoTime() - start;
                LAZY_RAW_NS.add(elapsed);
                Lc2hTimingRegistry.record("fast_multichunk.lazy_raw", elapsed);
            }
        }

        private boolean highway(int index) {
            byte state = highway[index];
            if (state != UNKNOWN) {
                return state == TRUE;
            }
            long start = System.nanoTime();
            try {
                ChunkCoord coord = coord(index);
                boolean value;
                if (provider.getHighwayGenerationMode() == HighwayGenerationMode.INTERCITY_NETWORK_V1) {
                    // A cold route window is warmed off-thread. Treat it as
                    // highway until the immutable snapshot is published so a
                    // multi-building can never consume a route while the
                    // planner is still learning that window.
                    if (!IntercityHighwayIndex.isWarm(provider, profile, coord)) {
                        IntercityHighwayIndex.level(provider, profile, coord);
                        value = true;
                    } else {
                        value = IntercityHighwayIndex.peekLevel(provider, profile, coord, null) >= 0;
                    }
                } else {
                    value = BuildingInfo.hasHighway(coord, provider, profile);
                }
                highway[index] = value ? TRUE : FALSE;
                return value;
            } finally {
                long elapsed = System.nanoTime() - start;
                LAZY_HIGHWAY_NS.add(elapsed);
                Lc2hTimingRegistry.record("fast_multichunk.lazy_highway", elapsed);
            }
        }
    }

}
