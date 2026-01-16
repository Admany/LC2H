package org.admany.lc2h.util.lostcities;

import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.lost.MultiChunk;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class MultiChunkCacheAccess {

    private static final AtomicReference<Object> CACHE = new AtomicReference<>();
    private static final AtomicBoolean LOOKUP_DONE = new AtomicBoolean(false);
    private static final AtomicReference<Method> GET_METHOD = new AtomicReference<>();
    private static final AtomicReference<Method> PUT_METHOD = new AtomicReference<>();
    private static final AtomicReference<Field> INNER_MAP_FIELD = new AtomicReference<>();

    private MultiChunkCacheAccess() {
    }

    private static Object resolveCache() {
        Object cache = CACHE.get();
        if (cache != null) {
            return cache;
        }
        if (LOOKUP_DONE.get()) {
            return null;
        }
        LOOKUP_DONE.set(true);
        try {
            Field field = MultiChunk.class.getDeclaredField("MULTICHUNKS");
            field.setAccessible(true);
            Object value = field.get(null);
            if (value != null) {
                CACHE.compareAndSet(null, value);
            }
        } catch (Throwable ignored) {
        }
        return CACHE.get();
    }

    public static Object lock() {
        Object cache = resolveCache();
        return cache != null ? cache : MultiChunk.class;
    }

    public static MultiChunk get(ChunkCoord key) {
        Object cache = resolveCache();
        if (cache == null || key == null) {
            return null;
        }
        if (cache instanceof Map<?, ?> map) {
            return (MultiChunk) map.get(key);
        }
        return (MultiChunk) invoke(cache, GET_METHOD, "get", 1, key);
    }

    public static boolean contains(ChunkCoord key) {
        return get(key) != null;
    }

    public static void put(ChunkCoord key, MultiChunk value) {
        Object cache = resolveCache();
        if (cache == null || key == null) {
            return;
        }
        if (cache instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<ChunkCoord, MultiChunk> typed = (Map<ChunkCoord, MultiChunk>) map;
            typed.put(key, value);
            return;
        }
        invoke(cache, PUT_METHOD, "put", 2, key, value);
    }

    public static boolean remove(ChunkCoord key) {
        Object cache = resolveCache();
        if (cache == null || key == null) {
            return false;
        }
        if (cache instanceof Map<?, ?> map) {
            return map.remove(key) != null;
        }
        Map<?, ?> inner = resolveInnerMap(cache);
        return inner != null && inner.remove(key) != null;
    }

    public static boolean remove(Object key) {
        if (!(key instanceof ChunkCoord coord)) {
            return false;
        }
        return remove(coord);
    }

    private static Map<?, ?> resolveInnerMap(Object cache) {
        if (cache == null) {
            return null;
        }
        Field field = INNER_MAP_FIELD.get();
        if (field == null || field.getDeclaringClass() != cache.getClass()) {
            field = findInnerMapField(cache.getClass());
            if (field != null) {
                INNER_MAP_FIELD.set(field);
            }
        }
        if (field == null) {
            return null;
        }
        try {
            Object value = field.get(cache);
            return value instanceof Map<?, ?> map ? map : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Field findInnerMapField(Class<?> type) {
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

    private static Object invoke(Object target,
                                 AtomicReference<Method> ref,
                                 String name,
                                 int paramCount,
                                 Object... args) {
        Method method = ref.get();
        if (method == null || method.getDeclaringClass() != target.getClass()) {
            method = findMethod(target.getClass(), name, paramCount);
            if (method != null) {
                ref.set(method);
            }
        }
        if (method == null) {
            return null;
        }
        try {
            return method.invoke(target, args);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Method findMethod(Class<?> type, String name, int paramCount) {
        for (Method method : type.getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == paramCount) {
                method.setAccessible(true);
                return method;
            }
        }
        for (Method method : type.getDeclaredMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == paramCount) {
                method.setAccessible(true);
                return method;
            }
        }
        return null;
    }
}
