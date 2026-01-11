package org.admany.lc2h.mixin.lostcities;

import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.ChunkHeightmap;
import mcjty.lostcities.varia.TimedCache;
import mcjty.lostcities.worldgen.IDimensionInfo;
import mcjty.lostcities.worldgen.lost.BiomeInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import org.admany.lc2h.data.cache.LostCitiesCacheBridge;
import org.admany.lc2h.data.cache.LostCitiesCacheBudgetManager;
import org.admany.lc2h.mixin.accessor.BiomeInfoAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Mixin(value = BiomeInfo.class, remap = false)
public class MixinBiomeInfo {

    private static final AtomicReference<Object> BIOME_INFO_CACHE = new AtomicReference<>();
    private static final AtomicBoolean CACHE_LOOKUP_DONE = new AtomicBoolean(false);
    private static final AtomicReference<Field> CACHE_FIELD = new AtomicReference<>();
    private static final AtomicReference<Field> TIMED_CACHE_MAP_FIELD = new AtomicReference<>();
    private static final int LOCAL_BIOME_CACHE_SIZE = Math.max(64, Integer.getInteger("lc2h.biome.localCache", 256));
    private static final ThreadLocal<LinkedHashMap<ChunkCoord, BiomeInfo>> LOCAL_BIOME_CACHE =
        ThreadLocal.withInitial(() -> new LinkedHashMap<>(LOCAL_BIOME_CACHE_SIZE, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<ChunkCoord, BiomeInfo> eldest) {
                return size() > LOCAL_BIOME_CACHE_SIZE;
            }
        });

    private static final LostCitiesCacheBudgetManager.CacheGroup LC2H_BIOME_INFO_BUDGET =
        LostCitiesCacheBudgetManager.register("lc_biome_info", 256, 2048, MixinBiomeInfo::evictBiomeInfo);

    @Inject(method = "getBiomeInfo", at = @At("HEAD"), cancellable = true)
    private static void lc2h$getBiomeInfoThreadSafe(IDimensionInfo provider, ChunkCoord coord, CallbackInfoReturnable<BiomeInfo> cir) {
        if (provider == null || coord == null) {
            cir.setReturnValue(null);
            return;
        }
        BiomeInfo local = LOCAL_BIOME_CACHE.get().get(coord);
        if (local != null) {
            cir.setReturnValue(local);
            return;
        }
        BiomeInfo disk = LostCitiesCacheBridge.getDisk("biome_info", coord, BiomeInfo.class);
        if (disk != null) {
            LOCAL_BIOME_CACHE.get().put(coord, disk);
            Object cache = resolveCache();
            if (cache != null) {
                synchronized (cache) {
                    cachePut(cache, coord, disk);
                }
            }
            LostCitiesCacheBudgetManager.recordPut(LC2H_BIOME_INFO_BUDGET, coord, LC2H_BIOME_INFO_BUDGET.defaultEntryBytes(), true);
            cir.setReturnValue(disk);
            return;
        }
        Object cache = resolveCache();
        if (cache == null) {
            return;
        }
        synchronized (cache) {
            BiomeInfo existing = cacheGet(cache, coord);
            if (existing != null) {
                LostCitiesCacheBudgetManager.recordAccess(LC2H_BIOME_INFO_BUDGET, coord);
                LOCAL_BIOME_CACHE.get().put(coord, existing);
                cir.setReturnValue(existing);
                return;
            }

            BiomeInfo info = new BiomeInfo();
            ChunkHeightmap heightmap = provider.getHeightmap(coord);
            int chunkX = coord.chunkX();
            int chunkZ = coord.chunkZ();
            int y = heightmap != null ? heightmap.getHeight() : 64;
            Holder<Biome> biome = provider.getBiome(new BlockPos((chunkX << 4) + 8, y, (chunkZ << 4) + 8));
            ((BiomeInfoAccessor) (Object) info).lc2h$setMainBiome(biome);

            cachePut(cache, coord, info);
            LostCitiesCacheBudgetManager.recordPut(LC2H_BIOME_INFO_BUDGET, coord, LC2H_BIOME_INFO_BUDGET.defaultEntryBytes(), true);
            LOCAL_BIOME_CACHE.get().put(coord, info);
            LostCitiesCacheBridge.putDisk("biome_info", coord, info);
            cir.setReturnValue(info);
        }
    }

    @Inject(method = "cleanCache", at = @At("HEAD"))
    private static void lc2h$clearBiomeBudget(org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        LostCitiesCacheBudgetManager.clear(LC2H_BIOME_INFO_BUDGET);
        LOCAL_BIOME_CACHE.remove();
    }

    private static Object resolveCache() {
        Object cache = BIOME_INFO_CACHE.get();
        if (cache != null) {
            return cache;
        }
        if (CACHE_LOOKUP_DONE.get()) {
            return null;
        }
        CACHE_LOOKUP_DONE.set(true);
        Field field = findCacheField();
        if (field == null) {
            return null;
        }
        try {
            cache = field.get(null);
            if (cache != null) {
                BIOME_INFO_CACHE.compareAndSet(null, cache);
            }
        } catch (Throwable ignored) {
        }
        return BIOME_INFO_CACHE.get();
    }

    private static Field findCacheField() {
        Field field = CACHE_FIELD.get();
        if (field != null) {
            return field;
        }
        try {
            field = BiomeInfo.class.getDeclaredField("BIOME_INFO_CACHE");
            field.setAccessible(true);
            CACHE_FIELD.compareAndSet(null, field);
            return field;
        } catch (Throwable ignored) {
        }
        try {
            field = BiomeInfo.class.getDeclaredField("BIOME_INFO_MAP");
            field.setAccessible(true);
            CACHE_FIELD.compareAndSet(null, field);
            return field;
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static BiomeInfo cacheGet(Object cache, ChunkCoord coord) {
        if (cache instanceof TimedCache<?, ?> timed) {
            @SuppressWarnings("unchecked")
            TimedCache<ChunkCoord, BiomeInfo> typed = (TimedCache<ChunkCoord, BiomeInfo>) timed;
            return typed.get(coord);
        }
        if (cache instanceof Map<?, ?> map) {
            return (BiomeInfo) map.get(coord);
        }
        return null;
    }

    private static void cachePut(Object cache, ChunkCoord coord, BiomeInfo info) {
        if (cache instanceof TimedCache<?, ?> timed) {
            @SuppressWarnings("unchecked")
            TimedCache<ChunkCoord, BiomeInfo> typed = (TimedCache<ChunkCoord, BiomeInfo>) timed;
            typed.put(coord, info);
            return;
        }
        if (cache instanceof Map<?, ?> map) {
            @SuppressWarnings("rawtypes")
            Map raw = (Map) map;
            raw.put(coord, info);
        }
    }

    private static boolean evictBiomeInfo(Object key) {
        if (!(key instanceof ChunkCoord coord)) {
            return false;
        }
        Object cache = resolveCache();
        if (cache == null) {
            return false;
        }
        if (cache instanceof Map<?, ?> map) {
            return map.remove(coord) != null;
        }
        if (cache instanceof TimedCache<?, ?>) {
            Map<?, ?> inner = resolveTimedCacheMap(cache);
            return inner != null && inner.remove(coord) != null;
        }
        return false;
    }

    private static Map<?, ?> resolveTimedCacheMap(Object cache) {
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
            return value instanceof Map<?, ?> map ? map : null;
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
}
