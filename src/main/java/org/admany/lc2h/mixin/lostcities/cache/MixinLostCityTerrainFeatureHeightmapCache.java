package org.admany.lc2h.mixin.lostcities.cache;

import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.varia.TimedCache;
import mcjty.lostcities.worldgen.ChunkHeightmap;
import mcjty.lostcities.worldgen.LostCityTerrainFeature;
import org.admany.lc2h.data.cache.LostCitiesCacheBridge;
import org.admany.lc2h.data.cache.LostCitiesCacheBudgetManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;

@Mixin(value = LostCityTerrainFeature.class, remap = false)
public class MixinLostCityTerrainFeatureHeightmapCache {

    private static final Map<LostCityTerrainFeature, Object> LC2H_HEIGHTMAP_CACHES =
        Collections.synchronizedMap(new WeakHashMap<>());
    private static final AtomicReference<Field> HEIGHTMAP_CACHE_FIELD = new AtomicReference<>();
    private static final AtomicReference<Field> TIMED_CACHE_MAP_FIELD = new AtomicReference<>();
    private static final int LOCAL_HEIGHTMAP_CACHE_SIZE = Math.max(32, Integer.getInteger("lc2h.heightmap.localCache", 256));
    private static final ThreadLocal<LinkedHashMap<ChunkCoord, ChunkHeightmap>> LOCAL_HEIGHTMAP_CACHE =
        ThreadLocal.withInitial(() -> new LinkedHashMap<ChunkCoord, ChunkHeightmap>(LOCAL_HEIGHTMAP_CACHE_SIZE, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<ChunkCoord, ChunkHeightmap> eldest) {
                return size() > LOCAL_HEIGHTMAP_CACHE_SIZE;
            }
        });

    private static final LostCitiesCacheBudgetManager.CacheGroup LC2H_HEIGHTMAP_BUDGET =
        LostCitiesCacheBudgetManager.register("lc_heightmap", 2048, 1024, MixinLostCityTerrainFeatureHeightmapCache::evictHeightmap);

    @Inject(method = "<init>", at = @At("RETURN"))
    private void lc2h$initHeightmapCache(CallbackInfo ci) {
        Object cache = lc2h$getHeightmapCache();
        if (cache == null) {
            return;
        }
        if (cache instanceof Map<?, ?> map) {
            if (!(map instanceof ConcurrentHashMap) && lc2h$canReplaceHeightmapCache()) {
                @SuppressWarnings("unchecked")
                Map<ChunkCoord, ChunkHeightmap> typed = (Map<ChunkCoord, ChunkHeightmap>) map;
                ConcurrentHashMap<ChunkCoord, ChunkHeightmap> replacement = new ConcurrentHashMap<>(typed);
                if (lc2h$setHeightmapCache(replacement)) {
                    cache = replacement;
                }
            }
        }
        LC2H_HEIGHTMAP_CACHES.put((LostCityTerrainFeature) (Object) this, cache);
    }

    @Inject(method = "getHeightmap", at = @At("HEAD"), cancellable = true)
    private void lc2h$fastLocalHeightmap(ChunkCoord chunk,
                                         net.minecraft.world.level.WorldGenLevel world,
                                         org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<ChunkHeightmap> cir) {
        if (chunk == null) {
            return;
        }
        ChunkHeightmap cached = LOCAL_HEIGHTMAP_CACHE.get().get(chunk);
        if (cached != null) {
            cir.setReturnValue(cached);
            return;
        }
        ChunkHeightmap disk = LostCitiesCacheBridge.getDisk("heightmap", chunk, ChunkHeightmap.class);
        if (disk != null) {
            LOCAL_HEIGHTMAP_CACHE.get().put(chunk, disk);
            cir.setReturnValue(disk);
        }
    }

    @Redirect(
        method = "getHeightmap",
        at = @At(
            value = "INVOKE",
            target = "Ljava/util/Map;get(Ljava/lang/Object;)Ljava/lang/Object;"
        ),
        require = 0
    )
    private Object lc2h$trackHeightmapGet(Map<ChunkCoord, ChunkHeightmap> map, Object key) {
        Object value = map.get(key);
        if (value != null) {
            LostCitiesCacheBudgetManager.recordAccess(LC2H_HEIGHTMAP_BUDGET, key);
            if (key instanceof ChunkCoord coord && value instanceof ChunkHeightmap heightmap) {
                LOCAL_HEIGHTMAP_CACHE.get().put(coord, heightmap);
            }
        }
        return value;
    }

    @Redirect(
        method = "getHeightmap",
        at = @At(
            value = "INVOKE",
            target = "Ljava/util/Map;put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"
        ),
        require = 0
    )
    private Object lc2h$trackHeightmapPut(Map<ChunkCoord, ChunkHeightmap> map, Object key, Object value) {
        Object prev = map.put((ChunkCoord) key, (ChunkHeightmap) value);
        LostCitiesCacheBudgetManager.recordPut(LC2H_HEIGHTMAP_BUDGET, key, LC2H_HEIGHTMAP_BUDGET.defaultEntryBytes(), prev == null);
        if (key instanceof ChunkCoord coord && value instanceof ChunkHeightmap heightmap) {
            LOCAL_HEIGHTMAP_CACHE.get().put(coord, heightmap);
            LostCitiesCacheBridge.putDisk("heightmap", coord, heightmap);
        }
        return prev;
    }

    @Redirect(
        method = "getHeightmap",
        at = @At(
            value = "INVOKE",
            target = "Lmcjty/lostcities/varia/TimedCache;get(Ljava/lang/Object;)Ljava/lang/Object;"
        ),
        require = 0
    )
    private Object lc2h$trackHeightmapGetTimed(TimedCache<ChunkCoord, ChunkHeightmap> cache, Object key) {
        ChunkCoord coord = key instanceof ChunkCoord c ? c : null;
        Object value = coord != null ? cache.get(coord) : null;
        if (value != null) {
            LostCitiesCacheBudgetManager.recordAccess(LC2H_HEIGHTMAP_BUDGET, key);
            if (value instanceof ChunkHeightmap heightmap) {
                LOCAL_HEIGHTMAP_CACHE.get().put(coord, heightmap);
            }
        }
        return value;
    }

    @Redirect(
        method = "getHeightmap",
        at = @At(
            value = "INVOKE",
            target = "Lmcjty/lostcities/varia/TimedCache;put(Ljava/lang/Object;Ljava/lang/Object;)V"
        ),
        require = 0
    )
    private void lc2h$trackHeightmapPutTimed(TimedCache<ChunkCoord, ChunkHeightmap> cache, Object key, Object value) {
        ChunkCoord coord = key instanceof ChunkCoord c ? c : null;
        ChunkHeightmap heightmap = value instanceof ChunkHeightmap hm ? hm : null;
        if (coord == null || heightmap == null) {
            return;
        }
        boolean inserted = !timedCacheContains(cache, coord);
        cache.put(coord, heightmap);
        LostCitiesCacheBudgetManager.recordPut(LC2H_HEIGHTMAP_BUDGET, key, LC2H_HEIGHTMAP_BUDGET.defaultEntryBytes(), inserted);
        LOCAL_HEIGHTMAP_CACHE.get().put(coord, heightmap);
        LostCitiesCacheBridge.putDisk("heightmap", coord, heightmap);
    }

    private static boolean evictHeightmap(Object key) {
        if (!(key instanceof ChunkCoord coord)) {
            return false;
        }
        boolean removed = false;
        List<Map.Entry<LostCityTerrainFeature, Object>> snapshot;
        synchronized (LC2H_HEIGHTMAP_CACHES) {
            snapshot = new ArrayList<>(LC2H_HEIGHTMAP_CACHES.entrySet());
        }
        for (Map.Entry<LostCityTerrainFeature, Object> entry : snapshot) {
            LostCityTerrainFeature feature = entry.getKey();
            Object cache = entry.getValue();
            if (feature == null || cache == null) {
                synchronized (LC2H_HEIGHTMAP_CACHES) {
                    LC2H_HEIGHTMAP_CACHES.remove(feature);
                }
                continue;
            }
            removed |= removeFromCache(cache, coord);
        }
        return removed;
    }

    private static boolean removeFromCache(Object cache, ChunkCoord coord) {
        if (cache instanceof Map<?, ?> map) {
            return safeRemove(map, coord);
        }
        Map<?, ?> inner = resolveTimedCacheMap(cache);
        if (inner == null) {
            return false;
        }
        return safeRemove(inner, coord);
    }

    private Object lc2h$getHeightmapCache() {
        Field field = HEIGHTMAP_CACHE_FIELD.get();
        if (field == null || field.getDeclaringClass() != this.getClass()) {
            field = findHeightmapCacheField(this.getClass());
            if (field != null) {
                HEIGHTMAP_CACHE_FIELD.set(field);
            }
        }
        if (field == null) {
            return null;
        }
        try {
            return field.get(this);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private boolean lc2h$setHeightmapCache(Object value) {
        Field field = HEIGHTMAP_CACHE_FIELD.get();
        if (field == null || Modifier.isFinal(field.getModifiers())) {
            return false;
        }
        try {
            field.set(this, value);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean lc2h$canReplaceHeightmapCache() {
        Field field = HEIGHTMAP_CACHE_FIELD.get();
        return field != null && !Modifier.isFinal(field.getModifiers());
    }

    private static Field findHeightmapCacheField(Class<?> type) {
        try {
            Field field = type.getDeclaredField("cachedHeightmaps");
            field.setAccessible(true);
            return field;
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static boolean timedCacheContains(Object cache, Object key) {
        Map<?, ?> inner = resolveTimedCacheMap(cache);
        return inner != null && inner.containsKey(key);
    }

    private static Map<?, ?> resolveTimedCacheMap(Object cache) {
        if (cache == null) {
            return null;
        }
        Field field = TIMED_CACHE_MAP_FIELD.get();
        if (field == null || field.getDeclaringClass() != cache.getClass()) {
            field = findTimedCacheMapField(cache.getClass());
            if (field != null) {
                TIMED_CACHE_MAP_FIELD.set(field);
            }
        }
        if (field == null) {
            return null;
        }
        try {
            Object value = field.get(cache);
            if (!(value instanceof Map<?, ?> map)) {
                return null;
            }
            if (map instanceof ConcurrentMap<?, ?>) {
                return map;
            }
            if (!Modifier.isFinal(field.getModifiers())) {
                ConcurrentHashMap<Object, Object> replacement = new ConcurrentHashMap<>(map);
                field.set(cache, replacement);
                return replacement;
            }
            return map;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Field findTimedCacheMapField(Class<?> type) {
        try {
            Field field = type.getDeclaredField("cache");
            field.setAccessible(true);
            return field;
        } catch (Throwable ignored) {
        }
        for (Field field : type.getDeclaredFields()) {
            if (Map.class.isAssignableFrom(field.getType())) {
                try {
                    field.setAccessible(true);
                    return field;
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    private static boolean safeRemove(Map<?, ?> map, Object key) {
        if (map instanceof ConcurrentMap<?, ?>) {
            return map.remove(key) != null;
        }
        synchronized (map) {
            return map.remove(key) != null;
        }
    }
}
