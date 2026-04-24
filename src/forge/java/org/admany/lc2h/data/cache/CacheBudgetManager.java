package org.admany.lc2h.data.cache;

import org.admany.lc2h.LC2H;

import java.util.Comparator;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class CacheBudgetManager {

    public interface Evictor {

        boolean evict(Object key);
    }

    public static final long DEFAULT_MAX_BYTES = 10L * 1024L * 1024L;
    private static final long MIN_BYTES = 8L * 1024L * 1024L;
    private static final AtomicLong MAX_BYTES = new AtomicLong(sanitizeMaxBytes(
        Long.getLong("lc2h.cache.maxBytes", DEFAULT_MAX_BYTES)));
    private static final int MAX_EVICTIONS_PER_PASS = Math.max(64,
        Integer.getInteger("lc2h.cache.maxEvictionsPerPass", 512));

    private static final ConcurrentHashMap<String, CacheGroup> GROUPS = new ConcurrentHashMap<>();
    private static final AtomicLong TOTAL_BYTES = new AtomicLong(0);
    private static final AtomicBoolean EVICTING = new AtomicBoolean(false);

    private CacheBudgetManager() {
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
        maybeEvict();
        CombinedCacheBudgetManager.maybeEvict();
    }

    public static void recordAccess(CacheGroup group, Object key) {
        if (group == null || key == null) {
            return;
        }
        group.recordAccess(key);
        CombinedCacheBudgetManager.maybeEvict();
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
        maybeEvict();
        CombinedCacheBudgetManager.maybeEvict();
    }

    private static void maybeEvict() {
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
                LC2H.LOGGER.debug("[LC2H] Cache budget pressure remains: {} MB / {} MB",
                    TOTAL_BYTES.get() / (1024 * 1024), limit / (1024 * 1024));
            }
        } finally {
            EVICTING.set(false);
        }
    }

    private static CacheGroup pickHeaviestGroup() {
        return GROUPS.values().stream()
            .filter(group -> group.canEvict())
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

        private CacheGroup(String name, long defaultEntryBytes, int minRetain, Evictor evictor) {
            this.name = name;
            this.defaultEntryBytes = Math.max(1L, defaultEntryBytes);
            this.minRetain = Math.max(0, minRetain);
            this.evictor = evictor;
        }

        private void recordPut(Object key, long entryBytes, boolean inserted) {
            if (!inserted) {
                recordAccess(key);
                return;
            }
            int size = (int) Math.min(Integer.MAX_VALUE, Math.max(1L, entryBytes));
            sizes.put(key, size);
            order.addFirst(key);
            orderSize.incrementAndGet();
            trimOrder();
            entries.incrementAndGet();
            bytes.addAndGet(size);
            TOTAL_BYTES.addAndGet(size);
        }

        private void recordAccess(Object key) {
            order.addFirst(key);
            orderSize.incrementAndGet();
            trimOrder();
        }

        private void recordRemove(Object key) {
            Integer size = sizes.remove(key);
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
                    entries.decrementAndGet();
                    bytes.addAndGet(-size);
                    TOTAL_BYTES.addAndGet(-size);
                    return true;
                }
                if (!sizes.remove(key, size)) {
                    continue;
                }
                entries.decrementAndGet();
                bytes.addAndGet(-size);
                TOTAL_BYTES.addAndGet(-size);
                return true;
            }
        }

        private long clear() {
            long removed = bytes.getAndSet(0);
            entries.set(0);
            order.clear();
            orderSize.set(0);
            sizes.clear();
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
