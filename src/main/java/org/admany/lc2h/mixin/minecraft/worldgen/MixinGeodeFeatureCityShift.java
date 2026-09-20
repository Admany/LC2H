package org.admany.lc2h.mixin.minecraft.worldgen;

import mcjty.lostcities.config.LostCityProfile;
import mcjty.lostcities.worldgen.IDimensionInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.GeodeFeature;
import net.minecraft.world.level.levelgen.feature.configurations.GeodeConfiguration;
import net.minecraft.server.level.WorldGenRegion;
import org.admany.lc2h.config.ConfigManager;
import org.admany.lc2h.util.server.DimensionInfoAccessor;
import org.admany.lc2h.worldgen.terrain.CityShiftField;
import org.admany.lc2h.worldgen.terrain.NaturalHeightSampler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GeodeFeature.class)
public abstract class MixinGeodeFeatureCityShift {

    @Unique
    private static final ThreadLocal<Boolean> LC2H_SHIFTED_GEODE = new ThreadLocal<>();

    @Inject(method = "place", at = @At("HEAD"), cancellable = true)
    private void lc2h$moveGeodeWithBlendedTerrain(FeaturePlaceContext<GeodeConfiguration> context,
                                                  CallbackInfoReturnable<Boolean> cir) {
        if (Boolean.TRUE.equals(LC2H_SHIFTED_GEODE.get())
            || !ConfigManager.CITY_BLEND_ENABLED
            || context == null
            || !(context.level() instanceof WorldGenRegion region)) {
            return;
        }

        IDimensionInfo provider = DimensionInfoAccessor.getForLevel(region);
        if (provider == null || provider.getType() == null) {
            return;
        }
        LostCityProfile profile;
        try {
            profile = provider.getProfile();
        } catch (Throwable ignored) {
            return;
        }
        if (profile == null) {
            return;
        }
        NaturalHeightSampler.LevelSampler heights = NaturalHeightSampler.forLevel(region);
        CityShiftField.Context shiftContext = CityShiftField.context(provider, profile, heights);
        BlockPos origin = context.origin();
        if (shiftContext == null || origin == null) {
            return;
        }

        double shift = CityShiftField.sample(shiftContext, origin.getX(), origin.getZ());
        int verticalShift = (int) Math.round(shift);
        if (verticalShift <= 0) {
            return;
        }

        FeaturePlaceContext<GeodeConfiguration> shiftedContext = new FeaturePlaceContext<>(
            context.topFeature(),
            context.level(),
            context.chunkGenerator(),
            context.random(),
            origin.offset(0, -verticalShift, 0),
            context.config());
        LC2H_SHIFTED_GEODE.set(Boolean.TRUE);
        try {
            cir.setReturnValue(((GeodeFeature) (Object) this).place(shiftedContext));
        } finally {
            LC2H_SHIFTED_GEODE.remove();
        }
    }
}
