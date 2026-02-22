package org.admany.lc2h.mixin.lostcities.terrain;

import mcjty.lostcities.worldgen.IDimensionInfo;
import mcjty.lostcities.worldgen.LostCityTerrainFeature;
import mcjty.lostcities.worldgen.lost.BuildingInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelAccessor;
import org.admany.lc2h.LC2H;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Mixin(value = LostCityTerrainFeature.class, remap = false)
public abstract class MixinLostCityTerrainFeatureLootSafety {

    @Unique
    private static final AtomicLong LC2H_LOOT_ERROR_LOG_NS = new AtomicLong(0L);

    @Unique
    private static final long LC2H_LOOT_ERROR_LOG_INTERVAL_NS = TimeUnit.SECONDS.toNanos(5);

    @Redirect(
        method = "generateLoot",
        at = @At(
            value = "INVOKE",
            target = "Lmcjty/lostcities/worldgen/LostCityTerrainFeature;createLoot(Lmcjty/lostcities/worldgen/lost/BuildingInfo;Lnet/minecraft/util/RandomSource;Lnet/minecraft/world/level/LevelAccessor;Lnet/minecraft/core/BlockPos;Lmcjty/lostcities/worldgen/lost/BuildingInfo$ConditionTodo;Lmcjty/lostcities/worldgen/IDimensionInfo;)V"
        ),
        remap = false
    )
    private void lc2h$guardCreateLoot(
        BuildingInfo info,
        RandomSource random,
        LevelAccessor level,
        BlockPos pos,
        BuildingInfo.ConditionTodo todo,
        IDimensionInfo provider
    ) {
        try {
            LostCityTerrainFeature.createLoot(info, random, level, pos, todo, provider);
        } catch (Throwable t) {
            lc2h$rateLimitedLootLog(pos, todo, t);
        }
    }

    @Unique
    private static void lc2h$rateLimitedLootLog(BlockPos pos, BuildingInfo.ConditionTodo todo, Throwable t) {
        long now = System.nanoTime();
        long last = LC2H_LOOT_ERROR_LOG_NS.get();
        if ((now - last) < LC2H_LOOT_ERROR_LOG_INTERVAL_NS) {
            return;
        }
        LC2H_LOOT_ERROR_LOG_NS.set(now);
        String where = pos == null ? "<unknown>" : pos.toShortString();
        String todoId = todo == null ? "<unknown>" : String.valueOf(todo);
        LC2H.LOGGER.warn("[LC2H] Skipped invalid LostCities loot entry at {} (condition={}) due to: {}", where, todoId, t.toString());
    }
}
