package org.admany.lc2h.mixin.lostcities.cache;

import mcjty.lostcities.config.LostCityProfile;
import mcjty.lostcities.setup.Config;
import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.varia.TimedCache;
import mcjty.lostcities.worldgen.ChunkHeightmap;
import mcjty.lostcities.worldgen.IDimensionInfo;
import mcjty.lostcities.worldgen.LostCityTerrainFeature;
import net.minecraft.world.level.WorldGenLevel;
import org.admany.lc2h.data.cache.AsyncHeightmapCoordinator;
import org.admany.lc2h.data.cache.LostCitiesCacheBudgetManager;
import org.admany.lc2h.worldgen.scope.WorldGenScope;
import org.admany.lc2h.worldgen.terrain.LostCitiesHeightBranchKernel;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import javax.annotation.Nonnull;

@Mixin(value = LostCityTerrainFeature.class, remap = false)
public abstract class MixinLostCityTerrainFeatureHeightmapCache {
    @Shadow @Final public LostCityProfile profile;
    @Shadow @Final public IDimensionInfo provider;
    @Shadow @Final private TimedCache<ChunkCoord, ChunkHeightmap> cachedHeightmaps;

    @Shadow
    private void generateHeightmap(int chunkX, int chunkZ, WorldGenLevel region, ChunkHeightmap heightmap) {
        throw new AssertionError();
    }

    @Unique
    private static final LostCitiesCacheBudgetManager.CacheGroup LC2H_HEIGHTMAP_BUDGET =
        LostCitiesCacheBudgetManager.register("lc_heightmap", 2048, 256, ignored -> false);

    /** Calculate a heightmap outside Lost Cities' feature monitor. */
    @Overwrite
    public ChunkHeightmap getHeightmap(ChunkCoord chunk, @Nonnull WorldGenLevel world) {
        ChunkHeightmap local = cachedHeightmaps.get(chunk);
        if (local != null) {
            LostCitiesCacheBudgetManager.recordAccess(LC2H_HEIGHTMAP_BUDGET, chunk);
            return local;
        }

        AsyncHeightmapCoordinator.SamplePlan sample = lc2h$sample(chunk);
        WorldGenScope.CacheScope scope = WorldGenScope.cache(provider);
        ChunkHeightmap shared = AsyncHeightmapCoordinator.get(
            scope,
            chunk,
            sample,
            plan -> lc2h$calculate(plan, world)
        );
        boolean inserted = cachedHeightmaps.get(chunk) == null;
        cachedHeightmaps.put(chunk, shared);
        LostCitiesCacheBudgetManager.recordPut(
            LC2H_HEIGHTMAP_BUDGET,
            chunk,
            LC2H_HEIGHTMAP_BUDGET.defaultEntryBytes(),
            inserted
        );
        return shared;
    }

    @Unique
    private ChunkHeightmap lc2h$calculate(AsyncHeightmapCoordinator.SamplePlan plan, WorldGenLevel world) {
        WorldGenScope.CacheScope scope = WorldGenScope.cache(provider);
        return LostCitiesHeightBranchKernel.evaluate(scope, plan, world, profile, () -> {
            ChunkHeightmap result = new ChunkHeightmap(profile.LANDSCAPE_TYPE, profile.GROUNDLEVEL);
            generateHeightmap(plan.sampler().chunkX(), plan.sampler().chunkZ(), world, result);
            return result;
        });
    }

    @Unique
    private static AsyncHeightmapCoordinator.SamplePlan lc2h$sample(ChunkCoord chunk) {
        int size = Math.max(1, (Integer) Config.HEIGHT_SAMPLE_SIZE.get());
        int top = chunk.chunkX();
        int left = chunk.chunkZ();
        int directionX = 1;
        int directionZ = 1;
        ChunkCoord sampler = chunk;

        if (size > 1) {
            top = chunk.chunkX() / size * size;
            left = chunk.chunkZ() / size * size;
            directionX = chunk.chunkX() < 0 ? -1 : 1;
            directionZ = chunk.chunkZ() < 0 ? -1 : 1;
            // Lost Cities 7.5.5 samples the centre of every grouped area, including size 2.
            if (size > 1) {
                int offset = size / 2;
                sampler = new ChunkCoord(
                    chunk.dimension(),
                    top + offset * directionX,
                    left + offset * directionZ
                );
            }
        }
        AsyncHeightmapCoordinator.FlightKey flightKey = new AsyncHeightmapCoordinator.FlightKey(
            chunk.dimension(), top, left, directionX, directionZ, size
        );
        return new AsyncHeightmapCoordinator.SamplePlan(
            sampler, top, left, directionX, directionZ, size, flightKey
        );
    }
}
