package org.admany.lc2h.worldgen.noise;

import mcjty.lostcities.varia.ChunkCoord;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public final class CheapChunkNoiseField {

    private static final long TTL_MS = Math.max(30_000L,
        Long.getLong("lc2h.noise.fieldTtlMs", TimeUnit.MINUTES.toMillis(10)));
    private static final int PRUNE_SCAN = Math.max(128,
        Integer.getInteger("lc2h.noise.fieldPruneScan", 512));
    private static final int PRUNE_EVERY = Math.max(64,
        Integer.getInteger("lc2h.noise.fieldPruneEvery", 128));
    private static final ConcurrentHashMap<ChunkCoord, CachedField> CACHE = new ConcurrentHashMap<>();
    private static final java.util.concurrent.atomic.AtomicInteger PRUNE_COUNTER = new java.util.concurrent.atomic.AtomicInteger();

    private CheapChunkNoiseField() {
    }

    private record CachedField(double[] values, long timestampMs) {
    }

    public static double[] getOrCompute(ChunkCoord coord) {
        if (coord == null) {
            return new double[16 * 16];
        }
        long now = System.currentTimeMillis();
        CachedField cached = CACHE.get(coord);
        if (cached != null && (now - cached.timestampMs) <= TTL_MS) {
            return cached.values;
        }
        double[] computed = compute(coord);
        CACHE.put(coord, new CachedField(computed, now));
        maybePrune(now);
        return computed;
    }

    public static double sample(ChunkCoord coord, int x, int z) {
        double[] field = getOrCompute(coord);
        return field[(x << 4) | z];
    }

    private static double[] compute(ChunkCoord coord) {
        double[] out = new double[16 * 16];
        int baseX = coord.chunkX() << 4;
        int baseZ = coord.chunkZ() << 4;

        double[] lowSinX = new double[16];
        double[] highSinX = new double[16];
        double[] lowCosZ = new double[16];
        double[] highCosZ = new double[16];

        fillSinSeries(baseX, 0.01D, lowSinX);
        fillSinSeries(baseX, 0.05D, highSinX);
        fillCosSeries(baseZ, 0.01D, lowCosZ);
        fillCosSeries(baseZ, 0.05D, highCosZ);

        for (int x = 0; x < 16; x++) {
            double lowX = lowSinX[x];
            double highX = highSinX[x];
            int row = x << 4;
            for (int z = 0; z < 16; z++) {
                out[row | z] = (lowX * lowCosZ[z]) + ((highX * highCosZ[z]) * 0.5D);
            }
        }
        return out;
    }

    private static void fillSinSeries(int base, double step, double[] target) {
        double angle = base * step;
        double sin = Math.sin(angle);
        double cos = Math.cos(angle);
        double sinStep = Math.sin(step);
        double cosStep = Math.cos(step);
        for (int i = 0; i < target.length; i++) {
            target[i] = sin;
            double nextSin = (sin * cosStep) + (cos * sinStep);
            double nextCos = (cos * cosStep) - (sin * sinStep);
            sin = nextSin;
            cos = nextCos;
        }
    }

    private static void fillCosSeries(int base, double step, double[] target) {
        double angle = base * step;
        double sin = Math.sin(angle);
        double cos = Math.cos(angle);
        double sinStep = Math.sin(step);
        double cosStep = Math.cos(step);
        for (int i = 0; i < target.length; i++) {
            target[i] = cos;
            double nextSin = (sin * cosStep) + (cos * sinStep);
            double nextCos = (cos * cosStep) - (sin * sinStep);
            sin = nextSin;
            cos = nextCos;
        }
    }

    private static void maybePrune(long nowMs) {
        int count = PRUNE_COUNTER.incrementAndGet();
        if (count < PRUNE_EVERY) {
            return;
        }
        PRUNE_COUNTER.set(0);
        int scanned = 0;
        for (var entry : CACHE.entrySet()) {
            if (scanned++ >= PRUNE_SCAN) {
                break;
            }
            CachedField cached = entry.getValue();
            if (cached == null || (nowMs - cached.timestampMs) > TTL_MS) {
                CACHE.remove(entry.getKey(), cached);
            }
        }
    }
}
