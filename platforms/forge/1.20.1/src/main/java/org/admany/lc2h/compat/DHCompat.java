package org.admany.lc2h.compat;

import net.minecraftforge.fml.ModList;
import org.admany.lc2h.LC2H;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Distant Horizons compatibility boundary.
 *
 * <p>LC2H must not replace DH's distant generator with a sampler that can only
 * read already-loaded chunks. That old bridge produced empty LODs outside the
 * vanilla window and added server-tick work without generating terrain. DH now
 * remains the sole owner of its LOD pipeline.</p>
 */
public final class DHCompat {
    private static final String DH_WORLDGEN_CLASS =
        "com.seibel.distanthorizons.common.wrappers.worldGeneration.BatchGenerationEnvironment_forge";
    private static volatile boolean loaded;
    private static final AtomicBoolean markerFailureLogged = new AtomicBoolean();
    private static volatile boolean markerResolved;
    private static volatile MethodHandle worldgenThreadMarker;
    private static final AtomicLong distantWorldgenCalls = new AtomicLong();
    private static final AtomicLong distantLootSkips = new AtomicLong();
    // This counter is touched by every cached DH chunk read. LongAdder avoids
    // turning diagnostics into a contended atomic hot path during LOD pregen.
    private static final LongAdder imposterCacheHits = new LongAdder();
    private static final AtomicLong imposterCacheMisses = new AtomicLong();

    private DHCompat() {
    }

    public static void init() {
        loaded = ModList.get().isLoaded("distanthorizons");
        if (loaded) {
            LC2H.LOGGER.info("Distant Horizons detected; LC2H leaves DH's native distant generator active");
        }
    }

    public static boolean isLoaded() {
        return loaded;
    }

    /**
     * Uses DH's own ThreadLocal marker. This deliberately does not infer
     * ownership from thread names, which changed across DH releases and also
     * matched unrelated DH service threads.
     */
    public static boolean isDistantWorldgenThread() {
        if (!loaded) {
            return false;
        }
        try {
            MethodHandle marker = resolveWorldgenThreadMarker();
            if (marker == null) {
                return false;
            }
            boolean active = (boolean) marker.invokeExact();
            if (active) {
                distantWorldgenCalls.incrementAndGet();
            }
            return active;
        } catch (Throwable failure) {
            if (markerFailureLogged.compareAndSet(false, true)) {
                LC2H.LOGGER.warn("Distant Horizons worldgen marker unavailable; LC2H will avoid changing DH generation ownership: {}",
                    failure.toString());
            }
            return false;
        }
    }

    private static MethodHandle resolveWorldgenThreadMarker() {
        if (!markerResolved) {
            synchronized (DHCompat.class) {
                if (!markerResolved) {
                    try {
                        Class<?> markerClass = Class.forName(
                            DH_WORLDGEN_CLASS, false, DHCompat.class.getClassLoader());
                        worldgenThreadMarker = MethodHandles.publicLookup().findStatic(
                            markerClass,
                            "isThisDhWorldGenThread",
                            MethodType.methodType(boolean.class));
                    } catch (Throwable failure) {
                        if (markerFailureLogged.compareAndSet(false, true)) {
                            LC2H.LOGGER.warn("Distant Horizons worldgen marker unavailable; LC2H will avoid changing DH generation ownership: {}",
                                failure.toString());
                        }
                    } finally {
                        markerResolved = true;
                    }
                }
            }
        }
        return worldgenThreadMarker;
    }

    public static void recordDistantLootSkip() {
        distantLootSkips.incrementAndGet();
    }

    public static void recordImposterCacheHit() {
        imposterCacheHits.increment();
    }

    public static void recordImposterCacheMiss() {
        imposterCacheMisses.incrementAndGet();
    }

    public static String diagnostics() {
        return "loaded=" + loaded
            + ", markerResolved=" + markerResolved
            + ", markerAvailable=" + (worldgenThreadMarker != null)
            + ", distantWorldgenCalls=" + distantWorldgenCalls.get()
            + ", distantLootSkips=" + distantLootSkips.get()
            + ", imposterCacheHits=" + imposterCacheHits.sum()
            + ", imposterCacheMisses=" + imposterCacheMisses.get();
    }
}
