package org.admany.lc2h.worldgen.terrain;

import com.mojang.logging.LogUtils;
import mcjty.lostcities.config.LostCityProfile;
import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.IDimensionInfo;
import mcjty.lostcities.worldgen.LostCityTerrainFeature;
import mcjty.lostcities.worldgen.lost.BuildingInfo;
import mcjty.lostcities.worldgen.lost.City;
import mcjty.lostcities.worldgen.lost.CitySphere;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.admany.lc2h.worldgen.lostcities.ChunkRoleProbe;
import org.admany.lc2h.worldgen.lostcities.PlannerHotPath;
import org.admany.lc2h.worldgen.lostcities.LostCitiesGenerationLocks;
import org.admany.lc2h.util.PackedCoordinateKey;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicLong;

public final class CityShiftField {

    private static final int REGION_SIDE = 32;

    private static final int MAX_HALO = Math.max(12, Math.min(64,
        Integer.getInteger("lc2h.terrain.shift.maxHalo", 36)));

    private static final long SLOW_BUILD_WARN_NANOS = 2_000_000_000L;

    private static final double SQRT2 = Math.sqrt(2.0D);

    /* Sample the natural height on a coarse lattice and interpolate between
     * samples so each region has a bounded amount of work. */
    private static final int COARSE_STRIDE = Math.max(4, Math.min(32,
        Integer.getInteger("lc2h.terrain.shift.coarseStride", 16)));

    private static final int REFERENCE_BLUR_COARSE = 1;

    private static final int MAX_SWEEPS = 12;

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int MAX_CACHED_REGIONS = Math.max(32,
        Integer.getInteger("lc2h.terrain.shift.cacheMaxRegions", 512));

    private static final int ROLE_TILE_SIDE = 32;

    private static final int MAX_ROLE_TILES = Math.max(64,
        Integer.getInteger("lc2h.terrain.shift.roleCacheMaxTiles", 4096));

    private static final Map<RegionKey, Region> CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentLinkedQueue<RegionKey> CACHE_ORDER = new ConcurrentLinkedQueue<>();
    /** One immutable build per region without holding a monitor during noise sampling. */
    private static final Map<RegionKey, CompletableFuture<Region>> REGION_FLIGHTS =
        new ConcurrentHashMap<>();
    private static final Map<IDimensionInfo, RoleCache> ROLES =
        new ConcurrentHashMap<>();

    /* Build fields on a small side pool so worldgen workers do not do the
     * full regional height pass themselves. */
    private static final int ASYNC_BUILD_THREADS = Math.max(1, Math.min(2,
        Integer.getInteger("lc2h.terrain.shift.asyncThreads", 1)));
    private static final int ASYNC_BUILD_QUEUE = Math.max(4, Math.min(64,
        Integer.getInteger("lc2h.terrain.shift.asyncQueue", 32)));
    private static final long ASYNC_RETRY_DELAY_NANOS = TimeUnit.SECONDS.toNanos(1L);
    private static final long WORLDGEN_QUIET_NANOS = TimeUnit.MILLISECONDS.toNanos(Math.max(50L,
        Long.getLong("lc2h.terrain.shift.worldgenQuietMs", 350L)));
    private static final int ROLE_YIELD_INTERVAL = Math.max(1, Math.min(32,
        Integer.getInteger("lc2h.terrain.shift.roleYieldInterval", 8)));
    private static final AtomicInteger ASYNC_THREAD_IDS = new AtomicInteger();
    private static final ThreadPoolExecutor ASYNC_BUILD_EXECUTOR =
        new ThreadPoolExecutor(
            ASYNC_BUILD_THREADS,
            ASYNC_BUILD_THREADS,
            30L,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(ASYNC_BUILD_QUEUE),
            runnable -> {
                Thread thread = new Thread(runnable,
                    "lc2h-shift-builder-" + ASYNC_THREAD_IDS.incrementAndGet());
                thread.setDaemon(true);
                thread.setPriority(Thread.MIN_PRIORITY);
                return thread;
            },
            new ThreadPoolExecutor.AbortPolicy());
    private static final AtomicLong LIFECYCLE = new AtomicLong();

    private static final AtomicLong REGIONS_BUILT = new AtomicLong();
    private static final AtomicLong REGION_HITS = new AtomicLong();
    private static final AtomicLong DEMAND_CELLS = new AtomicLong();
    private static final AtomicLong POSITIVE_DEMAND_CELLS = new AtomicLong();
    private static final AtomicInteger MAX_DEMAND = new AtomicInteger();
    private static final AtomicLong POSITIVE_OWNED_CELLS = new AtomicLong();
    private static final AtomicLong RECEIVER_HEIGHT_CORRECTIONS = new AtomicLong();
    private static final AtomicInteger MAX_OWNED_SHIFT = new AtomicInteger();
    private static final AtomicLong HEIGHT_SAMPLES = new AtomicLong();
    private static final AtomicLong EXACT_HEIGHT_SAMPLES = new AtomicLong();
    private static final AtomicLong EXACT_HEIGHT_FAILURES = new AtomicLong();
    private static final AtomicLong EDGE_RECOVERY_CANDIDATES = new AtomicLong();
    private static final AtomicLong EDGE_RECOVERY_SAMPLES = new AtomicLong();
    private static final AtomicLong EDGE_RECOVERY_APPLIED = new AtomicLong();
    private static final AtomicLong EROSION_SAMPLES = new AtomicLong();
    private static final AtomicLong BUILD_NANOS = new AtomicLong();
    private static final AtomicLong SWEEPS = new AtomicLong();
    private static final AtomicLong UNCONVERGED = new AtomicLong();
    private static final AtomicLong STABLE_ROLE_HITS = new AtomicLong();
    private static final AtomicLong STABLE_ROLE_FALLBACKS = new AtomicLong();
    private static final AtomicLong REGION_FLIGHT_WAITS = new AtomicLong();
    private static final AtomicLong REGION_FALLBACKS = new AtomicLong();
    private static final AtomicLong ROLE_FLIGHT_WAITS = new AtomicLong();
    private static final AtomicLong ROLE_CACHE_PUBLISHES = new AtomicLong();
    private static final AtomicLong ROLE_DUPLICATE_SUPPRESSED = new AtomicLong();
    private static final AtomicLong ROLE_HEIGHT_ESTIMATES = new AtomicLong();
    private static final AtomicLong RAW_CITY_FACTOR_FALLBACKS = new AtomicLong();
    private static final AtomicLong CITY_LEVEL_DIRECT_LOOKUPS = new AtomicLong();
    private static final AtomicLong CITY_LEVEL_HEIGHT_FALLBACKS = new AtomicLong();
    private static final AtomicLong ASYNC_SUBMITTED = new AtomicLong();
    private static final AtomicLong ASYNC_COMPLETED = new AtomicLong();
    private static final AtomicLong ASYNC_REJECTED = new AtomicLong();
    private static final AtomicLong ASYNC_FAILED = new AtomicLong();
    private static final AtomicLong PREWARM_REQUESTS = new AtomicLong();
    private static final AtomicLong PREWARM_READY = new AtomicLong();
    private static final AtomicLong PREWARM_NO_WAIT = new AtomicLong();
    private static final Map<RegionKey, Long> ASYNC_RETRY_AFTER = new ConcurrentHashMap<>();

    /* Live-config shape controls.  The previous solver used only maxShift and
     * the erosion slope, allowing a city demand to run hundreds of blocks
     * into a natural ridge.  A bounded run-out keeps the city edge smooth
     * without turning the adjacent mountain into a wall. */
    private static volatile int BLEND_WIDTH_BLOCKS = 36;
    private static volatile double BLEND_SOFTNESS = 1.4D;

    private CityShiftField() {
    }

    public record Context(IDimensionInfo provider,
                          ResourceKey<Level> dimension,
                          LostCityProfile profile,
                          NaturalHeightSampler.LevelSampler terrain,
                          ShiftSettings settings) {
    }

    public static Context context(IDimensionInfo provider,
                                  LostCityProfile profile,
                                  NaturalHeightSampler.LevelSampler terrain) {
        if (provider == null || profile == null || terrain == null) {
            return null;
        }
        ResourceKey<Level> dimension = provider.getType();
        if (dimension == null) {
            return null;
        }
        return new Context(provider, dimension, profile, terrain, ShiftSettings.current());
    }

    public static double sample(Context context, int blockX, int blockZ) {
        if (context == null || !context.settings().enabled()) {
            return 0.0D;
        }
        return CityDensityShiftField.sampleSmooth(blockX, blockZ,
            (chunkX, chunkZ) -> shiftAtChunk(context, chunkX, chunkZ));
    }

    public static double shiftAtChunk(Context context, int chunkX, int chunkZ) {
        if (context == null || !context.settings().enabled()) {
            return 0.0D;
        }
        Region region = region(context,
            Math.floorDiv(chunkX, REGION_SIDE), Math.floorDiv(chunkZ, REGION_SIDE));
        if (region == null) {
            REGION_FALLBACKS.incrementAndGet();
            return fallbackShift(context, chunkX, chunkZ);
        }
        return region.shift[Math.floorMod(chunkZ, REGION_SIDE) * REGION_SIDE
            + Math.floorMod(chunkX, REGION_SIDE)];
    }

    public static int plannedFloor(Context context, int chunkX, int chunkZ) {
        if (context == null) {
            return 0;
        }
        int natural = context.terrain().chunkHeight(chunkX, chunkZ);
        return natural - (int) Math.round(shiftAtChunk(context, chunkX, chunkZ));
    }

    public static double slopeLimitAtChunk(Context context, int chunkX, int chunkZ) {
        if (context == null) {
            return 0.0D;
        }
        double erosion = context.terrain().erosionAt((chunkX << 4) + 8, (chunkZ << 4) + 8);
        return context.settings().slopeForErosion(erosion);
    }

    public static void clear() {
        LIFECYCLE.incrementAndGet();
        REGION_FLIGHTS.values().forEach(flight -> flight.cancel(false));
        CACHE.clear();
        CACHE_ORDER.clear();
        REGION_FLIGHTS.clear();
        ASYNC_RETRY_AFTER.clear();
        ROLES.clear();
        IntercityHighwayIndex.clear();
    }

    /** Start a regional field build before the NOISE status. */
    public static void prewarm(Context context, int chunkX, int chunkZ) {
        if (context == null || !context.settings().enabled()) {
            return;
        }
        PREWARM_REQUESTS.incrementAndGet();
        Region ready = region(context,
            Math.floorDiv(chunkX, REGION_SIDE), Math.floorDiv(chunkZ, REGION_SIDE));
        if (ready != null) {
            PREWARM_READY.incrementAndGet();
        } else {
            PREWARM_NO_WAIT.incrementAndGet();
        }
    }

    /** Return the NOISE-stage dependency for the chunk's field regions. */
    public static CompletableFuture<Void> readyForChunk(Context context, int chunkX, int chunkZ) {
        if (context == null || !context.settings().enabled()) {
            return CompletableFuture.completedFuture(null);
        }
        Set<Long> regions = new HashSet<>();
        for (int dz = -2; dz <= 2; dz++) {
            for (int dx = -2; dx <= 2; dx++) {
                int regionX = Math.floorDiv(chunkX + dx, REGION_SIDE);
                int regionZ = Math.floorDiv(chunkZ + dz, REGION_SIDE);
                regions.add(((long) regionX << 32) ^ (regionZ & 0xffffffffL));
            }
        }
        CompletableFuture<?>[] futures = new CompletableFuture<?>[regions.size()];
        int index = 0;
        for (long packed : regions) {
            int regionX = (int) (packed >> 32);
            int regionZ = (int) packed;
            futures[index++] = readyRegion(context, regionX, regionZ);
        }
        return CompletableFuture.allOf(futures);
    }

    private static CompletableFuture<Region> readyRegion(Context context, int regionX, int regionZ) {
        RegionKey key = new RegionKey(context.provider(), context.dimension(), regionX, regionZ,
            context.profile().GROUNDLEVEL, context.settings().version());
        Region cached = CACHE.get(key);
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }
        region(context, regionX, regionZ);
        cached = CACHE.get(key);
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }
        CompletableFuture<Region> flight = REGION_FLIGHTS.get(key);
        if (flight != null) {
            return awaitRegionReady(context, regionX, regionZ, key, flight);
        }
        /* Keep the dependency pending when the builder is not available. A
         * delayed retry prevents an edge chunk from running without a field. */
        return retryRegionReady(context, regionX, regionZ, key);
    }

    private static CompletableFuture<Region> awaitRegionReady(Context context,
                                                               int regionX,
                                                               int regionZ,
                                                               RegionKey key,
                                                               CompletableFuture<Region> flight) {
        CompletableFuture<Region> result = new CompletableFuture<>();
        flight.whenComplete((built, failure) -> {
            if (built != null && failure == null) {
                result.complete(built);
            } else {
                scheduleRegionRetry(context, regionX, regionZ, key, result);
            }
        });
        return result;
    }

    private static CompletableFuture<Region> retryRegionReady(Context context,
                                                               int regionX,
                                                               int regionZ,
                                                               RegionKey key) {
        CompletableFuture<Region> result = new CompletableFuture<>();
        scheduleRegionRetry(context, regionX, regionZ, key, result);
        return result;
    }

    private static void scheduleRegionRetry(Context context,
                                            int regionX,
                                            int regionZ,
                                            RegionKey key,
                                            CompletableFuture<Region> result) {
        if (result.isDone()) {
            return;
        }
        Long retryAt = ASYNC_RETRY_AFTER.get(key);
        long delay = retryAt == null
            ? ASYNC_RETRY_DELAY_NANOS
            : Math.max(1L, retryAt - System.nanoTime());
        CompletableFuture.delayedExecutor(delay, TimeUnit.NANOSECONDS).execute(() -> {
            if (result.isDone()) {
                return;
            }
            try {
                Region cached = CACHE.get(key);
                if (cached != null) {
                    result.complete(cached);
                    return;
                }
                region(context, regionX, regionZ);
                cached = CACHE.get(key);
                if (cached != null) {
                    result.complete(cached);
                    return;
                }
                CompletableFuture<Region> flight = REGION_FLIGHTS.get(key);
                if (flight != null) {
                    flight.whenComplete((built, failure) -> {
                        if (built != null && failure == null) {
                            result.complete(built);
                        } else {
                            scheduleRegionRetry(context, regionX, regionZ, key, result);
                        }
                    });
                } else {
                    scheduleRegionRetry(context, regionX, regionZ, key, result);
                }
            } catch (Throwable ignored) {
                scheduleRegionRetry(context, regionX, regionZ, key, result);
            }
        });
    }

    /** Return the natural surface stored with a published field. */
    public static Integer plannedNaturalSurface(Context context, int chunkX, int chunkZ) {
        Region region = cachedRegion(context, chunkX, chunkZ);
        if (region == null) {
            return null;
        }
        return Math.round(region.natural[Math.floorMod(chunkZ, REGION_SIDE) * REGION_SIDE
            + Math.floorMod(chunkX, REGION_SIDE)]);
    }

    /** Return the smoothed native surface stored with a published field. */
    public static Integer plannedReferenceSurface(Context context, int chunkX, int chunkZ) {
        Region region = cachedRegion(context, chunkX, chunkZ);
        if (region == null) {
            return null;
        }
        return Math.round(region.reference[Math.floorMod(chunkZ, REGION_SIDE) * REGION_SIDE
            + Math.floorMod(chunkX, REGION_SIDE)]);
    }

    /** Build-time values retained by chunkdebug. */
    public record DebugCell(boolean lockedSource,
                            int roleType,
                            int roleLevel,
                            int sourceFloor,
                            double sourceDemand,
                            double sourceDistance,
                            double fadeDistance,
                            double naturalSurface,
                            double latticeStep,
                            double preRecoveryShift,
                            boolean zeroDemandCityEdge,
                            boolean exactReceiverEdge,
                            boolean recoverySampled,
                            boolean recoveryCandidateSeen,
                            boolean recoveryApplied,
                            double recoveryCandidate,
                            int recoveryFloor,
                            int recoveryNatural,
                            double propagatedShift,
                            double finalShift) {
    }

    /** Return the published cell values used by chunkdebug. */
    public static DebugCell debugCell(Context context, int chunkX, int chunkZ) {
        Region region = cachedRegion(context, chunkX, chunkZ);
        if (region == null) {
            return null;
        }
        int index = Math.floorMod(chunkZ, REGION_SIDE) * REGION_SIDE
            + Math.floorMod(chunkX, REGION_SIDE);
        int role = region.role[index];
        byte flags = region.recoveryFlags[index];
        return new DebugCell(
            region.locked[index],
            role & 3,
            role >> 2,
            region.sourceFloor[index],
            region.sourceDemand[index],
            region.sourceDistance[index],
            region.fadeDistance[index],
            region.natural[index],
            region.step[index],
            region.preRecoveryShift[index],
            (flags & 1) != 0,
            (flags & 2) != 0,
            (flags & 4) != 0,
            (flags & 8) != 0,
            (flags & 16) != 0,
            region.recoveryCandidate[index],
            region.recoveryFloor[index],
            region.recoveryNatural[index],
            region.propagatedShift[index],
            region.shift[index]);
    }

    /** Return a published shift without starting a field build. */
    public static double cachedShiftAtChunk(Context context, int chunkX, int chunkZ) {
        Region region = cachedRegion(context, chunkX, chunkZ);
        if (region == null) {
            return 0.0D;
        }
        return region.shift[Math.floorMod(chunkZ, REGION_SIDE) * REGION_SIDE
            + Math.floorMod(chunkX, REGION_SIDE)];
    }

    /** Check published field coverage for a Blender region. */
    public static boolean hasCachedPositiveShiftNear(Context context,
                                                      int chunkX,
                                                      int chunkZ,
                                                      int radius) {
        if (context == null || !context.settings().enabled()) {
            return false;
        }
        int scan = Math.max(1, Math.min(MAX_HALO + 2, radius));
        for (int dz = -scan; dz <= scan; dz++) {
            for (int dx = -scan; dx <= scan; dx++) {
                if (cachedShiftAtChunk(context, chunkX + dx, chunkZ + dz) > 0.0D) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Region cachedRegion(Context context, int chunkX, int chunkZ) {
        if (context == null || !context.settings().enabled()) {
            return null;
        }
        RegionKey key = new RegionKey(context.provider(), context.dimension(),
            Math.floorDiv(chunkX, REGION_SIDE), Math.floorDiv(chunkZ, REGION_SIDE),
            context.profile().GROUNDLEVEL, context.settings().version());
        return CACHE.get(key);
    }

    /** Apply the config-screen blend shape without restarting the server. */
    public static void withBlendShape(int widthBlocks, double softness) {
        int width = Math.max(8, Math.min(MAX_HALO * 16, widthBlocks));
        double curve = Double.isFinite(softness)
            ? Math.max(0.35D, Math.min(3.0D, softness))
            : 1.4D;
        if (BLEND_WIDTH_BLOCKS == width && Double.compare(BLEND_SOFTNESS, curve) == 0) {
            return;
        }
        BLEND_WIDTH_BLOCKS = width;
        BLEND_SOFTNESS = curve;
        clear();
    }

    public static String diagnostics() {
        ShiftSettings settings = ShiftSettings.current();
        long built = REGIONS_BUILT.get();
        return settings.describe()
            + ", regionsBuilt=" + built
            + ", regionHits=" + REGION_HITS.get()
            + ", demandCells=" + DEMAND_CELLS.get()
            + ", positiveDemandCells=" + POSITIVE_DEMAND_CELLS.get()
            + ", maxDemand=" + MAX_DEMAND.get()
            + ", positiveOwnedCells=" + POSITIVE_OWNED_CELLS.get()
            + ", receiverHeightCorrections=" + RECEIVER_HEIGHT_CORRECTIONS.get()
            + ", maxOwnedShift=" + MAX_OWNED_SHIFT.get()
            + ", heightSamples=" + HEIGHT_SAMPLES.get()
            + ", exactHeightSamples=" + EXACT_HEIGHT_SAMPLES.get()
            + ", exactHeightFailures=" + EXACT_HEIGHT_FAILURES.get()
            + ", edgeRecovery[candidates=" + EDGE_RECOVERY_CANDIDATES.get()
            + " samples=" + EDGE_RECOVERY_SAMPLES.get()
            + " applied=" + EDGE_RECOVERY_APPLIED.get() + "]"
            + ", erosionSamples=" + EROSION_SAMPLES.get()
            + ", avgSweeps=" + (built == 0 ? "n/a"
                : String.format(java.util.Locale.ROOT, "%.1f", (double) SWEEPS.get() / built))
            + ", unconvergedRegions=" + UNCONVERGED.get()
            + ", avgBuildMs=" + (built == 0 ? "n/a"
                : String.format(java.util.Locale.ROOT, "%.1f", BUILD_NANOS.get() / 1.0E6D / built))
            + ", cachedRegions=" + CACHE.size()
            + ", cachedRoleTiles=" + ROLES.values().stream().mapToInt(RoleCache::size).sum()
            + ", stableRoleHits=" + STABLE_ROLE_HITS.get()
            + ", stableRoleFallbacks=" + STABLE_ROLE_FALLBACKS.get()
            + ", roleHeightEstimates=" + ROLE_HEIGHT_ESTIMATES.get()
            + ", rawCityFactorFallbacks=" + RAW_CITY_FACTOR_FALLBACKS.get()
            + ", cityLevelDirectLookups=" + CITY_LEVEL_DIRECT_LOOKUPS.get()
            + ", cityLevelHeightFallbacks=" + CITY_LEVEL_HEIGHT_FALLBACKS.get()
            + ", regionFlightWaits=" + REGION_FLIGHT_WAITS.get()
            + ", regionFallbacks=" + REGION_FALLBACKS.get()
            + ", roleFlightWaits=" + ROLE_FLIGHT_WAITS.get()
            + ", roleCachePublishes=" + ROLE_CACHE_PUBLISHES.get()
            + ", roleDuplicateSuppressed=" + ROLE_DUPLICATE_SUPPRESSED.get()
            + ", activeRegionFlights=" + REGION_FLIGHTS.size()
            + ", asyncSubmitted=" + ASYNC_SUBMITTED.get()
            + ", asyncCompleted=" + ASYNC_COMPLETED.get()
            + ", asyncRejected=" + ASYNC_REJECTED.get()
            + ", asyncFailed=" + ASYNC_FAILED.get()
            + ", prewarm[requests=" + PREWARM_REQUESTS.get()
            + " ready=" + PREWARM_READY.get()
            + " noWait=" + PREWARM_NO_WAIT.get() + "]"
            + ", asyncActive=" + ASYNC_BUILD_EXECUTOR.getActiveCount()
            + ", asyncQueued=" + ASYNC_BUILD_EXECUTOR.getQueue().size()
            + ", blendWidthBlocks=" + BLEND_WIDTH_BLOCKS
            + ", blendSoftness=" + String.format(java.util.Locale.ROOT, "%.3f", BLEND_SOFTNESS)
            + ", " + IntercityHighwayIndex.diagnostics();
    }

    private static Region region(Context context, int regionX, int regionZ) {
        RegionKey key = new RegionKey(context.provider(), context.dimension(), regionX, regionZ,
            context.profile().GROUNDLEVEL, context.settings().version());
        Region cached = CACHE.get(key);
        if (cached != null) {
            REGION_HITS.incrementAndGet();
            return cached;
        }

        Long retryAt = ASYNC_RETRY_AFTER.get(key);
        if (retryAt != null) {
            if (retryAt > System.nanoTime()) {
                return null;
            }
            ASYNC_RETRY_AFTER.remove(key, retryAt);
        }

        CompletableFuture<Region> created = new CompletableFuture<>();
        CompletableFuture<Region> flight = REGION_FLIGHTS.putIfAbsent(key, created);
        if (flight != null) {
            REGION_FLIGHT_WAITS.incrementAndGet();
            return awaitRegion(flight);
        }

        try {
            long lifecycle = LIFECYCLE.get();
            ASYNC_BUILD_EXECUTOR.execute(() -> buildRegionAsync(
                context, regionX, regionZ, key, created, lifecycle));
            ASYNC_SUBMITTED.incrementAndGet();
        } catch (RejectedExecutionException rejected) {
            ASYNC_REJECTED.incrementAndGet();
            /* Keep the expensive pass off worldgen workers. Retry after queue
             * pressure eases. */
            ASYNC_RETRY_AFTER.put(key, System.nanoTime() + ASYNC_RETRY_DELAY_NANOS);
            REGION_FLIGHTS.remove(key, created);
            created.completeExceptionally(rejected);
        }
        /* Return the scalar fallback while the region is built. */
        return awaitRegion(created);
    }

    private static void buildRegionAsync(Context context,
                                         int regionX,
                                         int regionZ,
                                         RegionKey key,
                                         CompletableFuture<Region> created,
                                         long lifecycle) {
        try {
            if (lifecycle != LIFECYCLE.get()) {
                created.cancel(false);
                return;
            }
            long started = System.nanoTime();
            Region built = buildRegion(context, regionX, regionZ);
            if (lifecycle != LIFECYCLE.get()) {
                created.cancel(false);
                return;
            }
            long elapsed = System.nanoTime() - started;
            BUILD_NANOS.addAndGet(elapsed);
            REGIONS_BUILT.incrementAndGet();
            if (elapsed > SLOW_BUILD_WARN_NANOS) {
                LOGGER.warn("[LC2H] Shift field region {},{} took {} ms to build "
                        + "(coarse={} ms roles={} ms gradient={} ms finish={} ms; {}). "
                        + "It ran on the bounded async shift builder pool; worldgen did not wait.",
                    regionX, regionZ, elapsed / 1_000_000L,
                    built.coarseNanos() / 1_000_000L,
                    built.roleNanos() / 1_000_000L,
                    built.gradientNanos() / 1_000_000L,
                    built.finishNanos() / 1_000_000L,
                    context.settings().describe());
            }
            Region published = CACHE.putIfAbsent(key, built);
            Region result = published != null ? published : built;
            if (published == null) {
                CACHE_ORDER.add(key);
                trimRegions();
            }
            created.complete(result);
            ASYNC_COMPLETED.incrementAndGet();
        } catch (CancellationException ignored) {
            created.cancel(false);
        } catch (Throwable failure) {
            ASYNC_FAILED.incrementAndGet();
            /* Retry after a transient builder failure so the chunk does not
             * fall back to an unmodified edge. */
            ASYNC_RETRY_AFTER.put(key, System.nanoTime() + ASYNC_RETRY_DELAY_NANOS);
            created.completeExceptionally(failure);
        } finally {
            REGION_FLIGHTS.remove(key, created);
        }
    }

    private static Region awaitRegion(CompletableFuture<Region> flight) {
        try {
            return flight.getNow(null);
        } catch (CompletionException | CancellationException ignored) {
            return null;
        }
    }

    private static double fallbackShift(Context context, int chunkX, int chunkZ) {
        ChunkRoleProbe.Probe probe = ChunkRoleProbe.peekStableTerrainProbe(
            context.provider(), context.dimension(), chunkX, chunkZ);
        if (probe == null || (!probe.isCity() && probe.highwayLevel() < 0)) {
            return 0.0D;
        }
        /* Use a resident height while the regional field is pending. */
        Integer cachedNatural = context.terrain().cachedChunkHeight(chunkX, chunkZ);
        if (cachedNatural == null) {
            REGION_FALLBACKS.incrementAndGet();
            return 0.0D;
        }
        int natural = cachedNatural;
        int level = probe.isCity() ? probe.cityLevel() : probe.highwayLevel();
        int floor = context.profile().GROUNDLEVEL
            + level * LostCityTerrainFeature.FLOORHEIGHT;
        if (natural < floor) {
            return 0.0D;
        }
        if (natural >= floor + MountainCityReservationPlanner.MIN_RISE
            && probe.isCity()
            && MountainCityReservationPlanner.peekRemovesBuildingCell(
                context.provider(), new ChunkCoord(context.dimension(), chunkX, chunkZ),
                context.profile())) {
            return 0.0D;
        }
        return Math.min(context.settings().maxShift(), natural - floor);
    }

    private static void trimRegions() {
        while (CACHE.size() > MAX_CACHED_REGIONS) {
            RegionKey oldest = CACHE_ORDER.poll();
            if (oldest == null) {
                return;
            }
            CACHE.remove(oldest);
        }
    }

    private static Region buildRegion(Context context, int regionX, int regionZ) {
        long phaseStarted = System.nanoTime();
        ShiftSettings settings = context.settings();
        int halo = settings.halo();
        int side = REGION_SIDE + halo * 2;
        int originX = regionX * REGION_SIDE - halo;
        int originZ = regionZ * REGION_SIDE - halo;
        int cells = side * side;

        int coarseSide = side / COARSE_STRIDE + 2;
        float[] coarseNatural = new float[coarseSide * coarseSide];
        float[] coarseStep = new float[coarseSide * coarseSide];
        for (int cz = 0; cz < coarseSide; cz++) {
            for (int cx = 0; cx < coarseSide; cx++) {
                yieldToWorldgen();
                int chunkX = originX + cx * COARSE_STRIDE;
                int chunkZ = originZ + cz * COARSE_STRIDE;
                HEIGHT_SAMPLES.incrementAndGet();
                coarseNatural[cz * coarseSide + cx] =
                    context.terrain().chunkHeight(chunkX, chunkZ);
                EROSION_SAMPLES.incrementAndGet();
                double erosion = context.terrain()
                    .erosionAt((chunkX << 4) + 8, (chunkZ << 4) + 8);
                coarseStep[cz * coarseSide + cx] =
                    (float) settings.latticeStepForErosion(erosion);
            }
        }
        float[] reference = blur(coarseNatural, coarseSide, REFERENCE_BLUR_COARSE);

        float[] step = new float[cells];
        float[] naturalSurface = new float[cells];
        for (int gz = 0; gz < side; gz++) {
            for (int gx = 0; gx < side; gx++) {
                int index = gz * side + gx;
                step[index] = (float) bilinear(coarseStep, coarseSide, gx, gz);
                naturalSurface[index] = (float) bilinear(coarseNatural, coarseSide, gx, gz);
            }
        }
        long coarseNanos = System.nanoTime() - phaseStarted;

        phaseStarted = System.nanoTime();
        float[] value = new float[cells];
        float[] sourceDemand = new float[cells];
        float[] sourceDistance = new float[cells];
        int[] sourceFloor = new int[cells];
        int[] roleByCell = new int[cells];
        boolean[] locked = new boolean[cells];
        Arrays.fill(value, Float.NEGATIVE_INFINITY);
        Arrays.fill(sourceDistance, Float.POSITIVE_INFINITY);
        Arrays.fill(sourceFloor, Integer.MIN_VALUE);
        for (int gz = 0; gz < side; gz++) {
            for (int gx = 0; gx < side; gx++) {
                if ((gx % ROLE_YIELD_INTERVAL) == 0) {
                    yieldToWorldgen();
                }
                int chunkX = originX + gx;
                int chunkZ = originZ + gz;
                int index = gz * side + gx;
                long roleValue = packedRole(context, chunkX, chunkZ);
                int role = roleCode(roleValue);
                roleByCell[index] = role;
                if (!demandsShift(role)) {
                    continue;
                }
                int floor = context.profile().GROUNDLEVEL
                    + roleLevel(role) * LostCityTerrainFeature.FLOORHEIGHT;
                int natural = naturalHeight(roleValue);
                if (natural == Integer.MIN_VALUE) {
                    // A cached role can arrive before its height. Sample it now.
                    HEIGHT_SAMPLES.incrementAndGet();
                    natural = context.terrain().chunkHeight(chunkX, chunkZ);
                }
                /* Keep exact heights for city sources so nearby ridges use the
                 * real boundary instead of the coarse average. */
                naturalSurface[index] = natural;
                int demand = Math.max(0, Math.min(settings.maxShift(), natural - floor));
                value[index] = demand;
                sourceDemand[index] = demand;
                sourceDistance[index] = 0.0F;
                sourceFloor[index] = floor;
                locked[index] = true;
                DEMAND_CELLS.incrementAndGet();
                if (demand > 0) {
                    POSITIVE_DEMAND_CELLS.incrementAndGet();
                    MAX_DEMAND.accumulateAndGet(demand, Math::max);
                }
            }
        }
        long roleNanos = System.nanoTime() - phaseStarted;
        /* Keep propagation distance separate from the visual fade distance. */
        float[] fadeDistance = nearestLockedDistance(locked, side, BLEND_WIDTH_BLOCKS);

        /* Refresh the first non-city ring with exact heights before the
         * gradient pass so narrow ridges are represented. */
        refineCityEdgeNaturalSurface(context, originX, originZ, side, locked, naturalSurface);

        phaseStarted = System.nanoTime();
        int sweeps = boundedGradientTransform(value, sourceDemand, sourceDistance, locked, step,
            naturalSurface, side, BLEND_WIDTH_BLOCKS);
        float[] preRecoveryShift = Arrays.copyOf(value, cells);
        float[] recoveryCandidate = new float[cells];
        int[] recoveryFloor = new int[cells];
        int[] recoveryNatural = new int[cells];
        byte[] recoveryFlags = new byte[cells];
        Arrays.fill(recoveryCandidate, Float.NEGATIVE_INFINITY);
        Arrays.fill(recoveryFloor, Integer.MIN_VALUE);
        Arrays.fill(recoveryNatural, Integer.MIN_VALUE);
        // Recover zero-demand edge cells from their exact base height. This is
        // limited to the configured blend radius.
        if (recoverSharpCityEdges(context, originX, originZ, side, value, sourceDemand,
            sourceDistance, sourceFloor, locked, step, settings, recoveryCandidate,
            recoveryFloor, recoveryNatural, recoveryFlags)) {
            sweeps += boundedGradientTransform(value, sourceDemand, sourceDistance, locked, step,
                naturalSurface, side, BLEND_WIDTH_BLOCKS);
        }
        SWEEPS.addAndGet(sweeps);
        if (sweeps >= MAX_SWEEPS) {
            UNCONVERGED.incrementAndGet();
        }
        long gradientNanos = System.nanoTime() - phaseStarted;

        phaseStarted = System.nanoTime();
        float[] owned = new float[REGION_SIDE * REGION_SIDE];
        float[] ownedNatural = new float[owned.length];
        float[] ownedReference = new float[owned.length];
        float[] ownedStep = new float[owned.length];
        float[] ownedSourceDemand = new float[owned.length];
        float[] ownedSourceDistance = new float[owned.length];
        float[] ownedFadeDistance = new float[owned.length];
        float[] ownedPreRecoveryShift = new float[owned.length];
        float[] ownedRecoveryCandidate = new float[owned.length];
        float[] ownedPropagatedShift = new float[owned.length];
        int[] ownedRole = new int[owned.length];
        int[] ownedSourceFloor = new int[owned.length];
        int[] ownedRecoveryFloor = new int[owned.length];
        int[] ownedRecoveryNatural = new int[owned.length];
        boolean[] ownedLocked = new boolean[owned.length];
        byte[] ownedRecoveryFlags = new byte[owned.length];
        double strength = settings.reliefStrength();
        for (int localZ = 0; localZ < REGION_SIDE; localZ++) {
            for (int localX = 0; localX < REGION_SIDE; localX++) {
                int gx = localX + halo;
                int gz = localZ + halo;
                int index = gz * side + gx;
                int ownedIndex = localZ * REGION_SIDE + localX;
                double natural = naturalSurface[index];
                double smooth = bilinear(reference, coarseSide, gx, gz);
                ownedNatural[ownedIndex] = (float) natural;
                ownedReference[ownedIndex] = (float) smooth;
                ownedStep[ownedIndex] = step[index];
                ownedSourceDemand[ownedIndex] = sourceDemand[index];
                ownedSourceDistance[ownedIndex] = sourceDistance[index];
                ownedFadeDistance[ownedIndex] = fadeDistance[index];
                ownedPreRecoveryShift[ownedIndex] = preRecoveryShift[index];
                ownedRecoveryCandidate[ownedIndex] = recoveryCandidate[index];
                ownedPropagatedShift[ownedIndex] = value[index];
                ownedRole[ownedIndex] = roleByCell[index];
                ownedSourceFloor[ownedIndex] = sourceFloor[index];
                ownedRecoveryFloor[ownedIndex] = recoveryFloor[index];
                ownedRecoveryNatural[ownedIndex] = recoveryNatural[index];
                ownedLocked[ownedIndex] = locked[index];
                ownedRecoveryFlags[ownedIndex] = recoveryFlags[index];
                double shift = value[index];
                if (shift <= 0.0D) {
                    owned[ownedIndex] = 0.0F;
                    continue;
                }
                double distance = fadeDistance[index];
                if (Double.isFinite(distance) && BLEND_WIDTH_BLOCKS > 0) {
                    double normalized = Mth.clamp(distance / BLEND_WIDTH_BLOCKS, 0.0D, 1.0D);
                    normalized = Math.pow(normalized, BLEND_SOFTNESS);
                    double fade = normalized * normalized * (3.0D - 2.0D * normalized);
                    shift *= 1.0D - fade;
                }
                POSITIVE_OWNED_CELLS.incrementAndGet();
                MAX_OWNED_SHIFT.accumulateAndGet((int) Math.ceil(shift), Math::max);
                if (strength > 0.0D && !locked[index] && sourceDemand[index] > 1.0F) {
                    double t = Mth.clamp(shift / sourceDemand[index], 0.0D, 1.0D);
                    double alpha = strength * 4.0D * t * (1.0D - t);
                    shift += alpha * Math.max(0.0D, natural - smooth);
                }
                owned[ownedIndex] = (float) shift;
            }
        }
        long finishNanos = System.nanoTime() - phaseStarted;
        return new Region(owned, ownedNatural, ownedReference, ownedStep,
            ownedSourceDemand, ownedSourceDistance, ownedFadeDistance, ownedPreRecoveryShift,
            ownedRecoveryCandidate, ownedPropagatedShift, ownedLocked,
            ownedRole, ownedSourceFloor, ownedRecoveryFloor, ownedRecoveryNatural,
            ownedRecoveryFlags,
            coarseNanos, roleNanos, gradientNanos, finishNanos);
    }

    private static void yieldToWorldgen() {
        /* Avoid waiting on Lost Cities locks while sampling a field. */
        if (LostCitiesGenerationLocks.activeHolders() > 0) {
            Thread.yield();
        }
    }

    private static boolean recoverSharpCityEdges(Context context,
                                                  int originX,
                                                  int originZ,
                                                  int side,
                                                  float[] value,
                                                  float[] sourceDemand,
                                                  float[] sourceDistance,
                                                  int[] sourceFloor,
                                                  boolean[] locked,
                                                  float[] step,
                                                  ShiftSettings settings,
                                                  float[] recoveryCandidate,
                                                  int[] recoveryFloor,
                                                  int[] recoveryNatural,
                                                  byte[] recoveryFlags) {
        if (BLEND_WIDTH_BLOCKS <= 0) {
            return false;
        }
        // The first ring seeds the existing gradient pass; do not sample the
        // whole skirt at full resolution.
        int radius = 1;
        boolean changed = false;
        for (int z = 0; z < side; z++) {
            for (int x = 0; x < side; x++) {
                int index = z * side + x;
                boolean zeroDemandCityEdge = locked[index]
                    && value[index] <= 1.0E-4F
                    && sourceFloor[index] != Integer.MIN_VALUE
                    && hasUnlockedNeighbour(locked, x, z, side);
                boolean exactReceiverEdge = !locked[index]
                    && (hasLockedNeighbour(locked, x, z, side)
                        || hasRawCityNeighbour(context, originX, originZ, x, z, side));
                if (zeroDemandCityEdge) {
                    recoveryFlags[index] |= 1;
                }
                if (exactReceiverEdge) {
                    recoveryFlags[index] |= 2;
                }
                if ((!zeroDemandCityEdge && !exactReceiverEdge && locked[index])
                    || (value[index] > 1.0E-4F && !exactReceiverEdge)) {
                    continue;
                }
                float best = value[index];
                float bestDistance = Float.POSITIVE_INFINITY;
                int exactNatural = Integer.MIN_VALUE;
                boolean exactSampled = false;
                if (zeroDemandCityEdge) {
                    exactNatural = exactEdgeHeight(context, originX + x, originZ + z);
                    exactSampled = true;
                    recoveryFlags[index] |= 4;
                    recoveryNatural[index] = exactNatural;
                    if (exactNatural != Integer.MIN_VALUE) {
                        best = (float) Math.min(settings.maxShift(),
                            Math.max(0.0D, exactNatural - sourceFloor[index]));
                        bestDistance = 0.0F;
                    }
                }
                for (int dz = -radius; dz <= radius; dz++) {
                    for (int dx = -radius; dx <= radius; dx++) {
                        if (dx == 0 && dz == 0) {
                            continue;
                        }
                        int nx = x + dx;
                        int nz = z + dz;
                        if (nx < 0 || nz < 0 || nx >= side || nz >= side) {
                            continue;
                        }
                        int neighbour = nz * side + nx;
                        int floor = sourceFloor[neighbour];
                        if (floor == Integer.MIN_VALUE) {
                            floor = rawCityFloor(context, originX + nx, originZ + nz);
                        }
                        if (floor == Integer.MIN_VALUE) {
                            continue;
                        }
                        float distance = (float) (16.0D * Math.hypot(dx, dz));
                        if (distance > BLEND_WIDTH_BLOCKS + 1.0E-4F) {
                            continue;
                        }
                        EDGE_RECOVERY_CANDIDATES.incrementAndGet();
                        if (!exactSampled) {
                            try {
                                EDGE_RECOVERY_SAMPLES.incrementAndGet();
                                exactNatural = context.terrain().chunkHeight(originX + x, originZ + z);
                                exactSampled = true;
                                recoveryFlags[index] |= 4;
                                recoveryNatural[index] = exactNatural;
                            } catch (Throwable ignored) {
                                EXACT_HEIGHT_FAILURES.incrementAndGet();
                                exactSampled = true;
                                recoveryFlags[index] |= 4;
                            }
                        }
                        if (exactNatural == Integer.MIN_VALUE) {
                            continue;
                        }
                        float slopeCost = 0.5F * (step[index] + step[neighbour])
                            * (distance / 16.0F);
                        float candidate = (float) Math.min(settings.maxShift(),
                            Math.max(0.0D, exactNatural - floor - slopeCost));
                        recoveryFlags[index] |= 8;
                        if (candidate > recoveryCandidate[index]) {
                            recoveryCandidate[index] = candidate;
                            recoveryFloor[index] = floor;
                            recoveryNatural[index] = exactNatural;
                        }
                        if (candidate > best + 1.0E-4F) {
                            best = candidate;
                            bestDistance = distance;
                        }
                    }
                }
                if (best > value[index] + 1.0E-4F) {
                    value[index] = best;
                    sourceDistance[index] = bestDistance;
                    if (zeroDemandCityEdge || exactReceiverEdge) {
                        sourceDemand[index] = best;
                        if (best > 0.0F) {
                            POSITIVE_DEMAND_CELLS.incrementAndGet();
                            MAX_DEMAND.accumulateAndGet((int) Math.ceil(best), Math::max);
                        }
                    }
                    EDGE_RECOVERY_APPLIED.incrementAndGet();
                    recoveryFlags[index] |= 16;
                    changed = true;
                }
            }
        }
        return changed;
    }

    private static void refineCityEdgeNaturalSurface(Context context,
                                                      int originX,
                                                      int originZ,
                                                      int side,
                                                      boolean[] locked,
                                                      float[] naturalSurface) {
        if (context == null || BLEND_WIDTH_BLOCKS <= 0) {
            return;
        }
        for (int z = 0; z < side; z++) {
            for (int x = 0; x < side; x++) {
                int index = z * side + x;
                if (locked[index] || !hasLockedNeighbour(locked, x, z, side)) {
                    continue;
                }
                int exact = exactEdgeHeight(context, originX + x, originZ + z);
                if (exact != Integer.MIN_VALUE) {
                    naturalSurface[index] = exact;
                }
            }
        }
    }

    private static boolean hasLockedNeighbour(boolean[] locked, int x, int z, int side) {
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                int nx = x + dx;
                int nz = z + dz;
                if (nx >= 0 && nz >= 0 && nx < side && nz < side
                    && locked[nz * side + nx]) {
                    return true;
                }
            }
        }
        return false;
    }

    private static float[] nearestLockedDistance(boolean[] locked,
                                                  int side,
                                                  int maxDistanceBlocks) {
        float[] distance = new float[locked.length];
        Arrays.fill(distance, Float.POSITIVE_INFINITY);
        int radius = Math.max(1, (int) Math.ceil(maxDistanceBlocks / 16.0D));
        for (int z = 0; z < side; z++) {
            for (int x = 0; x < side; x++) {
                int index = z * side + x;
                if (locked[index]) {
                    distance[index] = 0.0F;
                    continue;
                }
                float best = Float.POSITIVE_INFINITY;
                for (int dz = -radius; dz <= radius; dz++) {
                    for (int dx = -radius; dx <= radius; dx++) {
                        int nx = x + dx;
                        int nz = z + dz;
                        if (nx < 0 || nz < 0 || nx >= side || nz >= side
                            || !locked[nz * side + nx]) {
                            continue;
                        }
                        float candidate = (float) (16.0D * Math.hypot(dx, dz));
                        if (candidate <= maxDistanceBlocks + 1.0E-4F) {
                            best = Math.min(best, candidate);
                        }
                    }
                }
                distance[index] = best;
            }
        }
        return distance;
    }

    private static boolean hasRawCityNeighbour(Context context,
                                                int originX,
                                                int originZ,
                                                int x,
                                                int z,
                                                int side) {
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                int nx = x + dx;
                int nz = z + dz;
                if (nx >= 0 && nz >= 0 && nx < side && nz < side
                    && rawCityFloor(context, originX + nx, originZ + nz)
                        != Integer.MIN_VALUE) {
                    return true;
                }
            }
        }
        return false;
    }

    private static int rawCityFloor(Context context, int chunkX, int chunkZ) {
        if (context == null) {
            return Integer.MIN_VALUE;
        }
        try {
            ChunkCoord coord = new ChunkCoord(context.dimension(), chunkX, chunkZ);
            if (!rawCityPredicate(context, coord)) {
                return Integer.MIN_VALUE;
            }
            ChunkRoleProbe.Probe stable = ChunkRoleProbe.peekStableTerrainProbe(
                context.provider(), context.dimension(), chunkX, chunkZ);
            int level = stable != null ? stable.cityLevel() : 0;
            return context.profile().GROUNDLEVEL
                + level * LostCityTerrainFeature.FLOORHEIGHT;
        } catch (Throwable ignored) {
            return Integer.MIN_VALUE;
        }
    }

    /** Resolve the raw Lost Cities city predicate for the field. */
    private static boolean rawCityPredicate(Context context, ChunkCoord coord) {
        if (context == null || coord == null) {
            return false;
        }
        LostCityProfile profile = context.profile();
        IDimensionInfo provider = context.provider();
        try {
            if (profile.isFloating() && BuildingInfo.isVoidChunk(coord, provider)) {
                return false;
            }
            boolean city = PlannerHotPath.run(() ->
                BuildingInfo.isCityRaw(coord, provider, profile));
            if (city) {
                return true;
            }
            if ((profile.isSpace() || profile.isSpheres())
                && (CitySphere.onCitySphereBorder(coord, provider)
                || CitySphere.hasMonorailStation(coord, provider))) {
                return false;
            }
            boolean factorCity = City.getCityFactor(coord, provider, profile) > profile.CITY_THRESHOLD;
            if (factorCity) {
                RAW_CITY_FACTOR_FALLBACKS.incrementAndGet();
            }
            return factorCity;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean hasUnlockedNeighbour(boolean[] locked, int x, int z, int side) {
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                int nx = x + dx;
                int nz = z + dz;
                if (nx >= 0 && nz >= 0 && nx < side && nz < side
                    && !locked[nz * side + nx]) {
                    return true;
                }
            }
        }
        return false;
    }

    private static int exactEdgeHeight(Context context, int chunkX, int chunkZ) {
        try {
            EDGE_RECOVERY_SAMPLES.incrementAndGet();
            return context.terrain().chunkHeight(chunkX, chunkZ);
        } catch (Throwable ignored) {
            EXACT_HEIGHT_FAILURES.incrementAndGet();
            return Integer.MIN_VALUE;
        }
    }

    static int boundedGradientTransform(float[] value,
                                        float[] sourceDemand,
                                        boolean[] locked,
                                        float[] step,
                                        int side) {
        float[] sourceDistance = new float[value.length];
        Arrays.fill(sourceDistance, Float.POSITIVE_INFINITY);
        for (int i = 0; i < locked.length; i++) {
            if (locked[i]) {
                sourceDistance[i] = 0.0F;
            }
        }
        return boundedGradientTransform(value, sourceDemand, sourceDistance, locked, step, side,
            BLEND_WIDTH_BLOCKS);
    }

    static int boundedGradientTransform(float[] value,
                                        float[] sourceDemand,
                                        float[] sourceDistance,
                                        boolean[] locked,
                                        float[] step,
                                        int side,
                                        int blendWidthBlocks) {
        return boundedGradientTransform(value, sourceDemand, sourceDistance, locked, step,
            new float[value.length], side, blendWidthBlocks);
    }

    static int boundedGradientTransform(float[] value,
                                        float[] sourceDemand,
                                        float[] sourceDistance,
                                        boolean[] locked,
                                        float[] step,
                                        float[] naturalSurface,
                                        int side,
                                        int blendWidthBlocks) {
        final float epsilon = 1.0E-4F;
        final float maxDistance = Math.max(8.0F, blendWidthBlocks);
        long receiverHeightCorrections = 0L;
        for (int sweep = 1; sweep <= MAX_SWEEPS; sweep++) {
            boolean changed = false;
            boolean forward = (sweep & 1) == 1;
            int start = forward ? 0 : side - 1;
            int end = forward ? side : -1;
            int delta = forward ? 1 : -1;
            for (int z = start; z != end; z += delta) {
                for (int x = start; x != end; x += delta) {
                    int i = z * side + x;
                    /* Source demand is a lower bound. Let the envelope raise a
                     * neighbouring source without lowering its own demand. */
                    float best = value[i];
                    float bestDemand = sourceDemand[i];
                    for (int dz = -1; dz <= 1; dz++) {
                        for (int dx = -1; dx <= 1; dx++) {
                            if (dx == 0 && dz == 0) {
                                continue;
                            }
                            int nx = x + dx;
                            int nz = z + dz;
                            if (nx < 0 || nz < 0 || nx >= side || nz >= side) {
                                continue;
                            }
                            int n = nz * side + nx;
                            if (value[n] == Float.NEGATIVE_INFINITY) {
                                continue;
                            }
                            float neighbourDistance = sourceDistance[n];
                            if (!Float.isFinite(neighbourDistance)) {
                                continue;
                            }
                            float distanceCost = (dx != 0 && dz != 0)
                                ? (float) (16.0D * SQRT2)
                                : 16.0F;
                            float candidateDistance = neighbourDistance + distanceCost;
                            if (candidateDistance > maxDistance + epsilon) {
                                continue;
                            }
                            float mean = 0.5F * (step[i] + step[n]);
                            float cost = (dx != 0 && dz != 0) ? (float) (mean * SQRT2) : mean;
                            /* Propagate the allowed surface through the
                             * height difference between neighbouring cells. */
                            float receiverRise = naturalSurface[i] - naturalSurface[n];
                            float candidate = value[n] + receiverRise - cost;
                            if (candidate > best + epsilon) {
                                if (receiverRise > epsilon) {
                                    receiverHeightCorrections++;
                                }
                                best = candidate;
                                bestDemand = sourceDemand[n];
                                if (!locked[i]) {
                                    sourceDistance[i] = candidateDistance;
                                }
                            } else if (candidate > best - epsilon && sourceDemand[n] > bestDemand) {
                                best = Math.max(best, candidate);
                                bestDemand = sourceDemand[n];
                                if (!locked[i]) {
                                    sourceDistance[i] = Math.min(sourceDistance[i], candidateDistance);
                                }
                            }
                        }
                    }
                    if (best > value[i] + epsilon || bestDemand != sourceDemand[i]) {
                        changed |= best > value[i] + epsilon;
                        value[i] = Math.max(value[i], best);
                        sourceDemand[i] = bestDemand;
                    }
                }
            }
            if (!changed) {
                RECEIVER_HEIGHT_CORRECTIONS.addAndGet(receiverHeightCorrections);
                return sweep;
            }
        }
        RECEIVER_HEIGHT_CORRECTIONS.addAndGet(receiverHeightCorrections);
        return MAX_SWEEPS;
    }

    private static double bilinear(float[] coarse, int coarseSide, int x, int z) {
        double fx = (double) x / COARSE_STRIDE;
        double fz = (double) z / COARSE_STRIDE;
        int x0 = Mth.clamp((int) Math.floor(fx), 0, coarseSide - 1);
        int z0 = Mth.clamp((int) Math.floor(fz), 0, coarseSide - 1);
        int x1 = Math.min(x0 + 1, coarseSide - 1);
        int z1 = Math.min(z0 + 1, coarseSide - 1);
        double tx = Mth.clamp(fx - x0, 0.0D, 1.0D);
        double tz = Mth.clamp(fz - z0, 0.0D, 1.0D);
        double a = Mth.lerp(tx, coarse[z0 * coarseSide + x0], coarse[z0 * coarseSide + x1]);
        double b = Mth.lerp(tx, coarse[z1 * coarseSide + x0], coarse[z1 * coarseSide + x1]);
        return Mth.lerp(tz, a, b);
    }

    private static float[] blur(float[] source, int side, int radius) {
        float[] horizontal = new float[source.length];
        for (int z = 0; z < side; z++) {
            for (int x = 0; x < side; x++) {
                float sum = 0.0F;
                int count = 0;
                for (int d = -radius; d <= radius; d++) {
                    int sx = Mth.clamp(x + d, 0, side - 1);
                    sum += source[z * side + sx];
                    count++;
                }
                horizontal[z * side + x] = sum / count;
            }
        }
        float[] result = new float[source.length];
        for (int z = 0; z < side; z++) {
            for (int x = 0; x < side; x++) {
                float sum = 0.0F;
                int count = 0;
                for (int d = -radius; d <= radius; d++) {
                    int sz = Mth.clamp(z + d, 0, side - 1);
                    sum += horizontal[sz * side + x];
                    count++;
                }
                result[z * side + x] = sum / count;
            }
        }
        return result;
    }

    private static final int ROLE_NONE = 1;
    private static final int ROLE_CITY = 2;
    private static final int ROLE_HIGHWAY = 3;

    private static boolean demandsShift(int packed) {
        int type = packed & 3;
        return type == ROLE_CITY || type == ROLE_HIGHWAY;
    }

    private static int roleLevel(int packed) {
        return packed >> 2;
    }

    private static int packRole(int type, int level) {
        return (level << 2) | type;
    }

    private static long roleValue(int packedRole, int naturalHeight) {
        return ((long) naturalHeight << 32) | (packedRole & 0xffffffffL);
    }

    private static int roleCode(long value) {
        return (int) value;
    }

    private static int naturalHeight(long value) {
        return (int) (value >> 32);
    }

    private static long noRoleValue() {
        return roleValue(ROLE_NONE, Integer.MIN_VALUE);
    }

    private static long packedRole(Context context, int chunkX, int chunkZ) {
        RoleCache cache = ROLES.computeIfAbsent(context.provider(), ignored -> new RoleCache());
        return cache.getOrCompute(context, chunkX, chunkZ);
    }

    private static long computePackedRole(Context context, int chunkX, int chunkZ) {
        LostCityProfile profile = context.profile();
        IDimensionInfo provider = context.provider();
        ChunkCoord coord = new ChunkCoord(context.dimension(), chunkX, chunkZ);
        try {
            // Lost Cities' void predicate is a real heightmap lookup only for
            // floating profiles. Normal terrain can skip it completely.
            if (profile.isFloating() && BuildingInfo.isVoidChunk(coord, provider)) {
                return noRoleValue();
            }

        /* Reuse the immutable role from the density lattice when it is ready.
         * The async field handles terrain only. Roads and rails stay on the
         * Lost Cities placement path to avoid route-cache lock cycles. */
            ChunkRoleProbe.Probe stable = ChunkRoleProbe.peekStableTerrainProbe(
                provider, context.dimension(), chunkX, chunkZ);
            boolean city;
            if (stable != null) {
                STABLE_ROLE_HITS.incrementAndGet();
                city = stable.isCity();
            } else {
                /* Use the raw Lost Cities predicate when the stable role cache
                 * has no entry. */
                STABLE_ROLE_FALLBACKS.incrementAndGet();
                city = rawCityPredicate(context, coord);
            }
            int naturalHeight = Integer.MIN_VALUE;
            if (city) {
                /* Keep cold height lookups on the regional builder. */
                naturalHeight = roleHeight(context, chunkX, chunkZ);
                boolean elevated = naturalHeight
                    >= profile.GROUNDLEVEL + MountainCityReservationPlanner.MIN_RISE;
                /* Do not start a reservation build from the role cache. Use a
                 * published reservation when one is available. */
                if (elevated
                    && MountainCityReservationPlanner.peekRemovesBuildingCell(
                        provider, coord, profile)) {
                    city = false;
                }
            }
            if (!city) {
                return noRoleValue();
            }

            // Sphere border checks are only needed for city cells.
            if ((profile.isSpace() || profile.isSpheres())
                && (CitySphere.onCitySphereBorder(coord, provider)
                || CitySphere.hasMonorailStation(coord, provider))) {
                return noRoleValue();
            }
            if (city) {
                /* Use the cached level when available. For a cold role, query
                 * the profile's floor band without rebuilding the full field. */
                int level = stable != null
                    ? stable.cityLevel()
                    : cityLevelForColdRole(context, coord, naturalHeight);
                return roleValue(packRole(ROLE_CITY, level), naturalHeight);
            }

            // Defensive fallback if the role predicate changes.
            return noRoleValue();

        } catch (Throwable ignored) {
            return noRoleValue();
        }
    }

    private static int cityLevelFromHeight(int height, LostCityProfile profile) {
        if (height < profile.CITY_LEVEL0_HEIGHT) return 0;
        if (height < profile.CITY_LEVEL1_HEIGHT) return 1;
        if (height < profile.CITY_LEVEL2_HEIGHT) return 2;
        if (height < profile.CITY_LEVEL3_HEIGHT) return 3;
        if (height < profile.CITY_LEVEL4_HEIGHT) return 4;
        if (height < profile.CITY_LEVEL5_HEIGHT) return 5;
        if (height < profile.CITY_LEVEL6_HEIGHT) return 6;
        if (height < profile.CITY_LEVEL7_HEIGHT) return 7;
        return 8;
    }

    /** Resolve a cold role's Lost Cities floor band. */
    private static int cityLevelForColdRole(Context context,
                                            ChunkCoord coord,
                                            int naturalHeight) {
        try {
            CITY_LEVEL_DIRECT_LOOKUPS.incrementAndGet();
            int level = PlannerHotPath.run(() ->
                BuildingInfo.getCityLevel(coord, context.provider()));
            return Math.max(0, Math.min(8, level));
        } catch (Throwable ignored) {
            CITY_LEVEL_HEIGHT_FALLBACKS.incrementAndGet();
            return cityLevelFromHeight(naturalHeight, context.profile());
        }
    }

    private static int roleHeight(Context context, int chunkX, int chunkZ) {
        /* City demand uses the exact natural height. This method runs only on
         * the regional builder and reuses the height sampler cache. */
        HEIGHT_SAMPLES.incrementAndGet();
        return context.terrain().chunkHeight(chunkX, chunkZ);
    }

    private static final class RoleCache {
        private final ConcurrentHashMap<Long, RoleTile> tiles = new ConcurrentHashMap<>();
        private final ConcurrentLinkedQueue<RoleTileToken> order = new ConcurrentLinkedQueue<>();

        long getOrCompute(Context context, int chunkX, int chunkZ) {
            int tileX = Math.floorDiv(chunkX, ROLE_TILE_SIDE);
            int tileZ = Math.floorDiv(chunkZ, ROLE_TILE_SIDE);
            long tileKey = PackedCoordinateKey.of(tileX, tileZ);
            RoleTile tile = tiles.get(tileKey);
            if (tile == null) {
                RoleTile created = new RoleTile();
                RoleTile previous = tiles.putIfAbsent(tileKey, created);
                tile = previous == null ? created : previous;
                if (previous == null) {
                    order.add(new RoleTileToken(tileKey, created));
                    trim();
                }
            }
            int index = Math.floorMod(chunkZ, ROLE_TILE_SIDE) * ROLE_TILE_SIDE
                + Math.floorMod(chunkX, ROLE_TILE_SIDE);
            long cached = tile.values.get(index);
            if (cached != 0L) {
                return normalizeCachedRole(context, chunkX, chunkZ, cached);
            }
            CompletableFuture<Long> created = new CompletableFuture<>();
            CompletableFuture<Long> flight = tile.flights.putIfAbsent(index, created);
            if (flight != null) {
                ROLE_FLIGHT_WAITS.incrementAndGet();
                try {
                    long ready = flight.getNow(0L);
                    if (ready != 0L) {
                        return normalizeCachedRole(context, chunkX, chunkZ, ready);
                    }
                } catch (CompletionException | CancellationException ignored) {
                }
                /* Another region owns this cell. Reuse its eventual result
                 * instead of entering Lost Cities a second time. */
                ROLE_DUPLICATE_SUPPRESSED.incrementAndGet();
                return noRoleValue();
            }
            try {
                long resolved = computePackedRole(context, chunkX, chunkZ);
                if (resolved == 0L) {
                    // Keep the safe fallback and remember it. Some LC branches can
                    // throw during startup, so do not repeat that exception per region.
                    resolved = noRoleValue();
                }
                /* Store the height with the role so overlapping regions can
                 * reuse the same sample. */
                if (tile.values.compareAndSet(index, 0L, resolved)) {
                    ROLE_CACHE_PUBLISHES.incrementAndGet();
                }
                created.complete(resolved);
                return resolved;
            } catch (RuntimeException | Error failure) {
                created.completeExceptionally(failure);
                throw failure;
            } finally {
                tile.flights.remove(index, created);
            }
        }

        int size() {
            return tiles.size();
        }

        private static long normalizeCachedRole(Context context,
                                                int chunkX,
                                                int chunkZ,
                                                long cached) {
            int natural = naturalHeight(cached);
            if (natural != Integer.MIN_VALUE || !demandsShift(roleCode(cached))) {
                return cached;
            }
            return roleValue(roleCode(cached), roleHeight(context, chunkX, chunkZ));
        }

        private void trim() {
            while (tiles.size() > MAX_ROLE_TILES) {
                RoleTileToken oldest = order.poll();
                if (oldest == null) {
                    return;
                }
                /* A stale queue token must not evict a newer incarnation of
                 * the same tile after a rapid window churn. */
                tiles.remove(oldest.key(), oldest.tile());
            }
        }
    }

    private record RoleTileToken(long key, RoleTile tile) {
    }

    /** Keep overlapping role queries single-flight per cell. */
    private static final class RoleTile {
        private final AtomicLongArray values =
            new AtomicLongArray(ROLE_TILE_SIDE * ROLE_TILE_SIDE);
        private final ConcurrentHashMap<Integer, CompletableFuture<Long>> flights =
            new ConcurrentHashMap<>();
    }

    public record ShiftSettings(boolean enabled,
                                double slopeFlat,
                                double slopeSteep,
                                int maxShift,
                                double reliefStrength,
                                int version) {
        private static final double EROSION_FLAT = 0.35D;

        private static final double EROSION_MOUNTAIN = -0.55D;

        private static final double DEFAULT_SLOPE_FLAT = clampProperty(
            "lc2h.terrain.shift.slopeFlat", 0.30D, 0.10D, 1.0D);
        private static final double DEFAULT_SLOPE_STEEP = clampProperty(
            "lc2h.terrain.shift.slopeSteep", 0.75D, 0.15D, 2.0D);

        private static final int DEFAULT_MAX_SHIFT = Math.max(16, Math.min(256,
            Integer.getInteger("lc2h.terrain.shift.maxShift", 96)));
        private static final double DEFAULT_RELIEF = clampProperty(
            "lc2h.terrain.shift.reliefStrength", 0.55D, 0.0D, 1.0D);
        private static final boolean DEFAULT_ENABLED = Boolean.parseBoolean(
            System.getProperty("lc2h.terrain.shift.enabled", "false"));

        private static volatile ShiftSettings CURRENT = new ShiftSettings(DEFAULT_ENABLED,
            DEFAULT_SLOPE_FLAT, DEFAULT_SLOPE_STEEP, DEFAULT_MAX_SHIFT, DEFAULT_RELIEF, 1);

        public static ShiftSettings current() {
            return CURRENT;
        }

        public static ShiftSettings reset() {
            return replace(DEFAULT_ENABLED, DEFAULT_SLOPE_FLAT, DEFAULT_SLOPE_STEEP,
                DEFAULT_MAX_SHIFT, DEFAULT_RELIEF);
        }

        public static ShiftSettings withEnabled(boolean enabled) {
            ShiftSettings now = CURRENT;
            return replace(enabled, now.slopeFlat, now.slopeSteep, now.maxShift, now.reliefStrength);
        }

        public static ShiftSettings withSlopes(double flat, double steep) {
            ShiftSettings now = CURRENT;
            double clampedFlat = Mth.clamp(flat, 0.10D, 1.0D);
            return replace(now.enabled, clampedFlat,
                Mth.clamp(Math.max(steep, clampedFlat), 0.15D, 2.0D),
                now.maxShift, now.reliefStrength);
        }

        public static ShiftSettings withMaxShift(int maxShift) {
            ShiftSettings now = CURRENT;
            return replace(now.enabled, now.slopeFlat, now.slopeSteep,
                Math.max(16, Math.min(256, maxShift)), now.reliefStrength);
        }

        public static ShiftSettings withReliefStrength(double strength) {
            ShiftSettings now = CURRENT;
            return replace(now.enabled, now.slopeFlat, now.slopeSteep, now.maxShift,
                Mth.clamp(strength, 0.0D, 1.0D));
        }

        private static ShiftSettings replace(boolean enabled, double slopeFlat, double slopeSteep,
                                             int maxShift, double reliefStrength) {
            ShiftSettings versioned = new ShiftSettings(enabled, slopeFlat, slopeSteep,
                maxShift, reliefStrength, CURRENT.version + 1);
            CURRENT = versioned;
            CityShiftField.clear();
            return versioned;
        }

        public double slopeForErosion(double erosion) {
            double t = Mth.clamp((erosion - EROSION_MOUNTAIN) / (EROSION_FLAT - EROSION_MOUNTAIN),
                0.0D, 1.0D);
            double eased = t * t * (3.0D - 2.0D * t);
            return Mth.lerp(eased, slopeSteep, slopeFlat);
        }

        public double latticeStepForErosion(double erosion) {
            return slopeForErosion(erosion) * 16.0D;
        }

        public double minLatticeStep() {
            return slopeFlat * 16.0D;
        }

        public int halo() {
            /* Cover the blend radius plus interpolation guard cells. */
            int influenceChunks = (int) Math.ceil(BLEND_WIDTH_BLOCKS / 16.0D);
            return Math.min(MAX_HALO, Math.max(4, influenceChunks + 2));
        }

        public int maxRunOutBlocks() {
            return (int) Math.round(maxShift / slopeFlat);
        }

        public String describe() {
            return "enabled=" + enabled
                + " slope=" + String.format(java.util.Locale.ROOT, "%.2f..%.2f", slopeFlat, slopeSteep)
                + " (" + String.format(java.util.Locale.ROOT, "%.0f..%.0f deg",
                    Math.toDegrees(Math.atan(slopeFlat)), Math.toDegrees(Math.atan(slopeSteep))) + ")"
                + " maxShift=" + maxShift
                + " relief=" + String.format(java.util.Locale.ROOT, "%.2f", reliefStrength)
                + " halo=" + halo() + " chunks"
                + " maxRunOut=" + maxRunOutBlocks() + " blocks"
                + " version=" + version;
        }

        private static double clampProperty(String key, double fallback, double min, double max) {
            try {
                return Mth.clamp(
                    Double.parseDouble(System.getProperty(key, Double.toString(fallback))), min, max);
            } catch (RuntimeException ignored) {
                return fallback;
            }
        }
    }

    private record Region(float[] shift,
                          float[] natural,
                          float[] reference,
                          float[] step,
                          float[] sourceDemand,
                          float[] sourceDistance,
                          float[] fadeDistance,
                          float[] preRecoveryShift,
                          float[] recoveryCandidate,
                          float[] propagatedShift,
                          boolean[] locked,
                          int[] role,
                          int[] sourceFloor,
                          int[] recoveryFloor,
                          int[] recoveryNatural,
                          byte[] recoveryFlags,
                          long coarseNanos,
                          long roleNanos,
                          long gradientNanos,
                          long finishNanos) {
    }

    private static final class RegionKey {

        private final IDimensionInfo provider;
        private final ResourceKey<Level> dimension;
        private final int regionX;
        private final int regionZ;
        private final int ground;
        private final int settingsVersion;
        private final int hash;

        private RegionKey(IDimensionInfo provider, ResourceKey<Level> dimension,
                          int regionX, int regionZ, int ground, int settingsVersion) {
            this.provider = provider;
            this.dimension = dimension;
            this.regionX = regionX;
            this.regionZ = regionZ;
            this.ground = ground;
            this.settingsVersion = settingsVersion;
            int result = 31 * System.identityHashCode(provider) + dimension.hashCode();
            result = 31 * result + regionX;
            result = 31 * result + regionZ;
            result = 31 * result + ground;
            result = 31 * result + settingsVersion;
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
                && ground == key.ground
                && settingsVersion == key.settingsVersion;
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }
}
