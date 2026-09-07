package org.admany.lc2h.worldgen.terrain;

import mcjty.lostcities.config.HighwayGenerationMode;
import mcjty.lostcities.config.LostCityProfile;
import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.IDimensionInfo;
import mcjty.lostcities.worldgen.highway.HighwayConnectionKey;
import mcjty.lostcities.worldgen.highway.HighwayAxis;
import mcjty.lostcities.worldgen.highway.HighwayRoute;
import mcjty.lostcities.worldgen.highway.HighwaySegment;
import mcjty.lostcities.worldgen.highway.HubKey;
import mcjty.lostcities.worldgen.lost.CitySphere;
import org.admany.lc2h.concurrency.async.AsyncManager;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import org.admany.lc2h.worldgen.lostcities.PlannerHotPath;

/**
 * Read-through index for Lost Cities intercity routes.
 *
 * Lost Cities' planner still creates hubs, selects connections and assigns
 * route levels. This class only snapshots the public
 * immutable route records and answers later point queries without re-entering
 * the planner's serialized query path or starting a stream for every point.
 */
public final class IntercityHighwayIndex {
    private static final int MAX_WINDOWS = Math.max(128,
        Integer.getInteger("lc2h.terrain.highway.indexMaxWindows", 2048));
    private static final int MAX_OWNED_ROUTES = Math.max(256,
        Integer.getInteger("lc2h.terrain.highway.indexMaxOwnedRoutes", 8192));
    private static final int MAX_ROUTE_SEGMENTS = Math.max(512,
        Integer.getInteger("lc2h.terrain.highway.indexMaxRoutes", 16384));
    private static final int MAX_ASYNC_WARMUPS = Math.max(1,
        Integer.getInteger("lc2h.terrain.highway.maxWarmups", 2));

    private static final Map<IDimensionInfo, IntercityHighwayIndex> INDICES =
        new ConcurrentHashMap<>();
    private static volatile boolean asynchronousWarmupsAllowed;

    private final IDimensionInfo provider;
    private final LostCityProfile profile;
    private final int planningCellSize;
    private final int searchRadius;
    private final Map<WindowKey, WindowIndex> windows = new ConcurrentHashMap<>();
    private final Map<WindowKey, CompletableFuture<WindowIndex>> windowFlights = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<CacheToken<WindowKey, WindowIndex>> windowOrder = new ConcurrentLinkedQueue<>();
    /**
     * A window overlaps several neighbouring windows.  Keep the planner call
     * itself read-through cached so the serialized Lost Cities planner is
     * consulted once per owning hub, not once per window that happens to touch
     * that hub.
     */
    private final Map<HubKey, List<HighwayRoute>> ownedRoutes = new ConcurrentHashMap<>();
    private final Map<HubKey, CompletableFuture<List<HighwayRoute>>> ownedRouteFlights = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<CacheToken<HubKey, List<HighwayRoute>>> ownedRouteOrder = new ConcurrentLinkedQueue<>();
    /**
     * A route can be referenced by many overlapping planning windows.  Keep
     * its compact segments once and let windows share those immutable records.
     */
    private final Map<HighwayConnectionKey, List<Segment>> routeSegments = new ConcurrentHashMap<>();
    private final Map<HighwayConnectionKey, CompletableFuture<List<Segment>>> routeSegmentFlights = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<CacheToken<HighwayConnectionKey, List<Segment>>> routeSegmentOrder = new ConcurrentLinkedQueue<>();

    private final AtomicLong windowBuilds = new AtomicLong();
    private final AtomicLong windowBuildAttempts = new AtomicLong();
    private final AtomicLong routeSnapshots = new AtomicLong();
    private final AtomicLong routeSnapshotAttempts = new AtomicLong();
    private final AtomicLong segmentSnapshots = new AtomicLong();
    private final AtomicLong ownedRouteBuildAttempts = new AtomicLong();
    private final AtomicLong ownedRoutePublishes = new AtomicLong();
    private final AtomicLong queries = new AtomicLong();
    private final AtomicLong indexedHits = new AtomicLong();
    private final AtomicLong coldQueries = new AtomicLong();
    private final AtomicLong warmupRequests = new AtomicLong();
    private final AtomicLong warmupFailures = new AtomicLong();
    private final java.util.concurrent.atomic.AtomicInteger activeWarmups =
        new java.util.concurrent.atomic.AtomicInteger();

    private IntercityHighwayIndex(IDimensionInfo provider, LostCityProfile profile) {
        this.provider = provider;
        this.profile = profile;
        this.planningCellSize = Math.max(1, provider.getHighwayPlanner().settings().planningCellSize());
        this.searchRadius = Math.max(0, provider.getHighwayPlanner().settings().hubSearchRadiusCells());
    }

    public static int level(IDimensionInfo provider, LostCityProfile profile, ChunkCoord coord) {
        return level(provider, profile, coord, null);
    }

    public static int level(IDimensionInfo provider,
                            LostCityProfile profile,
                            ChunkCoord coord,
                            HighwayAxis axis) {
        if (provider == null || profile == null
            || provider.getHighwayGenerationMode() != HighwayGenerationMode.INTERCITY_NETWORK_V1) {
            return -1;
        }
        // IntercityHighwayPlanner applies this guard before its serialized
        // lookup. Keep the same profile semantics without entering that
        // lookup path for every terrain query.
        if ((profile.isSpace() || profile.isSpheres())
            && provider.getWorld() != null
            && CitySphere.intersectsWithCitySphere(coord, provider)) {
            return -1;
        }
        IntercityHighwayIndex index = INDICES.computeIfAbsent(provider,
            ignored -> new IntercityHighwayIndex(provider, profile));
        return index.levelAt(coord.chunkX(), coord.chunkZ(), axis);
    }

    static void clear() {
        INDICES.clear();
        asynchronousWarmupsAllowed = false;
    }

    public static void allowAsynchronousWarmups() {
        asynchronousWarmupsAllowed = true;
    }

    static String diagnostics() {
        long windows = 0;
        long routes = 0;
        long segments = 0;
        long queries = 0;
        long hits = 0;
        long ownedAttempts = 0;
        long ownedPublishes = 0;
        long routeIndexes = 0;
        long coldQueries = 0;
        long windowAttempts = 0;
        long routeAttempts = 0;
        long warmups = 0;
        long warmupFailures = 0;
        for (IntercityHighwayIndex index : INDICES.values()) {
            windows += index.windowBuilds.get();
            windowAttempts += index.windowBuildAttempts.get();
            routes += index.routeSnapshots.get();
            routeAttempts += index.routeSnapshotAttempts.get();
            segments += index.segmentSnapshots.get();
            queries += index.queries.get();
            hits += index.indexedHits.get();
            ownedAttempts += index.ownedRouteBuildAttempts.get();
            ownedPublishes += index.ownedRoutePublishes.get();
            routeIndexes += index.routeSegments.size();
            coldQueries += index.coldQueries.get();
            warmups += index.warmupRequests.get();
            warmupFailures += index.warmupFailures.get();
        }
        return "intercityWindows=" + windows
            + ", intercityWindowBuildAttempts=" + windowAttempts
            + ", intercityRoutes=" + routes
            + ", intercityRouteBuildAttempts=" + routeAttempts
            + ", intercitySegments=" + segments
            + ", intercityQueries=" + queries
            + ", intercityHits=" + hits
            + ", intercityOwnedRouteBuildAttempts=" + ownedAttempts
            + ", intercityOwnedRoutePublishes=" + ownedPublishes
            + ", intercityRouteIndexes=" + routeIndexes
            + ", intercityColdQueries=" + coldQueries
            + ", intercityWarmupRequests=" + warmups
            + ", intercityWarmupFailures=" + warmupFailures;
    }

    /**
     * Density evaluation must never start Lost Cities' serialized intercity
     * planner on the caller. Reuse a window that is already warm and report a
     * miss otherwise. A miss queues one bounded background warmup.
     */
    public static int peekLevel(IDimensionInfo provider,
                                LostCityProfile profile,
                                ChunkCoord coord,
                                HighwayAxis axis) {
        if (provider == null || profile == null || coord == null
            || provider.getHighwayGenerationMode() != HighwayGenerationMode.INTERCITY_NETWORK_V1) {
            return -1;
        }
        IntercityHighwayIndex index = INDICES.get(provider);
        if (index == null) {
            return -1;
        }
        return index.peekLevelAt(coord.chunkX(), coord.chunkZ(), axis);
    }

    public static boolean isWarm(IDimensionInfo provider,
                                 LostCityProfile profile,
                                 ChunkCoord coord) {
        if (provider == null || profile == null || coord == null
            || provider.getHighwayGenerationMode() != HighwayGenerationMode.INTERCITY_NETWORK_V1) {
            return true;
        }
        IntercityHighwayIndex index = INDICES.get(provider);
        return index != null && index.isWarmAt(coord.chunkX(), coord.chunkZ());
    }

    private int levelAt(int chunkX, int chunkZ, HighwayAxis axis) {
        queries.incrementAndGet();
        int planningX = Math.floorDiv(chunkX, planningCellSize);
        int planningZ = Math.floorDiv(chunkZ, planningCellSize);
        WindowKey key = new WindowKey(planningX, planningZ);
        /* Never run the serialized Lost Cities planner inside
         * ConcurrentHashMap.computeIfAbsent.  The mapping callback holds a
         * bin reservation while it recursively fills the route indexes,
         * which made unrelated shift builders park behind ReservationNodes.
         * Build outside the map and publish the immutable result once ready. */
        WindowIndex index = windows.get(key);
        if (index == null) {
            coldQueries.incrementAndGet();
            requestWarm(key);
            // Highway classification is advisory for the planner.  Never
            // park a worldgen worker behind Lost Cities' serialized planner.
            // The next query will see the immutable window once the warmup
            // publishes it.
            return -1;
        }
        trimWindows();
        int level = index.levelAt(chunkX, chunkZ, axis);
        if (level >= 0) {
            indexedHits.incrementAndGet();
        }
        return level;
    }

    private void requestWarm(WindowKey key) {
        /* Spawn preparation already has an authoritative Lost Cities planner
         * calculating these routes on the HariChunk owner. Starting another
         * complete route and density pass here steals a core and duplicates
         * that work while the server is waiting for spawn. Advisory index
         * warmups begin only after ServerStartedEvent. */
        if (!asynchronousWarmupsAllowed) {
            return;
        }
        if (windows.containsKey(key) || windowFlights.containsKey(key)) {
            return;
        }
        if (activeWarmups.get() >= MAX_ASYNC_WARMUPS) {
            return;
        }
        CompletableFuture<WindowIndex> created = new CompletableFuture<>();
        if (windowFlights.putIfAbsent(key, created) != null) {
            return;
        }
        if (activeWarmups.incrementAndGet() > MAX_ASYNC_WARMUPS) {
            activeWarmups.decrementAndGet();
            windowFlights.remove(key, created);
            return;
        }
        warmupRequests.incrementAndGet();
        try {
            AsyncManager.submitSupplierFallback("intercity-highway-window-warmup", () -> {
                try {
                    windowBuildAttempts.incrementAndGet();
                    WindowIndex built = buildWindow(key);
                    WindowIndex previous = windows.putIfAbsent(key, built);
                    WindowIndex published = previous != null ? previous : built;
                    if (previous == null) {
                        windowBuilds.incrementAndGet();
                        windowOrder.add(new CacheToken<>(key, published));
                    }
                    trimWindows();
                    created.complete(published);
                    return published;
                } catch (RuntimeException | Error failure) {
                    warmupFailures.incrementAndGet();
                    created.completeExceptionally(failure);
                    throw failure;
                } finally {
                    windowFlights.remove(key, created);
                    activeWarmups.decrementAndGet();
                }
            });
        } catch (Throwable failure) {
            warmupFailures.incrementAndGet();
            created.completeExceptionally(failure);
            windowFlights.remove(key, created);
            activeWarmups.decrementAndGet();
        }
    }

    private int peekLevelAt(int chunkX, int chunkZ, HighwayAxis axis) {
        int planningX = Math.floorDiv(chunkX, planningCellSize);
        int planningZ = Math.floorDiv(chunkZ, planningCellSize);
        WindowIndex index = windows.get(new WindowKey(planningX, planningZ));
        if (index == null) {
            coldQueries.incrementAndGet();
            return -1;
        }
        int level = index.levelAt(chunkX, chunkZ, axis);
        if (level >= 0) {
            indexedHits.incrementAndGet();
        }
        return level;
    }

    private boolean isWarmAt(int chunkX, int chunkZ) {
        int planningX = Math.floorDiv(chunkX, planningCellSize);
        int planningZ = Math.floorDiv(chunkZ, planningCellSize);
        return windows.containsKey(new WindowKey(planningX, planningZ));
    }

    private WindowIndex buildWindow(WindowKey key) {
        Map<HighwayConnectionKey, HighwayRoute> routes = new HashMap<>();
        var planner = provider.getHighwayPlanner();
        for (int dz = -searchRadius; dz <= searchRadius; dz++) {
            for (int dx = -searchRadius; dx <= searchRadius; dx++) {
                HubKey source = new HubKey(key.planningX + dx, key.planningZ + dz);
                for (HighwayRoute route : ownedRoutes(source, planner)) {
                    routes.putIfAbsent(route.key(), route);
                }
            }
        }

        Map<Integer, List<Segment>> byZ = new HashMap<>();
        Map<Integer, List<Segment>> byX = new HashMap<>();
        for (HighwayRoute route : routes.values()) {
            for (Segment compact : routeSegments(route)) {
                if (compact.axis() == HighwayAxis.X) {
                    byZ.computeIfAbsent(compact.startZ(), ignored -> new ArrayList<>()).add(compact);
                } else {
                    byX.computeIfAbsent(compact.startX(), ignored -> new ArrayList<>()).add(compact);
                }
            }
        }
        Comparator<Segment> order = Comparator.comparingInt(Segment::minAlong)
            .thenComparingInt(Segment::maxAlong)
            .thenComparingInt(Segment::level);
        byZ.values().forEach(list -> list.sort(order));
        byX.values().forEach(list -> list.sort(order));
        return new WindowIndex(copy(byZ), copy(byX));
    }

    private List<HighwayRoute> ownedRoutes(HubKey source,
                                           mcjty.lostcities.worldgen.highway.IntercityHighwayPlanner planner) {
        /* The planner call is deliberately outside the CHM mapping callback.
         * It can recurse into Lost Cities terrain evaluation and can take a
         * long time on a cold hub. */
        List<HighwayRoute> result = ownedRoutes.get(source);
        if (result == null) {
            CompletableFuture<List<HighwayRoute>> created = new CompletableFuture<>();
            CompletableFuture<List<HighwayRoute>> existing = ownedRouteFlights.putIfAbsent(source, created);
            if (existing != null) {
                result = existing.getNow(null);
                if (result == null && !PlannerHotPath.shouldAvoidBlocking()) {
                    result = existing.join();
                }
                if (result == null) {
                    result = List.of();
                }
            } else {
                try {
                    result = ownedRoutes.get(source);
                    if (result == null) {
                        ownedRouteBuildAttempts.incrementAndGet();
                        List<HighwayRoute> routes = planner.getOwnedRoutes(source);
                        List<HighwayRoute> snapshot = routes == null || routes.isEmpty()
                            ? List.of() : List.copyOf(routes);
                        List<HighwayRoute> previous = ownedRoutes.putIfAbsent(source, snapshot);
                        result = previous != null ? previous : snapshot;
                        if (previous == null) {
                            ownedRoutePublishes.incrementAndGet();
                            ownedRouteOrder.add(new CacheToken<>(source, result));
                        }
                    }
                    created.complete(result);
                } catch (RuntimeException | Error failure) {
                    created.completeExceptionally(failure);
                    throw failure;
                } finally {
                    ownedRouteFlights.remove(source, created);
                }
            }
        }
        trimOwnedRoutes();
        return result;
    }

    private List<Segment> routeSegments(HighwayRoute route) {
        HighwayConnectionKey key = route.key();
        List<Segment> result = routeSegments.get(key);
        if (result == null) {
            CompletableFuture<List<Segment>> created = new CompletableFuture<>();
            CompletableFuture<List<Segment>> existing = routeSegmentFlights.putIfAbsent(key, created);
            if (existing != null) {
                result = existing.getNow(null);
                if (result == null && !PlannerHotPath.shouldAvoidBlocking()) {
                    result = existing.join();
                }
                if (result == null) {
                    result = List.of();
                }
            } else {
                try {
                    result = routeSegments.get(key);
                    if (result == null) {
                        routeSnapshotAttempts.incrementAndGet();
                        List<Segment> snapshot = new ArrayList<>(route.segments().size());
                        for (HighwaySegment segment : route.segments()) {
                            snapshot.add(new Segment(segment.startX(), segment.startZ(),
                                segment.endX(), segment.endZ(), segment.axis(), route.highwayLevel()));
                        }
                        List<Segment> immutable = List.copyOf(snapshot);
                        List<Segment> previous = routeSegments.putIfAbsent(key, immutable);
                        result = previous != null ? previous : immutable;
                        if (previous == null) {
                            routeSnapshots.incrementAndGet();
                            segmentSnapshots.addAndGet(result.size());
                            routeSegmentOrder.add(new CacheToken<>(key, result));
                        }
                    }
                    created.complete(result);
                } catch (RuntimeException | Error failure) {
                    created.completeExceptionally(failure);
                    throw failure;
                } finally {
                    routeSegmentFlights.remove(key, created);
                }
            }
        }
        trimRouteSegments();
        return result;
    }

    private void trimWindows() {
        while (windows.size() > MAX_WINDOWS) {
            CacheToken<WindowKey, WindowIndex> oldest = windowOrder.poll();
            if (oldest == null) return;
            windows.remove(oldest.key(), oldest.value());
        }
    }

    private void trimOwnedRoutes() {
        while (ownedRoutes.size() > MAX_OWNED_ROUTES) {
            CacheToken<HubKey, List<HighwayRoute>> oldest = ownedRouteOrder.poll();
            if (oldest == null) return;
            ownedRoutes.remove(oldest.key(), oldest.value());
        }
    }

    private void trimRouteSegments() {
        while (routeSegments.size() > MAX_ROUTE_SEGMENTS) {
            CacheToken<HighwayConnectionKey, List<Segment>> oldest = routeSegmentOrder.poll();
            if (oldest == null) return;
            routeSegments.remove(oldest.key(), oldest.value());
        }
    }

    private static Map<Integer, List<Segment>> copy(Map<Integer, List<Segment>> source) {
        Map<Integer, List<Segment>> result = new HashMap<>(source.size());
        source.forEach((coordinate, segments) -> result.put(coordinate, List.copyOf(segments)));
        return Map.copyOf(result);
    }

    private record WindowKey(int planningX, int planningZ) {
    }

    private record CacheToken<K, V>(K key, V value) {
    }

    private record Segment(int startX, int startZ, int endX, int endZ,
                           HighwayAxis axis, int level) {
        int minAlong() {
            return startX != endX ? Math.min(startX, endX) : Math.min(startZ, endZ);
        }

        int maxAlong() {
            return startX != endX ? Math.max(startX, endX) : Math.max(startZ, endZ);
        }

        boolean containsX(int x) {
            return x >= Math.min(startX, endX) && x <= Math.max(startX, endX);
        }

        boolean containsZ(int z) {
            return z >= Math.min(startZ, endZ) && z <= Math.max(startZ, endZ);
        }
    }

    private record WindowIndex(Map<Integer, List<Segment>> byZ,
                               Map<Integer, List<Segment>> byX) {
        int levelAt(int chunkX, int chunkZ, HighwayAxis axis) {
            int xLevel = level(byZ.get(chunkZ), chunkX, true);
            int zLevel = level(byX.get(chunkX), chunkZ, false);
            if (axis == HighwayAxis.X) {
                return xLevel;
            }
            if (axis == HighwayAxis.Z) {
                return zLevel;
            }
            if (xLevel < 0) {
                return zLevel;
            }
            if (zLevel < 0) {
                return xLevel;
            }
            return Math.max(xLevel, zLevel);
        }

        private static int level(List<Segment> segments, int coordinate, boolean xAxis) {
            if (segments == null) {
                return -1;
            }
            int result = -1;
            for (Segment segment : segments) {
                if (segment.minAlong() > coordinate) {
                    break;
                }
                if ((xAxis ? segment.containsX(coordinate) : segment.containsZ(coordinate))) {
                    result = result < 0 ? segment.level() : Math.min(result, segment.level());
                }
            }
            return result;
        }
    }
}
