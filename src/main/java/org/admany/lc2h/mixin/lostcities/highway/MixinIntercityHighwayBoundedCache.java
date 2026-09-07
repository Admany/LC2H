package org.admany.lc2h.mixin.lostcities.highway;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.LinkedHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Removes the global monitor from Lost Cities' intercity cache loaders.
 *
 * The upstream cache keeps its access ordered map protected by the original
 * monitor, but also invokes expensive hub and route planners while holding
 * that monitor. Independent planning cells therefore become serial. This
 * replacement retains the same map, bounds and hit/miss semantics while
 * confining expensive work behind a per key single flight lock.
 */
@Pseudo
@Mixin(targets = "mcjty.lostcities.worldgen.highway.IntercityHighwayPlanner$BoundedCache", remap = false)
public abstract class MixinIntercityHighwayBoundedCache<K, V> {
    @Shadow @Final
    private LinkedHashMap<K, V> values;

    @Shadow
    private long hits;

    @Shadow
    private long misses;

    @Shadow
    private void trim() {
    }

    @Unique
    private final ConcurrentHashMap<K, Object> lc2h$inFlightKeys = new ConcurrentHashMap<>();

    /**
     * @author LC2H
     * @reason Compute independent intercity planner keys concurrently without
     * holding Lost Cities' global access order monitor across planner work.
     */
    @Overwrite
    public V computeIfAbsent(K key, Function<K, V> factory) {
        synchronized (this) {
            V cached = values.get(key);
            if (cached != null) {
                hits++;
                return cached;
            }
        }

        Object keyLock = lc2h$inFlightKeys.computeIfAbsent(key, ignored -> new Object());
        try {
            synchronized (keyLock) {
                synchronized (this) {
                    V cached = values.get(key);
                    if (cached != null) {
                        hits++;
                        return cached;
                    }
                    misses++;
                }

                V computed = factory.apply(key);
                synchronized (this) {
                    values.put(key, computed);
                    trim();
                }
                return computed;
            }
        } finally {
            lc2h$inFlightKeys.remove(key, keyLock);
        }
    }
}
