package org.admany.lc2h.worldgen.terrain;

import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class NaturalHeightSampler {

    private static final Heightmap.Types TYPE = Heightmap.Types.OCEAN_FLOOR_WG;

    private static final int MAX_CACHED_CHUNKS = Math.max(4096,
        Integer.getInteger("lc2h.terrain.naturalHeight.cacheMax", 1 << 19));

    private static final Map<ServerLevel, LevelSampler> SAMPLERS = new ConcurrentHashMap<>();
    private static final AtomicLong SAMPLES = new AtomicLong();
    private static final AtomicLong HITS = new AtomicLong();
    private static final AtomicLong FAILURES = new AtomicLong();
    private static final AtomicLong EVICTIONS = new AtomicLong();
    private static final AtomicLong EROSION_FAILURES = new AtomicLong();

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
            + ", cached=" + SAMPLERS.values().stream().mapToInt(s -> s.heights.size()).sum();
    }

    public static final class LevelSampler {

        private final ChunkGenerator generator;
        private final RandomState randomState;
        private final LevelHeightAccessor heightAccessor;
        private final ConcurrentHashMap<Long, Integer> heights = new ConcurrentHashMap<>();

        private LevelSampler(ChunkGenerator generator,
                             RandomState randomState,
                             LevelHeightAccessor heightAccessor) {
            this.generator = generator;
            this.randomState = randomState;
            this.heightAccessor = heightAccessor;
        }

        public int chunkHeight(int chunkX, int chunkZ) {
            long key = (((long) chunkX) << 32) ^ (chunkZ & 0xffffffffL);
            Integer cached = this.heights.get(key);
            if (cached != null) {
                HITS.incrementAndGet();
                return cached;
            }
            int height = sample((chunkX << 4) + 8, (chunkZ << 4) + 8);
            if (this.heights.size() > MAX_CACHED_CHUNKS) {
                this.heights.clear();
                EVICTIONS.incrementAndGet();
            }
            Integer previous = this.heights.putIfAbsent(key, height);
            return previous == null ? height : previous;
        }

        public int blockHeight(int blockX, int blockZ) {
            return sample(blockX, blockZ);
        }

        public double erosionAt(int blockX, int blockZ) {
            try {
                return this.randomState.router().erosion().compute(
                    new DensityFunction.SinglePointContext(blockX, 0, blockZ));
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
    }
}
