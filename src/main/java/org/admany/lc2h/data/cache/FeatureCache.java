package org.admany.lc2h.data.cache;

import org.admany.lc2h.LC2H;

import java.time.Duration;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

public class FeatureCache {

    private static final Map<String, Object> memoryCache = new ConcurrentHashMap<>();
    private static final int MAX_MEMORY_CACHE_SIZE = 500;

    private static final String MEMORY_CACHE = "feature_memory";
    private static final String DISK_CACHE = "feature_disk";

    private static Object cacheManager;
    private static boolean quantifiedAvailable = false;

    static {
        try {
            Class<?> quantifiedApiClass = Class.forName("org.admany.quantified.api.QuantifiedAPI");

            quantifiedApiClass.getMethod("register", String.class).invoke(null, LC2H.MODID);
            cacheManager = quantifiedApiClass.getMethod("getCacheManager").invoke(null);
            quantifiedAvailable = true;
            if (org.admany.lc2h.logging.config.ConfigManager.ENABLE_DEBUG_LOGGING) {
                LC2H.LOGGER.info("[LC2H] FeatureCache: Quantified API integration enabled");
            } else {
                LC2H.LOGGER.debug("[LC2H] FeatureCache: Quantified API integration enabled");
            }
        } catch (Exception e) {
            LC2H.LOGGER.warn("[LC2H] FeatureCache: Quantified API not available, using local memory cache only", e);
            quantifiedAvailable = false;
        }
    }

    public static void put(String key, Object value) {
        put(key, value, false);
    }

    public static void put(String key, Object value, boolean useDiskCache) {
        try {
            if (value == null) return;

            String cacheName = useDiskCache ? DISK_CACHE : MEMORY_CACHE;

            if (quantifiedAvailable && cacheManager != null) {
                Class<?> quantifiedApiClass = Class.forName("org.admany.quantified.api.QuantifiedAPI");
                if (useDiskCache) {
                    try {
                        quantifiedApiClass.getMethod("putCached", String.class, String.class, Object.class, Duration.class, long.class, boolean.class)
                            .invoke(null, cacheName, key, value, Duration.ofHours(24), 10000L, true);
                    } catch (Exception e) {
                        LC2H.LOGGER.debug("[LC2H] Could not use disk cache", e);
                    }
                } else {
                    try {
                        quantifiedApiClass.getMethod("putCached", String.class, String.class, Object.class)
                            .invoke(null, cacheName, key, value);
                    } catch (Exception e) {
                        LC2H.LOGGER.debug("[LC2H] Could not use memory cache", e);
                    }
                }
            }

            memoryCache.put(key, value);
            if (memoryCache.size() > MAX_MEMORY_CACHE_SIZE) {
                String oldestKey = memoryCache.keySet().iterator().next();
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
            Object value = memoryCache.get(key);
            if (value != null) {
                return value;
            }

            if (quantifiedAvailable && cacheManager != null) {
                Class<?> quantifiedApiClass = Class.forName("org.admany.quantified.api.QuantifiedAPI");
                String cacheName = checkDiskCache ? DISK_CACHE : MEMORY_CACHE;
                try {
                    if (checkDiskCache) {
                        value = quantifiedApiClass.getMethod("getCached", String.class, String.class, Supplier.class, Duration.class, long.class, boolean.class)
                            .invoke(null, new Object[]{cacheName, key, (Supplier<Object>) () -> null, Duration.ofHours(24), 10000L, true});
                    } else {
                        value = quantifiedApiClass.getMethod("getCached", String.class, String.class, Supplier.class)
                            .invoke(null, new Object[]{cacheName, key, (Supplier<Object>) () -> null});
                    }
                } catch (Exception e) {
                    LC2H.LOGGER.debug("[LC2H] Could not retrieve from Quantified cache", e);
                }
                if (value != null) {
                    memoryCache.put(key, value);
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

            if (quantifiedAvailable && cacheManager != null) {
                if (clearDiskCache) {
                    cacheManager.getClass().getMethod("clearAllCaches").invoke(cacheManager);
                    quantifiedEntries = (Long) cacheManager.getClass().getMethod("getTotalCacheEntryCount").invoke(cacheManager);
                } else {
                    cacheManager.getClass().getMethod("clearCache", String.class).invoke(cacheManager, MEMORY_CACHE);
                    quantifiedEntries = (Long) cacheManager.getClass().getMethod("getCacheEntryCount", String.class).invoke(cacheManager, MEMORY_CACHE);
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
            if (memoryCache.size() > MAX_MEMORY_CACHE_SIZE / 2) {
                int toRemove = memoryCache.size() - (MAX_MEMORY_CACHE_SIZE / 2);
                for (int i = 0; i < toRemove && !memoryCache.isEmpty(); i++) {
                    String oldestKey = memoryCache.keySet().iterator().next();
                    memoryCache.remove(oldestKey);
                    localEntries++;
                }
            }

            if (quantifiedAvailable && cacheManager != null) {
                quantifiedEntries = (Long) cacheManager.getClass().getMethod("clearOldCaches", long.class).invoke(cacheManager, maxAgeMs);
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

    public static long getMemoryUsageMB() {
        if (quantifiedAvailable && cacheManager != null) {
            try {
                return (Long) cacheManager.getClass().getMethod("getTotalCacheSizeMB").invoke(cacheManager);
            } catch (Exception e) {
                LC2H.LOGGER.debug("[LC2H] Could not get memory usage from cache manager", e);
            }
        }
        return (memoryCache.size() * 256L) / (1024 * 1024);
    }

    public static boolean isMemoryPressureHigh() {
        if (quantifiedAvailable && cacheManager != null) {
            try {
                return (Boolean) cacheManager.getClass().getMethod("isMemoryPressureHigh").invoke(cacheManager);
            } catch (Exception e) {
                LC2H.LOGGER.debug("[LC2H] Could not check memory pressure from cache manager", e);
            }
        }
        return memoryCache.size() > MAX_MEMORY_CACHE_SIZE * 0.9;
    }

    public static void triggerMemoryPressureCleanup() {
        if (quantifiedAvailable && cacheManager != null) {
            try {
                cacheManager.getClass().getMethod("triggerMemoryPressureCleanup").invoke(cacheManager);
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

        if (quantifiedAvailable && cacheManager != null) {
            try {
                @SuppressWarnings("unchecked")
                Set<String> cacheNames = (Set<String>) cacheManager.getClass().getMethod("getCacheNames").invoke(cacheManager);
                if (!cacheNames.isEmpty()) {
                    sb.append(", caches=[");
                    for (String name : cacheNames) {
                        try {
                            long entryCount = (Long) cacheManager.getClass().getMethod("getCacheEntryCount", String.class).invoke(cacheManager, name);
                            sb.append(name).append("(").append(entryCount).append("),");
                        } catch (Exception e) {
                            sb.append(name).append("(?),");
                        }
                    }
                    sb.setLength(sb.length() - 1); // Remove last comma
                    sb.append("]");
                }
            } catch (Exception e) {
                LC2H.LOGGER.debug("[LC2H] Could not get cache names from cache manager", e);
            }
        }

        LC2H.LOGGER.info(sb.toString());
    }

    public static CacheStats snapshot() {
        long local = memoryCache.size();
        Long quantified = null;

        if (quantifiedAvailable && cacheManager != null) {
            try {
                quantified = (Long) cacheManager.getClass().getMethod("getTotalCacheEntryCount").invoke(cacheManager);
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
                    Class<?> quantifiedApiClass = Class.forName("org.admany.quantified.api.QuantifiedAPI");
                    Boolean isConnected = (Boolean) quantifiedApiClass.getMethod("isConnected", String.class).invoke(null, LC2H.MODID);
                    if (isConnected != null && isConnected) {
                        quantifiedApiClass.getMethod("disconnect", String.class).invoke(null, LC2H.MODID);
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

            cacheManager = null;
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
                    Class<?> quantifiedApiClass = Class.forName("org.admany.quantified.api.QuantifiedAPI");
                    quantifiedApiClass.getMethod("disconnect", String.class).invoke(null, LC2H.MODID);
                    LC2H.LOGGER.info("[LC2H] FeatureCache: Force disconnected from Quantified API");
                } catch (Exception e) {
                    LC2H.LOGGER.debug("[LC2H] FeatureCache: Quantified API already shut down", e);
                }
            }

            cacheManager = null;
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
}