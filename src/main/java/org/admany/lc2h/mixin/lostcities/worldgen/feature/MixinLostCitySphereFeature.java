package org.admany.lc2h.mixin.lostcities.worldgen.feature;

import mcjty.lostcities.setup.Registration;
import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.IDimensionInfo;
import mcjty.lostcities.worldgen.LostCitySphereFeature;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;

import org.admany.lc2h.dev.diagnostics.CriticalMixinHookValidator;
import org.admany.lc2h.dev.debug.WorldParityObservedChunkTracker;
import org.admany.lc2h.worldgen.async.warmup.AsyncChunkWarmup;
import org.admany.lc2h.worldgen.lostcities.LostCityFeatureGuards;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.atomic.AtomicBoolean;

@Mixin(value = LostCitySphereFeature.class, remap = false)
public class MixinLostCitySphereFeature {
    private static final AtomicBoolean LC2H_LOGGED_SPHERE_PLACE_NOTE = new AtomicBoolean(false);

    @Inject(method = "place(Lnet/minecraft/world/level/levelgen/feature/FeaturePlaceContext;)Z", at = @At("HEAD"), remap = true)
    private void lc2h$warmupSphere(FeaturePlaceContext<?> context, CallbackInfoReturnable<Boolean> cir) {
        CriticalMixinHookValidator.markObserved(CriticalMixinHookValidator.LOST_CITY_SPHERE_PLACE_HEAD);
        if (LostCityFeatureGuards.TRACE_PLACE && LC2H_LOGGED_SPHERE_PLACE_NOTE.compareAndSet(false, true)) {
            org.admany.lc2h.LC2H.LOGGER.debug(
                "[LC2H] LostCitySphereFeature.place was observed; this warms sphere generation only and does not imply LostCityFeature terrain generate redirect will run"
            );
        }
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
        WorldParityObservedChunkTracker.record(coord);
        if (AsyncChunkWarmup.shouldWarmupFromCurrentThread()
            && AsyncChunkWarmup.shouldAcceptPreschedule()
            && !AsyncChunkWarmup.isPreScheduled(coord)) {
            AsyncChunkWarmup.preSchedule(provider, coord);
        }
    }
}
