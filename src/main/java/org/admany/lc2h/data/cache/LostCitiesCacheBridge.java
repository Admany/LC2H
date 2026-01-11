package org.admany.lc2h.data.cache;

import org.admany.lc2h.LC2H;

import java.io.Serializable;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public final class LostCitiesCacheBridge {

    private static final String MODID = "lostcities";
    private static final String DISK_CACHE_PREFIX = "lostcities_";
    private static final java.util.concurrent.atomic.AtomicReference<Duration> DISK_TTL =
        new java.util.concurrent.atomic.AtomicReference<>(
            Duration.ofHours(Math.max(1L, Long.getLong("lc2h.lostcities.cache.diskTtlHours", 6L)))
        );
    private static final long DISK_MAX_ENTRIES = Math.max(1L,
        Long.getLong("lc2h.lostcities.cache.diskMaxEntries", 200_000L));

    private static final AtomicBoolean AVAILABLE = new AtomicBoolean(true);
    private static final Object INIT_LOCK = new Object();
    private static volatile boolean READY = false;
    private static final Set<Class<?>> NON_SERIALIZABLE = ConcurrentHashMap.newKeySet();

    private LostCitiesCacheBridge() {
    }

    public static <T> T getDisk(String cacheName, Object key, Class<T> type) {
        if (!ensureReady() || key == null || type == null) {
            return null;
        }
        try {
            Class<?> quantifiedApiClass = Class.forName("org.admany.quantified.api.QuantifiedAPI");
            Object value = quantifiedApiClass.getMethod(
                    "getCached",
                    String.class,
                    String.class,
                    java.util.function.Supplier.class,
                    Duration.class,
                    long.class,
                    boolean.class
                )
                .invoke(null, diskCacheName(cacheName), key.toString(),
                    (java.util.function.Supplier<Object>) () -> null,
                    DISK_TTL.get(),
                    DISK_MAX_ENTRIES,
                    true);
            if (type.isInstance(value)) {
                return type.cast(value);
            }
        } catch (Throwable t) {
            disable(t, "get");
        }
        return null;
    }

    public static void putDisk(String cacheName, Object key, Object value) {
        if (!ensureReady() || key == null || value == null) {
            return;
        }
        if (!isSerializable(value)) {
            return;
        }
        try {
            Class<?> quantifiedApiClass = Class.forName("org.admany.quantified.api.QuantifiedAPI");
            quantifiedApiClass.getMethod(
                    "putCached",
                    String.class,
                    String.class,
                    Object.class,
                    Duration.class,
                    long.class,
                    boolean.class
                )
                .invoke(null, diskCacheName(cacheName), key.toString(), value, DISK_TTL.get(), DISK_MAX_ENTRIES, true);
        } catch (Throwable t) {
            disable(t, "put");
        }
    }

    public static void applyDiskTtlHours(long hours) {
        long clamped = Math.max(1L, hours);
        DISK_TTL.set(Duration.ofHours(clamped));
    }

    private static boolean ensureReady() {
        if (!AVAILABLE.get()) {
            return false;
        }
        if (READY) {
            return true;
        }
        synchronized (INIT_LOCK) {
            if (READY) {
                return true;
            }
            try {
                Class<?> quantifiedApiClass = Class.forName("org.admany.quantified.api.QuantifiedAPI");
                try {
                    quantifiedApiClass.getMethod("register", String.class, String.class, String.class)
                        .invoke(null, MODID, "Lost Cities", "unknown");
                } catch (NoSuchMethodException ignored) {
                    quantifiedApiClass.getMethod("register", String.class).invoke(null, MODID);
                }
                READY = true;
                return true;
            } catch (Throwable t) {
                disable(t, "register");
                return false;
            }
        }
    }

    private static boolean isSerializable(Object value) {
        if (value instanceof Serializable) {
            return true;
        }
        Class<?> type = value.getClass();
        if (NON_SERIALIZABLE.contains(type)) {
            return false;
        }
        NON_SERIALIZABLE.add(type);
        return false;
    }

    private static String diskCacheName(String name) {
        if (name == null || name.isBlank()) {
            return DISK_CACHE_PREFIX + "disk";
        }
        return DISK_CACHE_PREFIX + name;
    }

    private static void disable(Throwable t, String action) {
        if (AVAILABLE.compareAndSet(true, false)) {
            LC2H.LOGGER.warn("[LC2H] [LostCities] Quantified disk cache {} failed; disabling. Reason: {}",
                action, t.toString());
        }
    }
}
