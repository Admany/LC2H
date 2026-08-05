package org.admany.lc2h.data.cache;

import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.ChunkHeightmap;
import mcjty.lostcities.worldgen.IDimensionInfo;
import mcjty.lostcities.worldgen.lost.BiomeInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import org.admany.lc2h.mixin.accessor.lostcities.BiomeInfoAccessor;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.LongAdder;

/**
 * World-scoped, bounded runtime cache for Lost Cities biome facts.
 *
 * <p>This intentionally stays outside the mixin package: transformed target
 * classes may directly call it without making Mixin load its helper classes.</p>
 */
public final class BiomeInfoRuntimeCache {
    private static final int MAX_ENTRIES = Math.max(4_096,
        Integer.getInteger("lc2h.biome.scopedCache", 32_768));
    private static final ConcurrentHashMap<ScopeKey, ScopeCache> SCOPES = new ConcurrentHashMap<>();
    private static final ThreadLocal<ScopeRef> LOCAL_SCOPE = new ThreadLocal<>();
    private static final LongAdder HITS = new LongAdder();
    private static final LongAdder MISSES = new LongAdder();
    private static final LongAdder RACES = new LongAdder();
    private static final LongAdder EVICTIONS = new LongAdder();

    private BiomeInfoRuntimeCache() {
    }

    public static BiomeInfo get(IDimensionInfo provider, ChunkCoord coord) {
        ScopeRef local = LOCAL_SCOPE.get();
        ScopeCache cache;
        if (local != null && local.matches(provider)) {
            cache = local.cache;
        } else {
            ScopeKey scope = ScopeKey.of(provider);
            cache = SCOPES.computeIfAbsent(scope, ignored -> new ScopeCache());
            LOCAL_SCOPE.set(new ScopeRef(provider, cache));
        }
        return cache.getOrCompute(provider, coord);
    }

    public static void clear() {
        LOCAL_SCOPE.remove();
        SCOPES.clear();
    }

    public static String diagnostics() {
        int scopes;
        int entries = 0;
        scopes = SCOPES.size();
        for (ScopeCache cache : SCOPES.values()) {
            entries += cache.entries.size();
        }
        return "scopes=" + scopes
            + " entries=" + entries + "/" + MAX_ENTRIES
            + " hits=" + HITS.sum()
            + " misses=" + MISSES.sum()
            + " races=" + RACES.sum()
            + " evictions=" + EVICTIONS.sum();
    }

    private static BiomeInfo create(IDimensionInfo provider, ChunkCoord coord) {
        BiomeInfo info = new BiomeInfo();
        ChunkHeightmap heightmap = provider.getHeightmap(coord);
        int y = heightmap != null ? heightmap.getHeight() : 64;
        Holder<Biome> biome = provider.getBiome(new BlockPos(
            (coord.chunkX() << 4) + 8,
            y,
            (coord.chunkZ() << 4) + 8));
        ((BiomeInfoAccessor) (Object) info).lc2h$setMainBiome(biome);
        return info;
    }

    private static final class ScopeCache {
        private final ConcurrentHashMap<ChunkCoord, BiomeInfo> entries = new ConcurrentHashMap<>();
        private final ConcurrentLinkedQueue<ChunkCoord> insertionOrder = new ConcurrentLinkedQueue<>();

        private BiomeInfo getOrCompute(IDimensionInfo provider, ChunkCoord coord) {
            BiomeInfo cached = entries.get(coord);
            if (cached != null) {
                HITS.increment();
                return cached;
            }
            MISSES.increment();
            BiomeInfo computed = create(provider, coord);
            BiomeInfo raced = entries.putIfAbsent(coord, computed);
            if (raced != null) {
                RACES.increment();
                return raced;
            }
            insertionOrder.offer(coord);
            trimIfNeeded();
            return computed;
        }

        private void trimIfNeeded() {
            if (entries.size() <= MAX_ENTRIES) {
                return;
            }
            int target = MAX_ENTRIES - Math.max(256, MAX_ENTRIES / 16);
            while (entries.size() > target) {
                ChunkCoord oldest = insertionOrder.poll();
                if (oldest == null) {
                    break;
                }
                if (entries.remove(oldest) != null) {
                    EVICTIONS.increment();
                }
            }
        }
    }

    private static final class ScopeRef {
        private final java.lang.ref.WeakReference<IDimensionInfo> provider;
        private final ScopeCache cache;

        private ScopeRef(IDimensionInfo provider, ScopeCache cache) {
            this.provider = new java.lang.ref.WeakReference<>(provider);
            this.cache = cache;
        }

        private boolean matches(IDimensionInfo candidate) {
            return provider.get() == candidate;
        }
    }

    private record ScopeKey(long seed,
                            String dimension,
                            String profile,
                            String outsideProfile,
                            String worldStyle) {
        private static ScopeKey of(IDimensionInfo provider) {
            return new ScopeKey(
                provider.getSeed(),
                String.valueOf(provider.dimension()),
                provider.getProfile() == null ? "<none>" : provider.getProfile().getName(),
                provider.getOutsideProfile() == null ? "<none>" : provider.getOutsideProfile().getName(),
                provider.getProfile() == null ? "<none>" : provider.getProfile().getWorldStyle());
        }
    }
}
