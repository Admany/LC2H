package org.admany.lc2h.mixin;

import mcjty.lostcities.setup.Registration;
import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.IDimensionInfo;
import mcjty.lostcities.worldgen.LostCitySphereFeature;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;

import org.admany.lc2h.worldgen.async.warmup.AsyncChunkWarmup;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = LostCitySphereFeature.class, remap = false)
public class MixinLostCitySphereFeature {

    @Inject(method = "place", at = @At("HEAD"), remap = true)
    private void lc2h$warmupSphere(FeaturePlaceContext<?> context, CallbackInfoReturnable<Boolean> cir) {
        WorldGenLevel level = context.level();
        if (!(level instanceof WorldGenRegion)) {
            return;
        }

        IDimensionInfo provider = Registration.LOSTCITY_FEATURE.get().getDimensionInfo(level);
        if (provider == null) {
            return;
        }

        provider.setWorld(level);

        ChunkCoord coord = new ChunkCoord(
            provider.dimension(),
            context.origin().getX() >> 4,
            context.origin().getZ() >> 4
        );
        if (!AsyncChunkWarmup.isPreScheduled(coord)) {
            AsyncChunkWarmup.preSchedule(provider, coord);
        }
    }
}