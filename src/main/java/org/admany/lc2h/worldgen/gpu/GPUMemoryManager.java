package org.admany.lc2h.worldgen.gpu;

import mcjty.lostcities.varia.ChunkCoord;

import java.util.concurrent.ConcurrentHashMap;

public final class GPUMemoryManager {

    private GPUMemoryManager() {
    }

    public static boolean putGPUData(ChunkCoord coord, float[] data, ConcurrentHashMap<ChunkCoord, float[]> cache) {
        return GpuMemoryState.putGPUData(coord, data, cache);
    }

    public static void putGPUDataRuntimeOnly(ChunkCoord coord, float[] data, ConcurrentHashMap<ChunkCoord, float[]> cache) {
        GpuMemoryState.putGPUDataRuntimeOnly(coord, data, cache);
    }

    public static float[] getGPUData(ChunkCoord coord, ConcurrentHashMap<ChunkCoord, float[]> cache) {
        return GpuMemoryState.getGPUData(coord, cache);
    }

    public static void removeGPUData(ChunkCoord coord) {
        GpuMemoryState.removeGPUData(coord);
    }

    public static void clearAllGPUCaches() {
        GpuMemoryState.clearAllGPUCaches();
    }

    public static String getMemoryStats() {
        return GpuMemoryState.getMemoryStats();
    }

    public static long getCachedBytes() {
        return GpuMemoryState.getCurrentMemoryBytes();
    }

    public static int getCachedEntryCount() {
        return GpuMemoryState.getCurrentEntryCount();
    }

    public static long getDiskCacheEntryCount() {
        return GpuDiskCache.getBackingCacheEntryCount();
    }

    public static long getDiskCacheBytes() {
        return GpuDiskCache.estimateBackingCacheBytes();
    }

    public static void continuousCleanup() {
        GpuMemoryState.continuousCleanup();
    }

    public static void cleanupOldEntries(long maxAgeMs) {
        GpuMemoryState.cleanupOldEntries(maxAgeMs);
    }

    public static void markAsHot(ChunkCoord coord) {
        GpuMemoryState.markAsHot(coord);
    }

    public static String getProtectionStatus(ChunkCoord coord) {
        return GpuMemoryState.getProtectionStatus(coord);
    }

    public static long getQuantifiedAPICacheSize() {
        return GpuMemoryState.getQuantifiedAPICacheSize();
    }

    public static void clearQuantifiedAPICaches() {
        GpuMemoryState.clearQuantifiedAPICaches();
    }

    public static String getComprehensiveMemoryStats() {
        return GpuMemoryState.getComprehensiveMemoryStats();
    }

    public static void comprehensiveCleanup() {
        GpuMemoryState.comprehensiveCleanup();
    }

    public static void forceGPUMemoryCleanup() {
        GpuMemoryState.forceGPUMemoryCleanup();
    }
}
