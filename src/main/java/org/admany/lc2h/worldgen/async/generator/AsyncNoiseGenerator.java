package org.admany.lc2h.worldgen.async.generator;

import org.admany.lc2h.LC2H;
import org.admany.lc2h.concurrency.async.AsyncManager;
import org.admany.lc2h.data.cache.FeatureCache;
import org.admany.lc2h.worldgen.noise.CheapChunkNoiseField;
import mcjty.lostcities.varia.ChunkCoord;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;

public class AsyncNoiseGenerator {

    private static final ResourceKey<net.minecraft.world.level.Level> OVERWORLD =
        ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, ResourceLocation.withDefaultNamespace("overworld"));

    public static void generateNoiseAsync(int chunkX, int chunkZ) {
        String cacheKey = "noise_" + chunkX + "_" + chunkZ;
        FeatureCache.containsAsync(cacheKey).thenAccept(cached -> {
            if (Boolean.TRUE.equals(cached)) {
                LC2H.LOGGER.debug("Using cached noise for " + cacheKey);
                return;
            }

            AsyncManager.submitSupplier("noise_gen", () -> {
                ChunkCoord coord = new ChunkCoord(OVERWORLD, chunkX, chunkZ);
                return CheapChunkNoiseField.getOrCompute(coord);
            }).thenAccept(result -> {
                FeatureCache.put(cacheKey, result);
                LC2H.LOGGER.debug("Noise generation completed for {}", cacheKey);
            });
        }).exceptionally(t -> {
            LC2H.LOGGER.error("Noise cache lookup failed for {}: {}", cacheKey, t.getMessage());
            return null;
        });
    }
}
