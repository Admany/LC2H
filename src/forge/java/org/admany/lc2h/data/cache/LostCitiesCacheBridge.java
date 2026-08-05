package org.admany.lc2h.data.cache;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.admany.lc2h.LC2H;
import org.admany.lc2h.worldgen.scope.WorldGenScope;
import org.admany.lc2h.worldgen.lostcities.LostCityGenerationHotPath;
import org.admany.lc2h.worldgen.lostcities.PlannerHotPath;
import org.admany.lc2h.dev.diagnostics.Lc2hTimingRegistry;
import org.admany.quantified.api.CacheRequest;
import org.admany.quantified.api.QuantifiedAPI;

import java.io.Serializable;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public final class LostCitiesCacheBridge {

    private static final String MODID = "lostcities";
    private static final String DISK_CACHE_PREFIX = "lostcities_v" + WorldGenScope.CACHE_SCHEMA_VERSION + "_";
    private static final java.util.concurrent.atomic.AtomicReference<Duration> DISK_TTL =
        new java.util.concurrent.atomic.AtomicReference<>(
            Duration.ofHours(Math.max(1L, Long.getLong("lc2h.lostcities.cache.diskTtlHours", 2L)))
        );
    private static final long DISK_MAX_ENTRIES = Math.max(1L,
        Long.getLong("lc2h.lostcities.cache.diskMaxEntries", 50_000L));

    private static final AtomicBoolean AVAILABLE = new AtomicBoolean(true);
    private static final Object INIT_LOCK = new Object();
    private static volatile boolean READY = false;
    private static final Set<Class<?>> NON_SERIALIZABLE = ConcurrentHashMap.newKeySet();
    private static final ConcurrentHashMap<String, CacheRequest> CACHE_REQUESTS = new ConcurrentHashMap<>();
    private static final java.util.concurrent.atomic.AtomicLong GET_HITS = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong GET_MISSES = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong PUTS = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong DISABLED_CALLS = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong NON_SERIALIZABLE_REJECTIONS = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong HOT_PATH_BYPASSES = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong UNSCOPED_BYPASSES = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong REPEATED_NON_SERIALIZABLE_BYPASSES = new java.util.concurrent.atomic.AtomicLong();
    private static final Lc2hTimingRegistry.TimingHandle GET_TIMING = Lc2hTimingRegistry.bucket("cache.lostcities_disk_get");
    private static final Lc2hTimingRegistry.TimingHandle PUT_TIMING = Lc2hTimingRegistry.bucket("cache.lostcities_disk_put");

    private static final boolean DISK_CACHE_ON_CLIENT = Boolean.parseBoolean(
        System.getProperty("lc2h.lostcities.cache.diskOnClient", "false"));

    private LostCitiesCacheBridge() {
    }

    public static <T> T getDisk(String cacheName, Object key, Class<T> type) {
        if (!shouldUseDiskCache() || !ensureReady() || key == null || type == null) {
            DISABLED_CALLS.incrementAndGet();
            return null;
        }
        long startNs = System.nanoTime();
        try {
            Object value = cacheRequest(cacheName).get(WorldGenScope.bridgeDiskKey(key), () -> null);
            if (type.isInstance(value)) {
                GET_HITS.incrementAndGet();
                return type.cast(value);
            }
            GET_MISSES.incrementAndGet();
        } catch (Throwable t) {
            disable(t, "get");
        } finally {
            GET_TIMING.record(System.nanoTime() - startNs);
        }
        return null;
    }

    public static void putDisk(String cacheName, Object key, Object value) {
        if (!shouldUseDiskCache() || !ensureReady() || key == null || value == null) {
            DISABLED_CALLS.incrementAndGet();
            return;
        }
        if (!isSerializable(value)) {
            return;
        }
        long startNs = System.nanoTime();
        try {
            cacheRequest(cacheName).put(WorldGenScope.bridgeDiskKey(key), value);
            PUTS.incrementAndGet();
        } catch (Throwable t) {
            disable(t, "put");
        } finally {
            PUT_TIMING.record(System.nanoTime() - startNs);
        }
    }

    public static void applyDiskTtlHours(long hours) {
        long clamped = Math.max(1L, hours);
        DISK_TTL.set(Duration.ofHours(clamped));
        CACHE_REQUESTS.clear();
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
                READY = true;
                return true;
            } catch (Throwable t) {
                disable(t, "register");
                return false;
            }
        }
    }

    private static boolean shouldUseDiskCache() {
        if (LostCityGenerationHotPath.isActive() || PlannerHotPath.isActive()) {
            HOT_PATH_BYPASSES.incrementAndGet();
            return false;
        }
        if (!WorldGenScope.isDiskScopeReady()) {
            UNSCOPED_BYPASSES.incrementAndGet();
            return false;
        }
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

    private static CacheRequest cacheRequest(String name) {
        String cacheName = diskCacheName(name);
        return CACHE_REQUESTS.computeIfAbsent(cacheName, key -> QuantifiedAPI.cache(MODID, key)
            .ttl(DISK_TTL.get())
            .maxEntries(DISK_MAX_ENTRIES)
            .diskPreferred()
            .compressed()
            .refreshOnAccess());
    }

    private static boolean isSerializable(Object value) {
        if (value instanceof Serializable) {
            return true;
        }
        Class<?> type = value.getClass();
        if (NON_SERIALIZABLE.contains(type)) {
            REPEATED_NON_SERIALIZABLE_BYPASSES.incrementAndGet();
            return false;
        }
        if (NON_SERIALIZABLE.add(type)) {
            NON_SERIALIZABLE_REJECTIONS.incrementAndGet();
        }
        return false;
    }

    private static String diskCacheName(String name) {
        if (name == null || name.isBlank()) {
            return DISK_CACHE_PREFIX + "disk";
        }
        return DISK_CACHE_PREFIX + name;
    }

    public static String diagnostics() {
        return "available=" + AVAILABLE.get()
            + ", ready=" + READY
            + ", requests=" + CACHE_REQUESTS.size()
            + ", hits=" + GET_HITS.get()
            + ", misses=" + GET_MISSES.get()
            + ", puts=" + PUTS.get()
            + ", disabledCalls=" + DISABLED_CALLS.get()
            + ", nonSerializable=" + NON_SERIALIZABLE_REJECTIONS.get()
            + ", repeatedNonSerializable=" + REPEATED_NON_SERIALIZABLE_BYPASSES.get()
            + ", hotPathBypasses=" + HOT_PATH_BYPASSES.get()
            + ", unscopedBypasses=" + UNSCOPED_BYPASSES.get()
            + ", schema=" + WorldGenScope.CACHE_SCHEMA_VERSION
            + ", diskScope=" + WorldGenScope.activeDiskScope();
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
