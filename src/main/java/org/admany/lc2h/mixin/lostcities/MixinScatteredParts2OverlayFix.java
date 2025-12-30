package org.admany.lc2h.mixin.lostcities;

import mcjty.lostcities.worldgen.LostCityTerrainFeature;
import mcjty.lostcities.worldgen.gen.Scattered;
import mcjty.lostcities.worldgen.lost.BuildingInfo;
import mcjty.lostcities.worldgen.lost.Transform;
import mcjty.lostcities.worldgen.lost.cityassets.IBuildingPart;
import org.admany.lc2h.logging.config.ConfigManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(value = Scattered.class, remap = false)
public abstract class MixinScatteredParts2OverlayFix {

    @Unique
    private static final ThreadLocal<Integer> LC2H_SCATTERED_BASE_Y = ThreadLocal.withInitial(() -> 0);

    @Redirect(
            method = "generateScatteredBuilding",
            at = @At(
                    value = "INVOKE",
                    target = "Lmcjty/lostcities/worldgen/LostCityTerrainFeature;generatePart(Lmcjty/lostcities/worldgen/lost/BuildingInfo;Lmcjty/lostcities/worldgen/lost/cityassets/IBuildingPart;Lmcjty/lostcities/worldgen/lost/Transform;IIILmcjty/lostcities/worldgen/LostCityTerrainFeature$HardAirSetting;)I",
                    ordinal = 0
            )
    )
    private static int lc2h$storeBaseYAndGenerate(
            LostCityTerrainFeature feature,
            BuildingInfo info,
            IBuildingPart part,
            Transform transform,
            int ox,
            int oy,
            int oz,
            LostCityTerrainFeature.HardAirSetting airWaterLevel
    ) {
        if (ConfigManager.ENABLE_SCATTERED_PARTS2_OVERLAY_FIX) {
            LC2H_SCATTERED_BASE_Y.set(oy);
        }
        return feature.generatePart(info, part, transform, ox, oy, oz, airWaterLevel);
    }

    @Redirect(
            method = "generateScatteredBuilding",
            at = @At(
                    value = "INVOKE",
                    target = "Lmcjty/lostcities/worldgen/LostCityTerrainFeature;generatePart(Lmcjty/lostcities/worldgen/lost/BuildingInfo;Lmcjty/lostcities/worldgen/lost/cityassets/IBuildingPart;Lmcjty/lostcities/worldgen/lost/Transform;IIILmcjty/lostcities/worldgen/LostCityTerrainFeature$HardAirSetting;)I",
                    ordinal = 1
            )
    )
    private static int lc2h$generatePart2AtBaseY(
            LostCityTerrainFeature feature,
            BuildingInfo info,
            IBuildingPart part,
            Transform transform,
            int ox,
            int oy,
            int oz,
            LostCityTerrainFeature.HardAirSetting airWaterLevel
    ) {
        if (!ConfigManager.ENABLE_SCATTERED_PARTS2_OVERLAY_FIX) {
            return feature.generatePart(info, part, transform, ox, oy, oz, airWaterLevel);
        }
        int baseY = LC2H_SCATTERED_BASE_Y.get();
        return feature.generatePart(info, part, transform, ox, baseY, oz, airWaterLevel);
    }
}

