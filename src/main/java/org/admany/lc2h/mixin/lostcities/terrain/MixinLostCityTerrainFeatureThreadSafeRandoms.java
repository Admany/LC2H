package org.admany.lc2h.mixin.lostcities.terrain;

import mcjty.lostcities.worldgen.ChunkDriver;
import mcjty.lostcities.worldgen.IDimensionInfo;
import mcjty.lostcities.worldgen.LostCityTerrainFeature;
import mcjty.lostcities.worldgen.lost.BuildingInfo;
import mcjty.lostcities.worldgen.lost.cityassets.CompiledPalette;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.BlockPos;
import org.admany.lc2h.dev.debug.PreCaptureTargetTraceRegistry;
import org.admany.lc2h.runtime.Lc2hParityRandoms;
import org.admany.lc2h.runtime.Lc2hRuntimeModes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

@Mixin(value = LostCityTerrainFeature.class, remap = false)
public abstract class MixinLostCityTerrainFeatureThreadSafeRandoms {

    @Unique private static final ThreadLocal<Random> LC2H_RANDOMIZED_OFFSET = ThreadLocal.withInitial(Random::new);
    @Unique private static final ThreadLocal<Random> LC2H_RANDOMIZED_OFFSET_L1 = ThreadLocal.withInitial(Random::new);
    @Unique private static final ThreadLocal<Random> LC2H_RANDOMIZED_OFFSET_L2 = ThreadLocal.withInitial(Random::new);
    @Unique private static final ThreadLocal<Random> LC2H_VEGETATION_RAND = ThreadLocal.withInitial(Random::new);
    @Unique private static final ThreadLocal<PaletteSequence> LC2H_PALETTE_SEQUENCE = ThreadLocal.withInitial(PaletteSequence::new);

    @Shadow public ChunkDriver driver;
    @Shadow public IDimensionInfo provider;
    @Shadow public BlockState air;
    @Shadow private static int gSeed;
    @Shadow private BlockState[] randomLeafs;
    @Shadow private BlockState[] randomDirt;
    @Shadow private Set<BlockState> randomDirtSet;


    @Overwrite
    public static int getRandomizedOffset(int chunkX, int chunkZ, int min, int max) {
        Random rand = LC2H_RANDOMIZED_OFFSET.get();
        rand.setSeed(chunkZ * 256203221L + chunkX * 899809363L);
        return rand.nextInt(max - min + 1) + min;
    }

    @Overwrite
    public static int getHeightOffsetL1(int chunkX, int chunkZ) {
        Random rand = LC2H_RANDOMIZED_OFFSET_L1.get();
        rand.setSeed(chunkZ * 341873128712L + chunkX * 132897987541L);
        return rand.nextInt(5);
    }

    @Overwrite
    public static int getHeightOffsetL2(int chunkX, int chunkZ) {
        Random rand = LC2H_RANDOMIZED_OFFSET_L2.get();
        rand.setSeed(chunkZ * 132897987541L + chunkX * 341873128712L);
        return rand.nextInt(5);
    }

    @Overwrite
    private BlockState getRandomLeaf(BuildingInfo info, CompiledPalette compiledPalette) {
        Character leavesBlock = info.getCityStyle().getLeavesBlock();
        if (leavesBlock != null) {
            BlockState direct = compiledPalette.get(leavesBlock.charValue());
            lc2h$recordPaletteChoice("leaf.direct", -1, 0, 0, direct, "cityStyleLeavesBlock=" + leavesBlock);
            return direct;
        }
        if (randomLeafs == null) {
            BlockState oak = Blocks.OAK_LEAVES.defaultBlockState().setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT, Boolean.TRUE);
            BlockState jungle = Blocks.JUNGLE_LEAVES.defaultBlockState().setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT, Boolean.TRUE);
            BlockState spruce = Blocks.SPRUCE_LEAVES.defaultBlockState().setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT, Boolean.TRUE);
            randomLeafs = new BlockState[128];
            int idx = 0;
            while (idx < 20) {
                randomLeafs[idx++] = jungle;
            }
            while (idx < 40) {
                randomLeafs[idx++] = spruce;
            }
            while (idx < randomLeafs.length) {
                randomLeafs[idx++] = oak;
            }
        }
        int seedBefore = gSeed;
        int paletteIndex = lc2h$nextPaletteIndex(info, 0x1EAF);
        int seedAfter = gSeed;
        BlockState chosen = randomLeafs[paletteIndex];
        lc2h$recordPaletteChoice("leaf.random", paletteIndex, seedBefore, seedAfter, chosen, null);
        return chosen;
    }

    @Overwrite
    private BlockState getRandomDirt(BuildingInfo info, CompiledPalette compiledPalette) {
        Character rubbleDirtBlock = info.getCityStyle().getRubbleDirtBlock();
        if (rubbleDirtBlock != null) {
            BlockState direct = compiledPalette.get(rubbleDirtBlock.charValue());
            lc2h$recordPaletteChoice("dirt.direct", -1, 0, 0, direct, "cityStyleRubbleDirtBlock=" + rubbleDirtBlock);
            return direct;
        }
        if (randomDirt == null) {
            randomDirtSet = new HashSet<>();
            BlockState mossyStoneBricks = Blocks.MOSSY_STONE_BRICKS.defaultBlockState();
            BlockState mossyCobblestone = Blocks.MOSSY_COBBLESTONE.defaultBlockState();
            BlockState mossBlock = Blocks.MOSS_BLOCK.defaultBlockState();
            randomDirtSet.add(mossyStoneBricks);
            randomDirtSet.add(mossyCobblestone);
            randomDirtSet.add(mossBlock);
            randomDirt = new BlockState[128];
            int idx = 0;
            while (idx < 20) {
                randomDirt[idx++] = mossyStoneBricks;
            }
            while (idx < 60) {
                randomDirt[idx++] = mossyCobblestone;
            }
            while (idx < randomDirt.length) {
                randomDirt[idx++] = mossBlock;
            }
        }
        int seedBefore = gSeed;
        int paletteIndex = lc2h$nextPaletteIndex(info, 0xD17A);
        int seedAfter = gSeed;
        BlockState chosen = randomDirt[paletteIndex];
        lc2h$recordPaletteChoice("dirt.random", paletteIndex, seedBefore, seedAfter, chosen, null);
        return chosen;
    }

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

    @Unique
    private int lc2h$nextPaletteIndex(BuildingInfo info) {
        return lc2h$nextPaletteIndex(info, 0);
    }

    @Unique
    private int lc2h$nextPaletteIndex(BuildingInfo info, int salt) {
        if (Lc2hRuntimeModes.worldParityAuto()) {
            return Lc2hParityRandoms.withLostCitiesSeedLock(LostCityTerrainFeature::fastrand128);
        }
        PaletteSequence sequence = LC2H_PALETTE_SEQUENCE.get();
        sequence.ensureSeed(provider.getSeed(), info.coord.chunkX(), info.coord.chunkZ());
        return sequence.nextIndex();
    }

    @Unique
    private static int lc2h$initialPaletteSeed(long worldSeed, int chunkX, int chunkZ) {
        long mixed = 123456789L;
        mixed ^= worldSeed * 377L;
        mixed = Long.rotateLeft(mixed, 17) ^ ((long) chunkX * 132897987541L);
        mixed = Long.rotateLeft(mixed, 13) ^ ((long) chunkZ * 341873128712L);
        mixed ^= (mixed >>> 33);
        mixed *= 0xff51afd7ed558ccdL;
        mixed ^= (mixed >>> 33);
        mixed *= 0xc4ceb9fe1a85ec53L;
        mixed ^= (mixed >>> 33);
        int seeded = (int) mixed;
        return seeded == 0 ? 123456789 : seeded;
    }

    @Unique
    private static final class PaletteSequence {
        private long key = Long.MIN_VALUE;
        private int state = 123456789;

        private void ensureSeed(long worldSeed, int chunkX, int chunkZ) {
            long newKey = worldSeed;
            newKey = Long.rotateLeft(newKey, 19) ^ chunkX;
            newKey = Long.rotateLeft(newKey, 7) ^ (((long) chunkZ) << 32);
            if (newKey != key) {
                key = newKey;
                state = lc2h$initialPaletteSeed(worldSeed, chunkX, chunkZ);
            }
        }

        private int nextIndex() {
            state = 214013 * state + 2531011;
            return (state >>> 16) & 127;
        }
    }

    @Unique
    private void lc2h$recordPaletteChoice(String paletteKind,
                                          int paletteIndex,
                                          int gSeedBefore,
                                          int gSeedAfter,
                                          BlockState chosenState,
                                          String detail) {
        try {
            if (driver == null) {
                return;
            }
            BlockPos currentPos = new BlockPos(driver.getX(), driver.getY(), driver.getZ());
            if (!PreCaptureTargetTraceRegistry.shouldTracePosition(currentPos)) {
                return;
            }
            PreCaptureTargetTraceRegistry.recordTargetPaletteChoice(
                currentPos,
                paletteKind,
                paletteIndex,
                gSeedBefore,
                gSeedAfter,
                chosenState,
                detail);
        } catch (Throwable ignored) {
        }
    }
}
