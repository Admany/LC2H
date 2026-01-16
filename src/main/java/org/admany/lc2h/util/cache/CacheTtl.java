package org.admany.lc2h.util.cache;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class CacheTtl {
    private CacheTtl() {
    }

    public static <K> boolean isFresh(ConcurrentHashMap<K, Long> map, K key, long ttlMs, long nowMs) {
        if (map == null || key == null) {
            return false;
        }
        Long ts = map.get(key);
        if (ts == null) {
            return false;
        }
        if (ttlMs <= 0) {
            return true;
        }
        if ((nowMs - ts) <= ttlMs) {
            return true;
        }
        map.remove(key, ts);
        return false;
    }

    public static <K> boolean markIfFresh(ConcurrentHashMap<K, Long> map, K key, long ttlMs, long nowMs) {
        if (map == null || key == null) {
            return false;
        }
        while (true) {
            Long existing = map.get(key);
            if (existing != null) {
                if (ttlMs <= 0 || (nowMs - existing) <= ttlMs) {
                    return true;
                }
                map.remove(key, existing);
            }
            if (map.putIfAbsent(key, nowMs) == null) {
                return false;
            }
        }
    }

    public static <K> int pruneExpired(ConcurrentHashMap<K, Long> map, long ttlMs, int maxScan, long nowMs) {
        if (map == null || map.isEmpty() || ttlMs <= 0) {
            return 0;
        }
        int removed = 0;
        int scanned = 0;
        for (Map.Entry<K, Long> entry : map.entrySet()) {
            if (maxScan > 0 && scanned >= maxScan) {
                break;
            }
            scanned++;
            Long ts = entry.getValue();
            if (ts != null && (nowMs - ts) > ttlMs) {
                if (map.remove(entry.getKey(), ts)) {
                    removed++;
                }
            }
        }
        return removed;
    }
}
