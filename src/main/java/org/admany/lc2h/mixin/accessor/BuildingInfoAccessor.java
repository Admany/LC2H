package org.admany.lc2h.mixin.accessor;

import mcjty.lostcities.api.ILostCityBuilding;
import mcjty.lostcities.config.LostCityProfile;
import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.lost.BuildingInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = BuildingInfo.class, remap = false)
public interface BuildingInfoAccessor {

    @Accessor("coord")
    ChunkCoord lc2h$getCoord();

    @Accessor("profile")
    LostCityProfile lc2h$getProfile();

    @Accessor("buildingType")
    ILostCityBuilding lc2h$getBuildingType();

    @Accessor("floors")
    int lc2h$getFloors();

    @Accessor("cellars")
    int lc2h$getCellars();

    @Accessor("cityLevel")
    int lc2h$getCityLevel();
}
