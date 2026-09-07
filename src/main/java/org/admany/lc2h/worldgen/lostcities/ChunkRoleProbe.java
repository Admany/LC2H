package org.admany.lc2h.worldgen.lostcities;

import mcjty.lostcities.api.LostChunkCharacteristics;
import mcjty.lostcities.config.HighwayGenerationMode;
import mcjty.lostcities.config.LostCityProfile;
import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.IDimensionInfo;
import mcjty.lostcities.worldgen.lost.BuildingInfo;
import mcjty.lostcities.worldgen.lost.City;
import mcjty.lostcities.worldgen.lost.Highway;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.admany.lc2h.worldgen.terrain.IntercityHighwayIndex;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class ChunkRoleProbe {

    private static final long TTL_MS = Math.max(5_000L,
        Long.getLong("lc2h.chunkRoleProbe.ttlMs", TimeUnit.MINUTES.toMillis(10)));
    private static final int PRUNE_EVERY = Math.max(128,
        Integer.getInteger("lc2h.chunkRoleProbe.pruneEvery", 512));

    private static final ConcurrentHashMap<ChunkCoord, Entry> CACHE = new ConcurrentHashMap<>();
    /** Provider-scoped cache of raw Lost Cities terrain predicates. */
    private static final ConcurrentHashMap<IDimensionInfo, ConcurrentHashMap<ChunkCoord, Probe>>
        TERRAIN_CACHE = new ConcurrentHashMap<>();
    /** Prepare cold stable probes off the structure-placement worker. */
    private static final AtomicInteger STABLE_PREWARM_THREAD_IDS = new AtomicInteger();
    private static final ThreadPoolExecutor STABLE_PREWARM_EXECUTOR =
        new ThreadPoolExecutor(
            2,
            2,
            30L,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(64),
            runnable -> {
                Thread thread = new Thread(runnable,
                    "lc2h-stable-role-prewarm-" + STABLE_PREWARM_THREAD_IDS.incrementAndGet());
                thread.setDaemon(true);
                thread.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
                return thread;
            },
            new ThreadPoolExecutor.AbortPolicy());
    private static final AtomicInteger CHARACTERISTICS_PREWARM_THREAD_IDS = new AtomicInteger();
    private static final ThreadPoolExecutor CHARACTERISTICS_PREWARM_EXECUTOR =
        new ThreadPoolExecutor(
            1,
            1,
            30L,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(64),
            runnable -> {
                Thread thread = new Thread(runnable,
                    "lc2h-characteristics-prewarm-" + CHARACTERISTICS_PREWARM_THREAD_IDS.incrementAndGet());
                thread.setDaemon(true);
                thread.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 2));
                return thread;
            },
            new ThreadPoolExecutor.AbortPolicy());
    private static final ConcurrentHashMap<IDimensionInfo,
        ConcurrentHashMap<ChunkCoord, CompletableFuture<Probe>>> STABLE_FLIGHTS =
        new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<IDimensionInfo,
        ConcurrentHashMap<ChunkCoord, CompletableFuture<LostChunkCharacteristics>>> CHARACTERISTICS_FLIGHTS =
        new ConcurrentHashMap<>();
    private static final AtomicLong STABLE_PREWARM_SUBMITTED = new AtomicLong();
    private static final AtomicLong STABLE_PREWARM_COMPLETED = new AtomicLong();
    private static final AtomicLong STABLE_PREWARM_REJECTED = new AtomicLong();
    private static final AtomicLong STABLE_CONTENTION_FALLBACKS = new AtomicLong();
    private static final AtomicLong CHARACTERISTICS_PREWARM_SUBMITTED = new AtomicLong();
    private static final AtomicLong CHARACTERISTICS_PREWARM_COMPLETED = new AtomicLong();
    private static final AtomicLong CHARACTERISTICS_PREWARM_REJECTED = new AtomicLong();
    private static final AtomicLong STABLE_LIFECYCLE = new AtomicLong();
    private static final AtomicInteger OP_COUNTER = new AtomicInteger(0);
    private static final boolean TREE_SURFACE_RAIL_ONLY =
        Boolean.parseBoolean(System.getProperty("lc2h.treeSafety.surfaceRailOnly", "true"));
    private static final Probe EMPTY_PROBE = new Probe(false, false, 0, false, -1, false, false, false, false);

    private ChunkRoleProbe() {
    }

    public record Probe(
        boolean isCity,
        boolean couldHaveBuilding,
        int cityLevel,
        boolean hasHighway,
        int highwayLevel,
        boolean highwayTunnel,
        boolean hasRailway,
        boolean hasSurfaceRailway,
        boolean buildingTypeKnown
    ) {
        public boolean isUnsafe() {
            return isCity || hasSurfaceHighway()
                || (TREE_SURFACE_RAIL_ONLY ? hasSurfaceRailway : hasRailway);
        }

        public boolean hasSurfaceHighway() {
            return hasHighway && !highwayTunnel;
        }
    }

    public record RoleGrid(
        ResourceKey<Level> dim,
        int centerX,
        int centerZ,
        int radius,
        Probe[] probes
    ) {
        public Probe get(int chunkX, int chunkZ) {
            int diameter = radius * 2 + 1;
            int localX = chunkX - centerX + radius;
            int localZ = chunkZ - centerZ + radius;
            if (localX < 0 || localZ < 0 || localX >= diameter || localZ >= diameter) {
                return EMPTY_PROBE;
            }
            Probe probe = probes[localZ * diameter + localX];
            return probe == null ? EMPTY_PROBE : probe;
        }

        public boolean isCity(int chunkX, int chunkZ) {
            return get(chunkX, chunkZ).isCity();
        }

        public boolean isUnsafe(int chunkX, int chunkZ) {
            return get(chunkX, chunkZ).isUnsafe();
        }
    }

    private record Entry(Probe probe, LostChunkCharacteristics characteristics, long timestampMs,
                         boolean highwayKnown, boolean routeKnown) {
    }

    public static Probe get(IDimensionInfo dimInfo, ResourceKey<Level> dim, int chunkX, int chunkZ) {
        if (dimInfo == null || dim == null) {
            return EMPTY_PROBE;
        }
        long now = System.currentTimeMillis();
        ChunkCoord coord = new ChunkCoord(dim, chunkX, chunkZ);
        Entry cached = CACHE.get(coord);
        if (cached != null && isFresh(cached, now)) {
            return cached.probe();
        }

        Entry snapshot = fromSnapshot(coord, now);
        if (snapshot != null) {
            CACHE.put(coord, snapshot);
            maybePrune(now);
            return snapshot.probe();
        }

        Entry computed = compute(dimInfo, coord, now);
        CACHE.put(coord, computed);
        maybePrune(now);
        return computed.probe();
    }

    public static LostChunkCharacteristics getCharacteristics(IDimensionInfo dimInfo,
                                                              ResourceKey<Level> dim,
                                                              int chunkX,
                                                              int chunkZ) {
        if (dimInfo == null || dim == null) {
            return null;
        }
        long now = System.currentTimeMillis();
        ChunkCoord coord = new ChunkCoord(dim, chunkX, chunkZ);
        Entry cached = CACHE.get(coord);
        if (cached != null && isFresh(cached, now)) {
            return cached.characteristics();
        }

        Entry snapshot = fromSnapshot(coord, now);
        if (snapshot != null) {
            CACHE.put(coord, snapshot);
            maybePrune(now);
            return snapshot.characteristics();
        }

        Entry computed = compute(dimInfo, coord, now);
        CACHE.put(coord, computed);
        maybePrune(now);
        return computed.characteristics();
    }

    public static LostChunkCharacteristics peekCharacteristics(ChunkCoord coord) {
        if (coord == null) {
            return null;
        }
        Entry cached = CACHE.get(coord);
        long now = System.currentTimeMillis();
        if (cached != null && isFresh(cached, now)) {
            return cached.characteristics();
        }
        BuildingInfoSnapshotStore.Snapshot snapshot = BuildingInfoSnapshotStore.get(coord);
        return snapshot != null ? snapshot.characteristics() : null;
    }

    public static Probe peek(IDimensionInfo dimInfo,
                             ResourceKey<Level> dim,
                             int chunkX,
                             int chunkZ) {
        if (dimInfo == null || dim == null) {
            return null;
        }
        ChunkCoord coord = new ChunkCoord(dim, chunkX, chunkZ);
        long now = System.currentTimeMillis();
        Entry cached = CACHE.get(coord);
        if (cached != null && isFresh(cached, now)) {
            return cached.probe();
        }
        Entry snapshot = fromSnapshot(coord, now);
        return snapshot == null ? null : snapshot.probe();
    }

    public static Probe getTreeSafetyProbe(IDimensionInfo dimInfo,
                                           ResourceKey<Level> dim,
                                           int chunkX,
                                           int chunkZ) {
        Probe published = peek(dimInfo, dim, chunkX, chunkZ);
        return published != null
            ? published
            : getStableTerrainProbe(dimInfo, dim, chunkX, chunkZ);
    }

    /** True when a tree-safety answer is already published for this chunk. */
    public static boolean hasTreeSafetyProbe(IDimensionInfo dimInfo,
                                             ResourceKey<Level> dim,
                                             int chunkX,
                                             int chunkZ) {
        if (dimInfo == null || dim == null) {
            return false;
        }
        if (peek(dimInfo, dim, chunkX, chunkZ) != null) {
            return true;
        }
        ConcurrentHashMap<ChunkCoord, Probe> providerCache = TERRAIN_CACHE.get(dimInfo);
        return providerCache != null && providerCache.containsKey(new ChunkCoord(dim, chunkX, chunkZ));
    }

    public static CompletableFuture<LostChunkCharacteristics> requestCharacteristicsAsync(
        IDimensionInfo dimInfo, ResourceKey<Level> dim, int chunkX, int chunkZ) {
        if (dimInfo == null || dim == null) {
            return CompletableFuture.completedFuture(null);
        }
        ChunkCoord coord = new ChunkCoord(dim, chunkX, chunkZ);
        LostChunkCharacteristics cached = peekCharacteristics(coord);
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }

        ConcurrentHashMap<ChunkCoord, CompletableFuture<LostChunkCharacteristics>> flights =
            CHARACTERISTICS_FLIGHTS.computeIfAbsent(dimInfo, ignored -> new ConcurrentHashMap<>());
        CompletableFuture<LostChunkCharacteristics> created = new CompletableFuture<>();
        CompletableFuture<LostChunkCharacteristics> existing = flights.putIfAbsent(coord, created);
        if (existing != null) {
            return existing;
        }

        long lifecycle = STABLE_LIFECYCLE.get();
        CHARACTERISTICS_PREWARM_SUBMITTED.incrementAndGet();
        try {
            CHARACTERISTICS_PREWARM_EXECUTOR.execute(() -> {
                try {
                    if (lifecycle != STABLE_LIFECYCLE.get()) {
                        created.cancel(false);
                        return;
                    }

                    LostChunkCharacteristics resolved = BuildingInfo.getChunkCharacteristics(coord, dimInfo);
                    if (resolved != null && lifecycle == STABLE_LIFECYCLE.get()) {
                        rememberCharacteristics(coord, resolved);
                        created.complete(resolved);
                    } else {
                        created.complete(null);
                    }
                    CHARACTERISTICS_PREWARM_COMPLETED.incrementAndGet();
                } catch (Throwable failure) {
                    created.completeExceptionally(failure);
                } finally {
                    flights.remove(coord, created);
                    if (flights.isEmpty()) {
                        CHARACTERISTICS_FLIGHTS.remove(dimInfo, flights);
                    }
                }
            });
        } catch (RejectedExecutionException rejected) {
            CHARACTERISTICS_PREWARM_REJECTED.incrementAndGet();
            flights.remove(coord, created);
            if (flights.isEmpty()) {
                CHARACTERISTICS_FLIGHTS.remove(dimInfo, flights);
            }
            created.completeExceptionally(rejected);
        }
        return created;
    }

    public static boolean isCity(IDimensionInfo dimInfo, ResourceKey<Level> dim, int chunkX, int chunkZ) {
        return get(dimInfo, dim, chunkX, chunkZ).isCity();
    }

    public static RoleGrid getGrid(IDimensionInfo dimInfo,
                                   ResourceKey<Level> dim,
                                   int centerX,
                                   int centerZ,
                                   int radius) {
        int clampedRadius = Math.max(0, Math.min(16, radius));
        int diameter = clampedRadius * 2 + 1;
        Probe[] probes = new Probe[diameter * diameter];
        int index = 0;
        for (int dz = -clampedRadius; dz <= clampedRadius; dz++) {
            for (int dx = -clampedRadius; dx <= clampedRadius; dx++) {
                probes[index++] = get(dimInfo, dim, centerX + dx, centerZ + dz);
            }
        }
        return new RoleGrid(dim, centerX, centerZ, clampedRadius, probes);
    }

    public static RoleGrid getInfrastructureGrid(IDimensionInfo dimInfo,
                                                 ResourceKey<Level> dim,
                                                 int centerX,
                                                 int centerZ,
                                                 int radius) {
        int clampedRadius = Math.max(0, Math.min(16, radius));
        int diameter = clampedRadius * 2 + 1;
        Probe[] probes = new Probe[diameter * diameter];
        int index = 0;
        for (int dz = -clampedRadius; dz <= clampedRadius; dz++) {
            for (int dx = -clampedRadius; dx <= clampedRadius; dx++) {
                int chunkX = centerX + dx;
                int chunkZ = centerZ + dz;
                Probe probe = get(dimInfo, dim, chunkX, chunkZ);
                probes[index++] = probe.isCity()
                    ? probe
                    : getHighwayAware(dimInfo, dim, chunkX, chunkZ);
            }
        }
        return new RoleGrid(dim, centerX, centerZ, clampedRadius, probes);
    }

    public static RoleGrid getStableTerrainGrid(IDimensionInfo dimInfo,
                                                ResourceKey<Level> dim,
                                                int centerX,
                                                int centerZ,
                                                int radius) {
        int clampedRadius = Math.max(0, Math.min(16, radius));
        int diameter = clampedRadius * 2 + 1;
        Probe[] probes = new Probe[diameter * diameter];
        int index = 0;
        for (int dz = -clampedRadius; dz <= clampedRadius; dz++) {
            for (int dx = -clampedRadius; dx <= clampedRadius; dx++) {
                probes[index++] = getStableTerrainProbe(dimInfo, dim, centerX + dx, centerZ + dz);
            }
        }
        return new RoleGrid(dim, centerX, centerZ, clampedRadius, probes);
    }

    public static Probe getStableTerrainProbe(IDimensionInfo dimInfo,
                                              ResourceKey<Level> dim,
                                              int chunkX,
                                              int chunkZ) {
        if (dimInfo == null || dim == null) {
            return EMPTY_PROBE;
        }
        ChunkCoord coord = new ChunkCoord(dim, chunkX, chunkZ);
        ConcurrentHashMap<ChunkCoord, Probe> providerCache =
            TERRAIN_CACHE.computeIfAbsent(dimInfo, ignored -> new ConcurrentHashMap<>());
        Probe cached = providerCache.get(coord);
        if (cached != null) {
            return cached;
        }
        ConcurrentHashMap<ChunkCoord, CompletableFuture<Probe>> flights =
            STABLE_FLIGHTS.computeIfAbsent(dimInfo, ignored -> new ConcurrentHashMap<>());
        CompletableFuture<Probe> created = new CompletableFuture<>();
        CompletableFuture<Probe> existing = flights.putIfAbsent(coord, created);
        if (existing != null) {
            try {
                Probe ready = existing.getNow(null);
                if (ready != null) {
                    return ready;
                }
                STABLE_CONTENTION_FALLBACKS.incrementAndGet();
                Probe fallback = computeStableTerrainProbe(dimInfo, coord);
                if (fallback != null) {
                    providerCache.putIfAbsent(coord, fallback);
                }
                return fallback == null ? EMPTY_PROBE : fallback;
            } catch (Throwable ignored) {
                STABLE_CONTENTION_FALLBACKS.incrementAndGet();
                try {
                    Probe fallback = computeStableTerrainProbe(dimInfo, coord);
                    if (fallback != null) {
                        providerCache.putIfAbsent(coord, fallback);
                    }
                    return fallback == null ? EMPTY_PROBE : fallback;
                } catch (Throwable ignoredFallback) {
                    return EMPTY_PROBE;
                }
            }
        }
        Probe resolved = computeStableTerrainProbe(dimInfo, coord);
        try {
            if (resolved == null) {
                created.complete(EMPTY_PROBE);
                return EMPTY_PROBE;
            }
            Probe previous = providerCache.putIfAbsent(coord, resolved);
            Probe result = previous != null ? previous : resolved;
            created.complete(result);
            return result;
        } finally {
            flights.remove(coord, created);
            if (flights.isEmpty()) {
                STABLE_FLIGHTS.remove(dimInfo, flights);
            }
        }
    }

    public static void requestStableTerrainProbe(IDimensionInfo dimInfo,
                                                  ResourceKey<Level> dim,
                                                  int chunkX,
                                                  int chunkZ) {
        requestStableTerrainProbeAsync(dimInfo, dim, chunkX, chunkZ);
    }

    public static CompletableFuture<Probe> requestStableTerrainProbeAsync(
        IDimensionInfo dimInfo, ResourceKey<Level> dim, int chunkX, int chunkZ) {
        if (dimInfo == null || dim == null) {
            return CompletableFuture.completedFuture(EMPTY_PROBE);
        }
        ChunkCoord coord = new ChunkCoord(dim, chunkX, chunkZ);
        ConcurrentHashMap<ChunkCoord, Probe> providerCache = TERRAIN_CACHE.get(dimInfo);
        Probe cached = providerCache == null ? null : providerCache.get(coord);
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }
        ConcurrentHashMap<ChunkCoord, CompletableFuture<Probe>> flights =
            STABLE_FLIGHTS.computeIfAbsent(dimInfo, ignored -> new ConcurrentHashMap<>());
        CompletableFuture<Probe> created = new CompletableFuture<>();
        CompletableFuture<Probe> existing = flights.putIfAbsent(coord, created);
        if (existing != null) {
            return existing;
        }
        long lifecycle = STABLE_LIFECYCLE.get();
        STABLE_PREWARM_SUBMITTED.incrementAndGet();
        try {
            STABLE_PREWARM_EXECUTOR.execute(() -> {
                try {
                    if (lifecycle != STABLE_LIFECYCLE.get()) {
                        created.cancel(false);
                        return;
                    }
                    Probe resolved = computeStableTerrainProbe(dimInfo, coord);
                    if (resolved != null && lifecycle == STABLE_LIFECYCLE.get()) {
                        TERRAIN_CACHE.computeIfAbsent(dimInfo, ignored -> new ConcurrentHashMap<>())
                            .putIfAbsent(coord, resolved);
                        created.complete(resolved);
                    } else {
                        created.complete(EMPTY_PROBE);
                    }
                    STABLE_PREWARM_COMPLETED.incrementAndGet();
                } catch (Throwable failure) {
                    created.completeExceptionally(failure);
                } finally {
                    flights.remove(coord, created);
                    if (flights.isEmpty()) {
                        STABLE_FLIGHTS.remove(dimInfo, flights);
                    }
                }
            });
        } catch (RejectedExecutionException rejected) {
            STABLE_PREWARM_REJECTED.incrementAndGet();
            flights.remove(coord, created);
            if (flights.isEmpty()) {
                STABLE_FLIGHTS.remove(dimInfo, flights);
            }
            created.completeExceptionally(rejected);
        }
        return created;
    }

    /** Return a previously resolved terrain probe without doing Lost Cities work. */
    public static Probe peekStableTerrainProbe(IDimensionInfo dimInfo,
                                               ResourceKey<Level> dim,
                                               int chunkX,
                                               int chunkZ) {
        if (dimInfo == null || dim == null) {
            return null;
        }
        ConcurrentHashMap<ChunkCoord, Probe> providerCache = TERRAIN_CACHE.get(dimInfo);
        if (providerCache == null) {
            return null;
        }
        return providerCache.get(new ChunkCoord(dim, chunkX, chunkZ));
    }

    public static boolean isUnsafe(IDimensionInfo dimInfo, ResourceKey<Level> dim, int chunkX, int chunkZ) {
        return getRouteAware(dimInfo, dim, chunkX, chunkZ).isUnsafe();
    }

    public static boolean hasHighway(IDimensionInfo dimInfo, ResourceKey<Level> dim, int chunkX, int chunkZ) {
        return getRouteAware(dimInfo, dim, chunkX, chunkZ).hasHighway();
    }

    public static boolean hasRailway(IDimensionInfo dimInfo, ResourceKey<Level> dim, int chunkX, int chunkZ) {
        return getRouteAware(dimInfo, dim, chunkX, chunkZ).hasRailway();
    }

    /** Returns the route-complete role used by tree-safety decisions. */
    public static Probe getRouteAwareProbe(IDimensionInfo dimInfo, ResourceKey<Level> dim,
                                           int chunkX, int chunkZ) {
        return getRouteAware(dimInfo, dim, chunkX, chunkZ);
    }

    public static RailSafetySummary summarizeRailSafety(IDimensionInfo dimInfo, ResourceKey<Level> dim,
                                                        int minChunkX, int maxChunkX,
                                                        int minChunkZ, int maxChunkZ) {
        if (dimInfo == null || dim == null || minChunkX > maxChunkX || minChunkZ > maxChunkZ) {
            return new RailSafetySummary(0, 0, 0, 0, 0, 0);
        }
        int scanned = 0;
        int railway = 0;
        int surfaceRailway = 0;
        int undergroundOnly = 0;
        int undergroundOnlyUnsafe = 0;
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                Probe probe = getRouteAware(dimInfo, dim, chunkX, chunkZ);
                scanned++;
                if (!probe.hasRailway()) {
                    continue;
                }
                railway++;
                if (probe.hasSurfaceRailway()) {
                    surfaceRailway++;
                    continue;
                }
                undergroundOnly++;
                if (!probe.isCity() && !probe.hasHighway() && probe.isUnsafe()) {
                    undergroundOnlyUnsafe++;
                }
            }
        }
        return new RailSafetySummary(scanned, railway, surfaceRailway, undergroundOnly,
            undergroundOnlyUnsafe, undergroundOnly - undergroundOnlyUnsafe);
    }

    public record RailSafetySummary(int scanned, int railway, int surfaceRailway,
                                    int undergroundOnly, int undergroundOnlyUnsafe,
                                    int undergroundOnlyTreeSafe) {
        public String summary() {
            return "scanned=" + scanned
                + " rail=" + railway
                + " surfaceRail=" + surfaceRailway
                + " undergroundOnly=" + undergroundOnly
                + " undergroundOnlyUnsafe=" + undergroundOnlyUnsafe
                + " undergroundOnlyTreeSafe=" + undergroundOnlyTreeSafe
                + " surfaceRailOnly=" + TREE_SURFACE_RAIL_ONLY;
        }
    }

    public static void invalidate(ChunkCoord coord) {
        if (coord != null) {
            CACHE.remove(coord);
            BuildingInfoSnapshotStore.invalidate(coord);
        }
    }

    public static void invalidateArea(ChunkCoord topLeft, int areaSize) {
        if (topLeft == null || areaSize <= 0) {
            return;
        }
        for (int dx = 0; dx < areaSize; dx++) {
            for (int dz = 0; dz < areaSize; dz++) {
                CACHE.remove(new ChunkCoord(topLeft.dimension(), topLeft.chunkX() + dx, topLeft.chunkZ() + dz));
            }
        }
        BuildingInfoSnapshotStore.invalidateArea(topLeft, areaSize);
    }

    public static void clear() {
        STABLE_LIFECYCLE.incrementAndGet();
        CACHE.clear();
        TERRAIN_CACHE.clear();
        BuildingInfoSnapshotStore.clear();
        STABLE_FLIGHTS.values().forEach(flights ->
            flights.values().forEach(future -> future.cancel(false)));
        STABLE_FLIGHTS.clear();
        CHARACTERISTICS_FLIGHTS.values().forEach(flights ->
            flights.values().forEach(future -> future.cancel(false)));
        CHARACTERISTICS_FLIGHTS.clear();
    }

    public static void rememberCharacteristics(ChunkCoord coord, LostChunkCharacteristics characteristics) {
        if (coord == null || characteristics == null) {
            return;
        }
        BuildingInfoSnapshotStore.Snapshot snapshot = BuildingInfoSnapshotStore.remember(coord, characteristics);
        if (snapshot != null) {
            CACHE.put(coord, entryFromSnapshot(snapshot));
        }
    }

    private static Entry fromSnapshot(ChunkCoord coord, long now) {
        BuildingInfoSnapshotStore.Snapshot snapshot = BuildingInfoSnapshotStore.get(coord);
        if (snapshot == null) {
            return null;
        }
        return entryFromSnapshot(snapshot);
    }

    private static Entry entryFromSnapshot(BuildingInfoSnapshotStore.Snapshot snapshot) {
        Probe probe = new Probe(
            snapshot.isCity(),
            snapshot.couldHaveBuilding(),
            snapshot.cityLevel(),
            false,
            -1,
            false,
            false,
            false,
            snapshot.buildingTypeKnown()
        );
        return new Entry(probe, snapshot.characteristics(), snapshot.timestampMs(), false, false);
    }

    private static Probe getHighwayAware(IDimensionInfo dimInfo, ResourceKey<Level> dim,
                                         int chunkX, int chunkZ) {
        if (dimInfo == null || dim == null) {
            return EMPTY_PROBE;
        }
        long now = System.currentTimeMillis();
        ChunkCoord coord = new ChunkCoord(dim, chunkX, chunkZ);
        Entry cached = CACHE.get(coord);
        if (cached != null && isFresh(cached, now) && cached.highwayKnown()) {
            return cached.probe();
        }

        Probe base = get(dimInfo, dim, chunkX, chunkZ);
        cached = CACHE.get(coord);
        LostCityProfile profile = null;
        try {
            profile = dimInfo.getProfile();
        } catch (Exception ignored) {
        }
        boolean hasHighway = false;
        int highwayLevel = -1;
        boolean highwayTunnel = false;
        try {
            if (profile != null) {
                highwayLevel = highwayLevel(dimInfo, profile, coord);
                hasHighway = highwayLevel >= 0;
                highwayTunnel = hasHighway && isHighwayTunnel(dimInfo, coord, profile,
                    base.isCity(), base.cityLevel(), highwayLevel);
            }
        } catch (Exception ignored) {
        }
        Probe upgraded = new Probe(base.isCity(), base.couldHaveBuilding(), base.cityLevel(),
            hasHighway, highwayLevel, highwayTunnel, base.hasRailway(), base.hasSurfaceRailway(),
            base.buildingTypeKnown());
        CACHE.put(coord, new Entry(upgraded,
            cached == null ? null : cached.characteristics(), now, true,
            cached != null && cached.routeKnown()));
        maybePrune(now);
        return upgraded;
    }

    private static Probe getRouteAware(IDimensionInfo dimInfo, ResourceKey<Level> dim, int chunkX, int chunkZ) {
        if (dimInfo == null || dim == null) {
            return EMPTY_PROBE;
        }
        long now = System.currentTimeMillis();
        ChunkCoord coord = new ChunkCoord(dim, chunkX, chunkZ);
        Entry cached = CACHE.get(coord);
        if (cached != null && isFresh(cached, now) && cached.routeKnown()) {
            return cached.probe();
        }

        Entry computed = compute(dimInfo, coord, now);
        CACHE.put(coord, computed);
        maybePrune(now);
        return computed.probe();
    }

    private static Entry compute(IDimensionInfo dimInfo, ChunkCoord coord, long now) {
        LostCityProfile profile = null;
        try {
            profile = dimInfo.getProfile();
        } catch (Exception ignored) {
        }

        LostChunkCharacteristics characteristics = null;
        try {
            characteristics = BuildingInfo.getChunkCharacteristics(coord, dimInfo);
        } catch (Exception ignored) {
        }

        boolean isCity = false;
        boolean couldHaveBuilding = false;
        int cityLevel = 0;
        boolean buildingTypeKnown = false;
        if (characteristics != null) {
            isCity = characteristics.isCity;
            couldHaveBuilding = characteristics.couldHaveBuilding;
            cityLevel = characteristics.cityLevel;
            buildingTypeKnown = characteristics.buildingType != null || characteristics.buildingTypeId != null;
        } else {
            try {
                isCity = BuildingInfo.isCityRaw(coord, dimInfo, profile);
                if (isCity) {
                    cityLevel = BuildingInfo.getCityLevel(coord, dimInfo);
                }
            } catch (Exception ignored) {
            }
        }

        boolean hasHighway = false;
        int highwayLevel = -1;
        boolean highwayTunnel = false;
        boolean hasRailway = false;
        boolean hasSurfaceRailway = false;
        try {
            if (profile != null) {
                if (dimInfo.getHighwayGenerationMode() == HighwayGenerationMode.INTERCITY_NETWORK_V1) {
                    highwayLevel = highwayLevel(dimInfo, profile, coord);
                    hasHighway = highwayLevel >= 0;
                } else {
                    hasHighway = BuildingInfo.hasHighway(coord, dimInfo, profile);
                    if (hasHighway) {
                        highwayLevel = highwayLevel(dimInfo, profile, coord);
                    }
                }
                if (hasHighway) {
                    highwayTunnel = isHighwayTunnel(dimInfo, coord, profile,
                        isCity, cityLevel, highwayLevel);
                }
                hasRailway = BuildingInfo.hasRailway(coord, dimInfo, profile);
                hasSurfaceRailway = BuildingInfo.hasRailwayAtSurface(coord, dimInfo, profile);
            }
        } catch (Exception ignored) {
        }

        Probe probe = new Probe(isCity, couldHaveBuilding, cityLevel, hasHighway,
            highwayLevel, highwayTunnel, hasRailway, hasSurfaceRailway, buildingTypeKnown);
        return new Entry(probe, characteristics, now, true, true);
    }

    private static Probe computeStableTerrainProbe(IDimensionInfo dimInfo, ChunkCoord coord) {
        try {
            LostCityProfile profile = dimInfo.getProfile();
            if (profile == null) {
                return null;
            }

            boolean isCity = !BuildingInfo.isVoidChunk(coord, dimInfo);
            if (isCity) {
                isCity = City.getCityFactor(coord, dimInfo, profile) > profile.CITY_THRESHOLD;
            }

            int cityLevel = isCity ? stableCityLevel(dimInfo, profile, coord) : 0;
            int highwayLevel = stableHighwayLevel(dimInfo, profile, coord);
            boolean hasHighway = highwayLevel >= 0;
            boolean highwayTunnel = hasHighway && isHighwayTunnel(dimInfo, coord, profile,
                isCity, cityLevel, highwayLevel);
            return new Probe(isCity, false, cityLevel, hasHighway, highwayLevel,
                highwayTunnel, false, false, false);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static int stableCityLevel(IDimensionInfo dimInfo,
                                       LostCityProfile profile,
                                       ChunkCoord coord) {
        if (dimInfo == null || profile == null || coord == null) {
            return 0;
        }
        try {
            mcjty.lostcities.worldgen.ChunkHeightmap heightmap = dimInfo.getHeightmap(coord);
            if (heightmap == null) {
                return 0;
            }
            int height = heightmap.getHeight();
            if (height < profile.CITY_LEVEL0_HEIGHT) return 0;
            if (height < profile.CITY_LEVEL1_HEIGHT) return 1;
            if (height < profile.CITY_LEVEL2_HEIGHT) return 2;
            if (height < profile.CITY_LEVEL3_HEIGHT) return 3;
            if (height < profile.CITY_LEVEL4_HEIGHT) return 4;
            if (height < profile.CITY_LEVEL5_HEIGHT) return 5;
            if (height < profile.CITY_LEVEL6_HEIGHT) return 6;
            if (height < profile.CITY_LEVEL7_HEIGHT) return 7;
            return 8;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static int highwayLevel(IDimensionInfo dimInfo,
                                    LostCityProfile profile,
                                    ChunkCoord coord) {
        if (dimInfo.getHighwayGenerationMode() == HighwayGenerationMode.INTERCITY_NETWORK_V1) {
            return IntercityHighwayIndex.level(dimInfo, profile, coord);
        }
        return Math.max(Highway.getXHighwayLevel(coord, dimInfo, profile),
            Highway.getZHighwayLevel(coord, dimInfo, profile));
    }

    private static int stableHighwayLevel(IDimensionInfo dimInfo,
                                          LostCityProfile profile,
                                          ChunkCoord coord) {
        if (dimInfo.getHighwayGenerationMode() == HighwayGenerationMode.INTERCITY_NETWORK_V1) {
            return IntercityHighwayIndex.peekLevel(dimInfo, profile, coord, null);
        }
        return -1;
    }

    private static boolean isHighwayTunnel(IDimensionInfo dimInfo,
                                           ChunkCoord coord,
                                           LostCityProfile profile,
                                           boolean isCity,
                                           int cityLevel,
                                           int highwayLevel) {
        if (highwayLevel < 0) {
            return false;
        }
        if (isCity) {
            return cityLevel > highwayLevel;
        }
        int routeY = profile.GROUNDLEVEL
            + highwayLevel * mcjty.lostcities.worldgen.LostCityTerrainFeature.FLOORHEIGHT
            + 3;
        return dimInfo.getHeightmap(coord).getHeight() > routeY;
    }

    private static boolean isFresh(Entry entry, long now) {
        return entry != null && (now - entry.timestampMs()) <= TTL_MS;
    }

    private static void maybePrune(long now) {
        int local = OP_COUNTER.incrementAndGet();
        if (local < PRUNE_EVERY) {
            return;
        }
        OP_COUNTER.set(0);
        for (Map.Entry<ChunkCoord, Entry> entry : CACHE.entrySet()) {
            Entry value = entry.getValue();
            if (value != null && !isFresh(value, now)) {
                CACHE.remove(entry.getKey(), value);
            }
        }
    }
}
