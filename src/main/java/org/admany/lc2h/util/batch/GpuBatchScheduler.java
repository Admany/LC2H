package org.admany.lc2h.util.batch;

import org.admany.lc2h.LC2H;
import org.admany.quantified.core.common.util.TaskScheduler;
import org.admany.quantified.core.common.util.TaskScheduler.ResourceHint;
import org.admany.quantified.core.common.util.TaskScheduler.TaskBatchItem;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public final class GpuBatchScheduler {

    private static final int MAX_BATCH = Integer.getInteger("lc.gpu_batch.size", 200);

    private GpuBatchScheduler() {
    }

    public static <T> CompletableFuture<List<T>> submit(String name, List<TaskBatchItem<T>> tasks) {
        if (tasks == null || tasks.isEmpty()) {
            return CompletableFuture.completedFuture(List.of());
        }
        if (tasks.size() > MAX_BATCH) {
            List<CompletableFuture<List<T>>> futures = new ArrayList<>();
            for (int i = 0; i < tasks.size(); i += MAX_BATCH) {
                int end = Math.min(tasks.size(), i + MAX_BATCH);
                futures.add(submitOnce(name + "-" + i / MAX_BATCH, tasks.subList(i, end)));
            }
            return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                    .thenApply(v -> {
                        List<T> combined = new ArrayList<>(tasks.size());
                        for (CompletableFuture<List<T>> f : futures) {
                            combined.addAll(f.join());
                        }
                        return combined;
                    });
        }
        return submitOnce(name, tasks);
    }

    private static <T> CompletableFuture<List<T>> submitOnce(String name, List<TaskBatchItem<T>> tasks) {
        try {
            return TaskScheduler.submitBatch(
                    LC2H.MODID,
                    name,
                    tasks,
                    task -> task.gpuTask() != null ? ResourceHint.GPU : ResourceHint.CPU
            );
        } catch (Throwable t) {
            LC2H.LOGGER.debug("[LC2H] GPU batch submit fallback to CPU ({}): {}", name, t.toString());
            return runCpu(tasks);
        }
    }

    private static <T> CompletableFuture<List<T>> runCpu(List<TaskBatchItem<T>> tasks) {
        return CompletableFuture.supplyAsync(() -> {
            List<T> results = new ArrayList<>(tasks.size());
            for (TaskBatchItem<T> task : tasks) {
                try {
                    results.add(task.cpuImplementation().get());
                } catch (Exception e) {
                    throw new CompletionException(e);
                }
            }
            return results;
        });
    }
}
