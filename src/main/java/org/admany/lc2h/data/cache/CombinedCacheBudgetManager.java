package org.admany.lc2h.data.cache;

import org.admany.lc2h.LC2H;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class CombinedCacheBudgetManager {

    private static final AtomicBoolean ENFORCE = new AtomicBoolean(true);
    private static final AtomicLong MAX_BYTES = new AtomicLong(512L * 1024L * 1024L);
    private static final AtomicBoolean EVICTING = new AtomicBoolean(false);
    private static final int MAX_EVICTIONS_PER_PASS = Math.max(64,
        Integer.getInteger("lc2h.cache.combined.maxEvictionsPerPass", 512));

    private CombinedCacheBudgetManager() {
    }

    public static void apply(boolean enforce, long maxBytes) {
        ENFORCE.set(enforce);
        if (maxBytes > 0) {
            MAX_BYTES.set(maxBytes);
        }
        maybeEvict();
    }

    public static void maybeEvict() {
        if (!ENFORCE.get()) {
            return;
        }
        long total = CacheBudgetManager.getTotalBytes() + LostCitiesCacheBudgetManager.getTotalBytes();
        long limit = MAX_BYTES.get();
        if (total <= limit) {
            return;
        }
        if (!EVICTING.compareAndSet(false, true)) {
            return;
        }
        try {
            int evicted = 0;
            while (total > limit && evicted < MAX_EVICTIONS_PER_PASS) {
                boolean removed = evictFromHeaviest();
                if (!removed) {
                    break;
                }
                evicted++;
                total = CacheBudgetManager.getTotalBytes() + LostCitiesCacheBudgetManager.getTotalBytes();
            }
            if (total > limit && LC2H.LOGGER.isDebugEnabled()) {
                LC2H.LOGGER.debug("[LC2H] Combined cache budget still high: {} MB / {} MB",
                    total / (1024 * 1024), limit / (1024 * 1024));
            }
        } finally {
            EVICTING.set(false);
        }
    }

    private static boolean evictFromHeaviest() {
        long lcBytes = CacheBudgetManager.getTotalBytes();
        long lcBytesLost = LostCitiesCacheBudgetManager.getTotalBytes();
        if (lcBytes >= lcBytesLost) {
            if (CacheBudgetManager.evictOneGlobal()) {
                return true;
            }
            return LostCitiesCacheBudgetManager.evictOneGlobal();
        }
        if (LostCitiesCacheBudgetManager.evictOneGlobal()) {
            return true;
        }
        return CacheBudgetManager.evictOneGlobal();
    }
}
