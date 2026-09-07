package org.admany.lc2h.dev.benchmark;

import mcjty.lostcities.varia.ChunkCoord;
import org.admany.lc2h.worldgen.noise.CheapChunkNoiseField;
import org.admany.quantified.api.QuantifiedAPI;
import org.admany.quantified.api.graph.QuantifiedTaskGraph;
import org.admany.quantified.core.common.platform.QuantifiedCoreRuntime;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.concurrent.CompletableFuture;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class Lc2hRewriteBenchmark {

    private Lc2hRewriteBenchmark() {
    }

    public static void main(String[] args) {
        try {
            bootstrapQuantified();
            diagnoseQapiGraphDuplicateCoalescing();
            validateNoiseParity();
            validateCityRandomParity();
            benchmarkNoise();
            benchmarkCityCenterRandom();
            benchmarkCityCenterLookupPath();
            benchmarkPackedRegionTraversal();
            benchmarkTreeNeighborhoodScan();
        } finally {
            shutdownQuantified();
        }
    }

    private static void diagnoseQapiGraphDuplicateCoalescing() {
        try {
            int duplicateNulls = runGraphDiagnostic(false);
            int uniqueNulls = runGraphDiagnostic(true);
            int duplicateSubmitAllNulls = runGraphSubmitAllDiagnostic(false);
            int uniqueSubmitAllNulls = runGraphSubmitAllDiagnostic(true);
            System.out.println("qapiGraph.sameKeyDuplicate.nullResults=" + duplicateNulls);
            System.out.println("qapiGraph.uniqueRunKey.nullResults=" + uniqueNulls);
            System.out.println("qapiGraph.submitAllSameKeyDuplicate.nullResults=" + duplicateSubmitAllNulls);
            System.out.println("qapiGraph.submitAllUniqueRunKey.nullResults=" + uniqueSubmitAllNulls);
        } catch (Throwable t) {
            System.out.println("qapiGraph.diagnosticSkipped=" + t.getClass().getSimpleName() + ":" + t.getMessage());
        }
    }

    private static void bootstrapQuantified() {
        try {
            Path root = Path.of("build", "lc2h-qapi-diagnostic").toAbsolutePath();
            Path config = root.resolve("config");
            Files.createDirectories(config);
            QuantifiedCoreRuntime.bootstrap(
                LoggerFactory.getLogger("LC2H-QAPI-Diagnostic"),
                new QuantifiedCoreRuntime.PlatformPaths(root, config)
            );
            QuantifiedAPI.register("lc2h", "LC2H Benchmark", "diagnostic");
        } catch (Throwable t) {
            System.out.println("qapiGraph.bootstrapSkipped=" + t.getClass().getSimpleName() + ":" + t.getMessage());
        }
    }

    private static int runGraphDiagnostic(boolean uniqueRunKeys) {
        int runs = 16;
        List<CompletableFuture<String>> futures = new ArrayList<>(runs);
        for (int i = 0; i < runs; i++) {
            String key = uniqueRunKeys ? "rewrite-graph-diagnostic:" + i : "rewrite-graph-diagnostic";
            QuantifiedTaskGraph.Builder graph = QuantifiedAPI.graph("lc2h", "rewrite-graph-diagnostic")
                .key(key)
                .localityKey("rewrite-graph-diagnostic");
            QuantifiedTaskGraph.NodeHandle<String> plan = graph
                .node("plan", () -> "value")
                .foreground()
                .threadSafe(true);
            QuantifiedTaskGraph.NodeHandle<String> gather = graph
                .node("gather", ctx -> ctx.result(plan))
                .dependsOn(plan)
                .foreground()
                .threadSafe(true);
            futures.add(graph.submit(gather));
        }

        int nulls = 0;
        for (CompletableFuture<String> future : futures) {
            String result = future.join();
            if (result == null) {
                nulls++;
            }
        }
        return nulls;
    }

    private static int runGraphSubmitAllDiagnostic(boolean uniqueRunKeys) {
        int runs = 16;
        List<CompletableFuture<String>> futures = new ArrayList<>(runs);
        for (int i = 0; i < runs; i++) {
            String key = uniqueRunKeys ? "rewrite-graph-submitall-diagnostic:" + i : "rewrite-graph-submitall-diagnostic";
            QuantifiedTaskGraph.Builder graph = QuantifiedAPI.graph("lc2h", "rewrite-graph-submitall-diagnostic")
                .key(key)
                .localityKey("rewrite-graph-submitall-diagnostic");
            graph.node("plan", () -> "value")
                .foreground()
                .threadSafe(true);
            futures.add(graph.submitAll().thenApply(resultMap -> {
                Object result = resultMap == null ? null : resultMap.get("plan");
                return result instanceof String value ? value : null;
            }));
        }

        int nulls = 0;
        for (CompletableFuture<String> future : futures) {
            String result = future.join();
            if (result == null) {
                nulls++;
            }
        }
        return nulls;
    }

    private static void validateNoiseParity() {
        ChunkCoord coord = new ChunkCoord(null, 7, -11);
        double[] field = CheapChunkNoiseField.getOrCompute(coord);
        double maxError = 0.0D;
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                double expected = naiveNoise(coord, x, z);
                double actual = field[(x << 4) | z];
                maxError = Math.max(maxError, Math.abs(expected - actual));
            }
        }
        System.out.println("noiseParity.maxError=" + maxError);
        if (maxError > 1.0E-9D) {
            throw new IllegalStateException("CheapChunkNoiseField parity failed: " + maxError);
        }
    }

    private static void benchmarkNoise() {
        List<ChunkCoord> coords = new ArrayList<>();
        for (int x = -32; x < 32; x++) {
            for (int z = -32; z < 32; z++) {
                coords.add(new ChunkCoord(null, x, z));
            }
        }

        long naiveNs = measureBestOf(5, () -> {
            double sink = 0.0D;
            for (ChunkCoord coord : coords) {
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        sink += naiveNoise(coord, x, z);
                    }
                }
            }
            blackhole(sink);
        });

        long cheapNs = measureBestOf(5, () -> {
            double sink = 0.0D;
            for (ChunkCoord coord : coords) {
                double[] field = CheapChunkNoiseField.getOrCompute(coord);
                for (double sample : field) {
                    sink += sample;
                }
            }
            blackhole(sink);
        });

        System.out.println("noise.naive.ms=" + millis(naiveNs));
        System.out.println("noise.cheap.ms=" + millis(cheapNs));
        System.out.println("noise.speedup=" + String.format(java.util.Locale.ROOT, "%.2fx", (double) naiveNs / Math.max(1L, cheapNs)));
    }

    private static void benchmarkCityCenterRandom() {
        int samples = 256 * 256;
        int[] xs = new int[samples];
        int[] zs = new int[samples];
        int cursor = 0;
        for (int x = -128; x < 128; x++) {
            for (int z = -128; z < 128; z++) {
                xs[cursor] = x;
                zs[cursor] = z;
                cursor++;
            }
        }

        long randomAllocNs = measureBestOf(7, () -> {
            double sink = 0.0D;
            for (int i = 0; i < samples; i++) {
                long seed = ((long) zs[i] * 797003437L) + ((long) xs[i] * 295075153L);
                sink += new Random(seed).nextDouble();
            }
            blackhole(sink);
        });

        long directRandomNs = measureBestOf(7, () -> {
            double sink = 0.0D;
            for (int i = 0; i < samples; i++) {
                long seed = ((long) zs[i] * 797003437L) + ((long) xs[i] * 295075153L);
                sink += firstRandomDouble(seed);
            }
            blackhole(sink);
        });

        System.out.println("cityCenter.randomAlloc.ms=" + millis(randomAllocNs));
        System.out.println("cityCenter.directRandom.ms=" + millis(directRandomNs));
        System.out.println("cityCenter.randomSpeedup=" + String.format(java.util.Locale.ROOT, "%.2fx", (double) randomAllocNs / Math.max(1L, directRandomNs)));
    }

    private static void validateCityRandomParity() {
        int samples = 256 * 256;
        int doubleMismatches = 0;
        int intMismatches = 0;
        for (int x = -128; x < 128; x++) {
            for (int z = -128; z < 128; z++) {
                long centerSeed = ((long) z * 797003437L) + ((long) x * 295075153L);
                if (Double.doubleToLongBits(new Random(centerSeed).nextDouble())
                    != Double.doubleToLongBits(firstRandomDouble(centerSeed))) {
                    doubleMismatches++;
                }
                long radiusSeed = ((long) z * 100001653L) + ((long) x * 295075153L);
                int bound = 1 + Math.floorMod((x * 31) + z, 191);
                if (new Random(radiusSeed).nextInt(bound) != firstRandomInt(radiusSeed, bound)) {
                    intMismatches++;
                }
            }
        }
        System.out.println("cityRandomParity.samples=" + samples);
        System.out.println("cityRandomParity.doubleMismatches=" + doubleMismatches);
        System.out.println("cityRandomParity.intMismatches=" + intMismatches);
        if (doubleMismatches != 0 || intMismatches != 0) {
            throw new IllegalStateException("Direct city RNG is not java.util.Random compatible");
        }
    }

    private static void benchmarkCityCenterLookupPath() {
        int side = 13;
        int cells = side * side;
        int[] xs = new int[cells];
        int[] zs = new int[cells];
        ConcurrentHashMap<Long, Boolean> boxedCache = new ConcurrentHashMap<>();
        int cursor = 0;
        for (int x = -6; x <= 6; x++) {
            for (int z = -6; z <= 6; z++) {
                xs[cursor] = 50_000 + x;
                zs[cursor] = -50_000 + z;
                long seed = ((long) zs[cursor] * 797003437L) + ((long) xs[cursor] * 295075153L);
                boxedCache.put(mixedChunkKey(xs[cursor], zs[cursor]), firstRandomDouble(seed) < 0.2D);
                cursor++;
            }
        }

        int runs = 200_000;
        long boxedCacheNs = measureBestOf(7, () -> {
            int sink = 0;
            for (int run = 0; run < runs; run++) {
                for (int i = 0; i < cells; i++) {
                    if (Boolean.TRUE.equals(boxedCache.get(mixedChunkKey(xs[i], zs[i])))) {
                        sink++;
                    }
                }
            }
            blackhole(sink);
        });
        long directNs = measureBestOf(7, () -> {
            int sink = 0;
            for (int run = 0; run < runs; run++) {
                for (int i = 0; i < cells; i++) {
                    long seed = ((long) zs[i] * 797003437L) + ((long) xs[i] * 295075153L);
                    if (firstRandomDouble(seed) < 0.2D) {
                        sink++;
                    }
                }
            }
            blackhole(sink);
        });

        System.out.println("cityCenter.boxedCacheLookup.ms=" + millis(boxedCacheNs));
        System.out.println("cityCenter.directDecision.ms=" + millis(directNs));
        System.out.println("cityCenter.directDecisionSpeedup=" + String.format(
            java.util.Locale.ROOT, "%.2fx", (double) boxedCacheNs / Math.max(1L, directNs)));
    }

    private static void benchmarkPackedRegionTraversal() {
        int runs = 150_000;
        long boxedNs = measureBestOf(7, () -> {
            int sink = 0;
            for (int i = 0; i < runs; i++) {
                sink += boxedRegionTraversal(i & 31, (i >>> 5) & 31);
            }
            blackhole(sink);
        });

        long packedNs = measureBestOf(7, () -> {
            int sink = 0;
            for (int i = 0; i < runs; i++) {
                sink += packedRegionTraversal(i & 31, (i >>> 5) & 31);
            }
            blackhole(sink);
        });

        System.out.println("cityRegion.boxedTraversal.ms=" + millis(boxedNs));
        System.out.println("cityRegion.packedTraversal.ms=" + millis(packedNs));
        System.out.println("cityRegion.traversalSpeedup=" + String.format(java.util.Locale.ROOT, "%.2fx", (double) boxedNs / Math.max(1L, packedNs)));
    }

    private static void benchmarkTreeNeighborhoodScan() {
        List<long[]> entries = new ArrayList<>(8192);
        Map<Long, List<long[]>> buckets = new HashMap<>();
        for (int chunkX = -64; chunkX < 64; chunkX++) {
            for (int chunkZ = -64; chunkZ < 64; chunkZ++) {
                long[] entry = new long[] {chunkX, chunkZ};
                entries.add(entry);
                long key = pack(chunkX, chunkZ);
                buckets.computeIfAbsent(key, ignored -> new ArrayList<>()).add(entry);
            }
        }
        int loadedChunkX = 0;
        int loadedChunkZ = 0;

        long linearNs = measureBestOf(20, () -> {
            int ready = 0;
            for (long[] entry : entries) {
                int cx = (int) entry[0];
                int cz = (int) entry[1];
                if (Math.abs(cx - loadedChunkX) <= 1 && Math.abs(cz - loadedChunkZ) <= 1) {
                    ready++;
                }
            }
            blackhole(ready);
        });

        long bucketedNs = measureBestOf(20, () -> {
            int ready = 0;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    List<long[]> bucket = buckets.get(pack(loadedChunkX + dx, loadedChunkZ + dz));
                    if (bucket != null) {
                        ready += bucket.size();
                    }
                }
            }
            blackhole(ready);
        });

        System.out.println("tree.linearNeighborhoodScan.us=" + micros(linearNs));
        System.out.println("tree.bucketNeighborhoodScan.us=" + micros(bucketedNs));
        System.out.println("tree.speedup=" + String.format(java.util.Locale.ROOT, "%.2fx", (double) linearNs / Math.max(1L, bucketedNs)));
    }

    private static double naiveNoise(ChunkCoord coord, int x, int z) {
        int blockX = (coord.chunkX() << 4) + x;
        int blockZ = (coord.chunkZ() << 4) + z;
        double baseNoise = Math.sin(blockX * 0.01D) * Math.cos(blockZ * 0.01D);
        double detailNoise = Math.sin(blockX * 0.05D) * Math.cos(blockZ * 0.05D) * 0.5D;
        return baseNoise + detailNoise;
    }

    private static long measureBestOf(int rounds, Runnable action) {
        long best = Long.MAX_VALUE;
        for (int i = 0; i < rounds; i++) {
            long start = System.nanoTime();
            action.run();
            long elapsed = System.nanoTime() - start;
            if (elapsed < best) {
                best = elapsed;
            }
        }
        return best;
    }

    private static long pack(int chunkX, int chunkZ) {
        return (((long) chunkX) << 32) ^ (chunkZ & 0xffffffffL);
    }

    private static long mixedChunkKey(int chunkX, int chunkZ) {
        long key = pack(chunkX, chunkZ);
        key = (key ^ (key >>> 30)) * 0xbf58476d1ce4e5b9L;
        key = (key ^ (key >>> 27)) * 0x94d049bb133111ebL;
        return key ^ (key >>> 31);
    }

    private static int boxedRegionTraversal(int startX, int startZ) {
        Set<Long> visited = new HashSet<>();
        ArrayDeque<Long> queue = new ArrayDeque<>();
        ArrayDeque<Integer> depths = new ArrayDeque<>();
        queue.add(pack(startX, startZ));
        depths.add(0);
        int total = 0;
        while (!queue.isEmpty()) {
            long multi = queue.removeFirst();
            int depth = depths.removeFirst();
            if (!visited.add(multi)) {
                continue;
            }
            total += (int) (multi ^ (multi >>> 32));
            if (visited.size() >= 8 || depth >= 1) {
                continue;
            }
            int x = (int) (multi >> 32);
            int z = (int) multi;
            long west = pack(x - 1, z);
            long east = pack(x + 1, z);
            long north = pack(x, z - 1);
            long south = pack(x, z + 1);
            if (!visited.contains(west)) {
                queue.addLast(west);
                depths.addLast(depth + 1);
            }
            if (!visited.contains(east)) {
                queue.addLast(east);
                depths.addLast(depth + 1);
            }
            if (!visited.contains(north)) {
                queue.addLast(north);
                depths.addLast(depth + 1);
            }
            if (!visited.contains(south)) {
                queue.addLast(south);
                depths.addLast(depth + 1);
            }
        }
        return total;
    }

    private static int packedRegionTraversal(int startX, int startZ) {
        long[] visited = new long[8];
        long[] queue = new long[36];
        int[] depths = new int[36];
        int visitedCount = 0;
        int head = 0;
        int tail = 0;
        queue[tail] = pack(startX, startZ);
        depths[tail] = 0;
        tail++;
        int total = 0;
        while (head < tail) {
            long multi = queue[head];
            int depth = depths[head];
            head++;
            if (contains(visited, visitedCount, multi)) {
                continue;
            }
            visited[visitedCount++] = multi;
            total += (int) (multi ^ (multi >>> 32));
            if (visitedCount >= visited.length || depth >= 1) {
                continue;
            }
            int x = (int) (multi >> 32);
            int z = (int) multi;
            tail = offer(queue, depths, head, tail, pack(x - 1, z), depth + 1, visited, visitedCount);
            tail = offer(queue, depths, head, tail, pack(x + 1, z), depth + 1, visited, visitedCount);
            tail = offer(queue, depths, head, tail, pack(x, z - 1), depth + 1, visited, visitedCount);
            tail = offer(queue, depths, head, tail, pack(x, z + 1), depth + 1, visited, visitedCount);
        }
        return total;
    }

    private static int offer(long[] queue, int[] depths, int head, int tail, long value, int depth, long[] visited, int visitedCount) {
        if (tail >= queue.length || contains(visited, visitedCount, value) || contains(queue, head, tail, value)) {
            return tail;
        }
        queue[tail] = value;
        depths[tail] = depth;
        return tail + 1;
    }

    private static boolean contains(long[] values, int size, long value) {
        for (int i = 0; i < size; i++) {
            if (values[i] == value) {
                return true;
            }
        }
        return false;
    }

    private static boolean contains(long[] values, int startInclusive, int endExclusive, long value) {
        for (int i = startInclusive; i < endExclusive; i++) {
            if (values[i] == value) {
                return true;
            }
        }
        return false;
    }

    private static final long RANDOM_MULTIPLIER = 0x5DEECE66DL;
    private static final long RANDOM_ADDEND = 0xBL;
    private static final long RANDOM_MASK = (1L << 48) - 1L;

    private static double firstRandomDouble(long seed) {
        long state = (seed ^ RANDOM_MULTIPLIER) & RANDOM_MASK;
        state = (state * RANDOM_MULTIPLIER + RANDOM_ADDEND) & RANDOM_MASK;
        long high = state >>> (48 - 26);
        state = (state * RANDOM_MULTIPLIER + RANDOM_ADDEND) & RANDOM_MASK;
        long low = state >>> (48 - 27);
        return ((high << 27) + low) * 0x1.0p-53;
    }

    private static int firstRandomInt(long seed, int bound) {
        if (bound <= 1) {
            return 0;
        }
        long state = (seed ^ RANDOM_MULTIPLIER) & RANDOM_MASK;
        state = (state * RANDOM_MULTIPLIER + RANDOM_ADDEND) & RANDOM_MASK;
        int bits = (int) (state >>> (48 - 31));
        if ((bound & -bound) == bound) {
            return (int) ((bound * (long) bits) >> 31);
        }
        int value = bits % bound;
        while (bits - value + (bound - 1) < 0) {
            state = (state * RANDOM_MULTIPLIER + RANDOM_ADDEND) & RANDOM_MASK;
            bits = (int) (state >>> (48 - 31));
            value = bits % bound;
        }
        return value;
    }

    private static String millis(long nanos) {
        return String.format(java.util.Locale.ROOT, "%.3f", nanos / 1_000_000.0D);
    }

    private static String micros(long nanos) {
        return String.format(java.util.Locale.ROOT, "%.3f", nanos / 1_000.0D);
    }

    private static volatile Object BLACKHOLE;

    private static void blackhole(Object value) {
        BLACKHOLE = value;
    }

    private static void shutdownQuantified() {
        try {
            QuantifiedCoreRuntime.shutdownRuntime();
        } catch (Throwable ignored) {
        }
    }
}
