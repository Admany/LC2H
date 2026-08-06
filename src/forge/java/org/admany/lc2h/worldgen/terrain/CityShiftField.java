package org.admany.lc2h.worldgen.terrain;

import mcjty.lostcities.config.LostCityProfile;
import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.IDimensionInfo;
import mcjty.lostcities.worldgen.LostCityTerrainFeature;
import mcjty.lostcities.worldgen.lost.BuildingInfo;
import mcjty.lostcities.worldgen.lost.City;
import mcjty.lostcities.worldgen.lost.CitySphere;
import mcjty.lostcities.worldgen.lost.Highway;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import org.admany.lc2h.worldgen.CityDensityShiftField;
import org.slf4j.Logger;
import org.admany.lc2h.worldgen.MountainCityReservationPlanner;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class CityShiftField {

    private static final int REGION_SIDE = 32;

    private static final int MAX_HALO = Math.max(12, Math.min(64,
        Integer.getInteger("lc2h.terrain.shift.maxHalo", 36)));

    private static final long SLOW_BUILD_WARN_NANOS = 2_000_000_000L;

    private static final double SQRT2 = Math.sqrt(2.0D);

    private static final int COARSE_STRIDE = 4;

    private static final int REFERENCE_BLUR_COARSE = 2;

    private static final int MAX_SWEEPS = 12;

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final Map<RegionKey, Region> CACHE = new ConcurrentHashMap<>();
    private static final Map<RegionKey, Object> LOCKS = new ConcurrentHashMap<>();
    private static final Map<IDimensionInfo, ConcurrentHashMap<Long, CellRole>> ROLES =
        new ConcurrentHashMap<>();

    private static final AtomicLong REGIONS_BUILT = new AtomicLong();
    private static final AtomicLong REGION_HITS = new AtomicLong();
    private static final AtomicLong DEMAND_CELLS = new AtomicLong();
    private static final AtomicLong HEIGHT_SAMPLES = new AtomicLong();
    private static final AtomicLong EROSION_SAMPLES = new AtomicLong();
    private static final AtomicLong BUILD_NANOS = new AtomicLong();
    private static final AtomicLong SWEEPS = new AtomicLong();
    private static final AtomicLong UNCONVERGED = new AtomicLong();

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
            return 0.0D;
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
        CACHE.clear();
        LOCKS.clear();
        ROLES.clear();
    }

    public static String diagnostics() {
        ShiftSettings settings = ShiftSettings.current();
        long built = REGIONS_BUILT.get();
        return settings.describe()
            + ", regionsBuilt=" + built
            + ", regionHits=" + REGION_HITS.get()
            + ", demandCells=" + DEMAND_CELLS.get()
            + ", heightSamples=" + HEIGHT_SAMPLES.get()
            + ", erosionSamples=" + EROSION_SAMPLES.get()
            + ", avgSweeps=" + (built == 0 ? "n/a"
                : String.format(java.util.Locale.ROOT, "%.1f", (double) SWEEPS.get() / built))
            + ", unconvergedRegions=" + UNCONVERGED.get()
            + ", avgBuildMs=" + (built == 0 ? "n/a"
                : String.format(java.util.Locale.ROOT, "%.1f", BUILD_NANOS.get() / 1.0E6D / built))
            + ", cachedRegions=" + CACHE.size();
    }

    private static Region region(Context context, int regionX, int regionZ) {
        RegionKey key = new RegionKey(context.provider(), context.dimension(), regionX, regionZ,
            context.profile().GROUNDLEVEL, context.settings().version());
        Region cached = CACHE.get(key);
        if (cached != null) {
            REGION_HITS.incrementAndGet();
            return cached;
        }

        Object lock = LOCKS.computeIfAbsent(key, ignored -> new Object());
        synchronized (lock) {
            cached = CACHE.get(key);
            if (cached != null) {
                return cached;
            }
            long started = System.nanoTime();
            Region built = buildRegion(context, regionX, regionZ);
            long elapsed = System.nanoTime() - started;
            BUILD_NANOS.addAndGet(elapsed);
            REGIONS_BUILT.incrementAndGet();
            if (elapsed > SLOW_BUILD_WARN_NANOS) {
                LOGGER.warn("[LC2H] Shift field region {},{} took {} ms to build ({}). "
                        + "Worldgen is blocked while this runs; lower lc2h.terrain.shift.maxShift "
                        + "or lc2h.terrain.shift.maxHalo if it repeats.",
                    regionX, regionZ, elapsed / 1_000_000L, context.settings().describe());
            }
            CACHE.put(key, built);
            return built;
        }
    }

    private static Region buildRegion(Context context, int regionX, int regionZ) {
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

        float[] value = new float[cells];
        float[] sourceDemand = new float[cells];
        boolean[] locked = new boolean[cells];
        Arrays.fill(value, Float.NEGATIVE_INFINITY);
        for (int gz = 0; gz < side; gz++) {
            for (int gx = 0; gx < side; gx++) {
                int chunkX = originX + gx;
                int chunkZ = originZ + gz;
                CellRole role = role(context, chunkX, chunkZ);
                if (!role.demandsShift()) {
                    continue;
                }
                int floor = context.profile().GROUNDLEVEL
                    + role.level() * LostCityTerrainFeature.FLOORHEIGHT;
                HEIGHT_SAMPLES.incrementAndGet();
                int natural = context.terrain().chunkHeight(chunkX, chunkZ);
                int demand = Math.max(0, Math.min(settings.maxShift(), natural - floor));
                int index = gz * side + gx;
                value[index] = demand;
                sourceDemand[index] = demand;

                locked[index] = true;
                DEMAND_CELLS.incrementAndGet();
            }
        }

        int sweeps = boundedGradientTransform(value, sourceDemand, locked, step, side);
        SWEEPS.addAndGet(sweeps);
        if (sweeps >= MAX_SWEEPS) {
            UNCONVERGED.incrementAndGet();
        }

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
        return new Region(owned);
    }

    static int boundedGradientTransform(float[] value,
                                        float[] sourceDemand,
                                        boolean[] locked,
                                        float[] step,
                                        int side) {
        final float epsilon = 1.0E-4F;
        for (int sweep = 1; sweep <= MAX_SWEEPS; sweep++) {
            boolean changed = false;
            boolean forward = (sweep & 1) == 1;
            int start = forward ? 0 : side - 1;
            int end = forward ? side : -1;
            int delta = forward ? 1 : -1;
            for (int z = start; z != end; z += delta) {
                for (int x = start; x != end; x += delta) {
                    int i = z * side + x;
                    if (locked[i]) {
                        continue;
                    }
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
                            float mean = 0.5F * (step[i] + step[n]);
                            float cost = (dx != 0 && dz != 0) ? (float) (mean * SQRT2) : mean;
                            float candidate = value[n] - cost;
                            if (candidate > best + epsilon) {
                                best = candidate;
                                bestDemand = sourceDemand[n];
                            } else if (candidate > best - epsilon && sourceDemand[n] > bestDemand) {
                                best = Math.max(best, candidate);
                                bestDemand = sourceDemand[n];
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

    private record CellRole(boolean city, boolean surfaceHighway, int level) {

        static final CellRole NONE = new CellRole(false, false, 0);

        boolean demandsShift() {
            return city || surfaceHighway;
        }
    }

    private static CellRole role(Context context, int chunkX, int chunkZ) {
        ConcurrentHashMap<Long, CellRole> cache =
            ROLES.computeIfAbsent(context.provider(), ignored -> new ConcurrentHashMap<>());
        long key = (((long) chunkX) << 32) ^ (chunkZ & 0xffffffffL);
        CellRole cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        CellRole resolved = computeRole(context, chunkX, chunkZ);
        if (resolved == null) {
            return CellRole.NONE;
        }
        CellRole previous = cache.putIfAbsent(key, resolved);
        return previous == null ? resolved : previous;
    }

    private static CellRole computeRole(Context context, int chunkX, int chunkZ) {
        LostCityProfile profile = context.profile();
        IDimensionInfo provider = context.provider();
        ChunkCoord coord = new ChunkCoord(context.dimension(), chunkX, chunkZ);
        try {
            if (BuildingInfo.isVoidChunk(coord, provider)) {
                return CellRole.NONE;
            }
            if ((profile.isSpace() || profile.isSpheres())
                && (CitySphere.onCitySphereBorder(coord, provider)
                || CitySphere.hasMonorailStation(coord, provider))) {
                return CellRole.NONE;
            }

            boolean city = City.getCityFactor(coord, provider, profile) > profile.CITY_THRESHOLD;
            if (city) {
                HEIGHT_SAMPLES.incrementAndGet();
                boolean elevated = context.terrain().chunkHeight(chunkX, chunkZ)
                    >= profile.GROUNDLEVEL + MountainCityReservationPlanner.MIN_RISE;
                if (elevated
                    && MountainCityReservationPlanner.removesBuildingCell(provider, coord, profile)) {
                    city = false;
                }
            }
            if (city) {
                return new CellRole(true, false, BuildingInfo.getCityLevel(coord, provider));
            }

            int highwayLevel = Math.max(
                Highway.getXHighwayLevel(coord, provider, profile),
                Highway.getZHighwayLevel(coord, provider, profile));
            if (highwayLevel < 0) {
                return CellRole.NONE;
            }

            int routeY = profile.GROUNDLEVEL
                + highwayLevel * LostCityTerrainFeature.FLOORHEIGHT + 3;
            HEIGHT_SAMPLES.incrementAndGet();
            boolean tunnel = context.terrain().chunkHeight(chunkX, chunkZ) > routeY;
            if (tunnel) {
                return CellRole.NONE;
            }
            return new CellRole(false, true, Math.max(0, highwayLevel));
        } catch (Throwable ignored) {
            return null;
        }
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
            System.getProperty("lc2h.terrain.shift.enabled", "true"));

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
            int reach = Math.min(MAX_HALO,
                (int) Math.ceil(maxShift / minLatticeStep())
                    + REFERENCE_BLUR_COARSE * COARSE_STRIDE + 1);

            return ((reach + COARSE_STRIDE - 1) / COARSE_STRIDE) * COARSE_STRIDE;
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

    private record Region(float[] shift) {
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
