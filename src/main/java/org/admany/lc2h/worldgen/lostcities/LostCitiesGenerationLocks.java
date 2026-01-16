package org.admany.lc2h.worldgen.lostcities;

import org.admany.lc2h.config.ConfigManager;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.concurrent.locks.ReentrantLock;

public final class LostCitiesGenerationLocks {

    private static final int STRIPES = clampPow2(Integer.getInteger("lc2h.lostcities.genLockStripes", 64), 1, 4096);
    private static final int SHIFT = Math.max(0, Integer.getInteger("lc2h.lostcities.genLockShift", 2));

    private static final ReentrantLock[] LOCKS = createLocks(STRIPES);

    private LostCitiesGenerationLocks() {
    }

    public static final class LockToken implements AutoCloseable {
        private final ReentrantLock lock;
        private final boolean acquired;

        private LockToken(ReentrantLock lock, boolean acquired) {
            this.lock = lock;
            this.acquired = acquired;
        }

        @Override
        public void close() {
            if (acquired && lock != null) {
                lock.unlock();
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

        lock.lock();
        try {
            action.run();
        } finally {
            lock.unlock();
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
        lock.lock();
        return new LockToken(lock, true);
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
