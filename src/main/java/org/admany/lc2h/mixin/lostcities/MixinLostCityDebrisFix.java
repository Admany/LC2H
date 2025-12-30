package org.admany.lc2h.mixin.lostcities;

import mcjty.lostcities.worldgen.ChunkDriver;
import mcjty.lostcities.worldgen.IDimensionInfo;
import mcjty.lostcities.worldgen.LostCityTerrainFeature;
import mcjty.lostcities.worldgen.lost.BuildingInfo;
import mcjty.lostcities.worldgen.lost.DamageArea;
import mcjty.lostcities.worldgen.lost.cityassets.CompiledPalette;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.util.RandomSource;
import org.admany.lc2h.logging.config.ConfigManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.Collections;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Supplier;

@Mixin(value = LostCityTerrainFeature.class, remap = false)
public abstract class MixinLostCityDebrisFix {

    @Shadow public ChunkDriver driver;
    @Shadow public IDimensionInfo provider;
    @Shadow public RandomSource rand;

    /**
     * @author LC2H
     * @reason Fix height cursor reuse + require solid support to avoid floating debris
     */
    @Overwrite
    private void generateDebrisFromChunk(BuildingInfo info, BuildingInfo adjacentInfo, BiFunction<Integer, Integer, Float> locationFactor) {
        if (!ConfigManager.ENABLE_EXPLOSION_DEBRIS) {
            return;
        }
        if (info == null || adjacentInfo == null || locationFactor == null) {
            return;
        }
        if (!adjacentInfo.hasBuilding) {
            return;
        }

        float damageFactor = adjacentInfo.getDamageArea().getDamageFactor();
        if (!(damageFactor > .5f)) {
            return;
        }

        int blocks = (1 + adjacentInfo.getNumFloors()) * 1000;
        float damage = Math.max(1.0f, damageFactor * DamageArea.BLOCK_DAMAGE_CHANCE);
        int destroyedBlocks = (int) (blocks * damage);
        destroyedBlocks /= info.profile.DEBRIS_TO_NEARBYCHUNK_FACTOR;
        if (destroyedBlocks <= 0) {
            return;
        }

        CompiledPalette adjacentPalette = adjacentInfo.getCompiledPalette();
        Character rubbleBlock = adjacentInfo.getBuilding().getRubbleBlock();
        if (!adjacentPalette.isDefined(rubbleBlock)) {
            rubbleBlock = adjacentInfo.getBuilding().getFillerBlock();
        }

        int maxBuildHeight = info.provider.getWorld().getMaxBuildHeight();
        int minBuildHeight = info.provider.getWorld().getMinBuildHeight();
        int startY = Math.min(adjacentInfo.getMaxHeight() + 10, maxBuildHeight - 2);
        if (startY < (minBuildHeight + 1)) {
            startY = minBuildHeight + 1;
        }

        CompiledPalette palette = info.getCompiledPalette();
        BlockState ironbarsState = Blocks.IRON_BARS.defaultBlockState();
        Character infobarsChar = info.getCityStyle().getIronbarsBlock();
        Supplier<BlockState> ironbars = infobarsChar == null ? () -> ironbarsState : () -> palette.get(infobarsChar);
        Set<BlockState> infoBarSet = infobarsChar == null ? Collections.singleton(ironbarsState) : palette.getAll(infobarsChar);

        for (int i = 0; i < destroyedBlocks; i++) {
            int x = rand.nextInt(16);
            int z = rand.nextInt(16);
            if (rand.nextFloat() >= locationFactor.apply(x, z)) {
                continue;
            }

            int y = startY;
            driver.current(x, y, z);
            while (y > minBuildHeight && !lc2h$hasSolidSupport(driver.getBlock())) {
                y--;
                driver.decY();
            }
            if (y <= minBuildHeight) {
                continue;
            }

            driver.current(x, y + 1, z);
            if (!LostCityTerrainFeature.isEmpty(driver.getBlock())) {
                continue;
            }

            BlockState b;
            if (rand.nextInt(5) == 0) {
                b = ironbars.get();
            } else {
                b = adjacentPalette.get(rubbleBlock);
            }

            if (b != null && !infoBarSet.contains(driver.getBlockDown())) {
                driver.block(b);
            }
        }
    }

    @Unique
    private static boolean lc2h$hasSolidSupport(BlockState state) {
        if (state == null || state.isAir()) {
            return false;
        }
        if (state.getFluidState() != null && !state.getFluidState().isEmpty()) {
            return false;
        }
        if (state.is(BlockTags.LEAVES)) return false;
        if (state.is(BlockTags.LOGS)) return false;
        if (state.is(BlockTags.FLOWERS)) return false;
        if (state.is(BlockTags.SAPLINGS)) return false;
        return !state.canBeReplaced();
    }
}
