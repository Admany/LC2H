package org.admany.lc2h.worldgen.lostcities;

import mcjty.lostcities.api.ILostCityMultiBuilding;
import mcjty.lostcities.api.LostChunkCharacteristics;
import mcjty.lostcities.api.MultiPos;
import mcjty.lostcities.varia.ChunkCoord;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import org.admany.lc2h.data.cache.BuildingInfoCacheRegistry;
import org.admany.lc2h.testutil.TestResourceKeys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultiBuildingFootprintRegistryTest {
    private static final net.minecraft.resources.ResourceKey<Level> DIMENSION =
        TestResourceKeys.testDimension("lc2h:multi_footprint_test");

    @AfterEach
    void tearDown() {
        BuildingInfoCacheRegistry.clear();
    }

    @Test
    void claimOnlyAcceptsTheExactMultibuildingCell() {
        ChunkCoord topLeft = new ChunkCoord(DIMENSION, 20, -8);
        MultiBuildingFootprintRegistry.Claim claim =
            new MultiBuildingFootprintRegistry.Claim("lc2h:test_multi", topLeft, 1, 2, 3, 4);

        LostChunkCharacteristics single = new LostChunkCharacteristics();
        single.multiPos = MultiPos.SINGLE;
        assertFalse(claim.matches(single));

        LostChunkCharacteristics wrongCell = characteristics("lc2h:test_multi", new MultiPos(0, 2, 3, 4));
        assertFalse(claim.matches(wrongCell));

        LostChunkCharacteristics wrongBuilding = characteristics("lc2h:other", new MultiPos(1, 2, 3, 4));
        assertFalse(claim.matches(wrongBuilding));

        LostChunkCharacteristics exact = characteristics("lc2h:test_multi", new MultiPos(1, 2, 3, 4));
        assertTrue(claim.matches(exact));
    }

    @Test
    void areaInvalidationRemovesPreviouslyCachedSingleCharacteristics() {
        ChunkCoord coord = new ChunkCoord(DIMENSION, 4, 9);
        LostChunkCharacteristics staleSingle = new LostChunkCharacteristics();
        staleSingle.multiPos = MultiPos.SINGLE;
        BuildingInfoCacheRegistry.scope(null).cityInfo.put(coord, staleSingle);

        BuildingInfoCacheRegistry.invalidateArea(coord, 1, 0);

        assertNull(BuildingInfoCacheRegistry.scope(null).cityInfo.get(coord));
    }

    private static LostChunkCharacteristics characteristics(String name, MultiPos pos) {
        LostChunkCharacteristics characteristics = new LostChunkCharacteristics();
        characteristics.multiPos = pos;
        characteristics.multiBuilding = (ILostCityMultiBuilding) Proxy.newProxyInstance(
            MultiBuildingFootprintRegistryTest.class.getClassLoader(),
            new Class<?>[]{ILostCityMultiBuilding.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getName" -> name;
                case "getId" -> ResourceLocation.parse(name);
                case "getDimX" -> pos.w();
                case "getDimZ" -> pos.h();
                case "getBuilding" -> "lc2h:test_building";
                case "toString" -> name;
                case "hashCode" -> name.hashCode();
                case "equals" -> proxy == args[0];
                default -> null;
            });
        return characteristics;
    }
}
