package org.admany.lc2h.util.chunk;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

public class ChunkCoord {
    private static final java.util.Map<String, java.util.Map<Long, ChunkCoord>> CACHE = new java.util.concurrent.ConcurrentHashMap<>();
    private static final int MAX_CACHE_SIZE = 10000; 
    private static final java.util.concurrent.atomic.AtomicInteger cacheSize = new java.util.concurrent.atomic.AtomicInteger(0);

    private final String dimension;
    private final int chunkX;
    private final int chunkZ;

    public ChunkCoord(String dimension, int chunkX, int chunkZ) {
        this.dimension = dimension;
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
    }

    public int chunkX() {
        return chunkX;
    }

    public int chunkZ() {
        return chunkZ;
    }

    public String dimension() {
        return dimension;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof ChunkCoord)) return false;
        ChunkCoord other = (ChunkCoord) obj;
        return chunkX == other.chunkX && chunkZ == other.chunkZ && dimension.equals(other.dimension);
    }

    @Override
    public int hashCode() {
        return dimension.hashCode() * 31 * 31 + chunkX * 31 + chunkZ;
    }

    private static String normalizeDimension(String dim) {
        if (dim == null) return "minecraft:overworld";
        String d = dim.trim();
        if (d.equalsIgnoreCase("overworld") || d.equalsIgnoreCase("world") || d.equalsIgnoreCase("the_overworld")) {
            return "minecraft:overworld";
        }
        if (d.contains(":")) return d.toLowerCase();
        return ("minecraft:" + d).toLowerCase();
    }

    private static long packKeyStatic(String dim, int chunkX, int chunkZ) {
        int dimHash = (dim == null) ? 0 : dim.toLowerCase().hashCode();
        int cx = chunkX & 0xffff;
        int cz = chunkZ & 0xffff;
        int low = (cx << 16) | cz;
        return (((long) dimHash) << 32) | (low & 0xffffffffL);
    }

    public static ChunkCoord of(String dimension, int chunkX, int chunkZ) {
        String dim = normalizeDimension(dimension);
        long key = packKeyStatic(dim, chunkX, chunkZ);
        ChunkCoord coord = CACHE.computeIfAbsent(dim, d -> new java.util.concurrent.ConcurrentHashMap<>())
                                .computeIfAbsent(key, k -> {
                                    cacheSize.incrementAndGet();
                                    return new ChunkCoord(dim, chunkX, chunkZ);
                                });
        if (cacheSize.get() > MAX_CACHE_SIZE) {
            clearCache();
        }
        return coord;
    }

    public static ChunkCoord of(ResourceKey<Level> key, int chunkX, int chunkZ) {
        if (key == null) return of((String) null, chunkX, chunkZ);
        return of(key.location().toString(), chunkX, chunkZ);
    }

    public ChunkCoord offset(int dx, int dz) {
        return ChunkCoord.of(dimension, chunkX + dx, chunkZ + dz);
    }


    public long packKey() {
        int dimHash = (dimension == null) ? 0 : dimension.toLowerCase().hashCode();
        int cx = chunkX & 0xffff;
        int cz = chunkZ & 0xffff;
        int low = (cx << 16) | cz;
        return (((long) dimHash) << 32) | (low & 0xffffffffL);
    }

    public static long packWorldKey(String dim, int worldX, int worldZ) {
        String d = normalizeDimension(dim);
        int dimHash = (d == null) ? 0 : d.toLowerCase().hashCode();
        int cx = (worldX >> 4) & 0xffff;
        int cz = (worldZ >> 4) & 0xffff;
        int low = (cx << 16) | cz;
        return (((long) dimHash) << 32) | (low & 0xffffffffL);
    }

    @Override
    public String toString() {
        return String.format("ChunkCoord[%s: %d, %d]", dimension, chunkX, chunkZ);
    }

    public static void clearCache() {
        CACHE.clear();
        cacheSize.set(0);
    }
}