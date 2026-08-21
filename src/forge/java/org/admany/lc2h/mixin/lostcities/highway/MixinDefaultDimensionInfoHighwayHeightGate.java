package org.admany.lc2h.mixin.lostcities.highway;

import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.ChunkHeightmap;
import mcjty.lostcities.worldgen.DefaultDimensionInfo;
import org.admany.lc2h.worldgen.lostcities.HighwayHeightmapPrefetchContext;
import org.admany.lc2h.worldgen.terrain.NaturalHeightSampler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(value = DefaultDimensionInfo.class, remap = false)
public abstract class MixinDefaultDimensionInfoHighwayHeightGate {

    @Redirect(
        method = "applyHighwayCityConstraints",
        at = @At(
            value = "INVOKE",
            target = "Lmcjty/lostcities/worldgen/DefaultDimensionInfo;getHeightmap(Lmcjty/lostcities/varia/ChunkCoord;)Lmcjty/lostcities/worldgen/ChunkHeightmap;"
        )
    )
    private ChunkHeightmap lc2h$avoidColdHeightBuildDuringHubScoring(DefaultDimensionInfo instance,
                                                                      ChunkCoord coord) {
        if (!HighwayHeightmapPrefetchContext.isActive()) {
            return instance.getHeightmap(coord);
        }

        int height = instance.getProfile().GROUNDLEVEL;
        NaturalHeightSampler.LevelSampler sampler = NaturalHeightSampler.forLevel(instance.getWorld());
        if (sampler != null) {
            Integer resident = sampler.cachedChunkHeight(coord.chunkX(), coord.chunkZ());
            if (resident != null) {
                height = resident;
            }
        }

        ChunkHeightmap result = new ChunkHeightmap(
            instance.getProfile().LANDSCAPE_TYPE,
            instance.getProfile().GROUNDLEVEL
        );
        result.update(height);
        return result;
    }
}
