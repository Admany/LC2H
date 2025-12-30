package org.admany.lc2h.tweaks;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class TweaksStagedPipeline {

    private TweaksStagedPipeline() {
    }

    public static CompletableFuture<ComputationResult> runPipeline(ComputationRequest request,
                                                                   Supplier<Object> computeStage) {
        return runPipeline(request, null, computeStage, null, null);
    }

    public static CompletableFuture<ComputationResult> runPipeline(ComputationRequest request,
                                                                   Runnable scatterStage,
                                                                   Supplier<Object> computeStage,
                                                                   Consumer<ComputationResult> gatherStage,
                                                                   Consumer<ComputationResult> compileStage) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(computeStage, "computeStage");

        Optional<ComputationResult> cached = TweaksActorSystem.getValidatedResult(request.key());
        CompletableFuture<ComputationResult> baseFuture;

        if (cached.isPresent()) {
            baseFuture = CompletableFuture.completedFuture(cached.get());
        } else {
            CompletableFuture<Void> scatterFuture = scatterStage != null
                    ? org.admany.lc2h.async.AsyncManager.submitTask("scatter", scatterStage, null, org.admany.lc2h.async.Priority.LOW)
                    : CompletableFuture.completedFuture(null);

            baseFuture = scatterFuture.thenComposeAsync(
                    unused -> TweaksActorSystem.submit(request, computeStage),
                    ForkJoinPool.commonPool()
            );
        }

        CompletableFuture<ComputationResult> gathered = baseFuture.thenApplyAsync(result -> {
            if (gatherStage != null) {
                gatherStage.accept(result);
            }
            return result;
        }, ForkJoinPool.commonPool());

        return gathered.thenApplyAsync(result -> {
            if (compileStage != null) {
                compileStage.accept(result);
            }
            return result;
        }, ForkJoinPool.commonPool());
    }

    public static void shutdown() {
        TweaksActorSystem.shutdown();
    }
}