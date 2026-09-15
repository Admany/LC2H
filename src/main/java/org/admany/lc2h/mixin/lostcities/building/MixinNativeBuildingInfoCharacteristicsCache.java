package org.admany.lc2h.mixin.lostcities.building;

import mcjty.lostcities.api.LostChunkCharacteristics;
import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.IDimensionInfo;
import mcjty.lostcities.worldgen.lost.BuildingInfo;
import org.admany.lc2h.data.cache.BuildingInfoCacheRegistry;
import org.admany.lc2h.data.cache.BuildingInfoCacheScope;
import org.admany.lc2h.dev.diagnostics.BuildingInfoDiagnostics;
import org.admany.lc2h.worldgen.lostcities.ChunkRoleProbe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.ReentrantLock;

@Mixin(value = BuildingInfo.class, remap = false)
public abstract class MixinNativeBuildingInfoCharacteristicsCache {

    @Unique
    private static final boolean LC2H_NATIVE_CHARACTERISTICS_CACHE =
        Boolean.parseBoolean(System.getProperty("lc2h.nativeCharacteristicsCache.enabled", "true"));

    @Unique
    private static final ThreadLocal<Set<FlightKey>> LC2H_NATIVE_CHARACTERISTICS_OWNERS =
        ThreadLocal.withInitial(HashSet::new);

    @Shadow
    private static Object getDimensionLock(net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension) {
        return null;
    }

    @Shadow
    private static LostChunkCharacteristics getChunkCharacteristicsLocked(ChunkCoord coord, IDimensionInfo provider) {
        throw new AssertionError();
    }

    @Shadow
    private static int getCityLevelLocked(ChunkCoord coord, IDimensionInfo provider) {
        return 0;
    }

    @org.spongepowered.asm.mixin.gen.Invoker("<init>")
    static BuildingInfo lc2h$invokeCreate(ChunkCoord coord, IDimensionInfo provider) {
        throw new AssertionError();
    }

    @org.spongepowered.asm.mixin.injection.Redirect(
        method = "<init>",
        at = @org.spongepowered.asm.mixin.injection.At(
            value = "INVOKE",
            target = "Lmcjty/lostcities/worldgen/lost/BuildingInfo;getDimensionLock(Lnet/minecraft/resources/ResourceKey;)Ljava/lang/Object;",
            remap = false
        ),
        require = 0
    )
    private Object lc2h$perInstanceMemoizationLock(net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension) {
        return new Object();
    }

    @Overwrite
    public static LostChunkCharacteristics getChunkCharacteristics(ChunkCoord coord, IDimensionInfo provider) {
        if (!LC2H_NATIVE_CHARACTERISTICS_CACHE || coord == null || provider == null) {
            return lc2h$nativeResolve(coord, provider);
        }
        BuildingInfoCacheScope scope = BuildingInfoCacheRegistry.scope(provider);
        LostChunkCharacteristics cached = scope.nativeCharacteristics.get(coord);
        if (cached != null) {
            BuildingInfoDiagnostics.recordNativeCharacteristicsHit();
            return cached;
        }

        BuildingInfoDiagnostics.recordNativeCharacteristicsMiss();
        CompletableFuture<LostChunkCharacteristics> created = new CompletableFuture<>();
        CompletableFuture<LostChunkCharacteristics> existing =
            scope.nativeCharacteristicFlights.putIfAbsent(coord, created);
        if (existing != null) {
            FlightKey key = new FlightKey(scope, coord);
            if (LC2H_NATIVE_CHARACTERISTICS_OWNERS.get().contains(key)) {
                return lc2h$nativeResolve(coord, provider);
            }
            if (existing.isDone()) {
                return existing.getNow(null);
            }
            return lc2h$nativeResolve(coord, provider);
        }

        FlightKey ownerKey = new FlightKey(scope, coord);
        Set<FlightKey> owners = LC2H_NATIVE_CHARACTERISTICS_OWNERS.get();
        owners.add(ownerKey);
        try {
            LostChunkCharacteristics resolved = lc2h$nativeResolve(coord, provider);
            if (resolved == null) {
                created.complete(null);
                return null;
            }
            LostChunkCharacteristics published = scope.nativeCharacteristics.putIfAbsent(coord, resolved);
            LostChunkCharacteristics result = published == null ? resolved : published;
            if (published == null) {
                BuildingInfoDiagnostics.recordNativeCharacteristicsPublish(true);
                ChunkRoleProbe.rememberCharacteristics(coord, result);
            }
            created.complete(result);
            return result;
        } catch (Throwable failure) {
            created.completeExceptionally(failure);
            throw failure;
        } finally {
            owners.remove(ownerKey);
            if (owners.isEmpty()) {
                LC2H_NATIVE_CHARACTERISTICS_OWNERS.remove();
            }
            scope.nativeCharacteristicFlights.remove(coord, created);
        }
    }

    @Overwrite
    public static int getCityLevel(ChunkCoord coord, IDimensionInfo provider) {
        if (coord == null || provider == null) {
            return getCityLevelLocked(coord, provider);
        }
        BuildingInfoCacheScope scope = BuildingInfoCacheRegistry.scope(provider);
        ReentrantLock lock = scope.buildingLocks.computeIfAbsent(coord, ignored -> new ReentrantLock());
        if (!lock.tryLock()) {
            return getCityLevelLocked(coord, provider);
        }
        try {
            return getCityLevelLocked(coord, provider);
        } finally {
            lock.unlock();
        }
    }

    @Overwrite
    public static BuildingInfo getBuildingInfo(ChunkCoord coord, IDimensionInfo provider) {
        if (coord == null) {
            return lc2h$invokeCreate(null, provider);
        }
        BuildingInfoCacheScope scope = BuildingInfoCacheRegistry.scope(provider);
        BuildingInfo cached = scope.buildingInfo.get(coord);
        if (cached != null) {
            BuildingInfoDiagnostics.recordBuildingInfoMemoryHit();
            return cached;
        }

        ReentrantLock lock = scope.buildingLocks.computeIfAbsent(coord, ignored -> new ReentrantLock());
        long waitStartNs = System.nanoTime();
        if (!lock.tryLock()) {
            // Do not make a server or cooperative generation worker wait for a
            // different worker that is constructing this coordinate.
            BuildingInfo created = lc2h$invokeCreate(coord, provider);
            BuildingInfo published = scope.buildingInfo.putIfAbsent(coord, created);
            return published == null ? created : published;
        }
        try {
            BuildingInfoDiagnostics.recordBuildingInfoLockWait(System.nanoTime() - waitStartNs);
            cached = scope.buildingInfo.get(coord);
            if (cached != null) {
                BuildingInfoDiagnostics.recordBuildingInfoMemoryHit();
                return cached;
            }
            BuildingInfo created = lc2h$invokeCreate(coord, provider);
            BuildingInfo published = scope.buildingInfo.putIfAbsent(coord, created);
            BuildingInfo result = published == null ? created : published;
            if (published == null) {
                BuildingInfoDiagnostics.recordBuildingInfoCreate();
            }
            return result;
        } finally {
            lock.unlock();
            if (!scope.buildingInfo.containsKey(coord) && !lock.isLocked() && !lock.hasQueuedThreads()) {
                scope.buildingLocks.remove(coord, lock);
            }
        }
    }

    @Unique
    private static LostChunkCharacteristics lc2h$nativeResolve(
        ChunkCoord coord, IDimensionInfo provider) {
        if (coord == null || coord.dimension() == null) {
            return getChunkCharacteristicsLocked(coord, provider);
        }
        BuildingInfoCacheScope scope = BuildingInfoCacheRegistry.scope(provider);
        ReentrantLock lock = scope.buildingLocks.computeIfAbsent(coord, ignored -> new ReentrantLock());
        if (!lock.tryLock()) {
            return getChunkCharacteristicsLocked(coord, provider);
        }
        try {
            return getChunkCharacteristicsLocked(coord, provider);
        } finally {
            lock.unlock();
        }
    }

    @Unique
    private record FlightKey(BuildingInfoCacheScope scope, ChunkCoord coord) {
    }
}
