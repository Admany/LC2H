package org.admany.lc2h.worldgen.lostcities;

import org.admany.lc2h.config.ConfigManager;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.admany.lc2h.dev.diagnostics.Lc2hTimingRegistry;

public final class LostCitiesGenerationLocks {

    private static final int STRIPES = clampPow2(Integer.getInteger("lc2h.lostcities.genLockStripes", 64), 1, 4096);
    private static final int SHIFT = Math.max(0, Integer.getInteger("lc2h.lostcities.genLockShift", 2));

    private static final ReentrantLock[] LOCKS = createLocks(STRIPES);
    private static final LongAdder ACQUISITIONS = new LongAdder();
    private static final LongAdder CONTENTIONS = new LongAdder();
    private static final LongAdder WAIT_NS = new LongAdder();
    private static final LongAdder HOLD_NS = new LongAdder();
    private static final AtomicInteger ACTIVE_HOLDERS = new AtomicInteger();
    private static final AtomicLong LAST_ACTIVITY_NANOS = new AtomicLong(System.nanoTime());
    private static final Lc2hTimingRegistry.TimingHandle WAIT_TIMING = Lc2hTimingRegistry.bucket("lostcities.generation_lock_wait");
    private static final Lc2hTimingRegistry.TimingHandle HOLD_TIMING = Lc2hTimingRegistry.bucket("lostcities.generation_lock_hold");

    private LostCitiesGenerationLocks() {
    }

    public static final class LockToken implements AutoCloseable {
        private final ReentrantLock lock;
        private final boolean acquired;
        private final long holdStartNs;

        private LockToken(ReentrantLock lock, boolean acquired) {
            this.lock = lock;
            this.acquired = acquired;
            this.holdStartNs = acquired ? System.nanoTime() : 0L;
        }

        @Override
        public void close() {
            if (acquired && lock != null) {
                try {
                    recordHold(System.nanoTime() - holdStartNs);
                } finally {
                    try {
                        lock.unlock();
                    } finally {
                        ACTIVE_HOLDERS.decrementAndGet();
                        LAST_ACTIVITY_NANOS.set(System.nanoTime());
                    }
                }
            }
        }
    }

    public static boolean isEnabled() {
        String override = System.getProperty("lc2h.lostcities.genLock");
        if (override != null) {
            return Boolean.parseBoolean(override);
        }
        return ConfigManager.ENABLE_LOSTCITIES_GENERATION_LOCK;
    }

    public static void withChunkStripeLock(ResourceKey<Level> dimension, int chunkX, int chunkZ, Runnable action) {
        if (!isEnabled() || action == null) {
            if (action != null) action.run();
            return;
        }

        int gx = chunkX >> SHIFT;
        int gz = chunkZ >> SHIFT;
        int dimHash = dimension != null ? dimension.location().hashCode() : 0;
        int h = mix(dimHash ^ (gx * 73471) ^ (gz * 91283));
        ReentrantLock lock = LOCKS[h & (STRIPES - 1)];

        long holdStartNs = acquire(lock);
        ACTIVE_HOLDERS.incrementAndGet();
        LAST_ACTIVITY_NANOS.set(System.nanoTime());
        try {
            action.run();
        } finally {
            try {
                recordHold(System.nanoTime() - holdStartNs);
            } finally {
                try {
                    lock.unlock();
                } finally {
                    ACTIVE_HOLDERS.decrementAndGet();
                    LAST_ACTIVITY_NANOS.set(System.nanoTime());
                }
            }
        }
    }

    public static LockToken acquireChunkStripeLock(ResourceKey<Level> dimension, int chunkX, int chunkZ) {
        if (!isEnabled()) {
            return new LockToken(null, false);
        }

        int gx = chunkX >> SHIFT;
        int gz = chunkZ >> SHIFT;
        int dimHash = dimension != null ? dimension.location().hashCode() : 0;
        int h = mix(dimHash ^ (gx * 73471) ^ (gz * 91283));
        ReentrantLock lock = LOCKS[h & (STRIPES - 1)];
        acquire(lock);
        ACTIVE_HOLDERS.incrementAndGet();
        LAST_ACTIVITY_NANOS.set(System.nanoTime());
        return new LockToken(lock, true);
    }

    public static int activeHolders() {
        return ACTIVE_HOLDERS.get();
    }

    public static long nanosSinceActivity() {
        return Math.max(0L, System.nanoTime() - LAST_ACTIVITY_NANOS.get());
    }

    private static long acquire(ReentrantLock lock) {
        long waitStartNs = System.nanoTime();
        if (!lock.tryLock()) {
            CONTENTIONS.increment();
            lock.lock();
        }
        long waitedNs = System.nanoTime() - waitStartNs;
        ACQUISITIONS.increment();
        WAIT_NS.add(waitedNs);
        WAIT_TIMING.record(waitedNs);
        return System.nanoTime();
    }

    private static void recordHold(long holdNs) {
        if (holdNs <= 0L) {
            return;
        }
        HOLD_NS.add(holdNs);
        HOLD_TIMING.record(holdNs);
    }

    public static String diagnostics() {
        long acquisitions = ACQUISITIONS.sum();
        return "enabled=" + isEnabled()
            + " stripes=" + STRIPES
            + " shift=" + SHIFT
            + " acquisitions=" + acquisitions
            + " active=" + ACTIVE_HOLDERS.get()
            + " contended=" + CONTENTIONS.sum()
            + " avgWaitUs=" + formatMicros(WAIT_NS.sum(), acquisitions)
            + " avgHoldMs=" + formatMillis(HOLD_NS.sum(), acquisitions);
    }

    private static String formatMicros(long totalNs, long count) {
        return String.format(java.util.Locale.ROOT, "%.3f", count <= 0L ? 0.0D : totalNs / (double) count / 1_000.0D);
    }

    private static String formatMillis(long totalNs, long count) {
        return String.format(java.util.Locale.ROOT, "%.3f", count <= 0L ? 0.0D : totalNs / (double) count / 1_000_000.0D);
    }

    private static int mix(int x) {
        x ^= (x >>> 16);
        x *= 0x7feb352d;
        x ^= (x >>> 15);
        x *= 0x846ca68b;
        x ^= (x >>> 16);
        return x;
    }

    private static ReentrantLock[] createLocks(int stripes) {
        ReentrantLock[] locks = new ReentrantLock[stripes];
        for (int i = 0; i < stripes; i++) {
            locks[i] = new ReentrantLock();
        }
        return locks;
    }

    private static int clampPow2(int value, int min, int max) {
        int clamped = Math.max(min, Math.min(max, value));
        int pow2 = 1;
        while (pow2 < clamped) {
            pow2 <<= 1;
        }
        return pow2;
    }
}
