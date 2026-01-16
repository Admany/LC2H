package org.admany.lc2h.tweaks;

import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.IDimensionInfo;
import mcjty.lostcities.worldgen.lost.BuildingInfo;
import mcjty.lostcities.worldgen.lost.MultiChunk;
import org.admany.lc2h.mixin.accessor.lostcities.MultiChunkAccessor;

import java.lang.reflect.Field;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

public final class TweaksActorSystem {

    private static final ConcurrentHashMap<ComputationRequestKey, CompletableFuture<ComputationResult>> IN_FLIGHT = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<ComputationRequestKey, ComputationResult> VALIDATED_RESULTS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<ComputationRequestKey, Long> VALIDATED_TS = new ConcurrentHashMap<>();
    private static final long VALIDATED_TTL_MS = Math.max(0L, Long.getLong("lc2h.tweaks.validatedTtlMs", TimeUnit.MINUTES.toMillis(10)));
    private static final int VALIDATED_PRUNE_SCAN = Math.max(10, Integer.getInteger("lc2h.tweaks.validatedPruneScan", 512));
    private static final int VALIDATED_PRUNE_EVERY = Math.max(10, Integer.getInteger("lc2h.tweaks.validatedPruneEvery", 512));
    private static final AtomicInteger validatedOps = new AtomicInteger(0);

    private TweaksActorSystem() {
    }

 
    public static CompletableFuture<ComputationResult> submit(ComputationRequest request, Supplier<Object> supplier) {
        ComputationRequestKey key = request.key();
        return IN_FLIGHT.computeIfAbsent(key, unused -> {
            CompletableFuture<ComputationResult> future = org.admany.lc2h.concurrency.async.AsyncManager.submitSupplier("actor", () -> execute(request, supplier), org.admany.lc2h.concurrency.async.Priority.LOW)
                    .thenApply(result -> {
                        if (!isResultLegit(result)) {
                            throw new IllegalStateException("Rejected async result for " + describe(key));
                        }
                        VALIDATED_RESULTS.put(key, result);
                        VALIDATED_TS.put(key, System.currentTimeMillis());
                        return result;
                    });

            future.whenComplete((res, err) -> {
                if (err != null) {
                    VALIDATED_RESULTS.remove(key);
                    VALIDATED_TS.remove(key);
                }
                IN_FLIGHT.remove(key, future);
            });

            return future;
        });
    }

    private static ComputationResult execute(ComputationRequest request, Supplier<Object> supplier) {
        long start = System.nanoTime();
        Object payload = supplier.get();
        long end = System.nanoTime();
        return new ComputationResult(request, payload, end - start);
    }

    private static boolean isResultLegit(ComputationResult result) {
        return switch (result.request().type()) {
            case MULTI_CHUNK -> validateMultiChunk(result);
            case BUILDING_INFO -> validateBuildingInfo(result);
        };
    }

    private static boolean validateMultiChunk(ComputationResult result) {
        if (!(result.payload() instanceof MultiChunk multiChunk)) {
            return false;
        }

        IDimensionInfo provider = result.request().provider();
        if (provider == null) {
            return false;
        }

        MultiChunkAccessor accessor = (MultiChunkAccessor) (Object) multiChunk;
        try {
            int expectedArea = provider.getWorldStyle().getMultiSettings().areasize();
            if (expectedArea > 0 && accessor.lc2h$getAreaSize() != expectedArea) {
                return false;
            }
        } catch (Throwable ignored) {
            return false;
        }

        return accessor.lc2h$getTopLeft() != null;
    }

    private static boolean validateBuildingInfo(ComputationResult result) {
        if (!(result.payload() instanceof BuildingInfo info)) {
            return false;
        }

        ChunkCoord infoCoord = resolveCoord(info);
        ChunkCoord requestCoord = result.request().coord();
        return infoCoord == null || requestCoord.equals(infoCoord);
    }

    private static ChunkCoord resolveCoord(BuildingInfo info) {
        try {
            Field field = info.getClass().getField("coord");
            field.setAccessible(true);
            Object value = field.get(info);
            if (value instanceof ChunkCoord coord) {
                return coord;
            }
        } catch (ReflectiveOperationException ignored) {
        }
        return null;
    }

    public static Optional<ComputationResult> getValidatedResult(ComputationRequestKey key) {
        if (key == null) {
            return Optional.empty();
        }
        long now = System.currentTimeMillis();
        if (VALIDATED_TTL_MS > 0) {
            Long ts = VALIDATED_TS.get(key);
            if (ts == null || (now - ts) > VALIDATED_TTL_MS) {
                VALIDATED_RESULTS.remove(key);
                VALIDATED_TS.remove(key);
                return Optional.empty();
            }
        }
        ComputationResult result = VALIDATED_RESULTS.get(key);
        if (result != null && VALIDATED_TTL_MS > 0) {
            VALIDATED_TS.put(key, now);
        }
        maybePruneValidated(now);
        return Optional.ofNullable(result);
    }

    public static void shutdown() {
        VALIDATED_RESULTS.clear();
        VALIDATED_TS.clear();
    }

    public static int getInFlightCount() {
        return IN_FLIGHT.size();
    }

    public static int getValidatedCount() {
        return VALIDATED_RESULTS.size();
    }

    public static int pruneExpiredEntries() {
        return pruneExpiredEntries(System.currentTimeMillis());
    }

    private static int pruneExpiredEntries(long now) {
        if (VALIDATED_TTL_MS <= 0 || VALIDATED_TS.isEmpty()) {
            return 0;
        }
        int removed = 0;
        int scanned = 0;
        for (var entry : VALIDATED_TS.entrySet()) {
            if (VALIDATED_PRUNE_SCAN > 0 && scanned >= VALIDATED_PRUNE_SCAN) {
                break;
            }
            scanned++;
            Long ts = entry.getValue();
            if (ts != null && (now - ts) > VALIDATED_TTL_MS) {
                ComputationRequestKey key = entry.getKey();
                if (VALIDATED_TS.remove(key, ts)) {
                    VALIDATED_RESULTS.remove(key);
                    removed++;
                }
            }
        }
        return removed;
    }

    private static void maybePruneValidated(long now) {
        if (VALIDATED_TTL_MS <= 0) {
            return;
        }
        int ops = validatedOps.incrementAndGet();
        if (ops < VALIDATED_PRUNE_EVERY) {
            return;
        }
        if (!validatedOps.compareAndSet(ops, 0)) {
            return;
        }
        pruneExpiredEntries(now);
    }

    private static String describe(ComputationRequestKey key) {
        return key.type() + "@" + key.coord().chunkX() + "," + key.coord().chunkZ();
    }
}
