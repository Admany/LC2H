package org.admany.lc2h.worldgen.async.generator;

import org.admany.lc2h.LC2H;
import org.admany.lc2h.concurrency.async.AsyncManager;
import org.admany.lc2h.data.cache.FeatureCache;

public class AsyncStreetGenerator {

    public static void generateStreetsAsync(int cityX, int cityZ) {
        String cacheKey = "streets_" + cityX + "_" + cityZ;

        Object cached = FeatureCache.get(cacheKey);
        if (cached != null) {
            LC2H.LOGGER.debug("Using cached streets for " + cacheKey);
            return;
        }

        AsyncManager.submitTask("street_gen", () -> {
            LC2H.LOGGER.info("Generating streets asynchronously for city at " + cityX + "," + cityZ);
        }, new Object()).thenAccept(result -> {
            FeatureCache.put(cacheKey, result);
            AsyncManager.syncToMain(() -> {
                LC2H.LOGGER.debug("Street generation completed for " + cacheKey);
            });
        });
    }
}
