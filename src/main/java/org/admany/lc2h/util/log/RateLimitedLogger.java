package org.admany.lc2h.util.log;

import org.admany.lc2h.LC2H;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class RateLimitedLogger {
    private static final long COOL_DOWN_MS = 5000L;
    private static final Map<String, Long> LAST = new ConcurrentHashMap<>();

    private RateLimitedLogger() {}

    public static void info(String key, String message) {
        long now = System.currentTimeMillis();
        Long prev = LAST.get(key);
        if (prev == null || now - prev > COOL_DOWN_MS) {
            LAST.put(key, now);
            LC2H.LOGGER.info(message);
        }
    }

    public static void warn(String key, String message) {
        long now = System.currentTimeMillis();
        Long prev = LAST.get(key);
        if (prev == null || now - prev > COOL_DOWN_MS) {
            LAST.put(key, now);
            LC2H.LOGGER.warn(message);
        }
    }

    public static void warn(String key, String message, Object... args) {
        long now = System.currentTimeMillis();
        Long prev = LAST.get(key);
        if (prev == null || now - prev > COOL_DOWN_MS) {
            LAST.put(key, now);
            LC2H.LOGGER.warn(message, args);
        }
    }
}