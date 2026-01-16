package org.admany.lc2h.data.cache;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.admany.lc2h.LC2H;
import org.admany.quantified.api.QuantifiedAPI;
import org.admany.quantified.core.common.cache.CacheManager;
import org.admany.quantified.core.common.cache.interfaces.ThreadSafeCache;

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
    private static final ConcurrentHashMap<String, ThreadSafeCache<String, Object>> DISK_CACHES =
        new ConcurrentHashMap<>();

    private static final boolean DISK_CACHE_ON_CLIENT = Boolean.parseBoolean(
        System.getProperty("lc2h.lostcities.cache.diskOnClient", "false"));

    private LostCitiesCacheBridge() {
    }

    public static <T> T getDisk(String cacheName, Object key, Class<T> type) {
        if (!shouldUseDiskCache() || !ensureReady() || key == null || type == null) {
            return null;
        }
        try {
            ThreadSafeCache<String, Object> cache = diskCache(cacheName);
            if (cache == null) {
                return null;
            }
            Object value = cache.getIfPresent(key.toString());
            if (type.isInstance(value)) {
                return type.cast(value);
            }
        } catch (Throwable t) {
            disable(t, "get");
        }
        return null;
    }

    public static void putDisk(String cacheName, Object key, Object value) {
        if (!shouldUseDiskCache() || !ensureReady() || key == null || value == null) {
            return;
        }
        if (!isSerializable(value)) {
            return;
        }
        try {
            ThreadSafeCache<String, Object> cache = diskCache(cacheName);
            if (cache != null) {
                cache.put(key.toString(), value);
            }
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
                QuantifiedAPI.register(MODID, "Lost Cities", "unknown");
                READY = true;
                return true;
            } catch (Throwable t) {
                disable(t, "register");
                return false;
            }
        }
    }

    private static boolean shouldUseDiskCache() {
        if (DISK_CACHE_ON_CLIENT) {
            return true;
        }
        return !isClientMainThread();
    }

    private static boolean isClientMainThread() {
        if (FMLEnvironment.dist != Dist.CLIENT) {
            return false;
        }
        String threadName = Thread.currentThread().getName();
        if (threadName == null) {
            return false;
        }
        if ("Render thread".equals(threadName) || "Client thread".equals(threadName)) {
            return true;
        }
        if (!threadName.contains("Render") && !threadName.contains("Client")) {
            return false;
        }
        try {
            return ClientThreadChecker.isClientThread();
        } catch (Throwable t) {
            return false;
        }
    }

    private static ThreadSafeCache<String, Object> diskCache(String name) {
        String cacheName = diskCacheName(name);
        return DISK_CACHES.computeIfAbsent(cacheName, key -> {
            try {
                ThreadSafeCache<String, Object> existing = CacheManager.lookup(key);
                if (existing != null) {
                    return existing;
                }
                return CacheManager.register(key, DISK_MAX_ENTRIES, DISK_TTL.get(), true, true, true);
            } catch (Throwable t) {
                disable(t, "register-cache");
                return null;
            }
        });
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

    private static final class ClientThreadChecker {
        private static boolean isClientThread() {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.isSameThread()) {
                return true;
            }
            return RenderSystem.isOnRenderThread();
        }
    }
}
