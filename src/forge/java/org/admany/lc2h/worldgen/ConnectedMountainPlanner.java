package org.admany.lc2h.worldgen;

import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.ChunkHeightmap;
import mcjty.lostcities.worldgen.IDimensionInfo;
import mcjty.lostcities.worldgen.LostCityTerrainFeature;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;

import java.util.ArrayDeque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Produces one flat, Lost-Cities-compatible floor for a city chunk without
 * blindly erasing the centre of a real mountain.
 *
 * <p>The old density hand-off treated every raw city chunk as an isolated
 * column: a high chunk was always pulled all the way down to city ground.
 * That fixes exposed mountain walls, but a contiguous mountain spanning many
 * chunks becomes one enormous flat cut. This planner first maps the connected
 * elevated landform around the city chunk. Small hills and the outer skirt of
 * a mountain are still graded into the city. Only a sufficiently deep,
 * sufficiently large mountain core retains part of its original elevation.
 * The retained height is quantised to Lost Cities floor increments, so the
 * density pass and the later building-floor pass agree on one usable level.</p>
 */
public final class ConnectedMountainPlanner {

    private static final int SCAN_RADIUS = Math.max(3, Math.min(8,
        Integer.getInteger("lc2h.terrain.connectedMountain.radiusChunks", 6)));
    private static final int MIN_RISE = Math.max(8, Math.min(48,
        Integer.getInteger("lc2h.terrain.connectedMountain.minRise", 8)));
    private static final int MIN_COMPONENT = Math.max(4, Math.min(64,
        Integer.getInteger("lc2h.terrain.connectedMountain.minChunks", 4)));
    private static final int PEAK_TRIM = Math.max(0, Math.min(12,
        Integer.getInteger("lc2h.terrain.connectedMountain.peakTrim", 4)));
    private static final int CORE_DEPTH = Math.max(2, Math.min(SCAN_RADIUS,
        Integer.getInteger("lc2h.terrain.connectedMountain.coreDepthChunks", 4)));
    private static final double MAX_PRESERVE = clampProperty(
        "lc2h.terrain.connectedMountain.maxPreserve", 0.72D, 0.25D, 0.90D);
    private static final int MAX_CACHE = Math.max(1024,
        Integer.getInteger("lc2h.terrain.connectedMountain.cacheMax", 16384));

    private static final ConcurrentHashMap<PlanKey, MountainPlan> CACHE = new ConcurrentHashMap<>();
    private static final AtomicLong REQUESTS = new AtomicLong();
    private static final AtomicLong HITS = new AtomicLong();
    private static final AtomicLong MAPPED = new AtomicLong();
    private static final AtomicLong PRESERVED = new AtomicLong();
    private static final AtomicLong SMALL_OR_EDGE = new AtomicLong();

    private ConnectedMountainPlanner() {
    }

    public enum Decision {
        INVALID_CONTEXT,
        BELOW_MOUNTAIN_THRESHOLD,
        COMPONENT_TOO_SMALL,
        COMPONENT_TOO_SHALLOW,
        COMPONENT_EDGE,
        QUANTIZED_TO_CITY_FLOOR,
        PRESERVED_MOUNTAIN_CORE
    }

    /**
     * The one authoritative terrain/floor decision consumed by both the
     * vanilla density hook and Lost Cities' later structure-floor query.
     */
    public record MountainPlan(
        int cityGround,
        int coarseHeight,
        int maximumSampleHeight,
        int naturalHeight,
        int targetHeight,
        int componentSize,
        int peakHeight,
        int edgeDistanceChunks,
        double coreFactor,
        double unquantizedRetainedRise,
        int floorStep,
        Decision decision
    ) {
        public int removedBlocks() {
            return Math.max(0, naturalHeight - targetHeight);
        }

        public int retainedBlocks() {
            return Math.max(0, targetHeight - cityGround);
        }

        public boolean preservesMountainCore() {
            return decision == Decision.PRESERVED_MOUNTAIN_CORE;
        }

        public String reason() {
            return switch (decision) {
                case INVALID_CONTEXT -> "provider or dimension was unavailable";
                case BELOW_MOUNTAIN_THRESHOLD -> "natural terrain was below the mountain threshold";
                case COMPONENT_TOO_SMALL -> "elevated terrain did not form a large enough connected mountain";
                case COMPONENT_TOO_SHALLOW -> "connected terrain had too little vertical relief to preserve";
                case COMPONENT_EDGE -> "this chunk is on the mountain skirt, so it transitions into the city";
                case QUANTIZED_TO_CITY_FLOOR -> "retained relief was smaller than one Lost Cities floor step";
                case PRESERVED_MOUNTAIN_CORE -> "this chunk is deep enough inside a connected mountain core";
            };
        }

        public String concise() {
            return "decision=" + decision
                + " coarse=" + coarseHeight
                + " sampledMax=" + maximumSampleHeight
                + " natural=" + naturalHeight
                + " city=" + cityGround
                + " target=" + targetHeight
                + " removed=" + removedBlocks()
                + " retained=" + retainedBlocks()
                + " component=" + componentSize
                + " peak=" + peakHeight
                + " edgeDepth=" + edgeDistanceChunks
                + " core=" + String.format(java.util.Locale.ROOT, "%.3f", coreFactor)
                + " nativeDensityShift=" + removedBlocks();
        }
    }

    public static MountainPlan plan(IDimensionInfo provider,
                                    ResourceKey<Level> dimension,
                                    int chunkX,
                                    int chunkZ,
                                    int cityGround) {
        REQUESTS.incrementAndGet();
        if (provider == null || dimension == null) {
            return flatPlan(cityGround, new HeightSample(cityGround, cityGround, cityGround),
                0, cityGround, 0, Decision.INVALID_CONTEXT);
        }
        PlanKey key = new PlanKey(provider, dimension, chunkX, chunkZ, cityGround);
        MountainPlan cached = CACHE.get(key);
        if (cached != null) {
            HITS.incrementAndGet();
            return cached;
        }
        MountainPlan planned = mapConnectedMountain(provider, dimension, chunkX, chunkZ, cityGround);
        MountainPlan previous = CACHE.putIfAbsent(key, planned);
        if (CACHE.size() > MAX_CACHE) {
            // Lifecycle cleanup is authoritative. This is only an emergency
            // bound for unusually long-running pregeneration sessions.
            CACHE.clear();
        }
        return previous != null ? previous : planned;
    }

    public static int plannedCityHeight(IDimensionInfo provider,
                                        ResourceKey<Level> dimension,
                                        int chunkX,
                                        int chunkZ,
                                        int cityGround) {
        return plan(provider, dimension, chunkX, chunkZ, cityGround).targetHeight();
    }

    private static MountainPlan mapConnectedMountain(IDimensionInfo provider,
                                                     ResourceKey<Level> dimension,
                                                     int centerX,
                                                     int centerZ,
                                                     int cityGround) {
        MAPPED.incrementAndGet();
        int diameter = SCAN_RADIUS * 2 + 1;
        int cells = diameter * diameter;
        int[] heights = new int[cells];
        boolean[] elevated = new boolean[cells];
        HeightSample centerSample = new HeightSample(cityGround, cityGround, cityGround);
        for (int localZ = 0; localZ < diameter; localZ++) {
            for (int localX = 0; localX < diameter; localX++) {
                int chunkX = centerX + localX - SCAN_RADIUS;
                int chunkZ = centerZ + localZ - SCAN_RADIUS;
                HeightSample sample = sampleHeight(provider, dimension, chunkX, chunkZ, cityGround);
                int height = sample.representativeHeight();
                int index = localZ * diameter + localX;
                heights[index] = height;
                elevated[index] = height >= cityGround + MIN_RISE;
                if (localX == SCAN_RADIUS && localZ == SCAN_RADIUS) {
                    centerSample = sample;
                }
            }
        }

        int center = SCAN_RADIUS * diameter + SCAN_RADIUS;
        int naturalHeight = heights[center];
        if (!elevated[center]) {
            SMALL_OR_EDGE.incrementAndGet();
            return flatPlan(cityGround, centerSample, 0, naturalHeight, 0,
                Decision.BELOW_MOUNTAIN_THRESHOLD);
        }

        boolean[] connected = new boolean[cells];
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        connected[center] = true;
        queue.add(center);
        int componentSize = 0;
        int peak = naturalHeight;
        while (!queue.isEmpty()) {
            int index = queue.removeFirst();
            componentSize++;
            peak = Math.max(peak, heights[index]);
            int x = index % diameter;
            int z = index / diameter;
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    if ((dx == 0 && dz == 0) || (dx != 0 && dz != 0)) {
                        continue;
                    }
                    int nx = x + dx;
                    int nz = z + dz;
                    if (nx < 0 || nz < 0 || nx >= diameter || nz >= diameter) {
                        continue;
                    }
                    int next = nz * diameter + nx;
                    if (elevated[next] && !connected[next]) {
                        connected[next] = true;
                        queue.addLast(next);
                    }
                }
            }
        }
        if (componentSize < MIN_COMPONENT) {
            SMALL_OR_EDGE.incrementAndGet();
            return flatPlan(cityGround, centerSample, componentSize, peak, 0,
                Decision.COMPONENT_TOO_SMALL);
        }
        if (peak < cityGround + MIN_RISE) {
            SMALL_OR_EDGE.incrementAndGet();
            return flatPlan(cityGround, centerSample, componentSize, peak, 0,
                Decision.COMPONENT_TOO_SHALLOW);
        }

        // Distance from the queried chunk to the edge of its connected
        // elevated component. A city touching the skirt is graded normally;
        // only a real interior core retains substantial relief.
        int edgeDistance = SCAN_RADIUS + 1;
        for (int index = 0; index < cells; index++) {
            if (!connected[index]) {
                continue;
            }
            int x = index % diameter;
            int z = index / diameter;
            boolean boundary = x == 0 || z == 0 || x == diameter - 1 || z == diameter - 1;
            if (!boundary) {
                boundary = !connected[index - 1] || !connected[index + 1]
                    || !connected[index - diameter] || !connected[index + diameter];
            }
            if (boundary) {
                int dx = x - SCAN_RADIUS;
                int dz = z - SCAN_RADIUS;
                edgeDistance = Math.min(edgeDistance, Math.max(Math.abs(dx), Math.abs(dz)));
            }
        }
        if (edgeDistance <= 0) {
            SMALL_OR_EDGE.incrementAndGet();
            return flatPlan(cityGround, centerSample, componentSize, peak, edgeDistance,
                Decision.COMPONENT_EDGE);
        }

        double core = Mth.clamp(edgeDistance / (double) CORE_DEPTH, 0.0D, 1.0D);
        core = core * core * core * (core * (core * 6.0D - 15.0D) + 10.0D);
        double retainedRise = (naturalHeight - cityGround) * MAX_PRESERVE * core;
        int floorStep = Math.max(1, LostCityTerrainFeature.FLOORHEIGHT);
        int steps = (int) Math.round(retainedRise / floorStep);
        int target = Math.min(naturalHeight, cityGround + steps * floorStep);
        if (target <= cityGround) {
            SMALL_OR_EDGE.incrementAndGet();
            return new MountainPlan(cityGround, centerSample.coarseHeight(),
                centerSample.maximumSampleHeight(), naturalHeight, cityGround, componentSize, peak,
                edgeDistance, core, retainedRise, floorStep, Decision.QUANTIZED_TO_CITY_FLOOR);
        }
        PRESERVED.incrementAndGet();
        return new MountainPlan(cityGround, centerSample.coarseHeight(),
            centerSample.maximumSampleHeight(), naturalHeight, target, componentSize, peak,
            edgeDistance, core, retainedRise, floorStep, Decision.PRESERVED_MOUNTAIN_CORE);
    }

    private static MountainPlan flatPlan(int cityGround,
                                         HeightSample sample,
                                         int componentSize,
                                         int peak,
                                         int edgeDistance,
                                         Decision decision) {
        return new MountainPlan(cityGround, sample.coarseHeight(), sample.maximumSampleHeight(),
            sample.representativeHeight(), cityGround, componentSize, peak,
            edgeDistance, 0.0D, 0.0D, Math.max(1, LostCityTerrainFeature.FLOORHEIGHT), decision);
    }

    /**
     * Lost Cities' single {@code getHeight()} value can under-report a steep
     * chunk by tens of blocks. Its accurate heightmap also exposes the maximum
     * of the generator's four interior samples. Preserve that signal, while
     * trimming a few blocks so one sharp corner cannot lift an entire city
     * floor to the absolute summit.
     */
    private static HeightSample sampleHeight(IDimensionInfo provider,
                                             ResourceKey<Level> dimension,
                                             int chunkX,
                                             int chunkZ,
                                             int fallback) {
        try {
            ChunkHeightmap heightmap = provider.getHeightmap(new ChunkCoord(dimension, chunkX, chunkZ));
            int coarse = heightmap.getHeight();
            int sampledMax = heightmap.getMaxHeight();
            if (sampledMax <= 0 || sampledMax < coarse - 128 || sampledMax > coarse + 256) {
                sampledMax = coarse;
            }
            int representative = Math.max(coarse, sampledMax - PEAK_TRIM);
            return new HeightSample(coarse, sampledMax, representative);
        } catch (Throwable ignored) {
            return new HeightSample(fallback, fallback, fallback);
        }
    }

    public static void clear() {
        CACHE.clear();
    }

    public static String diagnostics() {
        return "radius=" + SCAN_RADIUS
            + ", minRise=" + MIN_RISE
            + ", minChunks=" + MIN_COMPONENT
            + ", peakTrim=" + PEAK_TRIM
            + ", coreDepth=" + CORE_DEPTH
            + ", maxPreserve=" + MAX_PRESERVE
            + ", requests=" + REQUESTS.get()
            + ", hits=" + HITS.get()
            + ", mapped=" + MAPPED.get()
            + ", preserved=" + PRESERVED.get()
            + ", flattenedSmallOrEdge=" + SMALL_OR_EDGE.get()
            + ", cache=" + CACHE.size();
    }

    private static double clampProperty(String key, double fallback, double min, double max) {
        try {
            return Mth.clamp(Double.parseDouble(System.getProperty(key, Double.toString(fallback))), min, max);
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static final class PlanKey {
        private final IDimensionInfo provider;
        private final ResourceKey<Level> dimension;
        private final int chunkX;
        private final int chunkZ;
        private final int cityGround;
        private final int hash;

        private PlanKey(IDimensionInfo provider, ResourceKey<Level> dimension,
                        int chunkX, int chunkZ, int cityGround) {
            this.provider = provider;
            this.dimension = dimension;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.cityGround = cityGround;
            int result = 31 * System.identityHashCode(provider) + dimension.hashCode();
            result = 31 * result + chunkX;
            result = 31 * result + chunkZ;
            result = 31 * result + cityGround;
            this.hash = result;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof PlanKey key)) {
                return false;
            }
            return provider == key.provider
                && dimension.equals(key.dimension)
                && chunkX == key.chunkX
                && chunkZ == key.chunkZ
                && cityGround == key.cityGround;
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }

    private record HeightSample(int coarseHeight, int maximumSampleHeight, int representativeHeight) {
    }
}
