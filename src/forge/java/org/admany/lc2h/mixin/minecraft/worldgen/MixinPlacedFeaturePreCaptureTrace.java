package org.admany.lc2h.mixin.minecraft.worldgen;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import org.admany.lc2h.dev.debug.PreCaptureTargetTraceRegistry;
import org.admany.lc2h.worldgen.lostcities.DeferredTreeCaptureContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PlacedFeature.class)
public abstract class MixinPlacedFeaturePreCaptureTrace {

    @Inject(
        method = "placeWithBiomeCheck(Lnet/minecraft/world/level/WorldGenLevel;Lnet/minecraft/world/level/chunk/ChunkGenerator;Lnet/minecraft/util/RandomSource;Lnet/minecraft/core/BlockPos;)Z",
        at = @At("HEAD"))
    private void lc2h$tracePlacedFeatureEnter(WorldGenLevel level,
                                              ChunkGenerator generator,
                                              RandomSource random,
                                              BlockPos origin,
                                              CallbackInfoReturnable<Boolean> cir) {
        // TreeFeature capture must finish before biome decoration advances to
        // another top-level placed feature.  Do not let an aborted tree call
        // virtualize/capture unrelated vegetation such as glow lichen.
        DeferredTreeCaptureContext.discardStalePlacedFeatureCapture();
        PreCaptureTargetTraceRegistry.recordDecorationPlacedFeatureEnter(
            lc2h$placedFeatureId(),
            lc2h$configuredFeatureId(),
            lc2h$featureClassName(),
            origin);
    }

    @Inject(
        method = "placeWithBiomeCheck(Lnet/minecraft/world/level/WorldGenLevel;Lnet/minecraft/world/level/chunk/ChunkGenerator;Lnet/minecraft/util/RandomSource;Lnet/minecraft/core/BlockPos;)Z",
        at = @At("RETURN"))
    private void lc2h$tracePlacedFeatureExit(WorldGenLevel level,
                                             ChunkGenerator generator,
                                             RandomSource random,
                                             BlockPos origin,
                                             CallbackInfoReturnable<Boolean> cir) {
        PreCaptureTargetTraceRegistry.recordDecorationPlacedFeatureExit(
            lc2h$placedFeatureId(),
            lc2h$configuredFeatureId(),
            lc2h$featureClassName(),
            origin,
            cir.getReturnValueZ());
    }

    private String lc2h$placedFeatureId() {
        try {
            return ((PlacedFeature) (Object) this).toString();
        } catch (Throwable ignored) {
            return "unknown";
        }
    }

    private String lc2h$configuredFeatureId() {
        try {
            Holder<ConfiguredFeature<?, ?>> holder = ((PlacedFeature) (Object) this).feature();
            if (holder == null) {
                return "unknown";
            }
            ResourceKey<ConfiguredFeature<?, ?>> key = holder.unwrapKey().orElse(null);
            if (key != null) {
                ResourceLocation location = key.location();
                if (location != null) {
                    return location.toString();
                }
            }
            ConfiguredFeature<?, ?> configuredFeature = holder.value();
            return configuredFeature == null ? "unknown" : configuredFeature.toString();
        } catch (Throwable ignored) {
            return "unknown";
        }
    }

    private String lc2h$featureClassName() {
        try {
            Holder<ConfiguredFeature<?, ?>> holder = ((PlacedFeature) (Object) this).feature();
            ConfiguredFeature<?, ?> configuredFeature = holder == null ? null : holder.value();
            Feature<?> feature = configuredFeature == null ? null : configuredFeature.feature();
            return feature == null ? "unknown" : feature.getClass().getName();
        } catch (Throwable ignored) {
            return "unknown";
        }
    }
}
