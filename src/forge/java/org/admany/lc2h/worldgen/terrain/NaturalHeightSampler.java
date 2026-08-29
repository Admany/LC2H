package org.admany.lc2h.worldgen.terrain;

import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import org.admany.lc2h.util.PackedCoordinateKey;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

public final class NaturalHeightSampler {

    private static final Heightmap.Types TYPE = Heightmap.Types.OCEAN_FLOOR_WG;

    private static final int MAX_CACHED_CHUNKS = Math.max(4096,
        Integer.getInteger("lc2h.terrain.naturalHeight.cacheMax", 1 << 17));

    private static final Map<ServerLevel, LevelSampler> SAMPLERS = new ConcurrentHashMap<>();
    private static final AtomicLong SAMPLES = new AtomicLong();
    private static final AtomicLong HITS = new AtomicLong();
    private static final AtomicLong FAILURES = new AtomicLong();
    private static final AtomicLong EVICTIONS = new AtomicLong();
    private static final AtomicLong EROSION_FAILURES = new AtomicLong();
    private static final AtomicLong EROSION_HITS = new AtomicLong();
    private static final AtomicLong EROSION_SAMPLES = new AtomicLong();
    private static final AtomicLong HEIGHT_FLIGHT_WAITS = new AtomicLong();
    private static final AtomicLong HEIGHT_NON_BLOCKING_FALLBACKS = new AtomicLong();
    private static final AtomicLong RESIDENT_PUBLISHES = new AtomicLong();

    private NaturalHeightSampler() {
    }

    public static LevelSampler forLevel(WorldGenLevel world) {
        if (world == null) {
            return null;
        }
        ServerLevel level;
        try {
            level = world.getLevel();
        } catch (Throwable ignored) {
            return null;
        }
        if (level == null) {
            return null;
        }
        LevelSampler existing = SAMPLERS.get(level);
        if (existing != null) {
            return existing;
        }
        LevelSampler created = create(level);
        if (created == null) {
            return null;
        }
        LevelSampler previous = SAMPLERS.putIfAbsent(level, created);
        return previous == null ? created : previous;
    }

    private static LevelSampler create(ServerLevel level) {
        try {
            ServerChunkCache source = level.getChunkSource();
            ChunkGenerator generator = source.getGenerator();
            RandomState randomState = source.randomState();
            if (generator == null || randomState == null) {
                return null;
            }
            return new LevelSampler(generator, randomState, level);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static void clear() {
        SAMPLERS.clear();
    }

    public static String diagnostics() {
        long samples = SAMPLES.get();
        long hits = HITS.get();
        long total = samples + hits;
        return "levels=" + SAMPLERS.size()
            + ", generatorSamples=" + samples
            + ", cacheHits=" + hits
            + ", hitRate=" + (total == 0 ? "n/a"
                : String.format(java.util.Locale.ROOT, "%.1f%%", 100.0D * hits / total))
            + ", failures=" + FAILURES.get()
            + ", cacheDrops=" + EVICTIONS.get()
            + ", erosionFailures=" + EROSION_FAILURES.get()
            + ", erosionHits=" + EROSION_HITS.get()
            + ", erosionSamples=" + EROSION_SAMPLES.get()
            + ", heightFlightWaits=" + HEIGHT_FLIGHT_WAITS.get()
            + ", heightNonBlockingFallbacks=" + HEIGHT_NON_BLOCKING_FALLBACKS.get()
            + ", residentPublishes=" + RESIDENT_PUBLISHES.get()
            + ", cached=" + SAMPLERS.values().stream().mapToInt(s -> s.heights.size()).sum()
            + ", cachedErosion=" + SAMPLERS.values().stream().mapToInt(s -> s.erosion.size()).sum();
    }

    public static final class LevelSampler {

        private final ChunkGenerator generator;
        private final RandomState randomState;
        private final LevelHeightAccessor heightAccessor;
        private final ConcurrentHashMap<Long, Integer> heights = new ConcurrentHashMap<>();
        private final ConcurrentLinkedQueue<Long> heightOrder = new ConcurrentLinkedQueue<>();
        private final ConcurrentHashMap<Long, CompletableFuture<Integer>> heightFlights =
            new ConcurrentHashMap<>();
        private final ConcurrentHashMap<Long, Double> erosion = new ConcurrentHashMap<>();
        private final ConcurrentLinkedQueue<Long> erosionOrder = new ConcurrentLinkedQueue<>();

        private LevelSampler(ChunkGenerator generator,
                             RandomState randomState,
                             LevelHeightAccessor heightAccessor) {
            this.generator = generator;
            this.randomState = randomState;
            this.heightAccessor = heightAccessor;
        }

        public int chunkHeight(int chunkX, int chunkZ) {
            long key = PackedCoordinateKey.of(chunkX, chunkZ);
            Integer cached = this.heights.get(key);
            if (cached != null) {
                HITS.incrementAndGet();
                return cached;
            }
            CompletableFuture<Integer> created = new CompletableFuture<>();
            CompletableFuture<Integer> existing = this.heightFlights.putIfAbsent(key, created);
            if (existing != null) {
                HEIGHT_FLIGHT_WAITS.incrementAndGet();
                return existing.join();
            }
            try {
                // Only the flight owner enters NoiseBasedChunkGenerator. All
                // other workers reuse the same immutable sampled height.
                int height = sample((chunkX << 4) + 8, (chunkZ << 4) + 8);
                Integer previous = this.heights.putIfAbsent(key, height);
                int result = previous != null ? previous : height;
                if (previous == null) {
                    this.heightOrder.add(key);
                    trimHeightCache();
                }
                created.complete(result);
                return result;
            } catch (RuntimeException | Error failure) {
                created.completeExceptionally(failure);
                throw failure;
            } finally {
                this.heightFlights.remove(key, created);
            }
        }

        public int blockHeight(int blockX, int blockZ) {
            return sample(blockX, blockZ);
        }

        public double erosionAt(int blockX, int blockZ) {
            long key = PackedCoordinateKey.of(blockX, blockZ);
            Double cached = this.erosion.get(key);
            if (cached != null) {
                EROSION_HITS.incrementAndGet();
                return cached;
            }
            EROSION_SAMPLES.incrementAndGet();
            try {
                double value = this.randomState.router().erosion().compute(
                    new DensityFunction.SinglePointContext(blockX, 0, blockZ));
                Double previous = this.erosion.putIfAbsent(key, value);
                if (previous != null) {
                    return previous;
                }
                this.erosionOrder.add(key);
                trimErosionCache();
                return value;
            } catch (Throwable ignored) {
                EROSION_FAILURES.incrementAndGet();
                return 0.0D;
            }
        }

        private int sample(int blockX, int blockZ) {
            SAMPLES.incrementAndGet();
            try {
                return this.generator.getBaseHeight(
                    blockX, blockZ, TYPE, this.heightAccessor, this.randomState);
            } catch (Throwable ignored) {
                FAILURES.incrementAndGet();
                return this.heightAccessor.getMinBuildHeight();
            }
        }

        public int cachedChunks() {
            return this.heights.size();
        }

        /**
         * Noise and surface generation have already built this heightmap by
         * the time Lost Cities enters its feature. Reuse that result instead
         * of asking Minecraft to rebuild a full one-column NoiseChunk.
         */
        public void publishResidentChunk(ChunkAccess chunk) {
            if (chunk == null) {
                return;
            }
            int chunkX = chunk.getPos().x;
            int chunkZ = chunk.getPos().z;
            long key = PackedCoordinateKey.of(chunkX, chunkZ);
            if (this.heights.containsKey(key)) {
                return;
            }
            int height;
            try {
                // ChunkAccess exposes the top block Y while getBaseHeight()
                // returns the first free Y above it.
                height = chunk.getHeight(TYPE, 8, 8) + 1;
            } catch (Throwable ignored) {
                return;
            }
            Integer previous = this.heights.putIfAbsent(key, height);
            if (previous == null) {
                RESIDENT_PUBLISHES.incrementAndGet();
                this.heightOrder.add(key);
                trimHeightCache();
            }
        }

        /** Return a resident height or the supplied fallback without waiting. */
        public int chunkHeightNonBlocking(int chunkX, int chunkZ, int fallback) {
            long key = PackedCoordinateKey.of(chunkX, chunkZ);
            Integer cached = this.heights.get(key);
            if (cached != null) {
                HITS.incrementAndGet();
                return cached;
            }
            CompletableFuture<Integer> inFlight = this.heightFlights.get(key);
            if (inFlight != null) {
                HEIGHT_NON_BLOCKING_FALLBACKS.incrementAndGet();
                return fallback;
            }
            /* Do not start a generator sample here. The next pass will use the
             * value published by chunkHeight(). */
            HEIGHT_NON_BLOCKING_FALLBACKS.incrementAndGet();
            return fallback;
        }

        /** Return a sampled height only when it is already resident. */
        public Integer cachedChunkHeight(int chunkX, int chunkZ) {
            long key = PackedCoordinateKey.of(chunkX, chunkZ);
            Integer cached = this.heights.get(key);
            if (cached != null) {
                HITS.incrementAndGet();
            }
            return cached;
        }

        private void trimHeightCache() {
            while (this.heights.size() > MAX_CACHED_CHUNKS) {
                Long oldest = this.heightOrder.poll();
                if (oldest == null) {
                    return;
                }
                if (this.heights.remove(oldest) != null) {
                    EVICTIONS.incrementAndGet();
                }
            }
        }

        private void trimErosionCache() {
            while (this.erosion.size() > MAX_CACHED_CHUNKS) {
                Long oldest = this.erosionOrder.poll();
                if (oldest == null) {
                    return;
                }
                this.erosion.remove(oldest);
            }
        }
    }
}
