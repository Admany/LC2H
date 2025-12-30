package org.admany.lc2h.tweaks;

import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.IDimensionInfo;
import mcjty.lostcities.worldgen.lost.BuildingInfo;
import mcjty.lostcities.worldgen.lost.MultiChunk;
import org.admany.lc2h.mixin.accessor.MultiChunkAccessor;

import java.lang.reflect.Field;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

public final class TweaksActorSystem {

    private static final ConcurrentHashMap<ComputationRequestKey, CompletableFuture<ComputationResult>> IN_FLIGHT = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<ComputationRequestKey, ComputationResult> VALIDATED_RESULTS = new ConcurrentHashMap<>();

    private TweaksActorSystem() {
    }

 
    public static CompletableFuture<ComputationResult> submit(ComputationRequest request, Supplier<Object> supplier) {
        ComputationRequestKey key = request.key();
        return IN_FLIGHT.computeIfAbsent(key, unused -> {
            CompletableFuture<ComputationResult> future = org.admany.lc2h.async.AsyncManager.submitSupplier("actor", () -> execute(request, supplier), org.admany.lc2h.async.Priority.LOW)
                    .thenApply(result -> {
                        if (!isResultLegit(result)) {
                            throw new IllegalStateException("Rejected async result for " + describe(key));
                        }
                        VALIDATED_RESULTS.put(key, result);
                        return result;
                    });

            future.whenComplete((res, err) -> {
                if (err != null) {
                    VALIDATED_RESULTS.remove(key);
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
        return Optional.ofNullable(VALIDATED_RESULTS.get(key));
    }

    public static void shutdown() {
    }

    public static int getInFlightCount() {
        return IN_FLIGHT.size();
    }

    public static int getValidatedCount() {
        return VALIDATED_RESULTS.size();
    }

    private static String describe(ComputationRequestKey key) {
        return key.type() + "@" + key.coord().chunkX() + "," + key.coord().chunkZ();
    }
}