package org.admany.lc2h.worldgen.gpu;

import mcjty.lostcities.varia.ChunkCoord;
import org.admany.lc2h.LC2H;
import org.admany.lc2h.worldgen.async.generator.AsyncDebrisGenerator;
import org.admany.lc2h.worldgen.async.generator.AsyncPaletteGenerator;
import org.admany.lc2h.worldgen.async.planner.AsyncBuildingInfoPlanner;
import org.admany.lc2h.worldgen.async.planner.AsyncMultiChunkPlanner;
import org.admany.lc2h.worldgen.async.planner.AsyncTerrainCorrectionPlanner;
import org.admany.lc2h.worldgen.async.planner.AsyncTerrainFeaturePlanner;
import org.admany.lc2h.util.server.ServerTickLoad;
import org.admany.quantified.core.common.cache.CacheManager;
import org.admany.quantified.core.common.opencl.core.OpenCLManager;
import org.admany.quantified.core.common.opencl.core.OpenCLRuntime;
import org.admany.quantified.core.common.opencl.gpu.GPUMonitor;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Locale;

final class GpuMemoryState {

    static final long MAX_GPU_MEMORY_BYTES = 64L * 1024L * 1024L;
    static final long MAX_TOTAL_MEMORY_BYTES = 4L * 1024 * 1024 * 1024;
    static final int MAX_ENTRIES_PER_CACHE = 300;
    static final int CLEANUP_BATCH_SIZE = 50;

    static final long MIN_AGE_FOR_EVICTION_MS = 15_000;
    static final long RECENTLY_PROCESSED_GRACE_PERIOD_MS = 10_000;

    static final double VRAM_SATURATION_THRESHOLD = 0.90d;
    static final long VRAM_AUTO_CLEAR_COOLDOWN_MS = 15_000;
    static final long IDLE_AUTO_CLEAR_MS = 30_000;

    static final long EMERGENCY_CLEANUP_COOLDOWN_MS = 60_000;

    static volatile long lastEmergencyCleanupTime = 0;
    static volatile long lastVramAutoClearMs = 0;
    static volatile long lastAccessMs = 0;
    static volatile long currentMemoryBytes = 0;

    static final Object MEMORY_LOCK = new Object();

    static final LinkedHashMap<ChunkCoord, Long> ACCESS_ORDER = new LinkedHashMap<>(16, 0.75f, true);
    static final Map<ChunkCoord, Integer> MEMORY_SIZES = new ConcurrentHashMap<>();
    static final Map<ChunkCoord, Long> ADD_TIMES = new ConcurrentHashMap<>();
    static final java.util.concurrent.atomic.AtomicLong DISK_PROMOTIONS = new java.util.concurrent.atomic.AtomicLong(0L);

    static final long AGGRESSIVE_CLEANUP_THRESHOLD = (long) (MAX_GPU_MEMORY_BYTES * 0.6);
    static final List<ConcurrentHashMap<ChunkCoord, float[]>> RUNTIME_CACHES = List.of(
        AsyncMultiChunkPlanner.GPU_DATA_CACHE,
        AsyncBuildingInfoPlanner.GPU_DATA_CACHE,
        AsyncTerrainFeaturePlanner.GPU_DATA_CACHE,
        AsyncTerrainCorrectionPlanner.GPU_DATA_CACHE,
        AsyncPaletteGenerator.GPU_DATA_CACHE,
        AsyncDebrisGenerator.GPU_DATA_CACHE
    );

    static {
        GpuDiskCache.getDiskCacheDirectory();
    }

    private GpuMemoryState() {
    }

    static boolean putGPUData(ChunkCoord coord, float[] data, ConcurrentHashMap<ChunkCoord, float[]> cache) {
        if (coord == null || data == null || cache == null) {
            return false;
        }

        int dataSize = data.length * 4;
        boolean cachedInRam = false;

        synchronized (MEMORY_LOCK) {
            if (!checkGPUMonitorCapacity(dataSize)) {
                LC2H.LOGGER.debug("Skipped GPU data cache for {} because Quantified API limits were exceeded", coord);
            } else {
                Integer priorSize = MEMORY_SIZES.get(coord);
                if (priorSize != null) {
                    ACCESS_ORDER.put(coord, System.nanoTime());
                    lastAccessMs = System.currentTimeMillis();
                }
                int additionalBytes = priorSize == null ? dataSize : Math.max(0, dataSize - priorSize);
                ensureMemoryCapacityLocked(additionalBytes);

                cache.put(coord, data);

                if (priorSize == null) {
                    currentMemoryBytes += dataSize;
                    MEMORY_SIZES.put(coord, dataSize);
                    ADD_TIMES.put(coord, System.nanoTime());
                } else if (priorSize != dataSize) {
                    currentMemoryBytes += (dataSize - priorSize);
                    MEMORY_SIZES.put(coord, dataSize);
                }

                ACCESS_ORDER.put(coord, System.nanoTime());
                lastAccessMs = System.currentTimeMillis();
                cachedInRam = true;

                if (LC2H.LOGGER.isDebugEnabled()) {
                    LC2H.LOGGER.debug("Cached GPU data for {} ({} bytes). Current GPU cache: {} MB",
                        coord, dataSize, currentMemoryBytes / (1024 * 1024));
                }
            }
        }

        GpuDiskCache.persist(coord, data);

        if (!cachedInRam && LC2H.LOGGER.isDebugEnabled()) {
            LC2H.LOGGER.debug("Stored {} in disk cache only ({} bytes)", coord, dataSize);
        }

        return cachedInRam;
    }

    static void putGPUDataRuntimeOnly(ChunkCoord coord, float[] data, ConcurrentHashMap<ChunkCoord, float[]> cache) {
        if (coord == null || data == null || cache == null) {
            return;
        }

        synchronized (MEMORY_LOCK) {
            if (!MEMORY_SIZES.containsKey(coord)) {
                return;
            }
            cache.put(coord, data);
            ACCESS_ORDER.put(coord, System.nanoTime());
            lastAccessMs = System.currentTimeMillis();
        }
    }

    static float[] getGPUData(ChunkCoord coord, ConcurrentHashMap<ChunkCoord, float[]> cache) {
        if (coord == null || cache == null) {
            return null;
        }

        float[] data = cache.get(coord);
        if (data != null) {
            synchronized (MEMORY_LOCK) {
                ACCESS_ORDER.put(coord, System.nanoTime());
                lastAccessMs = System.currentTimeMillis();
            }
            return data;
        }

        float[] diskCopy = GpuDiskCache.load(coord);
        if (diskCopy != null) {
            promoteDiskCopy(coord, diskCopy, cache);
            if (LC2H.LOGGER.isDebugEnabled()) {
                LC2H.LOGGER.debug("Loaded GPU data for {} from disk cache ({} floats)", coord, diskCopy.length);
            }
        }
        return diskCopy;
    }

    static void removeGPUData(ChunkCoord coord) {
        if (coord == null) {
            return;
        }

        synchronized (MEMORY_LOCK) {
            removeFromRuntimeCachesLocked(coord);
            ACCESS_ORDER.remove(coord);
            removeFromTrackingLocked(coord);
        }

        GpuDiskCache.delete(coord);
    }

    static void removeFromRamOnly(ChunkCoord coord) {
        if (coord == null) {
            return;
        }

        synchronized (MEMORY_LOCK) {
            removeFromRuntimeCachesLocked(coord);
            ACCESS_ORDER.remove(coord);
            removeFromTrackingLocked(coord);
        }

    }

    static void clearAllGPUCaches() {
        synchronized (MEMORY_LOCK) {
            resetCaches(true, true);
            LC2H.LOGGER.info("Cleared GPU caches");
        }
    }

    private static void resetCaches(boolean resetRuntime, boolean clearDisk) {
        RUNTIME_CACHES.forEach(Map::clear);

        ACCESS_ORDER.clear();
        MEMORY_SIZES.clear();
        ADD_TIMES.clear();
        currentMemoryBytes = 0;

        if (clearDisk) {
            GpuDiskCache.clearAll();
        }

        if (!resetRuntime) {
            return;
        }

        System.gc();
        System.runFinalization();

        try {
            OpenCLRuntime.destroy();
            LC2H.LOGGER.info("Destroyed OpenCL context during shutdown to release GPU memory");
        } catch (Throwable t) {
            LC2H.LOGGER.debug("Failed to destroy OpenCL context on shutdown: {}", t.getMessage());
        }
    }

    private static void removeFromRuntimeCachesLocked(ChunkCoord coord) {
        for (ConcurrentHashMap<ChunkCoord, float[]> cache : RUNTIME_CACHES) {
            cache.remove(coord);
        }
    }

    private static void removeFromTrackingLocked(ChunkCoord coord) {
        Integer size = MEMORY_SIZES.remove(coord);
        ADD_TIMES.remove(coord);
        if (size != null) {
            currentMemoryBytes -= size;
        }
    }

    private static void removeFromRamOnlyLocked(ChunkCoord coord) {
        removeFromRuntimeCachesLocked(coord);
        removeFromTrackingLocked(coord);
    }

    private static void ensureMemoryCapacityLocked(int requiredBytes) {
        long maxBytes = effectiveMaxGpuBytes();
        long aggressiveThreshold = Math.min(AGGRESSIVE_CLEANUP_THRESHOLD, (long) (maxBytes * 0.6));
        if (currentMemoryBytes + requiredBytes > aggressiveThreshold) {
            aggressiveCleanup();
        }

        while (currentMemoryBytes + requiredBytes > maxBytes && !ACCESS_ORDER.isEmpty()) {
            evictLRUEntry();
        }

        ensureCacheCapacity(AsyncMultiChunkPlanner.GPU_DATA_CACHE);
        ensureCacheCapacity(AsyncBuildingInfoPlanner.GPU_DATA_CACHE);
        ensureCacheCapacity(AsyncTerrainFeaturePlanner.GPU_DATA_CACHE);
        ensureCacheCapacity(AsyncTerrainCorrectionPlanner.GPU_DATA_CACHE);
        ensureCacheCapacity(AsyncPaletteGenerator.GPU_DATA_CACHE);
        ensureCacheCapacity(AsyncDebrisGenerator.GPU_DATA_CACHE);

        checkTotalMemoryPressure();
    }

    private static void aggressiveCleanup() {
        int cleaned = 0;
        int skipped = 0;
        long now = System.nanoTime();
        long minAgeCutoff = now - (MIN_AGE_FOR_EVICTION_MS * 1_000_000);

        Iterator<Map.Entry<ChunkCoord, Long>> it = ACCESS_ORDER.entrySet().iterator();
        while (it.hasNext() && cleaned < CLEANUP_BATCH_SIZE &&
               currentMemoryBytes > (MAX_GPU_MEMORY_BYTES * 0.6)) {

            Map.Entry<ChunkCoord, Long> entry = it.next();
            ChunkCoord coord = entry.getKey();
            Long addTime = ADD_TIMES.get(coord);

            if (addTime != null && (now - addTime) < (RECENTLY_PROCESSED_GRACE_PERIOD_MS * 1_000_000)) {
                skipped++;
                continue;
            }

            if (entry.getValue() > minAgeCutoff) {
                break;
            }

            removeFromRamOnlyLocked(coord);
            it.remove();
            cleaned++;
        }

        if (cleaned > 0 || skipped > 0) {
            LC2H.LOGGER.debug("Aggressive GPU cleanup removed {} entries, skipped {} newer entries. Cache now {} MB",
                cleaned, skipped, currentMemoryBytes / (1024 * 1024));
        }
    }

    private static void checkTotalMemoryPressure() {
        try {
            Runtime runtime = Runtime.getRuntime();
            long maxMemory = runtime.maxMemory();
            long totalMemory = runtime.totalMemory();
            long freeMemory = runtime.freeMemory();
            long usedMemory = totalMemory - freeMemory;

            long now = System.currentTimeMillis();
            if (now - lastEmergencyCleanupTime < EMERGENCY_CLEANUP_COOLDOWN_MS) {
                return;
            }

            boolean heapExceeded = usedMemory > MAX_TOTAL_MEMORY_BYTES || (maxMemory > 0 && usedMemory > (maxMemory * 0.85));
            if (heapExceeded) {
                lastEmergencyCleanupTime = now;
                if (org.admany.lc2h.config.ConfigManager.ENABLE_DEBUG_LOGGING) {
                    LC2H.LOGGER.warn("High JVM memory usage: {} MB used of {} MB. Starting emergency cleanup.",
                        usedMemory / (1024 * 1024), maxMemory / (1024 * 1024));
                } else {
                    LC2H.LOGGER.debug("High JVM memory usage: {} MB used of {} MB. Starting emergency cleanup.",
                        usedMemory / (1024 * 1024), maxMemory / (1024 * 1024));
                }
                emergencyCleanup();
            }
        } catch (Throwable ignored) {
        }
    }

    private static void emergencyCleanup() {
        synchronized (MEMORY_LOCK) {
            int originalSize = ACCESS_ORDER.size();
            long originalMemory = currentMemoryBytes;
            long now = System.nanoTime();

            int toRemove = (int) (originalSize * 0.75);
            int removed = 0;
            int skipped = 0;

            Iterator<Map.Entry<ChunkCoord, Long>> it = ACCESS_ORDER.entrySet().iterator();
            while (it.hasNext() && removed < toRemove) {
                Map.Entry<ChunkCoord, Long> entry = it.next();
                ChunkCoord coord = entry.getKey();
                Long addTime = ADD_TIMES.get(coord);

                if (addTime != null && (now - addTime) < (5_000 * 1_000_000)) {
                    skipped++;
                    continue;
                }

                removeFromRamOnlyLocked(coord);
                it.remove();
                removed++;
            }

            long freedMemory = originalMemory - currentMemoryBytes;
            if (org.admany.lc2h.config.ConfigManager.ENABLE_DEBUG_LOGGING) {
                LC2H.LOGGER.warn("Emergency cleanup removed {} entries, skipped {} recent entries, freed {} MB",
                    removed, skipped, freedMemory / (1024 * 1024));
            } else {
                LC2H.LOGGER.debug("Emergency cleanup removed {} entries, skipped {} recent entries, freed {} MB",
                    removed, skipped, freedMemory / (1024 * 1024));
            }

            if (freedMemory > 0) {
                if (org.admany.lc2h.config.ConfigManager.ENABLE_DEBUG_LOGGING) {
                    LC2H.LOGGER.info("Emergency cleanup finished; GPU caches trimmed to ease memory pressure");
                } else {
                    LC2H.LOGGER.debug("Emergency cleanup finished; GPU caches trimmed to ease memory pressure");
                }
            } else {
                if (org.admany.lc2h.config.ConfigManager.ENABLE_DEBUG_LOGGING) {
                    LC2H.LOGGER.warn("Emergency cleanup could not free memory because entries were too recent");
                } else {
                    LC2H.LOGGER.debug("Emergency cleanup could not free memory because entries were too recent");
                }
            }
        }
    }

    private static void ensureCacheCapacity(ConcurrentHashMap<ChunkCoord, float[]> cache) {
        while (cache.size() > MAX_ENTRIES_PER_CACHE && !cache.isEmpty()) {
            evictLRUEntry();
        }
    }

    private static boolean checkGPUMonitorCapacity(int requiredBytes) {
        try {
            GPUMonitor monitor = GPUMonitor.getInstance();
            boolean canAccept = monitor.canAcceptTask((long) requiredBytes, 1);

            if (!canAccept) {
                GPUMonitor.GPUStatus status = monitor.getStatus();
                if (status != null) {
                    long totalVram = status.totalVramBytes();
                    long usedVram = status.usedVramBytes();
                    double memoryUtil = status.memoryUtilization();

                    if (LC2H.LOGGER.isDebugEnabled()) {
                        LC2H.LOGGER.debug("GPU memory allocation rejected: VRAM {}/{} MB ({}%), request {} bytes",
                            usedVram / (1024 * 1024),
                            totalVram / (1024 * 1024),
                            memoryUtil * 100d,
                            requiredBytes);
                    }

                    boolean saturated = totalVram > 0 && (usedVram >= totalVram || memoryUtil >= VRAM_SATURATION_THRESHOLD);
                    if (saturated) {
                        handleVramSaturation(totalVram, usedVram);

                        monitor = GPUMonitor.getInstance();
                        canAccept = monitor.canAcceptTask((long) requiredBytes, 1);
                        if (canAccept) {
                            LC2H.LOGGER.info("GPU caches cleared after VRAM saturation; allocation permitted");
                        }
                    }
                }

                if (!canAccept) {
                    return false;
                }
            }

            return true;
        } catch (Throwable t) {
            LC2H.LOGGER.debug("Unable to query GPUMonitor capacity; allowing allocation: {}", t.getMessage());
            return true;
        }
    }

    private static void handleVramSaturation(long totalVram, long usedVram) {
        long now = System.currentTimeMillis();
        if (now - lastVramAutoClearMs < VRAM_AUTO_CLEAR_COOLDOWN_MS) {
            return;
        }
        lastVramAutoClearMs = now;

        long totalMb = Math.max(1L, totalVram / (1024 * 1024));
        long usedMb = usedVram / (1024 * 1024);

        LC2H.LOGGER.warn("VRAM reached the Quantified API cap ({} / {} MB). Delegating VRAM saturation handling to API.", usedMb, totalMb);

        boolean delegated = false;
        try {
            boolean cooldownActive = OpenCLManager.isInVramPressureCooldown();

            if (cooldownActive) {
                LC2H.LOGGER.debug("Quantified API VRAM cooldown active; skipping duplicate saturation delegation.");
            } else {
                String cause = String.format(Locale.ROOT,
                    "LC2H GPU cache pressure (GPU %d/%d MB, cache %d MB across %d entries)",
                    usedMb,
                    totalMb,
                    currentMemoryBytes / (1024 * 1024),
                    MEMORY_SIZES.size());

                OpenCLManager.handleVramSaturation(cause);
                delegated = true;
            }
        } catch (Throwable t) {
            LC2H.LOGGER.debug("Unable to delegate VRAM saturation handling to API: {}", t.getMessage());
        }

        if (delegated) {
            notifyQuantifiedGpuClear();
        }
    }

    private static void notifyQuantifiedGpuClear() {
        try {
            OpenCLManager.clearGPUCaches();
        } catch (Throwable t) {
            LC2H.LOGGER.debug("Unable to notify Quantified API GPU monitor to reset tracking: {}", t.getMessage());
        }
    }

    private static void evictLRUEntry() {
        long now = System.nanoTime();
        long minAgeCutoff = now - (MIN_AGE_FOR_EVICTION_MS * 1_000_000);

        Iterator<Map.Entry<ChunkCoord, Long>> it = ACCESS_ORDER.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<ChunkCoord, Long> entry = it.next();
            ChunkCoord coord = entry.getKey();
            Long addTime = ADD_TIMES.get(coord);

            if (addTime != null && (now - addTime) < (RECENTLY_PROCESSED_GRACE_PERIOD_MS * 1_000_000)) {
                continue;
            }

            if (entry.getValue() > minAgeCutoff) {
                break;
            }

            removeFromRamOnlyLocked(coord);
            it.remove();

            if (LC2H.LOGGER.isDebugEnabled()) {
                LC2H.LOGGER.debug("Evicted GPU data for {} (age: {}s) to free memory",
                    coord, (now - entry.getValue()) / 1_000_000_000);
            }
            return;
        }

        LC2H.LOGGER.debug("No GPU entries met the eviction threshold; all were used recently");
    }

    private static long effectiveMaxGpuBytes() {
        long maxBytes = MAX_GPU_MEMORY_BYTES;
        double tickMs = ServerTickLoad.getSmoothedTickMs();
        if (tickMs >= 45.0D) {
            maxBytes = (long) (maxBytes * 0.5);
        } else if (tickMs >= 35.0D) {
            maxBytes = (long) (maxBytes * 0.7);
        }
        return Math.max(16L * 1024L * 1024L, maxBytes);
    }

    private static void promoteDiskCopy(ChunkCoord coord, float[] data, ConcurrentHashMap<ChunkCoord, float[]> cache) {
        if (coord == null || data == null || cache == null) {
            return;
        }
        int dataSize = data.length * 4;
        synchronized (MEMORY_LOCK) {
            Integer priorSize = MEMORY_SIZES.get(coord);
            long maxBytes = effectiveMaxGpuBytes();
            if (priorSize == null && currentMemoryBytes + dataSize > maxBytes) {
                return;
            }
            if (!checkGPUMonitorCapacity(dataSize)) {
                return;
            }
            ensureMemoryCapacityLocked(priorSize == null ? dataSize : Math.max(0, dataSize - priorSize));
            cache.put(coord, data);
            if (priorSize == null) {
                currentMemoryBytes += dataSize;
                MEMORY_SIZES.put(coord, dataSize);
                ADD_TIMES.put(coord, System.nanoTime());
            } else if (priorSize != dataSize) {
                currentMemoryBytes += (dataSize - priorSize);
                MEMORY_SIZES.put(coord, dataSize);
            }
            ACCESS_ORDER.put(coord, System.nanoTime());
            lastAccessMs = System.currentTimeMillis();
            DISK_PROMOTIONS.incrementAndGet();
        }
    }

    static String getMemoryStats() {
        synchronized (MEMORY_LOCK) {
            long now = System.nanoTime();
            int protectedCount = 0;
            int unprotectedCount = 0;
            long maxBytes = effectiveMaxGpuBytes();

            for (Map.Entry<ChunkCoord, Long> entry : ACCESS_ORDER.entrySet()) {
                long ageMs = (now - entry.getValue()) / 1_000_000;
                Long addTime = ADD_TIMES.get(entry.getKey());
                long addAgeMs = addTime != null ? (now - addTime) / 1_000_000 : -1;

                boolean protectedByAccess = ageMs < MIN_AGE_FOR_EVICTION_MS;
                boolean protectedByAdd = addAgeMs >= 0 && addAgeMs < RECENTLY_PROCESSED_GRACE_PERIOD_MS;

                if (protectedByAccess || protectedByAdd) {
                    protectedCount++;
                } else {
                    unprotectedCount++;
                }
            }

            return String.format("GPU Memory: %d MB used (%d%%), %d entries (%d protected, %d evictable)",
                currentMemoryBytes / (1024 * 1024),
                (int) ((currentMemoryBytes * 100L) / Math.max(1, maxBytes)),
                MEMORY_SIZES.size(),
                protectedCount,
                unprotectedCount);
        }
    }

    static void continuousCleanup() {
        synchronized (MEMORY_LOCK) {
            long maxBytes = effectiveMaxGpuBytes();
            long aggressiveThreshold = Math.min(AGGRESSIVE_CLEANUP_THRESHOLD, (long) (maxBytes * 0.6));
            if (currentMemoryBytes > aggressiveThreshold) {
                aggressiveCleanup();
            }

            checkTotalMemoryPressure();
        }
    }

    static void cleanupOldEntries(long maxAgeMs) {
        synchronized (MEMORY_LOCK) {
            long cutoff = System.nanoTime() - (maxAgeMs * 1_000_000);
            int cleaned = 0;
            long freedMemory = 0;

            Iterator<Map.Entry<ChunkCoord, Long>> it = ACCESS_ORDER.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<ChunkCoord, Long> entry = it.next();
                if (entry.getValue() < cutoff) {
                    ChunkCoord coord = entry.getKey();
                    Integer size = MEMORY_SIZES.get(coord);
                    if (size != null) {
                        freedMemory += size;
                    }
                    removeFromRamOnlyLocked(coord);
                    it.remove();
                    cleaned++;
                } else {
                    break;
                }
            }

            if (cleaned > 0) {
                LC2H.LOGGER.debug("Periodic cleanup removed {} old entries and freed {} MB",
                    cleaned, freedMemory / (1024 * 1024));
            }

            long maxBytes = effectiveMaxGpuBytes();
            if (currentMemoryBytes > (maxBytes * 0.7)) {
                int additionalCleaned = 0;
                long now = System.nanoTime();
                long minAgeCutoff = now - (MIN_AGE_FOR_EVICTION_MS * 1_000_000);

                it = ACCESS_ORDER.entrySet().iterator();
                while (it.hasNext() && additionalCleaned < 25 && currentMemoryBytes > (maxBytes * 0.6)) {
                    Map.Entry<ChunkCoord, Long> entry = it.next();
                    ChunkCoord coord = entry.getKey();
                    Long addTime = ADD_TIMES.get(coord);

                    if (addTime != null && (now - addTime) < (RECENTLY_PROCESSED_GRACE_PERIOD_MS * 1_000_000)) {
                        continue;
                    }

                    if (entry.getValue() > minAgeCutoff) {
                        break;
                    }

                    removeFromRamOnlyLocked(coord);
                    it.remove();
                    additionalCleaned++;
                }
                if (additionalCleaned > 0) {
                    LC2H.LOGGER.debug("Additional capacity cleanup removed {} entries to ease memory pressure", additionalCleaned);
                }
            }
        }
    }

    static void markAsHot(ChunkCoord coord) {
        if (coord == null) {
            return;
        }

        synchronized (MEMORY_LOCK) {
            if (ACCESS_ORDER.containsKey(coord)) {
                ACCESS_ORDER.put(coord, System.nanoTime());
            }
        }
    }

    static String getProtectionStatus(ChunkCoord coord) {
        if (coord == null) {
            return "null";
        }

        synchronized (MEMORY_LOCK) {
            Long accessTime = ACCESS_ORDER.get(coord);
            Long addTime = ADD_TIMES.get(coord);

            if (accessTime == null) {
                return "not_cached";
            }

            long now = System.nanoTime();
            long ageMs = (now - accessTime) / 1_000_000;
            long addAgeMs = addTime != null ? (now - addTime) / 1_000_000 : -1;

            boolean protectedByAccess = ageMs < MIN_AGE_FOR_EVICTION_MS;
            boolean protectedByAdd = addAgeMs >= 0 && addAgeMs < RECENTLY_PROCESSED_GRACE_PERIOD_MS;

            return String.format("age=%dms, add_age=%dms, protected=%s",
                ageMs, addAgeMs, (protectedByAccess || protectedByAdd));
        }
    }

    static long getQuantifiedAPICacheSize() {
        try {
            return CacheManager.getTotalCacheSize();
        } catch (Throwable t) {
            LC2H.LOGGER.debug("Unable to read Quantified API cache size: {}", t.getMessage());
            return 0;
        }
    }

    static long getCurrentMemoryBytes() {
        synchronized (MEMORY_LOCK) {
            return currentMemoryBytes;
        }
    }

    static int getCurrentEntryCount() {
        synchronized (MEMORY_LOCK) {
            return MEMORY_SIZES.size();
        }
    }

    static void clearQuantifiedAPICaches() {
        try {
            CacheManager.clearAllCaches();
            if (org.admany.lc2h.config.ConfigManager.ENABLE_DEBUG_LOGGING) {
                LC2H.LOGGER.info("Quantified API caches cleared");
            } else {
                LC2H.LOGGER.debug("Quantified API caches cleared");
            }
        } catch (Throwable t) {
            LC2H.LOGGER.warn("Failed to clear Quantified API caches: {}", t.getMessage());
        }
    }

    static String getComprehensiveMemoryStats() {
        String gpuStats = getMemoryStats();
        long quantifiedSize = getQuantifiedAPICacheSize();
        String gpuMonitorStats = getGPUMonitorStats();

        return String.format("%s, Quantified API: %d entries%s", gpuStats, quantifiedSize,
            gpuMonitorStats.isEmpty() ? "" : ", " + gpuMonitorStats);
    }

    private static String getGPUMonitorStats() {
        try {
            GPUMonitor.GPUStatus status = GPUMonitor.getInstance().getStatus();
            if (status == null) {
                return "";
            }

            return String.format(Locale.ROOT,
                "GPU Monitor: VRAM %d/%d MB (%.1f%%), Compute %.1f%%, Temp %.1f\u00B0C",
                status.usedVramBytes() / (1024 * 1024),
                status.totalVramBytes() / (1024 * 1024),
                status.memoryUtilization() * 100,
                status.computeUtilization() * 100,
                status.temperatureC());
        } catch (Throwable t) {
            LC2H.LOGGER.debug("Unable to read GPUMonitor stats: {}", t.getMessage());
        }
        return "";
    }

    @Deprecated(forRemoval = true)
    @SuppressWarnings("unused")
    private static String getGPUMonitorStatsLegacyReflection() {
        try {
            Class<?> gpuMonitorClass = Class.forName("org.admany.quantified.core.common.opencl.GPUMonitor");
            Object monitorInstance = gpuMonitorClass.getMethod("getInstance").invoke(null);
            Object gpuStatus = gpuMonitorClass.getMethod("getStatus").invoke(monitorInstance);

            if (gpuStatus != null) {
                long totalVram = (Long) gpuStatus.getClass().getMethod("totalVramBytes").invoke(gpuStatus);
                long usedVram = (Long) gpuStatus.getClass().getMethod("usedVramBytes").invoke(gpuStatus);
                double memoryUtil = (Double) gpuStatus.getClass().getMethod("memoryUtilization").invoke(gpuStatus);
                double computeUtil = (Double) gpuStatus.getClass().getMethod("computeUtilization").invoke(gpuStatus);
                double temp = (Double) gpuStatus.getClass().getMethod("temperatureC").invoke(gpuStatus);

                return String.format("GPU Monitor: VRAM %d/%d MB (%.1f%%), Compute %.1f%%, Temp %.1f°C",
                    usedVram / (1024 * 1024), totalVram / (1024 * 1024), memoryUtil * 100,
                    computeUtil * 100, temp);
            }
        } catch (Throwable t) {
            LC2H.LOGGER.debug("Unable to read GPUMonitor stats: {}", t.getMessage());
        }
        return "";
    }

    static void comprehensiveCleanup() {
        synchronized (MEMORY_LOCK) {
            emergencyCleanup();

            if (MEMORY_SIZES.isEmpty() && currentMemoryBytes == 0) {
                if (org.admany.lc2h.config.ConfigManager.ENABLE_DEBUG_LOGGING) {
                    LC2H.LOGGER.warn("GPU caches empty; clearing Quantified API caches to reduce memory pressure");
                } else {
                    LC2H.LOGGER.debug("GPU caches empty; clearing Quantified API caches to reduce memory pressure");
                }
                clearQuantifiedAPICaches();
            }
        }
    }

	    static void forceGPUMemoryCleanup() {
	        LC2H.LOGGER.info("Forcing GPU memory cleanup via OpenCL context reset");
	
	        clearAllGPUCaches();
	        clearQuantifiedAPICaches();
	
	        try {
	            OpenCLRuntime.destroy();
	            Thread.sleep(100);
	            boolean success = OpenCLRuntime.ensureInitialised();
	            if (success) {
	                LC2H.LOGGER.info("OpenCL context reset and re-initialized; GPU memory should now be free");
	            } else {
	                LC2H.LOGGER.warn("Failed to re-initialize OpenCL context after forced cleanup");
            }
	        } catch (Throwable t) {
	            LC2H.LOGGER.warn("Failed to force GPU memory cleanup: {}", t.getMessage());
	        }
	
	        try {
	            GPUMonitor.getInstance().clearMemoryTracking();
	            LC2H.LOGGER.info("GPUMonitor memory tracking cleared");
	        } catch (Throwable t) {
	            LC2H.LOGGER.debug("Unable to clear GPUMonitor tracking: {}", t.getMessage());
	        }
	    }
}
