package org.admany.lc2h.worldgen.terrain;

import mcjty.lostcities.config.HighwayGenerationMode;
import mcjty.lostcities.config.LostCityProfile;
import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.IDimensionInfo;
import mcjty.lostcities.worldgen.highway.HighwayAxis;
import mcjty.lostcities.worldgen.lost.BuildingInfo;
import mcjty.lostcities.worldgen.lost.City;
import mcjty.lostcities.worldgen.lost.CitySphere;
import mcjty.lostcities.worldgen.lost.Highway;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.admany.lc2h.worldgen.lostcities.ChunkRoleProbe;
import org.admany.lc2h.worldgen.terrain.NaturalHeightSampler;
import org.admany.lc2h.worldgen.terrain.IntercityHighwayIndex;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Comparator;
import java.util.PriorityQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/** Builds one compact mountain envelope before LC chooses multibuildings.
 * The result is shared by structure placement and the density transform. */
public final class MountainCityReservationPlanner {

    /* Terrain reservation alters Lost Cities' city predicate. Keep it opt-in
     * until its output has visual parity across the modded terrain packs we
     * support. The normal LC2H path still retains the safe caches and
     * parallel planning, but never removes a native city cell. */
    private static final boolean ENABLED = Boolean.parseBoolean(
        System.getProperty("lc2h.terrain.reservation.enabled", "false"));

    private static final int REGION_SIDE = 32;
    private static final int HALO = 8;
    private static final int GRID_SIDE = REGION_SIDE + HALO * 2;
    public static final int MIN_RISE = Math.max(8, Math.min(48,
        Integer.getInteger("lc2h.terrain.reservation.minRise", 12)));
    private static final int MIN_COMPONENT_CELLS = Math.max(4, Math.min(32,
        Integer.getInteger("lc2h.terrain.reservation.minComponentCells", 6)));
    private static final int MAX_RESERVED_CELLS = Math.max(8, Math.min(64,
        Integer.getInteger("lc2h.terrain.reservation.maxCells", 24)));
    private static final double MAX_CITY_SHARE = doubleProperty(
        "lc2h.terrain.reservation.maxCityShare", 0.12D, 0.04D, 0.25D);
    private static final int MAX_TUNNEL_HALF_LENGTH = Math.max(2, Math.min(HALO,
        Integer.getInteger("lc2h.terrain.reservation.maxTunnelHalfLength", 8)));
    private static final int MIN_TUNNEL_COVER = Math.max(6, Math.min(24,
        Integer.getInteger("lc2h.terrain.reservation.minTunnelCover", 10)));
    private static final int MAX_CACHE = Math.max(64,
        Integer.getInteger("lc2h.terrain.reservation.cacheMax", 256));

    /** Boundary cells keep a little native relief so the density lattice can
     * ease into the city floor instead of making a hard wall. */
    private static final double OUTER_SHELL_CITY_WEIGHT = doubleProperty(
        "lc2h.terrain.reservation.outerShellCityWeight", 0.82D, 0.60D, 0.95D);
    private static final double INNER_SHELL_CITY_WEIGHT = doubleProperty(
        "lc2h.terrain.reservation.innerShellCityWeight", 0.45D, 0.15D, 0.75D);

    private static final ConcurrentHashMap<RegionKey, RegionPlan> CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentLinkedQueue<RegionKey> CACHE_ORDER = new ConcurrentLinkedQueue<>();
    /* Cold regions can be requested by several worldgen workers at once. */
    private static final ConcurrentHashMap<RegionKey, CompletableFuture<RegionPlan>> IN_FLIGHT =
        new ConcurrentHashMap<>();
    private static final ThreadLocal<Boolean> PLANNING = ThreadLocal.withInitial(() -> Boolean.FALSE);
    private static final AtomicLong REGIONS = new AtomicLong();
    private static final AtomicLong HITS = new AtomicLong();
    private static final AtomicLong FLIGHT_WAITS = new AtomicLong();
    private static final AtomicLong RESERVED = new AtomicLong();
    private static final AtomicLong TRANSITIONS = new AtomicLong();
    private static final AtomicLong TUNNELS = new AtomicLong();
    private static final AtomicLong TUNNEL_REJECTS = new AtomicLong();
    private static final AtomicLong PATCH_REJECTS = new AtomicLong();
    private static final AtomicLong HIGHWAY_LOOKUPS = new AtomicLong();

    private MountainCityReservationPlanner() {
    }

    public enum Disposition {
        CITY,
        MOUNTAIN_TRANSITION,
        PRESERVED_MOUNTAIN,
        ENCLOSED_TUNNEL;

        public boolean removesBuildingCell() {
            return this != CITY;
        }
    }

    public record CellPlan(
        Disposition disposition,
        int naturalHeight,
        int cityGround,
        int componentSize,
        int coreDepth,
        int patchDepth,
        double cityShapeWeight,
        int regionReserved,
        int regionBudget,
        String reason
    ) {
        public boolean removesBuildingCell() {
            return disposition.removesBuildingCell();
        }
    }

    public static CellPlan plan(IDimensionInfo provider,
                                ResourceKey<Level> dimension,
                                int chunkX,
                                int chunkZ,
                                LostCityProfile profile) {
        if (!ENABLED) {
            return cityPlan(profile == null ? 0 : profile.GROUNDLEVEL,
                "terrain reservation disabled");
        }
        if (provider == null || dimension == null || profile == null
            || profile.isSpace() || profile.isSpheres()) {
            return cityPlan(profile == null ? 0 : profile.GROUNDLEVEL, "unsupported terrain mode");
        }
        int regionX = Math.floorDiv(chunkX, REGION_SIDE);
        int regionZ = Math.floorDiv(chunkZ, REGION_SIDE);
        RegionKey key = new RegionKey(provider, dimension, regionX, regionZ, profile.GROUNDLEVEL);
        RegionPlan region = CACHE.get(key);
        if (region == null) {
            CompletableFuture<RegionPlan> created = new CompletableFuture<>();
            CompletableFuture<RegionPlan> existing = IN_FLIGHT.putIfAbsent(key, created);
            if (existing == null) {
                try {
                    // Recheck after claiming the flight. A racing publisher
                    // or lifecycle callback may already have filled the cache.
                    region = CACHE.get(key);
                    if (region == null) {
                        region = buildRegion(provider, dimension, regionX, regionZ, profile);
                        RegionPlan previous = CACHE.putIfAbsent(key, region);
                        if (previous != null) {
                            region = previous;
                        } else {
                            CACHE_ORDER.add(key);
                            trimCache();
                        }
                    }
                    created.complete(region);
                } catch (RuntimeException | Error failure) {
                    created.completeExceptionally(failure);
                    throw failure;
                } finally {
                    IN_FLIGHT.remove(key, created);
                }
            } else {
                FLIGHT_WAITS.incrementAndGet();
                region = existing.join();
            }
        } else {
            HITS.incrementAndGet();
        }
        int localX = Math.floorMod(chunkX, REGION_SIDE);
        int localZ = Math.floorMod(chunkZ, REGION_SIDE);
        return region.cells[localZ * REGION_SIDE + localX];
    }

    private static void trimCache() {
        while (CACHE.size() > MAX_CACHE) {
            RegionKey oldest = CACHE_ORDER.poll();
            if (oldest == null) {
                return;
            }
            CACHE.remove(oldest);
        }
    }

    public static boolean removesBuildingCell(IDimensionInfo provider,
                                              ChunkCoord coord,
                                              LostCityProfile profile) {
        if (!ENABLED) {
            return false;
        }
        return coord != null && plan(provider, coord.dimension(), coord.chunkX(), coord.chunkZ(), profile)
            .removesBuildingCell();
    }

    /**
     * Returns a reservation decision only when this region has already been
     * published. Density and structure planning use this non blocking view so
     * a cold reservation build cannot run Lost Cities' full height sampler
     * while Minecraft is checking structures. A missing publication means the
     * caller keeps Lost Cities' original decision for this query; the owning
     * terrain path remains responsible for publishing the authoritative plan.
     */
    public static boolean peekRemovesBuildingCell(IDimensionInfo provider,
                                                  ChunkCoord coord,
                                                  LostCityProfile profile) {
        if (!ENABLED) {
            return false;
        }
        if (provider == null || coord == null || profile == null
            || profile.isSpace() || profile.isSpheres()) {
            return false;
        }
        RegionKey key = new RegionKey(provider, coord.dimension(),
            Math.floorDiv(coord.chunkX(), REGION_SIDE),
            Math.floorDiv(coord.chunkZ(), REGION_SIDE), profile.GROUNDLEVEL);
        RegionPlan region = CACHE.get(key);
        if (region == null) {
            CompletableFuture<RegionPlan> flight = IN_FLIGHT.get(key);
            if (flight != null) {
                try {
                    region = flight.getNow(null);
                } catch (RuntimeException ignored) {
                    region = null;
                }
            }
        }
        if (region == null) {
            return false;
        }
        int localX = Math.floorMod(coord.chunkX(), REGION_SIDE);
        int localZ = Math.floorMod(coord.chunkZ(), REGION_SIDE);
        return region.cells[localZ * REGION_SIDE + localX].removesBuildingCell();
    }

    private static RegionPlan buildRegion(IDimensionInfo provider,
                                          ResourceKey<Level> dimension,
                                          int regionX,
                                          int regionZ,
                                          LostCityProfile profile) {
        boolean outermost = !PLANNING.get();
        if (outermost) {
            PLANNING.set(Boolean.TRUE);
        }
        try {
            return buildRegionInternal(provider, dimension, regionX, regionZ, profile);
        } finally {
            if (outermost) {
                PLANNING.remove();
            }
        }
    }

    private static RegionPlan buildRegionInternal(IDimensionInfo provider,
                                                   ResourceKey<Level> dimension,
                                                   int regionX,
                                                   int regionZ,
                                                   LostCityProfile profile) {
        REGIONS.incrementAndGet();
        int originX = regionX * REGION_SIDE;
        int originZ = regionZ * REGION_SIDE;
        int cityGround = profile.GROUNDLEVEL;
        int cells = GRID_SIDE * GRID_SIDE;
        int[] heights = new int[cells];
        boolean[] elevated = new boolean[cells];
        boolean[] baseCity = new boolean[cells];

    /* Heights in the halo identify one connected landform. Raw city checks stay
     * inside the owned 32x32 cells because the halo cannot reserve structures. */
        for (int gz = 0; gz < GRID_SIDE; gz++) {
            int chunkZ = originZ + gz - HALO;
            for (int gx = 0; gx < GRID_SIDE; gx++) {
                int chunkX = originX + gx - HALO;
                int gridIndex = index(gx, gz);
                ChunkCoord coord = new ChunkCoord(dimension, chunkX, chunkZ);
                heights[gridIndex] = representativeHeight(provider, coord, cityGround);
                elevated[gridIndex] = heights[gridIndex] >= cityGround + MIN_RISE;
                if (isOwnedGridCell(gx, gz)) {
                    baseCity[gridIndex] = rawCity(coord, provider, profile);
                }
            }
        }

        int[] component = new int[cells];
        int[] componentSize = new int[cells + 1];
        labelComponents(elevated, component, componentSize);

        int ownedCityCells = 0;
        int[] eligiblePerComponent = new int[componentSize.length];
        int[] peakPerComponent = new int[componentSize.length];
        int[] peakIndexPerComponent = new int[componentSize.length];
        Arrays.fill(peakIndexPerComponent, -1);
        for (int localZ = 0; localZ < REGION_SIDE; localZ++) {
            for (int localX = 0; localX < REGION_SIDE; localX++) {
                int gridIndex = index(localX + HALO, localZ + HALO);
                if (baseCity[gridIndex]) {
                    ownedCityCells++;
                }
                int id = component[gridIndex];
                if (!baseCity[gridIndex] || !elevated[gridIndex] || id == 0) {
                    continue;
                }
                eligiblePerComponent[id]++;
                if (peakIndexPerComponent[id] < 0 || heights[gridIndex] > peakPerComponent[id]) {
                    peakPerComponent[id] = heights[gridIndex];
                    peakIndexPerComponent[id] = gridIndex;
                }
            }
        }

        int budget = boundedBudget(ownedCityCells);
        int selectedComponent = strongestComponent(
            eligiblePerComponent, componentSize, peakPerComponent, budget);
        if (selectedComponent == 0) {
            return cityRegion(heights, component, componentSize, cityGround, budget,
                "no connected mountain patch fits the regional budget");
        }

        boolean[] eligible = new boolean[cells];
        for (int localZ = 0; localZ < REGION_SIDE; localZ++) {
            for (int localX = 0; localX < REGION_SIDE; localX++) {
                int gridIndex = index(localX + HALO, localZ + HALO);
                eligible[gridIndex] = baseCity[gridIndex]
                    && elevated[gridIndex]
                    && component[gridIndex] == selectedComponent;
            }
        }

        boolean[] selected = compactSelection(eligible, heights, GRID_SIDE, budget);
        int accepted = countTrue(selected);
        if (accepted < MIN_COMPONENT_CELLS) {
            PATCH_REJECTS.incrementAndGet();
            return cityRegion(heights, component, componentSize, cityGround, budget,
                "compact patch was too small to form a stable landform");
        }

        TunnelResult tunnelResult = validateTunnelEnvelope(
            provider, dimension, profile, originX, originZ,
            heights, elevated, baseCity, selected);
        if (!tunnelResult.valid) {
            TUNNEL_REJECTS.incrementAndGet();
            PATCH_REJECTS.incrementAndGet();
            return cityRegion(heights, component, componentSize, cityGround, budget,
                tunnelResult.reason);
        }

        int[] patchDepth = selectedDepth(selected, GRID_SIDE);
        CellPlan[] result = new CellPlan[REGION_SIDE * REGION_SIDE];
        for (int localZ = 0; localZ < REGION_SIDE; localZ++) {
            for (int localX = 0; localX < REGION_SIDE; localX++) {
                int ownedIndex = localZ * REGION_SIDE + localX;
                int gridIndex = index(localX + HALO, localZ + HALO);
                int id = component[gridIndex];
                if (!selected[gridIndex]) {
                    result[ownedIndex] = new CellPlan(
                        Disposition.CITY, heights[gridIndex], cityGround,
                        id == 0 ? 0 : componentSize[id], 0, 0, 1.0D,
                        accepted, budget, "kept for Lost Cities outside the compact mountain envelope");
                    continue;
                }

                int naturalCoreDepth = coreDepth(localX + HALO, localZ + HALO, id, component);
                int compactDepth = patchDepth[gridIndex];
                boolean tunnel = tunnelResult.tunnelCells[gridIndex];
                Disposition disposition;
                double cityWeight;
                String reason;
                if (tunnel) {
                    disposition = Disposition.ENCLOSED_TUNNEL;
                    cityWeight = 0.0D;
                    reason = "complete highway corridor and both side walls remain inside one enclosed mountain envelope";
                    TUNNELS.incrementAndGet();
                } else if (compactDepth <= 0) {
                    disposition = Disposition.MOUNTAIN_TRANSITION;
                    cityWeight = OUTER_SHELL_CITY_WEIGHT;
                    reason = "outer density shell grades the preserved landform into the city";
                    TRANSITIONS.incrementAndGet();
                } else if (compactDepth == 1) {
                    disposition = Disposition.MOUNTAIN_TRANSITION;
                    cityWeight = INNER_SHELL_CITY_WEIGHT;
                    reason = "inner density shell retains relief while approaching the mountain core";
                    TRANSITIONS.incrementAndGet();
                } else {
                    disposition = Disposition.PRESERVED_MOUNTAIN;
                    cityWeight = 0.0D;
                    reason = "compact connected mountain core reserved before structure placement";
                }
                result[ownedIndex] = new CellPlan(
                    disposition, heights[gridIndex], cityGround,
                    componentSize[id], naturalCoreDepth, compactDepth, cityWeight,
                    accepted, budget, reason);
                RESERVED.incrementAndGet();
            }
        }
        return new RegionPlan(result);
    }

    /** Grows one connected patch so selection cannot jump to a second peak or
     * leave isolated one-cell islands. */
    static boolean[] compactSelection(boolean[] eligible, int[] heights, int side, int budget) {
        if (eligible == null || heights == null || eligible.length != heights.length
            || eligible.length != side * side || budget <= 0) {
            return new boolean[eligible == null ? 0 : eligible.length];
        }
        int[] depth = eligibleDepth(eligible, side);
        int seed = -1;
        for (int i = 0; i < eligible.length; i++) {
            if (!eligible[i]) {
                continue;
            }
            if (seed < 0
                || depth[i] > depth[seed]
                || (depth[i] == depth[seed] && heights[i] > heights[seed])
                || (depth[i] == depth[seed] && heights[i] == heights[seed] && i < seed)) {
                seed = i;
            }
        }
        boolean[] selected = new boolean[eligible.length];
        if (seed < 0) {
            return selected;
        }

        int seedX = seed % side;
        int seedZ = seed / side;
        boolean[] queued = new boolean[eligible.length];
        PriorityQueue<Integer> frontier = new PriorityQueue<>(Comparator
            .comparingInt((Integer i) -> distanceSquared(i, seedX, seedZ, side))
            .thenComparing((Integer i) -> -depth[i])
            .thenComparing((Integer i) -> -heights[i])
            .thenComparingInt(Integer::intValue));
        frontier.add(seed);
        queued[seed] = true;
        int accepted = 0;
        while (!frontier.isEmpty() && accepted < budget) {
            int current = frontier.remove();
            if (!eligible[current] || selected[current]) {
                continue;
            }
            selected[current] = true;
            accepted++;
            int x = current % side;
            int z = current / side;
            enqueueFrontier(x - 1, z, side, eligible, queued, frontier);
            enqueueFrontier(x + 1, z, side, eligible, queued, frontier);
            enqueueFrontier(x, z - 1, side, eligible, queued, frontier);
            enqueueFrontier(x, z + 1, side, eligible, queued, frontier);
        }
        return selected;
    }

    static int[] selectedDepth(boolean[] selected, int side) {
        return eligibleDepth(selected, side);
    }

    private static int[] eligibleDepth(boolean[] eligible, int side) {
        int[] depth = new int[eligible.length];
        Arrays.fill(depth, Integer.MAX_VALUE);
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        for (int i = 0; i < eligible.length; i++) {
            if (!eligible[i]) {
                depth[i] = -1;
                continue;
            }
            int x = i % side;
            int z = i / side;
            if (x == 0 || z == 0 || x == side - 1 || z == side - 1
                || !eligible[i - 1] || !eligible[i + 1]
                || !eligible[i - side] || !eligible[i + side]) {
                depth[i] = 0;
                queue.addLast(i);
            }
        }
        while (!queue.isEmpty()) {
            int current = queue.removeFirst();
            int x = current % side;
            int z = current / side;
            propagateDepth(x - 1, z, side, current, eligible, depth, queue);
            propagateDepth(x + 1, z, side, current, eligible, depth, queue);
            propagateDepth(x, z - 1, side, current, eligible, depth, queue);
            propagateDepth(x, z + 1, side, current, eligible, depth, queue);
        }
        return depth;
    }

    private static TunnelResult validateTunnelEnvelope(IDimensionInfo provider,
                                                       ResourceKey<Level> dimension,
                                                       LostCityProfile profile,
                                                       int originX,
                                                       int originZ,
                                                       int[] heights,
                                                       boolean[] elevated,
                                                       boolean[] baseCity,
                                                       boolean[] selected) {
        boolean[] tunnelCells = new boolean[selected.length];
        int[] xHighway = new int[selected.length];
        int[] zHighway = new int[selected.length];
        Arrays.fill(xHighway, Integer.MIN_VALUE);
        Arrays.fill(zHighway, Integer.MIN_VALUE);

        for (int gridIndex = 0; gridIndex < selected.length; gridIndex++) {
            if (!selected[gridIndex]) {
                continue;
            }
            int gx = gridIndex % GRID_SIDE;
            int gz = gridIndex / GRID_SIDE;
            int chunkX = originX + gx - HALO;
            int chunkZ = originZ + gz - HALO;
            ChunkCoord coord = new ChunkCoord(dimension, chunkX, chunkZ);
            int xLevel = highwayLevel(coord, provider, profile, true);
            int zLevel = highwayLevel(coord, provider, profile, false);
            if (xLevel < 0 && zLevel < 0) {
                continue;
            }

            boolean safeX = xLevel >= 0 && populateAndValidateTunnel(
                provider, dimension, profile, originX, originZ,
                gx, gz, true, heights, elevated, baseCity, selected, xHighway);
            boolean safeZ = zLevel >= 0 && populateAndValidateTunnel(
                provider, dimension, profile, originX, originZ,
                gx, gz, false, heights, elevated, baseCity, selected, zHighway);
            if (!safeX && !safeZ) {
                return new TunnelResult(false, tunnelCells,
                    "reservation rejected because a highway would expose one portal or side wall");
            }
            if (safeX) {
                markTunnelRoute(gx, gz, true, selected, elevated, tunnelCells);
            }
            if (safeZ) {
                markTunnelRoute(gx, gz, false, selected, elevated, tunnelCells);
            }
        }
        return new TunnelResult(true, tunnelCells, "tunnel envelope valid");
    }

    private static boolean populateAndValidateTunnel(IDimensionInfo provider,
                                                     ResourceKey<Level> dimension,
                                                     LostCityProfile profile,
                                                     int originX,
                                                     int originZ,
                                                     int gx,
                                                     int gz,
                                                     boolean alongX,
                                                     int[] heights,
                                                     boolean[] elevated,
                                                     boolean[] baseCity,
                                                     boolean[] selected,
                                                     int[] highway) {
        for (int step = -MAX_TUNNEL_HALF_LENGTH; step <= MAX_TUNNEL_HALF_LENGTH; step++) {
            int x = gx + (alongX ? step : 0);
            int z = gz + (alongX ? 0 : step);
            if (x <= 0 || z <= 0 || x >= GRID_SIDE - 1 || z >= GRID_SIDE - 1) {
                continue;
            }
            int current = index(x, z);
            if (highway[current] == Integer.MIN_VALUE) {
                ChunkCoord coord = new ChunkCoord(
                    dimension, originX + x - HALO, originZ + z - HALO);
                highway[current] = highwayLevel(coord, provider, profile, alongX);
            }
        }
        if (!enclosedTunnel(gx, gz, alongX, heights, elevated, highway, profile.GROUNDLEVEL)) {
            return false;
        }
        return tunnelEnvelopeContained(gx, gz, alongX, heights, elevated, baseCity, selected);
    }

    static boolean enclosedTunnel(int gx,
                                  int gz,
                                  boolean alongX,
                                  int[] heights,
                                  boolean[] elevated,
                                  int[] highway,
                                  int cityGround) {
        return tunnelExit(gx, gz, alongX, -1, heights, elevated, highway, cityGround)
            && tunnelExit(gx, gz, alongX, 1, heights, elevated, highway, cityGround);
    }

    private static boolean tunnelEnvelopeContained(int startX,
                                                   int startZ,
                                                   boolean alongX,
                                                   int[] heights,
                                                   boolean[] elevated,
                                                   boolean[] baseCity,
                                                   boolean[] selected) {
        for (int direction : new int[]{-1, 1}) {
            for (int step = 0; step <= MAX_TUNNEL_HALF_LENGTH; step++) {
                int x = startX + (alongX ? direction * step : 0);
                int z = startZ + (alongX ? 0 : direction * step);
                if (x <= 0 || z <= 0 || x >= GRID_SIDE - 1 || z >= GRID_SIDE - 1) {
                    return false;
                }
                int current = index(x, z);
                if (!elevated[current]) {
                    if (step == 0) {
                        return false;
                    }
                    break;
                }
                int sideA = alongX ? index(x, z - 1) : index(x - 1, z);
                int sideB = alongX ? index(x, z + 1) : index(x + 1, z);
                int sideAX = alongX ? x : x - 1;
                int sideAZ = alongX ? z - 1 : z;
                int sideBX = alongX ? x : x + 1;
                int sideBZ = alongX ? z + 1 : z;
                if (!isOwnedGridCell(x, z)
                    || !isOwnedGridCell(sideAX, sideAZ)
                    || !isOwnedGridCell(sideBX, sideBZ)
                    || heights[sideA] < MIN_TUNNEL_COVER
                    || heights[sideB] < MIN_TUNNEL_COVER) {
                    return false;
                }
    /* City route and wall cells must fit inside the reservation. Natural cells
     * do not consume the city budget, but stay in the envelope for side walls. */
                if (baseCity[current] && !selected[current]) {
                    return false;
                }
                if (baseCity[sideA] && !selected[sideA]) {
                    return false;
                }
                if (baseCity[sideB] && !selected[sideB]) {
                    return false;
                }
                selected[current] = true;
                selected[sideA] = true;
                selected[sideB] = true;
            }
        }
        return true;
    }

    private static void markTunnelRoute(int startX,
                                        int startZ,
                                        boolean alongX,
                                        boolean[] selected,
                                        boolean[] elevated,
                                        boolean[] tunnelCells) {
        for (int direction : new int[]{-1, 1}) {
            for (int step = 0; step <= MAX_TUNNEL_HALF_LENGTH; step++) {
                int x = startX + (alongX ? direction * step : 0);
                int z = startZ + (alongX ? 0 : direction * step);
                if (x < 0 || z < 0 || x >= GRID_SIDE || z >= GRID_SIDE) {
                    break;
                }
                int current = index(x, z);
                if (!elevated[current]) {
                    break;
                }
                if (selected[current]) {
                    tunnelCells[current] = true;
                }
            }
        }
    }

    /** A tunnel is valid only when its route has terrain on both sides and
     * reaches a portal inside the halo. */
    private static boolean tunnelExit(int startX,
                                      int startZ,
                                      boolean alongX,
                                      int direction,
                                      int[] heights,
                                      boolean[] elevated,
                                      int[] highway,
                                      int cityGround) {
        for (int step = 0; step <= MAX_TUNNEL_HALF_LENGTH; step++) {
            int x = startX + (alongX ? direction * step : 0);
            int z = startZ + (alongX ? 0 : direction * step);
            if (x <= 0 || z <= 0 || x >= GRID_SIDE - 1 || z >= GRID_SIDE - 1) {
                return false;
            }
            int current = index(x, z);
            if (highway[current] < 0) {
                return false;
            }
            if (!elevated[current]) {
                return step > 0;
            }
            int sideA = alongX ? index(x, z - 1) : index(x - 1, z);
            int sideB = alongX ? index(x, z + 1) : index(x + 1, z);
            if (!elevated[sideA] || !elevated[sideB]
                || heights[sideA] < cityGround + MIN_TUNNEL_COVER
                || heights[sideB] < cityGround + MIN_TUNNEL_COVER) {
                return false;
            }
        }
        return false;
    }

    private static int highwayLevel(ChunkCoord coord,
                                    IDimensionInfo provider,
                                    LostCityProfile profile,
                                    boolean alongX) {
        HIGHWAY_LOOKUPS.incrementAndGet();
        try {
            if (provider.getHighwayGenerationMode() == HighwayGenerationMode.INTERCITY_NETWORK_V1) {
                return IntercityHighwayIndex.level(provider, profile, coord,
                    alongX ? HighwayAxis.X : HighwayAxis.Z);
            }
            return alongX
                ? Highway.getXHighwayLevel(coord, provider, profile)
                : Highway.getZHighwayLevel(coord, provider, profile);
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static void labelComponents(boolean[] elevated, int[] component, int[] componentSize) {
        int componentId = 0;
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        for (int start = 0; start < elevated.length; start++) {
            if (!elevated[start] || component[start] != 0) {
                continue;
            }
            componentId++;
            component[start] = componentId;
            queue.add(start);
            int size = 0;
            while (!queue.isEmpty()) {
                int current = queue.removeFirst();
                size++;
                int x = current % GRID_SIDE;
                int z = current / GRID_SIDE;
                if (x > 0) {
                    enqueueComponent(current - 1, componentId, elevated, component, queue);
                }
                if (x + 1 < GRID_SIDE) {
                    enqueueComponent(current + 1, componentId, elevated, component, queue);
                }
                if (z > 0) {
                    enqueueComponent(current - GRID_SIDE, componentId, elevated, component, queue);
                }
                if (z + 1 < GRID_SIDE) {
                    enqueueComponent(current + GRID_SIDE, componentId, elevated, component, queue);
                }
            }
            componentSize[componentId] = size;
        }
    }

    private static int strongestComponent(int[] eligiblePerComponent,
                                          int[] componentSize,
                                          int[] peakPerComponent,
                                          int budget) {
        if (budget < MIN_COMPONENT_CELLS) {
            return 0;
        }
        int strongest = 0;
        long strongestScore = Long.MIN_VALUE;
        for (int id = 1; id < eligiblePerComponent.length; id++) {
            int eligible = eligiblePerComponent[id];
            if (eligible < MIN_COMPONENT_CELLS) {
                continue;
            }
            long score = (long) Math.min(eligible, budget) * 1_000_000L
                + (long) componentSize[id] * 1_000L
                + peakPerComponent[id];
            if (score > strongestScore) {
                strongest = id;
                strongestScore = score;
            }
        }
        return strongest;
    }

    private static RegionPlan cityRegion(int[] heights,
                                         int[] component,
                                         int[] componentSize,
                                         int cityGround,
                                         int budget,
                                         String reason) {
        CellPlan[] result = new CellPlan[REGION_SIDE * REGION_SIDE];
        for (int localZ = 0; localZ < REGION_SIDE; localZ++) {
            for (int localX = 0; localX < REGION_SIDE; localX++) {
                int ownedIndex = localZ * REGION_SIDE + localX;
                int gridIndex = index(localX + HALO, localZ + HALO);
                int id = component[gridIndex];
                result[ownedIndex] = new CellPlan(
                    Disposition.CITY, heights[gridIndex], cityGround,
                    id == 0 ? 0 : componentSize[id], 0, 0, 1.0D,
                    0, budget, reason);
            }
        }
        return new RegionPlan(result);
    }

    private static int coreDepth(int startX, int startZ, int componentId, int[] component) {
        if (componentId == 0) {
            return 0;
        }
        for (int radius = 1; radius <= HALO; radius++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dx = -radius; dx <= radius; dx++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
                        continue;
                    }
                    int x = startX + dx;
                    int z = startZ + dz;
                    if (x < 0 || z < 0 || x >= GRID_SIDE || z >= GRID_SIDE
                        || component[index(x, z)] != componentId) {
                        return radius - 1;
                    }
                }
            }
        }
        return HALO;
    }

    private static boolean rawCity(ChunkCoord coord,
                                   IDimensionInfo provider,
                                   LostCityProfile profile) {
        try {
            ChunkRoleProbe.Probe stable = ChunkRoleProbe.peekStableTerrainProbe(
                provider, coord.dimension(), coord.chunkX(), coord.chunkZ());
            if (stable != null) {
                return stable.isCity();
            }
            if (BuildingInfo.isVoidChunk(coord, provider)) {
                return false;
            }
            if ((profile.isSpace() || profile.isSpheres())
                && (CitySphere.onCitySphereBorder(coord, provider)
                || CitySphere.hasMonorailStation(coord, provider))) {
                return false;
            }
            return City.getCityFactor(coord, provider, profile) > profile.CITY_THRESHOLD;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static int representativeHeight(IDimensionInfo provider, ChunkCoord coord, int fallback) {
        try {
            NaturalHeightSampler.LevelSampler heights =
                NaturalHeightSampler.forLevel(provider.getWorld());
            if (heights == null) {
                return fallback;
            }
            Integer cached = heights.cachedChunkHeight(coord.chunkX(), coord.chunkZ());
            return cached == null ? fallback : cached;
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static void enqueueComponent(int index,
                                         int componentId,
                                         boolean[] elevated,
                                         int[] component,
                                         ArrayDeque<Integer> queue) {
        if (elevated[index] && component[index] == 0) {
            component[index] = componentId;
            queue.addLast(index);
        }
    }

    private static void enqueueFrontier(int x,
                                        int z,
                                        int side,
                                        boolean[] eligible,
                                        boolean[] queued,
                                        PriorityQueue<Integer> frontier) {
        if (x < 0 || z < 0 || x >= side || z >= side) {
            return;
        }
        int index = z * side + x;
        if (eligible[index] && !queued[index]) {
            queued[index] = true;
            frontier.add(index);
        }
    }

    private static void propagateDepth(int x,
                                       int z,
                                       int side,
                                       int from,
                                       boolean[] eligible,
                                       int[] depth,
                                       ArrayDeque<Integer> queue) {
        if (x < 0 || z < 0 || x >= side || z >= side) {
            return;
        }
        int index = z * side + x;
        int candidate = depth[from] + 1;
        if (eligible[index] && candidate < depth[index]) {
            depth[index] = candidate;
            queue.addLast(index);
        }
    }

    private static int distanceSquared(int index, int seedX, int seedZ, int side) {
        int dx = index % side - seedX;
        int dz = index / side - seedZ;
        return dx * dx + dz * dz;
    }

    private static boolean isOwnedGridCell(int gx, int gz) {
        return gx >= HALO && gz >= HALO
            && gx < HALO + REGION_SIDE && gz < HALO + REGION_SIDE;
    }

    private static int countTrue(boolean[] values) {
        int count = 0;
        for (boolean value : values) {
            if (value) {
                count++;
            }
        }
        return count;
    }

    private static int index(int x, int z) {
        return z * GRID_SIDE + x;
    }

    static int boundedBudget(int ownedCityCells) {
        int proportionalBudget = (int) Math.floor(Math.max(0, ownedCityCells) * MAX_CITY_SHARE);
        return Math.max(0, Math.min(MAX_RESERVED_CELLS, proportionalBudget));
    }

    private static CellPlan cityPlan(int ground, String reason) {
        return new CellPlan(Disposition.CITY, ground, ground, 0, 0, 0,
            1.0D, 0, 0, reason);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double doubleProperty(String key, double fallback, double min, double max) {
        try {
            return clamp(Double.parseDouble(System.getProperty(key, Double.toString(fallback))), min, max);
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    public static void clear() {
        CACHE.clear();
        CACHE_ORDER.clear();
        IN_FLIGHT.clear();
    }

    public static boolean isPlanning() {
        return PLANNING.get();
    }

    public static String diagnostics() {
        return "regionSide=" + REGION_SIDE
            + ", halo=" + HALO
            + ", maxCells=" + MAX_RESERVED_CELLS
            + ", maxCityShare=" + MAX_CITY_SHARE
            + ", regions=" + REGIONS.get()
            + ", hits=" + HITS.get()
            + ", singleFlightWaits=" + FLIGHT_WAITS.get()
            + ", reserved=" + RESERVED.get()
            + ", transitionCells=" + TRANSITIONS.get()
            + ", enclosedTunnelCells=" + TUNNELS.get()
            + ", tunnelPatchRejects=" + TUNNEL_REJECTS.get()
            + ", patchRejects=" + PATCH_REJECTS.get()
            + ", highwayLookups=" + HIGHWAY_LOOKUPS.get()
            + ", cache=" + CACHE.size();
    }

    private record TunnelResult(boolean valid, boolean[] tunnelCells, String reason) {
    }

    private record RegionPlan(CellPlan[] cells) {
    }

    private static final class RegionKey {
        private final IDimensionInfo provider;
        private final ResourceKey<Level> dimension;
        private final int regionX;
        private final int regionZ;
        private final int ground;
        private final int hash;

        private RegionKey(IDimensionInfo provider,
                          ResourceKey<Level> dimension,
                          int regionX,
                          int regionZ,
                          int ground) {
            this.provider = provider;
            this.dimension = dimension;
            this.regionX = regionX;
            this.regionZ = regionZ;
            this.ground = ground;
            int result = 31 * System.identityHashCode(provider) + dimension.hashCode();
            result = 31 * result + regionX;
            result = 31 * result + regionZ;
            result = 31 * result + ground;
            this.hash = result;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof RegionKey key)) {
                return false;
            }
            return provider == key.provider
                && dimension.equals(key.dimension)
                && regionX == key.regionX
                && regionZ == key.regionZ
                && ground == key.ground;
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }
}
