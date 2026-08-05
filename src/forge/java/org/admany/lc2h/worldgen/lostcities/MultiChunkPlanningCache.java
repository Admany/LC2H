package org.admany.lc2h.worldgen.lostcities;

import mcjty.lostcities.api.ILostCityAsset;
import mcjty.lostcities.config.LostCityProfile;
import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.IDimensionInfo;
import mcjty.lostcities.worldgen.lost.BuildingInfo;
import mcjty.lostcities.worldgen.lost.City;
import mcjty.lostcities.worldgen.lost.Railway;
import mcjty.lostcities.worldgen.lost.cityassets.CityStyle;
import mcjty.lostcities.worldgen.lost.cityassets.RegistryAssetRegistry;
import net.minecraft.world.level.CommonLevelAccessor;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

public final class MultiChunkPlanningCache {

    private static final ThreadLocal<Context> CONTEXT = new ThreadLocal<>();
    private static final LongAdder PLANS = new LongAdder();
    private static final LongAdder OCCUPIED_HITS = new LongAdder();
    private static final LongAdder OCCUPIED_MISSES = new LongAdder();
    private static final LongAdder CITY_RAW_HITS = new LongAdder();
    private static final LongAdder CITY_RAW_MISSES = new LongAdder();
    private static final LongAdder HIGHWAY_HITS = new LongAdder();
    private static final LongAdder HIGHWAY_MISSES = new LongAdder();
    private static final LongAdder RAIL_HITS = new LongAdder();
    private static final LongAdder RAIL_MISSES = new LongAdder();
    private static final LongAdder STYLE_HITS = new LongAdder();
    private static final LongAdder STYLE_MISSES = new LongAdder();
    private static final LongAdder ASSET_HITS = new LongAdder();
    private static final LongAdder ASSET_MISSES = new LongAdder();

    private MultiChunkPlanningCache() {
    }

    public static void begin() {
        Context context = CONTEXT.get();
        if (context == null) {
            context = new Context();
            CONTEXT.set(context);
            PLANS.increment();
        }
        context.depth++;
    }

    public static void end() {
        Context context = CONTEXT.get();
        if (context == null) {
            return;
        }
        context.depth--;
        if (context.depth <= 0) {
            CONTEXT.remove();
        }
    }

    public static void clear() {
        CONTEXT.remove();
    }

    public static boolean isChunkOccupied(IDimensionInfo provider, ChunkCoord coord) {
        Context context = CONTEXT.get();
        if (context == null || coord == null) {
            return City.isChunkOccupied(provider, coord);
        }
        Boolean cached = context.occupied.get(coord);
        if (cached != null) {
            OCCUPIED_HITS.increment();
            return cached;
        }
        OCCUPIED_MISSES.increment();
        boolean value = City.isChunkOccupied(provider, coord);
        context.occupied.put(coord, value);
        return value;
    }

    public static boolean isCityRaw(ChunkCoord coord, IDimensionInfo provider, LostCityProfile profile) {
        Context context = CONTEXT.get();
        if (context == null || coord == null) {
            return BuildingInfo.isCityRaw(coord, provider, profile);
        }
        Boolean cached = context.cityRaw.get(coord);
        if (cached != null) {
            CITY_RAW_HITS.increment();
            return cached;
        }
        CITY_RAW_MISSES.increment();
        boolean value = BuildingInfo.isCityRaw(coord, provider, profile);
        context.cityRaw.put(coord, value);
        return value;
    }

    public static boolean hasHighway(ChunkCoord coord, IDimensionInfo provider, LostCityProfile profile) {
        Context context = CONTEXT.get();
        if (context == null || coord == null) {
            return BuildingInfo.hasHighway(coord, provider, profile);
        }
        Boolean cached = context.highway.get(coord);
        if (cached != null) {
            HIGHWAY_HITS.increment();
            return cached;
        }
        HIGHWAY_MISSES.increment();
        boolean value = BuildingInfo.hasHighway(coord, provider, profile);
        context.highway.put(coord, value);
        return value;
    }

    public static Railway.RailChunkInfo railChunkType(ChunkCoord coord, IDimensionInfo provider, LostCityProfile profile) {
        Context context = CONTEXT.get();
        if (context == null || coord == null) {
            return Railway.getRailChunkType(coord, provider, profile);
        }
        Railway.RailChunkInfo cached = context.rail.get(coord);
        if (cached != null) {
            RAIL_HITS.increment();
            return cached;
        }
        RAIL_MISSES.increment();
        Railway.RailChunkInfo value = Railway.getRailChunkType(coord, provider, profile);
        context.rail.put(coord, value);
        return value;
    }

    public static CityStyle cityStyle(ChunkCoord coord, IDimensionInfo provider, LostCityProfile profile) {
        Context context = CONTEXT.get();
        if (context == null || coord == null) {
            return City.getCityStyle(coord, provider, profile);
        }
        CityStyle cached = context.cityStyle.get(coord);
        if (cached != null) {
            STYLE_HITS.increment();
            return cached;
        }
        STYLE_MISSES.increment();
        CityStyle value = City.getCityStyle(coord, provider, profile);
        if (value != null) {
            context.cityStyle.put(coord, value);
        }
        return value;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static ILostCityAsset asset(RegistryAssetRegistry registry, CommonLevelAccessor world, String name) {
        Context context = CONTEXT.get();
        if (context == null || registry == null || name == null) {
            return registry == null ? null : (ILostCityAsset) registry.get(world, name);
        }
        Map<String, ILostCityAsset> registryCache = context.assets.computeIfAbsent(registry, ignored -> new HashMap<>());
        if (registryCache.containsKey(name)) {
            ASSET_HITS.increment();
            return registryCache.get(name);
        }
        ASSET_MISSES.increment();
        ILostCityAsset value = (ILostCityAsset) registry.get(world, name);
        registryCache.put(name, value);
        return value;
    }

    public static String diagnostics() {
        return String.format(Locale.ROOT,
            "plans=%d occupied=%d/%d raw=%d/%d highway=%d/%d rail=%d/%d style=%d/%d assets=%d/%d",
            PLANS.sum(),
            OCCUPIED_HITS.sum(),
            OCCUPIED_MISSES.sum(),
            CITY_RAW_HITS.sum(),
            CITY_RAW_MISSES.sum(),
            HIGHWAY_HITS.sum(),
            HIGHWAY_MISSES.sum(),
            RAIL_HITS.sum(),
            RAIL_MISSES.sum(),
            STYLE_HITS.sum(),
            STYLE_MISSES.sum(),
            ASSET_HITS.sum(),
            ASSET_MISSES.sum());
    }

    private static final class Context {
        private int depth;
        private final HashMap<ChunkCoord, Boolean> occupied = new HashMap<>();
        private final HashMap<ChunkCoord, Boolean> cityRaw = new HashMap<>();
        private final HashMap<ChunkCoord, Boolean> highway = new HashMap<>();
        private final HashMap<ChunkCoord, Railway.RailChunkInfo> rail = new HashMap<>();
        private final HashMap<ChunkCoord, CityStyle> cityStyle = new HashMap<>();
        private final IdentityHashMap<RegistryAssetRegistry<?, ?>, Map<String, ILostCityAsset>> assets = new IdentityHashMap<>();
    }
}
