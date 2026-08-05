package org.admany.lc2h.worldgen.gpu;

import mcjty.lostcities.config.LostCityProfile;
import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.IDimensionInfo;
import mcjty.lostcities.worldgen.lost.cityassets.AssetRegistries;
import org.admany.lc2h.LC2H;
import org.admany.lc2h.worldgen.scope.WorldGenScope;
import org.admany.quantified.api.vulkan.QuantifiedVulkan;
import org.admany.quantified.api.vulkan.SpirvComputeProgram;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

/**
 * Exact, lifecycle-scoped city-center/radius facts for multichunk planning.
 *
 * <p>The Vulkan path only executes the numeric part of Lost Cities' ordinary
 * profile center scan. Unsupported profiles stay on the existing CPU path.
 * Results are consumed only after a one-time full CPU audit proves that the
 * shader reproduced java.util.Random's 48-bit LCG exactly.</p>
 */
public final class CityCenterGpuCache {
    private static final int INPUT_STRIDE = 8;
    private static final int LOCAL_SIZE = 256;
    private static final int MIN_GPU_PLANS = Math.max(1,
        Integer.getInteger("lc2h.gpu.cityCenters.minPlans", 8));
    private static final int DIRECT_GROUP_SIDE = Math.max(2,
        Integer.getInteger("lc2h.gpu.cityCenters.directGroupSide", 4));
    /**
     * Neighbouring multichunk plans overlap almost their entire city-center
     * search window.  Keep the pure numeric facts in fixed tiles so normal
     * CPU planning can reuse that overlap too; this is deliberately separate
     * from the optional Vulkan plan cache below.
     */
    private static final int CPU_TILE_SIDE = Math.max(16,
        Integer.getInteger("lc2h.cityCenters.cpuTileSide", 64));
    private static final long DIRECT_WAIT_MS = Math.max(0L,
        Long.getLong("lc2h.gpu.cityCenters.directWaitMs", 25L));
    /**
     * This is an exact, independently-audited numeric kernel.  It remains
     * opt-in until an authoritative producer can provide a sufficiently large
     * batch; direct Lost Cities startup groups are too small to amortize an
     * isolated Vulkan dispatch on real hardware.
     */
    private static final boolean ENABLED = Boolean.parseBoolean(
        System.getProperty("lc2h.gpu.cityCenters.enabled", "false"));
    private static final long RANDOM_MULTIPLIER = 0x5DEECE66DL;
    private static final long RANDOM_ADDEND = 0xBL;
    private static final long RANDOM_MASK = (1L << 48) - 1L;
    private static final long DOUBLE_SCALE = 1L << 53;

    private static final ConcurrentHashMap<Key, PreparedCenters> CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<TileKey, float[]> CPU_TILES = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<GroupKey, CompletableFuture<Void>> DIRECT_GROUPS = new ConcurrentHashMap<>();
    private static final AtomicBoolean GPU_DISABLED = new AtomicBoolean(false);
    private static final AtomicBoolean GPU_AUDITED = new AtomicBoolean(false);
    private static final AtomicBoolean FAILURE_LOGGED = new AtomicBoolean(false);
    private static final AtomicReference<String> LAST_FAILURE = new AtomicReference<>("");
    private static final LongAdder BATCHES = new LongAdder();
    private static final LongAdder GPU_BATCHES = new LongAdder();
    private static final LongAdder CPU_BATCHES = new LongAdder();
    private static final LongAdder CANDIDATES = new LongAdder();
    private static final LongAdder HITS = new LongAdder();
    private static final LongAdder MISSES = new LongAdder();
    private static final LongAdder AUDIT_FAILURES = new LongAdder();
    private static final LongAdder GPU_NS = new LongAdder();
    private static final LongAdder DIRECT_GROUP_BATCHES = new LongAdder();
    private static final LongAdder DIRECT_WAIT_TIMEOUTS = new LongAdder();
    private static final LongAdder CPU_TILE_HITS = new LongAdder();
    private static final LongAdder CPU_TILE_MISSES = new LongAdder();
    private static final LongAdder CPU_TILE_CANDIDATES = new LongAdder();
    private static final LongAdder CPU_TILED_ASSEMBLIES = new LongAdder();
    private static final LongAdder CPU_TILED_ASSEMBLY_NS = new LongAdder();

    private static volatile SpirvComputeProgram program;
    private static volatile QuantifiedVulkan.PreparedProgram preparedProgram;

    private CityCenterGpuCache() {
    }

    public record Request(Key key, int minX, int minZ, int side, int minRadius, int radiusRange, long chanceLimit) {
        int candidateCount() {
            return side * side;
        }
    }

    public record Key(String scope, String profile, int topLeftX, int topLeftZ, int areaSize) {
    }

    private record GroupKey(String scope, String profile, int areaSize, int groupX, int groupZ) {
    }

    private record TileKey(String scope, String profile, int tileSide, int tileX, int tileZ,
                           int minRadius, int radiusRange, long chanceLimit) {
    }

    public static final class PreparedCenters {
        private final int minX;
        private final int minZ;
        private final int side;
        private final float[] radii;

        private PreparedCenters(int minX, int minZ, int side, float[] radii) {
            this.minX = minX;
            this.minZ = minZ;
            this.side = side;
            this.radii = radii;
        }

        public float radiusAt(int chunkX, int chunkZ) {
            int x = chunkX - minX;
            int z = chunkZ - minZ;
            if (x < 0 || z < 0 || x >= side || z >= side) {
                return 0.0F;
            }
            return radii[x * side + z];
        }

        public int candidateCount() {
            return radii.length;
        }
    }

    public static Request request(IDimensionInfo provider, ChunkCoord multiCoord, int areaSize) {
        return buildRequest(provider, multiCoord, areaSize);
    }

    private static Request buildRequest(IDimensionInfo provider, ChunkCoord multiCoord, int areaSize) {
        if (provider == null || multiCoord == null || areaSize <= 0) {
            return null;
        }
        LostCityProfile profile = provider.getProfile();
        if (profile == null || profile.CITY_CHANCE < 0.0D || profile.isSpace() || profile.isSpheres()) {
            return null;
        }
        try {
            if (provider.getWorld() == null || AssetRegistries.PREDEFINED_CITIES.getNumAssets(provider.getWorld()) > 0) {
                return null;
            }
        } catch (Throwable ignored) {
            return null;
        }

        int radiusChunks = Math.max(0, (profile.CITY_MAXRADIUS + 15) / 16);
        if (radiusChunks <= 0) {
            return null;
        }
        int topLeftX = multiCoord.chunkX() * areaSize;
        int topLeftZ = multiCoord.chunkZ() * areaSize;
        int side = areaSize + radiusChunks * 2;
        int range = Math.max(1, profile.CITY_MAXRADIUS - profile.CITY_MINRADIUS);
        String scope = WorldGenScope.cache(provider).stableText();
        String profileSignature = WorldGenScope.profileSignature(profile);
        Key key = new Key(scope, profileSignature, topLeftX, topLeftZ, areaSize);
        return new Request(key, topLeftX - radiusChunks, topLeftZ - radiusChunks,
            side, profile.CITY_MINRADIUS, range, chanceLimit(profile.CITY_CHANCE));
    }

    public static PreparedCenters get(IDimensionInfo provider,
                                      LostCityProfile profile,
                                      ChunkCoord topLeft,
                                      int areaSize) {
        if (provider == null || profile == null || topLeft == null) {
            return null;
        }
        Key key = new Key(WorldGenScope.cache(provider).stableText(), WorldGenScope.profileSignature(profile),
            topLeft.chunkX(), topLeft.chunkZ(), areaSize);
        PreparedCenters prepared = CACHE.get(key);
        if (prepared == null) {
            MISSES.increment();
        } else {
            HITS.increment();
        }
        if (prepared != null) {
            return prepared;
        }

        // The exact CPU tiles are always available.  Vulkan is an optional
        // producer for CACHE, never a prerequisite for avoiding the repeated
        // 70k-candidate center scan in ordinary planning.
        Request request = buildRequest(provider,
            new ChunkCoord(topLeft.dimension(), Math.floorDiv(topLeft.chunkX(), areaSize), Math.floorDiv(topLeft.chunkZ(), areaSize)),
            areaSize);
        if (request == null) {
            return null;
        }
        PreparedCenters computed = assembleCpuTiles(request);
        PreparedCenters raced = CACHE.putIfAbsent(key, computed);
        return raced == null ? computed : raced;
    }

    public static CompletableFuture<Void> prepareBatch(List<Request> requests) {
        if (requests == null || requests.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        LinkedHashMap<Key, Request> missing = new LinkedHashMap<>();
        for (Request request : requests) {
            if (request != null && !CACHE.containsKey(request.key())) {
                missing.putIfAbsent(request.key(), request);
            }
        }
        if (missing.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        List<Request> active = List.copyOf(missing.values());
        TileBatch batch = TileBatch.forRequests(active);
        if (!ENABLED || active.size() < MIN_GPU_PLANS || batch.candidateCount == 0
            || GPU_DISABLED.get() || !QuantifiedVulkan.isGpuReady()) {
            installCpuTiles(batch);
            installFromTiles(active);
            CPU_BATCHES.increment();
            return CompletableFuture.completedFuture(null);
        }

        BATCHES.increment();
        CANDIDATES.add(batch.candidateCount);
        long gpuStart = System.nanoTime();
        return preparedProgram().submit(batch.taskKey, batch.dispatch(), Duration.ofSeconds(20), () -> {
                CPU_BATCHES.increment();
                return batch.computeCpu();
            })
            .handle((result, failure) -> {
                GPU_NS.add(System.nanoTime() - gpuStart);
                float[] resolved = result;
                if (failure != null || resolved == null || resolved.length != batch.candidateCount) {
                    recordFailure(failure != null
                        ? "dispatch: " + rootMessage(failure)
                        : "dispatch returned " + (resolved == null ? "null" : resolved.length)
                            + " values for " + batch.candidateCount + " candidates");
                    CPU_BATCHES.increment();
                    resolved = batch.computeCpu();
                }
                if (!GPU_DISABLED.get() && failure == null) {
                    resolved = auditGpuResult(batch, resolved);
                    GPU_BATCHES.increment();
                }
                batch.install(resolved);
                installFromTiles(active);
                return null;
            });
    }

    /**
     * Covers direct/native startup planning, where Lost Cities can invoke the
     * fast planner before the async multichunk queue has accumulated a batch.
     * A four-by-four aligned plan group is submitted once and shared by every
     * worldgen thread. Callers wait only for a small bounded interval; missing
     * results transparently use the exact scalar path while the GPU finishes.
     */
    public static void prepareDirectNeighborhood(IDimensionInfo provider,
                                                 ChunkCoord multiCoord,
                                                 int areaSize) {
        Request requested = request(provider, multiCoord, areaSize);
        if (!ENABLED || requested == null || CACHE.containsKey(requested.key()) || !QuantifiedVulkan.isGpuReady()) {
            return;
        }
        int groupX = Math.floorDiv(multiCoord.chunkX(), DIRECT_GROUP_SIDE) * DIRECT_GROUP_SIDE;
        int groupZ = Math.floorDiv(multiCoord.chunkZ(), DIRECT_GROUP_SIDE) * DIRECT_GROUP_SIDE;
        GroupKey groupKey = new GroupKey(requested.key().scope(), requested.key().profile(), areaSize, groupX, groupZ);
        CompletableFuture<Void> future = DIRECT_GROUPS.computeIfAbsent(groupKey, ignored -> {
            ArrayList<Request> group = new ArrayList<>(DIRECT_GROUP_SIDE * DIRECT_GROUP_SIDE);
            for (int x = 0; x < DIRECT_GROUP_SIDE; x++) {
                for (int z = 0; z < DIRECT_GROUP_SIDE; z++) {
                    Request candidate = request(provider,
                        new ChunkCoord(multiCoord.dimension(), groupX + x, groupZ + z), areaSize);
                    if (candidate != null) {
                        group.add(candidate);
                    }
                }
            }
            DIRECT_GROUP_BATCHES.increment();
            return prepareBatch(group);
        });
        future.whenComplete((unused, failure) -> DIRECT_GROUPS.remove(groupKey, future));
        if (DIRECT_WAIT_MS <= 0L || "Server thread".equals(Thread.currentThread().getName())) {
            return;
        }
        try {
            future.get(DIRECT_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeout) {
            DIRECT_WAIT_TIMEOUTS.increment();
        } catch (Exception ignored) {
            // The exact scalar path below remains authoritative.
        }
    }

    public static void clearAll() {
        CACHE.clear();
        CPU_TILES.clear();
        DIRECT_GROUPS.clear();
        GPU_DISABLED.set(false);
        GPU_AUDITED.set(false);
        FAILURE_LOGGED.set(false);
        LAST_FAILURE.set("");
        BATCHES.reset();
        GPU_BATCHES.reset();
        CPU_BATCHES.reset();
        CANDIDATES.reset();
        HITS.reset();
        MISSES.reset();
        AUDIT_FAILURES.reset();
        GPU_NS.reset();
        DIRECT_GROUP_BATCHES.reset();
        DIRECT_WAIT_TIMEOUTS.reset();
        CPU_TILE_HITS.reset();
        CPU_TILE_MISSES.reset();
        CPU_TILE_CANDIDATES.reset();
        CPU_TILED_ASSEMBLIES.reset();
        CPU_TILED_ASSEMBLY_NS.reset();
    }

    public static String diagnostics() {
        return "enabled=" + ENABLED
            + " gpuDisabled=" + GPU_DISABLED.get()
            + " gpuAudited=" + GPU_AUDITED.get()
            + " entries=" + CACHE.size()
            + " batches=" + BATCHES.sum()
            + " gpuBatches=" + GPU_BATCHES.sum()
            + " cpuBatches=" + CPU_BATCHES.sum()
            + " candidates=" + CANDIDATES.sum()
            + " hits=" + HITS.sum()
            + " misses=" + MISSES.sum()
            + " auditFailures=" + AUDIT_FAILURES.sum()
            + " lastFailure=" + compact(LAST_FAILURE.get())
            + " directGroups=" + DIRECT_GROUP_BATCHES.sum()
            + " directTimeouts=" + DIRECT_WAIT_TIMEOUTS.sum()
            + " cpuTiles=" + CPU_TILES.size()
            + " tileHit=" + CPU_TILE_HITS.sum()
            + " tileMiss=" + CPU_TILE_MISSES.sum()
            + " tileCandidates=" + CPU_TILE_CANDIDATES.sum()
            + " tiledPlans=" + CPU_TILED_ASSEMBLIES.sum()
            + " tiledMs=" + String.format(java.util.Locale.ROOT, "%.3f", CPU_TILED_ASSEMBLY_NS.sum() / 1_000_000.0D)
            + " gpuMs=" + String.format(java.util.Locale.ROOT, "%.3f", GPU_NS.sum() / 1_000_000.0D);
    }

    // Package-visible for the deterministic tile-boundary regression test.
    static PreparedCenters assembleCpuTiles(Request request) {
        long start = System.nanoTime();
        int tileSide = tileSideFor(request.side());
        float[] radii = new float[request.candidateCount()];
        int minTileX = Math.floorDiv(request.minX(), tileSide);
        int maxTileX = Math.floorDiv(request.minX() + request.side() - 1, tileSide);
        int minTileZ = Math.floorDiv(request.minZ(), tileSide);
        int maxTileZ = Math.floorDiv(request.minZ() + request.side() - 1, tileSide);
        for (int tileX = minTileX; tileX <= maxTileX; tileX++) {
            int tileMinX = tileX * tileSide;
            int fromX = Math.max(request.minX(), tileMinX);
            int toX = Math.min(request.minX() + request.side(), tileMinX + tileSide);
            for (int tileZ = minTileZ; tileZ <= maxTileZ; tileZ++) {
                int tileMinZ = tileZ * tileSide;
                int fromZ = Math.max(request.minZ(), tileMinZ);
                int toZ = Math.min(request.minZ() + request.side(), tileMinZ + tileSide);
                TileKey tileKey = new TileKey(request.key().scope(), request.key().profile(), tileSide, tileX, tileZ,
                    request.minRadius(), request.radiusRange(), request.chanceLimit());
                float[] tile = CPU_TILES.get(tileKey);
                if (tile == null) {
                    final boolean[] installed = {false};
                    tile = CPU_TILES.computeIfAbsent(tileKey, ignored -> {
                        installed[0] = true;
                        CPU_TILE_MISSES.increment();
                        return computeCpuTile(tileMinX, tileMinZ, tileSide, request);
                    });
                    if (!installed[0]) {
                        CPU_TILE_HITS.increment();
                    }
                } else {
                    CPU_TILE_HITS.increment();
                }
                for (int chunkX = fromX; chunkX < toX; chunkX++) {
                    int source = (chunkX - tileMinX) * tileSide + (fromZ - tileMinZ);
                    int target = (chunkX - request.minX()) * request.side() + (fromZ - request.minZ());
                    System.arraycopy(tile, source, radii, target, toZ - fromZ);
                }
            }
        }
        CPU_TILED_ASSEMBLIES.increment();
        CPU_TILED_ASSEMBLY_NS.add(System.nanoTime() - start);
        return new PreparedCenters(request.minX(), request.minZ(), request.side(), radii);
    }

    private static int tileSideFor(int requestSide) {
        int bounded = Math.max(16, Math.min(CPU_TILE_SIDE, requestSide));
        int side = 16;
        while (side < bounded && side < CPU_TILE_SIDE) {
            side <<= 1;
        }
        return Math.min(side, CPU_TILE_SIDE);
    }

    private static float[] computeCpuTile(int minX, int minZ, int tileSide, Request request) {
        float[] result = new float[tileSide * tileSide];
        int index = 0;
        for (int x = 0; x < tileSide; x++) {
            int chunkX = minX + x;
            for (int z = 0; z < tileSide; z++) {
                int chunkZ = minZ + z;
                long centerSeed = (long) chunkZ * 797003437L + (long) chunkX * 295075153L;
                if (firstDoubleNumerator(centerSeed) < request.chanceLimit()) {
                    long radiusSeed = (long) chunkZ * 100001653L + (long) chunkX * 295075153L;
                    result[index] = request.minRadius() + firstRandomInt(radiusSeed, request.radiusRange());
                }
                index++;
            }
        }
        CPU_TILE_CANDIDATES.add(result.length);
        return result;
    }

    private static void installCpuTiles(TileBatch batch) {
        for (TileWork tile : batch.tiles) {
            CPU_TILES.computeIfAbsent(tile.key, ignored -> {
                CPU_TILE_MISSES.increment();
                return tile.computeCpu();
            });
        }
    }

    private static void installFromTiles(List<Request> requests) {
        for (Request request : requests) {
            CACHE.putIfAbsent(request.key(), assembleCpuTiles(request));
        }
    }

    /**
     * Coalesces overlapping multichunk search windows into unique cache tiles.
     * The old GPU route dispatched one full search window per plan, so a 4x4
     * direct group recalculated the same city-center candidates many times.
     * A tile is both the CPU cache unit and the GPU ownership unit.
     */
    private static final class TileBatch {
        private final List<TileWork> tiles;
        private final float[] input;
        private final int candidateCount;
        private final long taskKey;

        private TileBatch(List<TileWork> tiles) {
            this.tiles = tiles;
            int count = 0;
            for (TileWork tile : tiles) {
                count += tile.candidateCount();
            }
            this.candidateCount = count;
            this.input = encodeTiles(tiles, count);
            this.taskKey = taskKey(tiles);
        }

        static TileBatch forRequests(List<Request> requests) {
            LinkedHashMap<TileKey, TileWork> tiles = new LinkedHashMap<>();
            for (Request request : requests) {
                int tileSide = tileSideFor(request.side());
                int minTileX = Math.floorDiv(request.minX(), tileSide);
                int maxTileX = Math.floorDiv(request.minX() + request.side() - 1, tileSide);
                int minTileZ = Math.floorDiv(request.minZ(), tileSide);
                int maxTileZ = Math.floorDiv(request.minZ() + request.side() - 1, tileSide);
                for (int tileX = minTileX; tileX <= maxTileX; tileX++) {
                    for (int tileZ = minTileZ; tileZ <= maxTileZ; tileZ++) {
                        final int resolvedTileX = tileX;
                        final int resolvedTileZ = tileZ;
                        TileKey key = new TileKey(request.key().scope(), request.key().profile(), tileSide, tileX, tileZ,
                            request.minRadius(), request.radiusRange(), request.chanceLimit());
                        tiles.computeIfAbsent(key, ignored -> new TileWork(key, resolvedTileX * tileSide, resolvedTileZ * tileSide,
                            tileSide, request.minRadius(), request.radiusRange(), request.chanceLimit()));
                    }
                }
            }
            return new TileBatch(List.copyOf(tiles.values()));
        }

        QuantifiedVulkan.Dispatch dispatch() {
            return new QuantifiedVulkan.Dispatch(new float[][] {input}, candidateCount,
                new int[] {candidateCount}, (candidateCount + LOCAL_SIZE - 1) / LOCAL_SIZE, 1, 1);
        }

        float[] computeCpu() {
            float[] output = new float[candidateCount];
            int offset = 0;
            for (TileWork tile : tiles) {
                float[] values = tile.computeCpu();
                System.arraycopy(values, 0, output, offset, values.length);
                offset += values.length;
            }
            return output;
        }

        void install(float[] flat) {
            int offset = 0;
            for (TileWork tile : tiles) {
                int count = tile.candidateCount();
                float[] values = Arrays.copyOfRange(flat, offset, offset + count);
                CPU_TILES.putIfAbsent(tile.key, values);
                offset += count;
            }
        }
    }

    private record TileWork(TileKey key, int minX, int minZ, int side,
                            int minRadius, int radiusRange, long chanceLimit) {
        int candidateCount() {
            return side * side;
        }

        float[] computeCpu() {
            Request request = new Request(new Key(key.scope(), key.profile(), minX, minZ, side), minX, minZ, side,
                minRadius, radiusRange, chanceLimit);
            return computeCpuTile(minX, minZ, side, request);
        }
    }

    private static float[] auditGpuResult(TileBatch batch, float[] gpu) {
        if (GPU_AUDITED.get()) {
            return gpu;
        }
        float[] cpu = batch.computeCpu();
        if (!Arrays.equals(cpu, gpu)) {
            GPU_DISABLED.set(true);
            AUDIT_FAILURES.increment();
            recordFailure("audit mismatch for " + batch.candidateCount + " candidates");
            LC2H.LOGGER.error("LC2H exact city-center Vulkan audit failed; disabling result consumption for this lifecycle");
            return cpu;
        }
        GPU_AUDITED.set(true);
        LC2H.LOGGER.info("LC2H exact city-center Vulkan audit passed for {} shared candidates", batch.candidateCount);
        return gpu;
    }

    private static SpirvComputeProgram program() {
        SpirvComputeProgram current = program;
        if (current != null) {
            return current;
        }
        synchronized (CityCenterGpuCache.class) {
            current = program;
            if (current == null) {
                current = SpirvComputeProgram.fromResource("lc2h:exact-city-center-facts-v1",
                    CityCenterGpuCache.class, "/lc2h/shaders/city_center_facts.comp.spv", 2, 4, LOCAL_SIZE);
                program = current;
            }
            return current;
        }
    }

    private static QuantifiedVulkan.PreparedProgram preparedProgram() {
        QuantifiedVulkan.PreparedProgram current = preparedProgram;
        if (current != null) {
            return current;
        }
        synchronized (CityCenterGpuCache.class) {
            current = preparedProgram;
            if (current == null) {
                current = QuantifiedVulkan.prepare(LC2H.MODID, "exact-city-center-facts", program());
                preparedProgram = current;
            }
            return current;
        }
    }

    private static float[] encodeTiles(List<TileWork> tiles, int count) {
        float[] input = new float[count * INPUT_STRIDE];
        int index = 0;
        for (TileWork tile : tiles) {
            for (int x = 0; x < tile.side(); x++) {
                int chunkX = tile.minX() + x;
                for (int z = 0; z < tile.side(); z++) {
                    int base = index++ * INPUT_STRIDE;
                    input[base] = chunkX;
                    input[base + 1] = tile.minZ() + z;
                    input[base + 2] = tile.minRadius();
                    input[base + 3] = tile.radiusRange();
                    input[base + 4] = tile.chanceLimit() & 0xffffL;
                    input[base + 5] = (tile.chanceLimit() >>> 16) & 0xffffL;
                    input[base + 6] = (tile.chanceLimit() >>> 32) & 0xffffL;
                    input[base + 7] = (tile.chanceLimit() >>> 48) & 0xffffL;
                }
            }
        }
        return input;
    }

    private static long chanceLimit(double chance) {
        if (!(chance > 0.0D)) {
            return 0L;
        }
        if (chance >= 1.0D) {
            return DOUBLE_SCALE;
        }
        return (long) Math.ceil(chance * 0x1.0p53);
    }

    private static long firstDoubleNumerator(long seed) {
        long state = nextSeed((seed ^ RANDOM_MULTIPLIER) & RANDOM_MASK);
        long high = state >>> 22;
        state = nextSeed(state);
        long low = state >>> 21;
        return (high << 27) + low;
    }

    private static int firstRandomInt(long seed, int bound) {
        if (bound <= 1) {
            return 0;
        }
        long state = nextSeed((seed ^ RANDOM_MULTIPLIER) & RANDOM_MASK);
        int bits = (int) (state >>> 17);
        if ((bound & -bound) == bound) {
            return (int) ((bound * (long) bits) >> 31);
        }
        int value = bits % bound;
        while (bits - value + (bound - 1) < 0) {
            state = nextSeed(state);
            bits = (int) (state >>> 17);
            value = bits % bound;
        }
        return value;
    }

    private static long nextSeed(long state) {
        return (state * RANDOM_MULTIPLIER + RANDOM_ADDEND) & RANDOM_MASK;
    }

    private static long taskKey(List<TileWork> tiles) {
        long hash = 0x9E3779B97F4A7C15L;
        for (TileWork tile : tiles) {
            hash = mix(hash, tile.key().hashCode());
            hash = mix(hash, tile.chanceLimit());
        }
        return hash & Long.MAX_VALUE;
    }

    private static long mix(long current, long value) {
        long mixed = current ^ (value + 0x9E3779B97F4A7C15L + (current << 6) + (current >>> 2));
        return mixed ^ (mixed >>> 33);
    }

    private static void recordFailure(String reason) {
        String value = reason == null || reason.isBlank() ? "unknown Vulkan failure" : reason;
        LAST_FAILURE.set(value);
        if (FAILURE_LOGGED.compareAndSet(false, true)) {
            LC2H.LOGGER.warn("LC2H exact city-center Vulkan path fell back to the exact CPU resolver: {}", value);
        }
    }

    private static String rootMessage(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return current.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
    }

    private static String compact(String value) {
        if (value == null || value.isBlank()) {
            return "none";
        }
        return value.replace(' ', '_').replace('\n', '_').replace('\r', '_');
    }
}
