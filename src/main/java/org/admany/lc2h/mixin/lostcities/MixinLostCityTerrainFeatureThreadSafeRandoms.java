package org.admany.lc2h.mixin.lostcities;

import mcjty.lostcities.worldgen.ChunkDriver;
import mcjty.lostcities.worldgen.IDimensionInfo;
import mcjty.lostcities.worldgen.LostCityTerrainFeature;
import mcjty.lostcities.worldgen.lost.BuildingInfo;
import mcjty.lostcities.worldgen.lost.cityassets.CompiledPalette;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.Random;

@Mixin(value = LostCityTerrainFeature.class, remap = false)
public abstract class MixinLostCityTerrainFeatureThreadSafeRandoms {

    @Unique private static final ThreadLocal<Random> LC2H_RANDOMIZED_OFFSET = ThreadLocal.withInitial(Random::new);
    @Unique private static final ThreadLocal<Random> LC2H_RANDOMIZED_OFFSET_L1 = ThreadLocal.withInitial(Random::new);
    @Unique private static final ThreadLocal<Random> LC2H_RANDOMIZED_OFFSET_L2 = ThreadLocal.withInitial(Random::new);
    @Unique private static final ThreadLocal<Random> LC2H_VEGETATION_RAND = ThreadLocal.withInitial(Random::new);

    @Shadow public ChunkDriver driver;
    @Shadow public IDimensionInfo provider;
    @Shadow public BlockState air;

    @Shadow
    private BlockState getRandomLeaf(BuildingInfo info, CompiledPalette compiledPalette) { return null; }

    /**
     * Thread-safe drop-in replacement for Lost Citiess static Random-based offset.
     * Preserves vanilla output while avoiding shared mutable Random state.
     *
     * @author LC2H
     * @reason Prevent nondeterminism and corruption under parallel worldgen
     */
    @Overwrite
    public static int getRandomizedOffset(int chunkX, int chunkZ, int min, int max) {
        Random rand = LC2H_RANDOMIZED_OFFSET.get();
        rand.setSeed(chunkZ * 256203221L + chunkX * 899809363L);
        return rand.nextInt(max - min + 1) + min;
    }

    /**
     * Thread-safe drop-in replacement for Lost Citiess static Random-based offset.
     *
     * @author LC2H
     * @reason Prevent nondeterminism and corruption under parallel worldgen
     */
    @Overwrite
    public static int getHeightOffsetL1(int chunkX, int chunkZ) {
        Random rand = LC2H_RANDOMIZED_OFFSET_L1.get();
        rand.setSeed(chunkZ * 341873128712L + chunkX * 132897987541L);
        return rand.nextInt(5);
    }

    /**
     * Thread-safe drop-in replacement for Lost Cities static Random-based offset.
     *
     * @author LC2H
     * @reason Prevent nondeterminism and corruption under parallel worldgen
     */
    @Overwrite
    public static int getHeightOffsetL2(int chunkX, int chunkZ) {
        Random rand = LC2H_RANDOMIZED_OFFSET_L2.get();
        rand.setSeed(chunkZ * 132897987541L + chunkX * 341873128712L);
        return rand.nextInt(5);
    }

    /**
     * Thread-safe replacement for Lost Cities static VEGETATION_RAND usage.
     *
     * @author LC2H
     * @reason Prevent nondeterministic vegetation placement under parallel worldgen
     */
    @Overwrite
    private void generateRandomVegetation(BuildingInfo info, int height) {
        Random vegetationRand = LC2H_VEGETATION_RAND.get();
        vegetationRand.setSeed(provider.getSeed() * 377 + info.coord.chunkZ() * 341873128712L + info.coord.chunkX() * 132897987541L);

        if (info.getXmin().hasBuilding) {
            for (int x = 0; x < info.profile.THICKNESS_OF_RANDOM_LEAFBLOCKS; x++) {
                for (int z = 0; z < 16; z++) {
                    driver.current(x, height, z);
                    while (driver.getBlockDown() == air && driver.getY() > 0) {
                        driver.decY();
                    }
                    float v = Math.min(.8f, info.profile.CHANCE_OF_RANDOM_LEAFBLOCKS * (info.profile.THICKNESS_OF_RANDOM_LEAFBLOCKS + 1 - x));
                    int cnt = 0;
                    while (vegetationRand.nextFloat() < v && cnt < 30) {
                        driver.add(getRandomLeaf(info, info.getCompiledPalette()));
                        cnt++;
                    }
                }
            }
        }
        if (info.getXmax().hasBuilding) {
            for (int x = 15 - info.profile.THICKNESS_OF_RANDOM_LEAFBLOCKS; x < 15; x++) {
                for (int z = 0; z < 16; z++) {
                    driver.current(x, height, z);
                    while (driver.getBlockDown() == air && driver.getY() > 0) {
                        driver.decY();
                    }
                    float v = Math.min(.8f, info.profile.CHANCE_OF_RANDOM_LEAFBLOCKS * (x - 14 + info.profile.THICKNESS_OF_RANDOM_LEAFBLOCKS));
                    int cnt = 0;
                    while (vegetationRand.nextFloat() < v && cnt < 30) {
                        driver.add(getRandomLeaf(info, info.getCompiledPalette()));
                        cnt++;
                    }
                }
            }
        }
        if (info.getZmin().hasBuilding) {
            for (int z = 0; z < info.profile.THICKNESS_OF_RANDOM_LEAFBLOCKS; z++) {
                for (int x = 0; x < 16; x++) {
                    driver.current(x, height, z);
                    while (driver.getBlockDown() == air && driver.getY() > 0) {
                        driver.decY();
                    }
                    float v = Math.min(.8f, info.profile.CHANCE_OF_RANDOM_LEAFBLOCKS * (info.profile.THICKNESS_OF_RANDOM_LEAFBLOCKS + 1 - z));
                    int cnt = 0;
                    while (vegetationRand.nextFloat() < v && cnt < 30) {
                        driver.add(getRandomLeaf(info, info.getCompiledPalette()));
                        cnt++;
                    }
                }
            }
        }
        if (info.getZmax().hasBuilding) {
            for (int z = 15 - info.profile.THICKNESS_OF_RANDOM_LEAFBLOCKS; z < 15; z++) {
                for (int x = 0; x < 16; x++) {
                    driver.current(x, height, z);
                    while (driver.getBlockDown() == air && driver.getY() > 0) {
                        driver.decY();
                    }
                    float v = info.profile.CHANCE_OF_RANDOM_LEAFBLOCKS * (z - 14 + info.profile.THICKNESS_OF_RANDOM_LEAFBLOCKS);
                    int cnt = 0;
                    while (vegetationRand.nextFloat() < v && cnt < 30) {
                        driver.add(getRandomLeaf(info, info.getCompiledPalette()));
                        cnt++;
                    }
                }
            }
        }
    }
}
