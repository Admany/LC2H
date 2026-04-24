package org.admany.lc2h.data.cache;

import org.admany.lc2h.LC2H;

import java.util.Comparator;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class LostCitiesCacheBudgetManager {

    public interface Evictor {
        boolean evict(Object key);
    }

    public static final long DEFAULT_MAX_BYTES = 384L * 1024L * 1024L;
    private static final long MIN_BYTES = 64L * 1024L * 1024L;
    private static final AtomicLong MAX_BYTES = new AtomicLong(sanitizeMaxBytes(
        Long.getLong("lc2h.lostcities.cache.maxBytes", DEFAULT_MAX_BYTES)));
    private static final AtomicLong TTL_MS = new AtomicLong(Math.max(60_000L,
        Long.getLong("lc2h.lostcities.cache.ttlMinutes", 20L) * 60_000L));
    private static final AtomicLong TTL_SWEEP_MS = new AtomicLong(Math.max(10_000L,
        Long.getLong("lc2h.lostcities.cache.ttlSweepMs", 60_000L)));
    private static final int MAX_EVICTIONS_PER_PASS = Math.max(64,
        Integer.getInteger("lc2h.lostcities.cache.maxEvictionsPerPass", 512));
    private static final int MAX_TTL_EVICTIONS_PER_PASS = Math.max(128,
        Integer.getInteger("lc2h.lostcities.cache.maxTtlEvictionsPerPass", 2048));

    private static final ConcurrentHashMap<String, CacheGroup> GROUPS = new ConcurrentHashMap<>();
    private static final AtomicLong TOTAL_BYTES = new AtomicLong(0);
    private static final AtomicBoolean EVICTING = new AtomicBoolean(false);
    private static final AtomicLong LAST_TTL_SWEEP_MS = new AtomicLong(0);

    private static final boolean EVICT_ON_CLIENT = Boolean.parseBoolean(
        System.getProperty("lc2h.lostcities.cache.evictOnClient", "false"));

    private LostCitiesCacheBudgetManager() {
    }

    public static CacheGroup register(String name, long defaultEntryBytes, int minRetain, Evictor evictor) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(evictor, "evictor");
        return GROUPS.computeIfAbsent(name, key -> new CacheGroup(key, defaultEntryBytes, minRetain, evictor));
    }

    public static void recordPut(CacheGroup group, Object key, long entryBytes, boolean inserted) {
        if (group == null || key == null) {
            return;
        }
        group.recordPut(key, entryBytes, inserted);
        if (shouldEnforceBudgetNow()) {
            maybeEvict();
            CombinedCacheBudgetManager.maybeEvict();
        }
    }

    public static void recordAccess(CacheGroup group, Object key) {
        if (group == null || key == null) {
            return;
        }
        group.recordAccess(key);
        if (shouldEnforceBudgetNow()) {
            maybeEvict();
            CombinedCacheBudgetManager.maybeEvict();
        }
    }

    public static void recordRemove(CacheGroup group, Object key) {
        if (group == null || key == null) {
            return;
        }
        group.recordRemove(key);
    }

    public static void clear(CacheGroup group) {
        if (group == null) {
            return;
        }
        long removed = group.clear();
        TOTAL_BYTES.addAndGet(-removed);
    }

    public static long getTotalBytes() {
        return TOTAL_BYTES.get();
    }

    public static long getMaxBytes() {
        return MAX_BYTES.get();
    }

    public static void applyMaxBytes(long bytes) {
        MAX_BYTES.set(sanitizeMaxBytes(bytes));
        if (shouldEnforceBudgetNow()) {
            maybeEvict();
            CombinedCacheBudgetManager.maybeEvict();
        }
    }

    private static boolean shouldEnforceBudgetNow() {
        if (EVICT_ON_CLIENT) {
            return true;
        }
        String threadName = Thread.currentThread().getName();
        if (threadName == null) {
            return true;
        }
        return !"Render thread".equals(threadName) && !"Client thread".equals(threadName);
    }

    public static void applyTtlMinutes(long minutes) {
        long millis = Math.max(60_000L, Math.max(1L, minutes) * 60_000L);
        TTL_MS.set(millis);
    }

    private static void maybeEvict() {
        long now = System.currentTimeMillis();
        maybeEvictExpired(now);

        long limit = MAX_BYTES.get();
        if (TOTAL_BYTES.get() <= limit) {
            return;
        }
        if (!EVICTING.compareAndSet(false, true)) {
            return;
        }
        try {
            int evicted = 0;
            while (TOTAL_BYTES.get() > limit && evicted < MAX_EVICTIONS_PER_PASS) {
                CacheGroup group = pickHeaviestGroup();
                if (group == null) {
                    break;
                }
                if (!group.evictOne()) {
                    break;
                }
                evicted++;
            }
            if (TOTAL_BYTES.get() > limit && LC2H.LOGGER.isDebugEnabled()) {
                LC2H.LOGGER.debug("[LC2H] [LostCities] Cache budget pressure remains: {} MB / {} MB",
                    TOTAL_BYTES.get() / (1024 * 1024), limit / (1024 * 1024));
            }
        } finally {
            EVICTING.set(false);
        }
    }

    private static void maybeEvictExpired(long now) {
        long ttl = TTL_MS.get();
        if (ttl <= 0L) {
            return;
        }
        long last = LAST_TTL_SWEEP_MS.get();
        long sweep = TTL_SWEEP_MS.get();
        if ((now - last) < sweep) {
            return;
        }
        if (!LAST_TTL_SWEEP_MS.compareAndSet(last, now)) {
            return;
        }
        int evicted = 0;
        for (CacheGroup group : GROUPS.values()) {
            evicted += group.evictExpired(now, ttl, MAX_TTL_EVICTIONS_PER_PASS - evicted);
            if (evicted >= MAX_TTL_EVICTIONS_PER_PASS) {
                break;
            }
        }
    }

    private static CacheGroup pickHeaviestGroup() {
        return GROUPS.values().stream()
            .filter(CacheGroup::canEvict)
            .max(Comparator.comparingLong(CacheGroup::bytes))
            .orElse(null);
    }

    public static boolean evictOneGlobal() {
        CacheGroup group = pickHeaviestGroup();
        if (group == null) {
            return false;
        }
        return group.evictOne();
    }

    private static long sanitizeMaxBytes(long bytes) {
        if (bytes <= 0L) {
            return DEFAULT_MAX_BYTES;
        }
        return Math.max(MIN_BYTES, bytes);
    }

    public static final class CacheGroup {
        private final String name;
        private final long defaultEntryBytes;
        private final int minRetain;
        private final Evictor evictor;
        private final AtomicLong bytes = new AtomicLong(0);
        private final AtomicLong entries = new AtomicLong(0);
        private final ConcurrentLinkedDeque<Object> order = new ConcurrentLinkedDeque<>();
        private final AtomicLong orderSize = new AtomicLong(0);
        private final ConcurrentHashMap<Object, Integer> sizes = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<Object, Long> lastAccess = new ConcurrentHashMap<>();

        private CacheGroup(String name, long defaultEntryBytes, int minRetain, Evictor evictor) {
            this.name = name;
            this.defaultEntryBytes = Math.max(1L, defaultEntryBytes);
            this.minRetain = Math.max(0, minRetain);
            this.evictor = evictor;
        }

        private void recordPut(Object key, long entryBytes, boolean inserted) {
            long now = System.currentTimeMillis();
            if (!inserted) {
                recordAccessInternal(key, now);
                return;
            }
            int size = (int) Math.min(Integer.MAX_VALUE, Math.max(1L, entryBytes));
            sizes.put(key, size);
            lastAccess.put(key, now);
            order.addFirst(key);
            orderSize.incrementAndGet();
            trimOrder();
            entries.incrementAndGet();
            bytes.addAndGet(size);
            TOTAL_BYTES.addAndGet(size);
        }

        private void recordAccess(Object key) {
            recordAccessInternal(key, System.currentTimeMillis());
        }

        private void recordAccessInternal(Object key, long now) {
            lastAccess.put(key, now);
            order.addFirst(key);
            orderSize.incrementAndGet();
            trimOrder();
        }

        private void recordRemove(Object key) {
            Integer size = sizes.remove(key);
            lastAccess.remove(key);
            if (size != null) {
                entries.decrementAndGet();
                bytes.addAndGet(-size);
                TOTAL_BYTES.addAndGet(-size);
            }
        }

        private boolean canEvict() {
            return entries.get() > minRetain;
        }

        private boolean evictOne() {
            if (!canEvict()) {
                return false;
            }
            while (true) {
                Object key = order.pollLast();
                if (key != null) {
                    orderSize.decrementAndGet();
                }
                if (key == null) {
                    return false;
                }
                Integer size = sizes.get(key);
                if (size == null) {
                    continue;
                }
                boolean removed = false;
                try {
                    removed = evictor.evict(key);
                } catch (Throwable ignored) {
                }
                if (removed) {
                    sizes.remove(key, size);
                    lastAccess.remove(key);
                    entries.decrementAndGet();
                    bytes.addAndGet(-size);
                    TOTAL_BYTES.addAndGet(-size);
                    return true;
                }
                if (!sizes.remove(key, size)) {
                    continue;
                }
                lastAccess.remove(key);
                entries.decrementAndGet();
                bytes.addAndGet(-size);
                TOTAL_BYTES.addAndGet(-size);
                return true;
            }
        }

        private int evictExpired(long now, long ttlMs, int max) {
            if (ttlMs <= 0L || max <= 0) {
                return 0;
            }
            int evicted = 0;
            while (evicted < max) {
                Object key = order.peekLast();
                if (key == null) {
                    break;
                }
                Long last = lastAccess.get(key);
                if (last == null || (now - last) <= ttlMs) {
                    break;
                }
                if (evictOne()) {
                    evicted++;
                } else {
                    break;
                }
            }
            return evicted;
        }

        private long clear() {
            long removed = bytes.getAndSet(0);
            entries.set(0);
            order.clear();
            orderSize.set(0);
            sizes.clear();
            lastAccess.clear();
            return removed;
        }

        private void trimOrder() {
            long entryCount = entries.get();
            long cap = Math.max(minRetain, entryCount) * 4L + 1024L;
            long current = orderSize.get();
            if (current <= cap) {
                return;
            }
            long target = cap;
            while (current > target) {
                Object removed = order.pollLast();
                if (removed == null) {
                    orderSize.set(0);
                    break;
                }
                current = orderSize.decrementAndGet();
            }
        }

        public long bytes() {
            return bytes.get();
        }

        public long entries() {
            return entries.get();
        }

        public long defaultEntryBytes() {
            return defaultEntryBytes;
        }

        public String name() {
            return name;
        }
    }
}
