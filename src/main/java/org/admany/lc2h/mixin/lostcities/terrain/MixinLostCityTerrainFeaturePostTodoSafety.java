package org.admany.lc2h.mixin.lostcities.terrain;

import mcjty.lostcities.worldgen.IDimensionInfo;
import mcjty.lostcities.worldgen.LostCityTerrainFeature;
import mcjty.lostcities.worldgen.lost.BuildingInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.WorldGenLevel;
import org.admany.lc2h.worldgen.lostcities.LostCityPostTodoSafety;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Prevent post-todo lighting work from loading chunks on worldgen workers. */
@Mixin(value = LostCityTerrainFeature.class, remap = false)
public class MixinLostCityTerrainFeaturePostTodoSafety {

    @Inject(
        method = "lambda$updateNeeded$22",
        at = @At("HEAD"),
        cancellable = true,
        require = 0,
        expect = 0,
        remap = false
    )
    private static void lc2h$skipOutsideWorldGenRegion(BuildingInfo info,
                                                        BlockPos pos,
                                                        int flags,
                                                        CallbackInfo ci) {
        if (info == null || pos == null) {
            return;
        }
        try {
            IDimensionInfo provider = info.provider;
            if (provider == null) {
                return;
            }
            WorldGenLevel world = provider.getWorld();
            if (world instanceof WorldGenRegion region
                && !region.hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) {
                LostCityPostTodoSafety.skippedOutsideRegion();
                ci.cancel();
                return;
            }
            if (world instanceof ServerLevel serverLevel) {
                MinecraftServer server = serverLevel.getServer();
                Thread owner = server == null ? null : server.getRunningThread();
                // Keep direct server-thread updates; workers must not load a
                // second chunk through ServerChunkCache.
                if (owner == null || Thread.currentThread() != owner) {
                    LostCityPostTodoSafety.skippedAsyncServerLevel();
                    ci.cancel();
                }
            }
        } catch (Throwable ignored) {
            // Leave unknown implementations unchanged.
        }
    }

}
