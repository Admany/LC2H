package org.admany.lc2h.worldgen.async.generator;

import org.admany.lc2h.LC2H;
import org.admany.lc2h.async.AsyncManager;
import org.admany.lc2h.data.cache.FeatureCache;

public class AsyncNoiseGenerator {

    public static void generateNoiseAsync(int chunkX, int chunkZ) {
        String cacheKey = "noise_" + chunkX + "_" + chunkZ;

        Object cached = FeatureCache.get(cacheKey);
        if (cached != null) {
            LC2H.LOGGER.debug("Using cached noise for " + cacheKey);
            return;
        }

        AsyncManager.submitTask("noise_gen", () -> {
            LC2H.LOGGER.info("Generating noise asynchronously for chunk " + chunkX + "," + chunkZ);
        }, new Object()).thenAccept(result -> {
            FeatureCache.put(cacheKey, result);
            AsyncManager.syncToMain(() -> {
                LC2H.LOGGER.debug("Noise generation completed for " + cacheKey);
            });
        });
    }
}