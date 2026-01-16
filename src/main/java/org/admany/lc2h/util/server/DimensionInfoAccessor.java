package org.admany.lc2h.util.server;

import mcjty.lostcities.setup.Registration;
import mcjty.lostcities.worldgen.IDimensionInfo;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.WorldGenLevel;

public final class DimensionInfoAccessor {

    private DimensionInfoAccessor() {}

    public static IDimensionInfo getForLevel(LevelAccessor level) {
        if (!(level instanceof WorldGenLevel worldGenLevel)) {
            return null;
        }

        IDimensionInfo info = Registration.LOSTCITY_FEATURE.get().getDimensionInfo(worldGenLevel);
        if (info != null && level instanceof WorldGenRegion) {
            info.setWorld(worldGenLevel);
        }
        return info;
    }
}