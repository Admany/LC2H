package org.admany.lc2h.dev.benchmark;

import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.IDimensionInfo;
import org.admany.lc2h.worldgen.async.planner.AsyncMultiChunkPlanner;
import org.admany.lc2h.worldgen.lostcities.ChunkRoleProbe;

import java.util.ArrayList;
import java.util.List;

public final class LostCityKernelBenchmark {

    private static final boolean COLD_CACHE_BENCHMARK = Boolean.getBoolean("lc2h.kernelbench.coldCache");

    private LostCityKernelBenchmark() {
    }

    public record Result(
        int samples,
        long legacyTotalNs,
        long directKernelTotalNs,
        long dagKernelTotalNs,
        long batchDagKernelTotalNs,
        long legacyAvgNs,
        long directKernelAvgNs,
        long dagKernelAvgNs,
        long batchDagKernelAvgNs,
        double directSpeedup,
        double dagSpeedup,
        double batchDagSpeedup
    ) {
        public String summary() {
            return "samples=" + samples
                + " legacyAvgMs=" + formatMs(legacyAvgNs)
                + " directKernelAvgMs=" + formatMs(directKernelAvgNs)
                + " directSpeedup=" + String.format(java.util.Locale.ROOT, "%.2fx", directSpeedup)
                + " dagKernelAvgMs=" + formatMs(dagKernelAvgNs)
                + " dagSpeedup=" + String.format(java.util.Locale.ROOT, "%.2fx", dagSpeedup)
                + " batchDagAvgMs=" + formatMs(batchDagKernelAvgNs)
                + " batchDagSpeedup=" + String.format(java.util.Locale.ROOT, "%.2fx", batchDagSpeedup);
        }
    }

    public static Result run(IDimensionInfo provider, ChunkCoord center, int radius, int iterations) {
        if (provider == null || center == null) {
            throw new IllegalArgumentException("provider and center are required");
        }
        int safeRadius = Math.max(0, Math.min(8, radius));
        int safeIterations = Math.max(1, Math.min(256, iterations));
        List<ChunkCoord> coords = coords(center, safeRadius, safeIterations);

        // Warm classloading/JIT paths without including them in the score.
        ChunkCoord warm = coords.get(0);
        try {
            AsyncMultiChunkPlanner.computeLegacyForBenchmark(provider, warm);
        } catch (Throwable ignored) {
        }
        try {
            AsyncMultiChunkPlanner.computeDirectKernelForBenchmark(provider, warm);
        } catch (Throwable ignored) {
        }
        try {
            AsyncMultiChunkPlanner.computeKernelForBenchmark(provider, warm);
        } catch (Throwable ignored) {
        }

        long legacyTotal = 0L;
        long directKernelTotal = 0L;
        long dagKernelTotal = 0L;
        long batchDagKernelTotal = 0L;
        int completed = 0;

        for (int i = 0; i < coords.size(); i++) {
            ChunkCoord coord = coords.get(i);
            int order = i % 3;
            clearBenchmarkCachesIfRequested();
            if (order == 0) {
                legacyTotal += timeLegacy(provider, coord);
                clearBenchmarkCachesIfRequested();
                directKernelTotal += timeDirectKernel(provider, coord);
                clearBenchmarkCachesIfRequested();
                dagKernelTotal += timeDagKernel(provider, coord);
            } else if (order == 1) {
                directKernelTotal += timeDirectKernel(provider, coord);
                clearBenchmarkCachesIfRequested();
                dagKernelTotal += timeDagKernel(provider, coord);
                clearBenchmarkCachesIfRequested();
                legacyTotal += timeLegacy(provider, coord);
            } else {
                dagKernelTotal += timeDagKernel(provider, coord);
                clearBenchmarkCachesIfRequested();
                legacyTotal += timeLegacy(provider, coord);
                clearBenchmarkCachesIfRequested();
                directKernelTotal += timeDirectKernel(provider, coord);
            }
            completed++;
        }

        long legacyAvg = completed == 0 ? 0L : legacyTotal / completed;
        long directKernelAvg = completed == 0 ? 0L : directKernelTotal / completed;
        long dagKernelAvg = completed == 0 ? 0L : dagKernelTotal / completed;
        batchDagKernelTotal = timeBatchDagKernel(provider, coords);
        long batchDagKernelAvg = completed == 0 ? 0L : batchDagKernelTotal / completed;
        double directSpeedup = directKernelAvg <= 0L ? 0.0D : legacyAvg / (double) directKernelAvg;
        double dagSpeedup = dagKernelAvg <= 0L ? 0.0D : legacyAvg / (double) dagKernelAvg;
        double batchDagSpeedup = batchDagKernelAvg <= 0L ? 0.0D : legacyAvg / (double) batchDagKernelAvg;
        return new Result(completed, legacyTotal, directKernelTotal, dagKernelTotal, batchDagKernelTotal,
            legacyAvg, directKernelAvg, dagKernelAvg, batchDagKernelAvg,
            directSpeedup, dagSpeedup, batchDagSpeedup);
    }

    private static long timeLegacy(IDimensionInfo provider, ChunkCoord coord) {
        long start = System.nanoTime();
        AsyncMultiChunkPlanner.computeLegacyForBenchmark(provider, coord);
        return System.nanoTime() - start;
    }

    private static long timeDirectKernel(IDimensionInfo provider, ChunkCoord coord) {
        long start = System.nanoTime();
        AsyncMultiChunkPlanner.computeDirectKernelForBenchmark(provider, coord);
        return System.nanoTime() - start;
    }

    private static long timeDagKernel(IDimensionInfo provider, ChunkCoord coord) {
        long start = System.nanoTime();
        AsyncMultiChunkPlanner.computeKernelForBenchmark(provider, coord);
        return System.nanoTime() - start;
    }

    private static long timeBatchDagKernel(IDimensionInfo provider, List<ChunkCoord> coords) {
        clearBenchmarkCachesIfRequested();
        long start = System.nanoTime();
        AsyncMultiChunkPlanner.computeKernelBatchForBenchmark(provider, coords);
        return System.nanoTime() - start;
    }

    private static void clearBenchmarkCachesIfRequested() {
        if (COLD_CACHE_BENCHMARK) {
            ChunkRoleProbe.clear();
        }
    }

    private static List<ChunkCoord> coords(ChunkCoord center, int radius, int iterations) {
        ArrayList<ChunkCoord> coords = new ArrayList<>(iterations);
        int diameter = radius * 2 + 1;
        int span = Math.max(1, diameter * diameter);
        for (int i = 0; i < iterations; i++) {
            int local = i % span;
            int dx = radius == 0 ? 0 : (local % diameter) - radius;
            int dz = radius == 0 ? 0 : (local / diameter) - radius;
            int ringOffset = (i / span) * Math.max(1, diameter + 1);
            coords.add(new ChunkCoord(center.dimension(), center.chunkX() + dx + ringOffset, center.chunkZ() + dz + ringOffset));
        }
        return coords;
    }

    private static String formatMs(long ns) {
        return String.format(java.util.Locale.ROOT, "%.3f", ns / 1_000_000.0D);
    }
}
