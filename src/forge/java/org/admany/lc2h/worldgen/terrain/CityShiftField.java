package org.admany.lc2h.worldgen.terrain;

import com.mojang.logging.LogUtils;
import mcjty.lostcities.config.HighwayGenerationMode;
import mcjty.lostcities.config.LostCityProfile;
import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.IDimensionInfo;
import mcjty.lostcities.worldgen.LostCityTerrainFeature;
import mcjty.lostcities.worldgen.lost.BuildingInfo;
import mcjty.lostcities.worldgen.lost.City;
import mcjty.lostcities.worldgen.lost.CitySphere;
import mcjty.lostcities.worldgen.lost.Highway;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.admany.lc2h.worldgen.lostcities.ChunkRoleProbe;
import org.admany.lc2h.worldgen.lostcities.PlannerHotPath;
import org.admany.lc2h.worldgen.lostcities.LostCitiesGenerationLocks;
import org.admany.lc2h.util.PackedCoordinateKey;

import java.util.Arrays;
import java.util.Map;
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

    /* A sixteen chunk lattice keeps the expensive vanilla height pass bounded
     * while still giving the gradient transform enough samples to make a
     * continuous city edge. Per role heights are interpolated from this
     * lattice instead of reopening the density graph for every city cell.
     * Eight remains available for compatibility/perf investigations through
     * the property, but the larger default prevents a cold region from
     * monopolising the CPU during startup. */
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

    /* A cold field is built away from the worldgen workers. The caller still
     * waits for the immutable result before sampling it: returning a partial
     * scalar here was the source of the old one-chunk city/mountain cutoffs.
     * Keeping this pool small prevents the exact build from becoming a second
     * worldgen stampede. */
    private static final int ASYNC_BUILD_THREADS = Math.max(1, Math.min(2,
        Integer.getInteger("lc2h.terrain.shift.asyncThreads", 1)));
    private static final int ASYNC_BUILD_QUEUE = Math.max(1, Math.min(16,
        Integer.getInteger("lc2h.terrain.shift.asyncQueue", 4)));
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
    private static final AtomicInteger MAX_OWNED_SHIFT = new AtomicInteger();
    private static final AtomicLong HEIGHT_SAMPLES = new AtomicLong();
    private static final AtomicLong EXACT_HEIGHT_SAMPLES = new AtomicLong();
    private static final AtomicLong EXACT_HEIGHT_FAILURES = new AtomicLong();
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
    private static final AtomicLong ASYNC_SUBMITTED = new AtomicLong();
    private static final AtomicLong ASYNC_COMPLETED = new AtomicLong();
    private static final AtomicLong ASYNC_REJECTED = new AtomicLong();
    private static final AtomicLong ASYNC_FAILED = new AtomicLong();
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
            + ", maxOwnedShift=" + MAX_OWNED_SHIFT.get()
            + ", heightSamples=" + HEIGHT_SAMPLES.get()
            + ", exactHeightSamples=" + EXACT_HEIGHT_SAMPLES.get()
            + ", exactHeightFailures=" + EXACT_HEIGHT_FAILURES.get()
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
            /* Never run the expensive height/role pass on a worldgen worker.
             * Queue pressure is expected during a burst; let the cheap,
             * deterministic scalar fallback cover this sample and retry the
             * immutable region after the bounded builder drains. */
            ASYNC_RETRY_AFTER.put(key, System.nanoTime() + ASYNC_RETRY_DELAY_NANOS);
            REGION_FLIGHTS.remove(key, created);
            created.completeExceptionally(rejected);
        }
        /* Never wait here. The worldgen worker immediately uses the bounded
         * scalar fallback; later chunks reuse the completed immutable region.
         * This keeps the concurrent path from parking behind Lost Cities'
         * generation lock while preserving a single published field. */
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
        /* A pending region must never make a worldgen worker sample or join
         * vanilla noise. Use a resident height if one exists and let the
         * asynchronous region publish the authoritative value later. */
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
        for (int gz = 0; gz < side; gz++) {
            for (int gx = 0; gx < side; gx++) {
                step[gz * side + gx] = (float) bilinear(coarseStep, coarseSide, gx, gz);
            }
        }
        long coarseNanos = System.nanoTime() - phaseStarted;

        phaseStarted = System.nanoTime();
        HeightEstimate previousEstimate = HEIGHT_ESTIMATE.get();
        HEIGHT_ESTIMATE.set(new HeightEstimate(context, originX, originZ,
            coarseNatural, coarseSide));
        float[] value = new float[cells];
        float[] sourceDemand = new float[cells];
        float[] sourceDistance = new float[cells];
        boolean[] locked = new boolean[cells];
        Arrays.fill(value, Float.NEGATIVE_INFINITY);
        Arrays.fill(sourceDistance, Float.POSITIVE_INFINITY);
        try {
            for (int gz = 0; gz < side; gz++) {
                for (int gx = 0; gx < side; gx++) {
                    if ((gx % ROLE_YIELD_INTERVAL) == 0) {
                        yieldToWorldgen();
                    }
                    int chunkX = originX + gx;
                    int chunkZ = originZ + gz;
                    long roleValue = packedRole(context, chunkX, chunkZ);
                    int role = roleCode(roleValue);
                    if (!demandsShift(role)) {
                        continue;
                    }
                    int floor = context.profile().GROUNDLEVEL
                        + roleLevel(role) * LostCityTerrainFeature.FLOORHEIGHT;
                    int natural = naturalHeight(roleValue);
                    if (natural == Integer.MIN_VALUE) {
                        // A role can come from an older cache before the sampler
                        // is ready. Keep the exact safe fallback for that window.
                        HEIGHT_SAMPLES.incrementAndGet();
                        natural = context.terrain().chunkHeight(chunkX, chunkZ);
                    }
                    int demand = Math.max(0, Math.min(settings.maxShift(), natural - floor));
                    int index = gz * side + gx;
                    value[index] = demand;
                    sourceDemand[index] = demand;
                    sourceDistance[index] = 0.0F;
                    locked[index] = true;
                    DEMAND_CELLS.incrementAndGet();
                    if (demand > 0) {
                        POSITIVE_DEMAND_CELLS.incrementAndGet();
                        MAX_DEMAND.accumulateAndGet(demand, Math::max);
                    }
                }
            }
        } finally {
            if (previousEstimate == null) {
                HEIGHT_ESTIMATE.remove();
            } else {
                HEIGHT_ESTIMATE.set(previousEstimate);
            }
        }
        long roleNanos = System.nanoTime() - phaseStarted;

        phaseStarted = System.nanoTime();
        int sweeps = boundedGradientTransform(value, sourceDemand, sourceDistance, locked, step, side,
            BLEND_WIDTH_BLOCKS);
        SWEEPS.addAndGet(sweeps);
        if (sweeps >= MAX_SWEEPS) {
            UNCONVERGED.incrementAndGet();
        }
        long gradientNanos = System.nanoTime() - phaseStarted;

        phaseStarted = System.nanoTime();
        float[] owned = new float[REGION_SIDE * REGION_SIDE];
        double strength = settings.reliefStrength();
        for (int localZ = 0; localZ < REGION_SIDE; localZ++) {
            for (int localX = 0; localX < REGION_SIDE; localX++) {
                int gx = localX + halo;
                int gz = localZ + halo;
                int index = gz * side + gx;
                double shift = value[index];
                if (shift <= 0.0D) {
                    owned[localZ * REGION_SIDE + localX] = 0.0F;
                    continue;
                }
                double distance = sourceDistance[index];
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
                    double natural = bilinear(coarseNatural, coarseSide, gx, gz);
                    double smooth = bilinear(reference, coarseSide, gx, gz);

                    shift += alpha * Math.max(0.0D, natural - smooth);
                }
                owned[localZ * REGION_SIDE + localX] = (float) shift;
            }
        }
        long finishNanos = System.nanoTime() - phaseStarted;
        return new Region(owned, coarseNanos, roleNanos, gradientNanos, finishNanos);
    }

    private static void yieldToWorldgen() {
        /* The region future is now joined by the caller. Waiting here for a
         * Lost Cities feature lock would deadlock when that feature asks the
         * late floor bridge for the same region. The role/BuildingInfo caches
         * are concurrent; give an active worker a scheduling hint without an
         * unbounded quiet-period delay on every lattice sample. */
        if (LostCitiesGenerationLocks.activeHolders() > 0) {
            Thread.yield();
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
        final float epsilon = 1.0E-4F;
        final float maxDistance = Math.max(8.0F, blendWidthBlocks);
        for (int sweep = 1; sweep <= MAX_SWEEPS; sweep++) {
            boolean changed = false;
            boolean forward = (sweep & 1) == 1;
            int start = forward ? 0 : side - 1;
            int end = forward ? side : -1;
            int delta = forward ? 1 : -1;
            for (int z = start; z != end; z += delta) {
                for (int x = start; x != end; x += delta) {
                    int i = z * side + x;
                    /* Source demands are lower bounds, not hard walls.  The
                     * previous pass skipped every source cell here, so two
                     * neighbouring city cells could retain unrelated
                     * height-derived demands (for example 30 next to 0) even
                     * though the transition solver had a slope budget.  Let
                     * the same bounded envelope raise the lower source while
                     * never lowering its own demand.  The locked bit remains
                     * useful to keep relief correction off the authoritative
                     * source cells below. */
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
                            float candidate = value[n] - cost;
                            if (candidate > best + epsilon) {
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
                return sweep;
            }
        }
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
         * That avoids another City.getCityFactor and highway noise lookup per cell.
         * If it is not ready, use the exact old calculation. */
            ChunkRoleProbe.Probe stable = ChunkRoleProbe.peekStableTerrainProbe(
                provider, context.dimension(), chunkX, chunkZ);
            boolean city;
            int cachedHighwayLevel = -1;
            if (stable != null) {
                STABLE_ROLE_HITS.incrementAndGet();
                city = stable.isCity();
                cachedHighwayLevel = stable.highwayLevel();
            } else {
                /* The stable probe also resolves highway tunnels and can
                 * reopen Lost Cities' full heightmap path. The shift builder
                 * already owns a coarse natural lattice and only needs the
                 * city predicate here. Reuse the cached/memoized City factor
                 * directly and let the normal highway lookup handle routes;
                 * this removes a second expensive probe stack per cell. */
                STABLE_ROLE_FALLBACKS.incrementAndGet();
                city = PlannerHotPath.run(() ->
                    City.getCityFactor(coord, provider, profile) > profile.CITY_THRESHOLD);
            }
            int naturalHeight = Integer.MIN_VALUE;
            if (city) {
                /* Keep the role lattice nonblocking. A cold exact heightmap
                 * lookup recursively re-enters Lost Cities generation and can
                 * leave the async builder stuck for tens of seconds. */
                naturalHeight = roleHeight(context, chunkX, chunkZ);
                boolean elevated = naturalHeight
                    >= profile.GROUNDLEVEL + MountainCityReservationPlanner.MIN_RISE;
                /* The reservation planner is intentionally not entered from
                 * the density role cache. Its cold build samples a full
                 * Lost Cities heightmap envelope and doing that while a role
                 * flight is held makes every neighbouring worldgen worker
                 * queue behind the same monitor. Structure placement still
                 * asks the authoritative planner directly. During terrain
                 * planning we only consume a plan that is already published.
                 */
                if (elevated
                    && MountainCityReservationPlanner.peekRemovesBuildingCell(
                        provider, coord, profile)) {
                    city = false;
                }
            }
            int highwayLevel = -1;
            if (!city) {
                highwayLevel = stable != null
                    ? cachedHighwayLevel
                    : highwayLevel(provider, profile, coord, chunkX, chunkZ);
            }
            if (!city && highwayLevel < 0) {
                return noRoleValue();
            }

        // Sphere border checks are expensive. Only run them for cells that could
        // become a city or surface highway.
            if ((profile.isSpace() || profile.isSpheres())
                && (CitySphere.onCitySphereBorder(coord, provider)
                || CitySphere.hasMonorailStation(coord, provider))) {
                return noRoleValue();
            }
            if (city) {
                /* This is an advisory terrain lattice, not the owner of the
                 * final Lost Cities floor. Calling BuildingInfo.getCityLevel
                 * here rebuilds a full density heightmap on both shift
                 * workers. Use the stable published level when possible and
                 * otherwise derive the same height band from this region's
                 * already sampled natural height. The real chunk path still
                 * computes and caches the authoritative level. */
                int level = stable != null
                    ? stable.cityLevel()
                    : cityLevelFromHeight(naturalHeight, profile);
                return roleValue(packRole(ROLE_CITY, level), naturalHeight);
            }

            int routeY = profile.GROUNDLEVEL
                + highwayLevel * LostCityTerrainFeature.FLOORHEIGHT + 3;
            naturalHeight = roleHeight(context, chunkX, chunkZ);
            boolean tunnel = naturalHeight > routeY;
            if (tunnel) {
                return noRoleValue();
            }
            return roleValue(packRole(ROLE_HIGHWAY, Math.max(0, highwayLevel)), naturalHeight);
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

    private static int highwayLevel(IDimensionInfo provider,
                                    LostCityProfile profile,
                                    ChunkCoord coord,
                                    int chunkX,
                                    int chunkZ) {
        // The legacy highway path rejects non-corridor cells before its noise lookup.
        // Keep the intercity path nonblocking in density evaluation. Its planner
        // is serialized and may recursively sample Lost Cities' full heightmap.
        if (provider.getHighwayGenerationMode() != HighwayGenerationMode.LEGACY) {
            if (provider.getHighwayGenerationMode() == HighwayGenerationMode.INTERCITY_NETWORK_V1) {
                return IntercityHighwayIndex.peekLevel(provider, profile, coord, null);
            }
            return Math.max(
                Highway.getXHighwayLevel(coord, provider, profile),
                Highway.getZHighwayLevel(coord, provider, profile));
        }
        int mask = profile.HIGHWAY_DISTANCE_MASK;
        if (mask <= 0) {
            return -1;
        }
        int xLevel = (chunkZ & mask) == 0
            ? Highway.getXHighwayLevel(coord, provider, profile) : -1;
        int zLevel = (chunkX & mask) == 0
            ? Highway.getZHighwayLevel(coord, provider, profile) : -1;
        return Math.max(xLevel, zLevel);
    }

    private static final ThreadLocal<HeightEstimate> HEIGHT_ESTIMATE = new ThreadLocal<>();

    private static int roleHeight(Context context, int chunkX, int chunkZ) {
        HeightEstimate estimate = HEIGHT_ESTIMATE.get();
        if (estimate != null && estimate.matches(context, chunkX, chunkZ)) {
            ROLE_HEIGHT_ESTIMATES.incrementAndGet();
            return estimate.height(chunkX, chunkZ);
        }
        /* A role lookup can race another bounded shift builder.  Do not join
         * that sampler from the worldgen path.  The exact owner publishes the
         * height into the cache and the next region reuses it; this lookup gets
         * a conservative floor estimate for the current immutable plan. */
        HEIGHT_SAMPLES.incrementAndGet();
        return context.terrain().chunkHeightNonBlocking(
            chunkX, chunkZ, context.profile().GROUNDLEVEL);
    }

    private record HeightEstimate(Context context,
                                  int originX,
                                  int originZ,
                                  float[] coarseNatural,
                                  int coarseSide) {
        boolean matches(Context candidate, int chunkX, int chunkZ) {
            return context == candidate
                && chunkX >= originX
                && chunkZ >= originZ
                && chunkX < originX + (coarseSide - 2) * COARSE_STRIDE + COARSE_STRIDE
                && chunkZ < originZ + (coarseSide - 2) * COARSE_STRIDE + COARSE_STRIDE;
        }

        int height(int chunkX, int chunkZ) {
            return (int) Math.round(bilinear(coarseNatural, coarseSide,
                chunkX - originX, chunkZ - originZ));
        }
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
                /* Another region owns this cell. Never re-enter Lost Cities'
                 * density graph here. A second computation is exactly what
                 * caused overlapping halo regions to multiply the cold role
                 * cost. The owner publishes the immutable role and later
                 * regions reuse it. */
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
                /* Keep the role decision even when its height came from the
                 * current region lattice. The role is stable; only the
                 * height estimate is local to this build. Publishing the role
                 * with a sentinel height lets overlapping regions reuse the
                 * expensive Lost Cities city/highway decision and interpolate
                 * their own height without stale terrain data. */
                long published = HEIGHT_ESTIMATE.get() == null
                    ? resolved
                    : roleValue(roleCode(resolved), Integer.MIN_VALUE);
                if (tile.values.compareAndSet(index, 0L, published)) {
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

    /** Per-cell flights keep overlapping role queries single flight without
     * serialising unrelated cells in the same 32 by 32 tile. */
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
            int reach = (int) Math.ceil(maxShift / minLatticeStep())
                + REFERENCE_BLUR_COARSE * COARSE_STRIDE + 1;
            int aligned = ((reach + COARSE_STRIDE - 1) / COARSE_STRIDE) * COARSE_STRIDE;
            return Math.min(MAX_HALO, aligned);
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
