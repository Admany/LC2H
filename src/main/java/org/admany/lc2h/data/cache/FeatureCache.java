package org.admany.lc2h.data.cache;

import org.admany.lc2h.LC2H;
import org.admany.quantified.api.QuantifiedAPI;
import org.admany.quantified.core.common.cache.CacheManager;
import org.admany.quantified.core.common.cache.interfaces.ThreadSafeCache;

import java.time.Duration;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class FeatureCache {

    private static final Map<String, LocalEntry> memoryCache = new ConcurrentHashMap<>();
    private static final int MAX_MEMORY_CACHE_SIZE = Math.max(500,
        Integer.getInteger("lc2h.featureCache.localMaxEntries", 2000));
    private static final long LOCAL_TTL_MS = Math.max(1_000L,
        Long.getLong("lc2h.featureCache.localTtlMs", TimeUnit.MINUTES.toMillis(20)));
    private static final long QUANTIFIED_MAX_ENTRIES = Math.max(1L,
        Long.getLong("lc2h.featureCache.maxEntries", 50_000L));
    private static final Duration MEMORY_TTL = Duration.ofMinutes(Math.max(1L,
        Long.getLong("lc2h.featureCache.memoryTtlMinutes", 20L)));
    private static final Duration DISK_TTL = Duration.ofHours(Math.max(1L,
        Long.getLong("lc2h.featureCache.diskTtlHours", 24L)));

    private static final String MEMORY_CACHE = "feature_memory";
    private static final String DISK_CACHE = "feature_disk";

    private static ThreadSafeCache<String, Object> quantifiedMemoryCache;
    private static ThreadSafeCache<String, Object> quantifiedDiskCache;
    private static boolean quantifiedAvailable = false;

    static {
        try {
            QuantifiedAPI.register(LC2H.MODID);
            initQuantifiedCaches();
            quantifiedAvailable = true;
            if (org.admany.lc2h.config.ConfigManager.ENABLE_DEBUG_LOGGING) {
                LC2H.LOGGER.info("[LC2H] FeatureCache: Quantified API integration enabled");
            } else {
                LC2H.LOGGER.debug("[LC2H] FeatureCache: Quantified API integration enabled");
            }
        } catch (Exception e) {
            LC2H.LOGGER.warn("[LC2H] FeatureCache: Quantified API not available, using local memory cache only", e);
            quantifiedAvailable = false;
        }
    }

    private static boolean ensureQuantifiedReady() {
        if (!quantifiedAvailable) {
            return false;
        }
        try {
            QuantifiedAPI.register(LC2H.MODID);
            if (quantifiedMemoryCache == null || quantifiedDiskCache == null) {
                initQuantifiedCaches();
            }
            return true;
        } catch (Throwable t) {
            quantifiedAvailable = false;
            quantifiedMemoryCache = null;
            quantifiedDiskCache = null;
            LC2H.LOGGER.debug("[LC2H] FeatureCache: Quantified API unavailable for this thread", t);
            return false;
        }
    }

    public static void put(String key, Object value) {
        put(key, value, false);
    }

    public static void put(String key, Object value, boolean useDiskCache) {
        try {
            if (value == null) return;

            long now = System.currentTimeMillis();

            if (ensureQuantifiedReady()) {
                if (useDiskCache) {
                    try {
                        if (quantifiedDiskCache != null) {
                            quantifiedDiskCache.put(key, value);
                        }
                    } catch (Exception e) {
                        LC2H.LOGGER.debug("[LC2H] Could not use disk cache", e);
                    }
                } else {
                    try {
                        if (quantifiedMemoryCache != null) {
                            quantifiedMemoryCache.put(key, value);
                        }
                    } catch (Exception e) {
                        LC2H.LOGGER.debug("[LC2H] Could not use memory cache", e);
                    }
                }
            }

            memoryCache.put(key, new LocalEntry(value, now));
            pruneExpiredLocalEntries(now);
            if (memoryCache.size() > MAX_MEMORY_CACHE_SIZE) {
                String oldestKey = findOldestLocalKey();
                memoryCache.remove(oldestKey);
                LC2H.LOGGER.debug("[LC2H] Evicted memory cache entry: " + oldestKey);
            }
        } catch (Exception e) {
            LC2H.LOGGER.error("[LC2H] Error caching feature: " + e.getMessage(), e);
        }
    }

    public static Object get(String key) {
        return get(key, false);
    }

    public static Object get(String key, boolean checkDiskCache) {
        try {
            long now = System.currentTimeMillis();
            LocalEntry local = memoryCache.get(key);
            if (local != null) {
                if ((now - local.lastAccessMs) <= LOCAL_TTL_MS) {
                    local.lastAccessMs = now;
                    return local.value;
                }
                memoryCache.remove(key, local);
            }

            Object value = null;
            if (ensureQuantifiedReady()) {
                try {
                    if (checkDiskCache) {
                        if (quantifiedDiskCache != null) {
                            value = quantifiedDiskCache.getIfPresent(key);
                        }
                    } else {
                        if (quantifiedMemoryCache != null) {
                            value = quantifiedMemoryCache.getIfPresent(key);
                        }
                    }
                } catch (Exception e) {
                    LC2H.LOGGER.debug("[LC2H] Could not retrieve from Quantified cache", e);
                }
                if (value != null) {
                    memoryCache.put(key, new LocalEntry(value, now));
                    return value;
                }
            }

            return null;
        } catch (Exception e) {
            LC2H.LOGGER.error("[LC2H] Error retrieving from cache: " + e.getMessage(), e);
            return null;
        }
    }

    public static CacheStats clear() {
        return clear(false);
    }

    public static CacheStats clear(boolean clearDiskCache) {
        long localEntries = memoryCache.size();
        Long quantifiedEntries = null;

        try {
            memoryCache.clear();

            if (ensureQuantifiedReady()) {
                if (clearDiskCache) {
                    if (quantifiedMemoryCache != null) {
                        quantifiedMemoryCache.invalidateAll();
                    }
                    if (quantifiedDiskCache != null) {
                        quantifiedDiskCache.invalidateAll();
                    }
                    quantifiedEntries = snapshotQuantifiedEntries();
                } else {
                    if (quantifiedMemoryCache != null) {
                        quantifiedMemoryCache.invalidateAll();
                    }
                    quantifiedEntries = cacheSize(quantifiedMemoryCache);
                }
            }

            LC2H.LOGGER.info("[LC2H] Feature cache cleared (local=" + localEntries +
                (quantifiedEntries != null ? ", quantified=" + quantifiedEntries : "") +
                (clearDiskCache ? ", disk=true" : "") + ")");
        } catch (Exception e) {
            LC2H.LOGGER.error("[LC2H] Error clearing cache: " + e.getMessage(), e);
        }

        return new CacheStats(localEntries, quantifiedEntries, clearDiskCache);
    }

    public static CacheStats clearOldEntries(long maxAgeMs) {
        long localEntries = 0;
        Long quantifiedEntries = null;

        try {
            localEntries = pruneLocalEntriesByAge(maxAgeMs);

            if (ensureQuantifiedReady()) {
                quantifiedEntries = snapshotQuantifiedEntries();
            }

            if (localEntries > 0 || (quantifiedEntries != null && quantifiedEntries > 0)) {
                LC2H.LOGGER.info("[LC2H] Cleared old cache entries (local=" + localEntries +
                    (quantifiedEntries != null ? ", quantified=" + quantifiedEntries : "") + ")");
            }
        } catch (Exception e) {
            LC2H.LOGGER.error("[LC2H] Error clearing old cache entries: " + e.getMessage(), e);
        }

        return new CacheStats(localEntries, quantifiedEntries, false);
    }

    public static int getSize() {
        CacheStats snapshot = snapshot();
        long total = snapshot.localEntries();
        if (snapshot.quantifiedEntries() != null) {
            total += snapshot.quantifiedEntries();
        }
        return (int) Math.min(Integer.MAX_VALUE, total);
    }

    public static long getLocalEntryCount() {
        pruneExpiredLocalEntries(System.currentTimeMillis());
        return memoryCache.size();
    }

    public static long getLocalMemoryBytes() {
        pruneExpiredLocalEntries(System.currentTimeMillis());
        return memoryCache.size() * 256L;
    }

    public static long getMemoryUsageMB() {
        if (ensureQuantifiedReady()) {
            try {
                return CacheManager.getTotalCacheSize() / (1024L * 1024L);
            } catch (Exception e) {
                LC2H.LOGGER.debug("[LC2H] Could not get memory usage from cache manager", e);
            }
        }
        pruneExpiredLocalEntries(System.currentTimeMillis());
        return (memoryCache.size() * 256L) / (1024 * 1024);
    }

    public static boolean isMemoryPressureHigh() {
        pruneExpiredLocalEntries(System.currentTimeMillis());
        return memoryCache.size() > MAX_MEMORY_CACHE_SIZE * 0.9;
    }

    public static void triggerMemoryPressureCleanup() {
        if (ensureQuantifiedReady()) {
            try {
                if (quantifiedMemoryCache != null) {
                    quantifiedMemoryCache.invalidateAll();
                }
                if (quantifiedDiskCache != null) {
                    quantifiedDiskCache.invalidateAll();
                }
            } catch (Exception e) {
                LC2H.LOGGER.debug("[LC2H] Could not trigger memory pressure cleanup", e);
            }
        } else {
            clearOldEntries(300_000);
        }
    }

    public static void logStats() {
        CacheStats snapshot = snapshot();
        StringBuilder sb = new StringBuilder("Feature cache stats: local=")
            .append(snapshot.localEntries())
            .append(", max=")
            .append(MAX_MEMORY_CACHE_SIZE);

        if (snapshot.quantifiedEntries() != null) {
            sb.append(", quantified=").append(snapshot.quantifiedEntries());
        }

        sb.append(", memoryMB=").append(getMemoryUsageMB());
        sb.append(", pressure=").append(isMemoryPressureHigh() ? "HIGH" : "NORMAL");

        if (ensureQuantifiedReady()) {
            try {
                Set<String> cacheNames = CacheManager.inventory().statsByName().keySet();
                if (!cacheNames.isEmpty()) {
                    sb.append(", caches=[");
                    for (String name : cacheNames) {
                        ThreadSafeCache<String, Object> cache = CacheManager.lookup(name);
                        if (cache != null) {
                            sb.append(name).append("(").append(cacheSize(cache)).append("),");
                        } else {
                            sb.append(name).append("(?),");
                        }
                    }
                    sb.setLength(sb.length() - 1);
                    sb.append("]");
                }
            } catch (Exception e) {
                LC2H.LOGGER.debug("[LC2H] Could not get cache names from cache manager", e);
            }
        }

        LC2H.LOGGER.info(sb.toString());
    }

    public static CacheStats snapshot() {
        pruneExpiredLocalEntries(System.currentTimeMillis());
        long local = memoryCache.size();
        Long quantified = null;

        if (ensureQuantifiedReady()) {
            try {
                quantified = snapshotQuantifiedEntries();
            } catch (Exception e) {
                LC2H.LOGGER.debug("[LC2H] Could not get cache entry count from cache manager", e);
            }
        }

        return new CacheStats(local, quantified, false);
    }

    public static void shutdown() {
        try {
            LC2H.LOGGER.info("[LC2H] FeatureCache: Starting shutdown sequence");

            clear(true);
            LC2H.LOGGER.debug("[LC2H] FeatureCache: Caches cleared");

            if (quantifiedAvailable) {
                try {
                    boolean isConnected = QuantifiedAPI.isConnected(LC2H.MODID);
                    if (isConnected) {
                        QuantifiedAPI.disconnect(LC2H.MODID);
                        LC2H.LOGGER.info("[LC2H] FeatureCache: Disconnected from Quantified API");
                    } else {
                        LC2H.LOGGER.debug("[LC2H] FeatureCache: Already disconnected from Quantified API");
                    }
                } catch (Exception e) {
                    LC2H.LOGGER.warn("[LC2H] FeatureCache: Error disconnecting from Quantified API", e);
                }
            }

            memoryCache.clear();
            LC2H.LOGGER.debug("[LC2H] FeatureCache: Local cache cleared");

            quantifiedMemoryCache = null;
            quantifiedDiskCache = null;
            quantifiedAvailable = false;

            LC2H.LOGGER.info("[LC2H] FeatureCache: Shutdown complete");
        } catch (Exception e) {
            LC2H.LOGGER.error("[LC2H] FeatureCache: Error during shutdown", e);
        }
    }

    public static void forceShutdown() {
        try {
            LC2H.LOGGER.info("[LC2H] FeatureCache: Force shutdown initiated");

            memoryCache.clear();

            if (quantifiedAvailable) {
                try {
                    QuantifiedAPI.disconnect(LC2H.MODID);
                    LC2H.LOGGER.info("[LC2H] FeatureCache: Force disconnected from Quantified API");
                } catch (Exception e) {
                    LC2H.LOGGER.debug("[LC2H] FeatureCache: Quantified API already shut down", e);
                }
            }

            quantifiedMemoryCache = null;
            quantifiedDiskCache = null;
            quantifiedAvailable = false;

            LC2H.LOGGER.info("[LC2H] FeatureCache: Force shutdown complete");
        } catch (Exception e) {
            LC2H.LOGGER.error("[LC2H] FeatureCache: Error during force shutdown", e);
        }
    }

    public record CacheStats(long localEntries, Long quantifiedEntries, boolean diskCacheCleared) {
        public OptionalLong totalQuantifiedSize() {
            if (quantifiedEntries == null) {
                return OptionalLong.empty();
            }
            return OptionalLong.of(quantifiedEntries);
        }
    }

    public static void pruneExpiredEntries() {
        pruneExpiredLocalEntries(System.currentTimeMillis());
    }

    private static void pruneExpiredLocalEntries(long now) {
        if (LOCAL_TTL_MS <= 0L || memoryCache.isEmpty()) {
            return;
        }
        for (Map.Entry<String, LocalEntry> entry : memoryCache.entrySet()) {
            LocalEntry local = entry.getValue();
            if (local == null) {
                continue;
            }
            if ((now - local.lastAccessMs) > LOCAL_TTL_MS) {
                memoryCache.remove(entry.getKey(), local);
            }
        }
    }

    private static long pruneLocalEntriesByAge(long maxAgeMs) {
        if (maxAgeMs <= 0L) {
            return 0L;
        }
        long now = System.currentTimeMillis();
        long removed = 0L;
        for (Map.Entry<String, LocalEntry> entry : memoryCache.entrySet()) {
            LocalEntry local = entry.getValue();
            if (local == null) {
                continue;
            }
            if ((now - local.lastAccessMs) > maxAgeMs) {
                if (memoryCache.remove(entry.getKey(), local)) {
                    removed++;
                }
            }
        }
        return removed;
    }

    private static String findOldestLocalKey() {
        long oldest = Long.MAX_VALUE;
        String oldestKey = null;
        for (Map.Entry<String, LocalEntry> entry : memoryCache.entrySet()) {
            LocalEntry local = entry.getValue();
            if (local == null) {
                continue;
            }
            if (local.lastAccessMs < oldest) {
                oldest = local.lastAccessMs;
                oldestKey = entry.getKey();
            }
        }
        if (oldestKey == null) {
            return memoryCache.keySet().iterator().next();
        }
        return oldestKey;
    }

    private static void initQuantifiedCaches() {
        ThreadSafeCache<String, Object> memory = CacheManager.lookup(MEMORY_CACHE);
        if (memory == null) {
            memory = CacheManager.register(
                MEMORY_CACHE,
                QUANTIFIED_MAX_ENTRIES,
                MEMORY_TTL,
                true,
                false,
                false
            );
        }
        ThreadSafeCache<String, Object> disk = CacheManager.lookup(DISK_CACHE);
        if (disk == null) {
            disk = CacheManager.register(
                DISK_CACHE,
                QUANTIFIED_MAX_ENTRIES,
                DISK_TTL,
                true,
                true,
                true
            );
        }
        quantifiedMemoryCache = memory;
        quantifiedDiskCache = disk;
    }

    private static long cacheSize(ThreadSafeCache<String, Object> cache) {
        if (cache == null) {
            return 0L;
        }
        try {
            return cache.size();
        } catch (Throwable t) {
            return 0L;
        }
    }

    private static Long snapshotQuantifiedEntries() {
        long total = 0L;
        total += cacheSize(quantifiedMemoryCache);
        total += cacheSize(quantifiedDiskCache);
        return total;
    }

    private static final class LocalEntry {
        private final Object value;
        private volatile long lastAccessMs;

        private LocalEntry(Object value, long lastAccessMs) {
            this.value = value;
            this.lastAccessMs = lastAccessMs;
        }
    }
}
