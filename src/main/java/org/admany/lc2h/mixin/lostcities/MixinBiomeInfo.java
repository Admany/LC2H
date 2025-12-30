package org.admany.lc2h.mixin.lostcities;

import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.ChunkHeightmap;
import mcjty.lostcities.worldgen.IDimensionInfo;
import mcjty.lostcities.worldgen.lost.BiomeInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import org.admany.lc2h.mixin.accessor.BiomeInfoAccessor;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;

@Mixin(value = BiomeInfo.class, remap = false)
public class MixinBiomeInfo {

    @Shadow @Final
    private static Map<ChunkCoord, BiomeInfo> BIOME_INFO_MAP;

    @Inject(method = "getBiomeInfo", at = @At("HEAD"), cancellable = true)
    private static void lc2h$getBiomeInfoThreadSafe(IDimensionInfo provider, ChunkCoord coord, CallbackInfoReturnable<BiomeInfo> cir) {
        if (provider == null || coord == null) {
            cir.setReturnValue(null);
            return;
        }
        synchronized (BIOME_INFO_MAP) {
            BiomeInfo existing = BIOME_INFO_MAP.get(coord);
            if (existing != null) {
                cir.setReturnValue(existing);
                return;
            }

            BiomeInfo info = new BiomeInfo();
            ChunkHeightmap heightmap = provider.getHeightmap(coord);
            int chunkX = coord.chunkX();
            int chunkZ = coord.chunkZ();
            int y = heightmap != null ? heightmap.getHeight() : 64;
            Holder<Biome> biome = provider.getBiome(new BlockPos((chunkX << 4) + 8, y, (chunkZ << 4) + 8));
            ((BiomeInfoAccessor) (Object) info).lc2h$setMainBiome(biome);

            BIOME_INFO_MAP.put(coord, info);
            cir.setReturnValue(info);
        }
    }
}
