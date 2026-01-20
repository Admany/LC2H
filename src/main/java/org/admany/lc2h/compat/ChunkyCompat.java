package org.admany.lc2h.compat;

import net.minecraftforge.fml.ModList;
import org.admany.lc2h.LC2H;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

public final class ChunkyCompat {
    private static final long CHECK_INTERVAL_MS = Math.max(250L,
        Long.getLong("lc2h.compat.chunky.checkIntervalMs", 2_000L));
    private static final boolean PAUSE_ON_CHUNKY =
        Boolean.parseBoolean(System.getProperty("lc2h.compat.chunky.pauseOnRun", "true"));
    private static final boolean THREAD_DETECT =
        Boolean.parseBoolean(System.getProperty("lc2h.compat.chunky.detectThread", "true"));
    private static final AtomicLong LAST_CHECK_MS = new AtomicLong(0L);
    private static volatile boolean cachedActive = false;
    private static volatile boolean loaded = false;
    private static volatile boolean resolved = false;

    private ChunkyCompat() {
    }

    public static boolean shouldPauseForChunky() {
        if (!PAUSE_ON_CHUNKY) {
            return false;
        }
        if (!isChunkyLoaded()) {
            return false;
        }
        return isChunkyActive();
    }

    private static boolean isChunkyLoaded() {
        if (resolved) {
            return loaded;
        }
        try {
            loaded = ModList.get().isLoaded("chunky");
        } catch (Throwable t) {
            loaded = false;
        }
        resolved = true;
        if (loaded) {
            LC2H.LOGGER.debug("[LC2H] Chunky detected; enabling compatibility throttles");
        }
        return loaded;
    }

    private static boolean isChunkyActive() {
        long now = System.currentTimeMillis();
        long last = LAST_CHECK_MS.get();
        if ((now - last) < CHECK_INTERVAL_MS) {
            return cachedActive;
        }
        if (!LAST_CHECK_MS.compareAndSet(last, now)) {
            return cachedActive;
        }

        boolean active = false;
        try {
            if (THREAD_DETECT) {
                active = isChunkyThreadActive();
            }
        } catch (Throwable ignored) {
            active = false;
        }

        cachedActive = active;
        return active;
    }

    private static boolean isChunkyThreadActive() {
        Map<Thread, StackTraceElement[]> threads = Thread.getAllStackTraces();
        if (threads == null || threads.isEmpty()) {
            return false;
        }
        for (Thread t : threads.keySet()) {
            if (t == null) {
                continue;
            }
            String name = t.getName();
            if (name == null) {
                continue;
            }
            String lower = name.toLowerCase();
            if (lower.contains("chunky")) {
                return t.isAlive();
            }
        }
        return false;
    }
}
