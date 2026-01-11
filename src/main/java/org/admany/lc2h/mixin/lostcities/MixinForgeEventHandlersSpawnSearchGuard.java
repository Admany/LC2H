package org.admany.lc2h.mixin.lostcities;

import mcjty.lostcities.setup.ForgeEventHandlers;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ServerLevelData;
import mcjty.lostcities.worldgen.IDimensionInfo;
import org.admany.lc2h.util.spawn.SpawnSearchGuard;
import org.admany.lc2h.util.spawn.SpawnSearchScheduler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Predicate;

@Mixin(value = ForgeEventHandlers.class, remap = false)
public class MixinForgeEventHandlersSpawnSearchGuard {

    @Inject(method = "findSafeSpawnPoint", at = @At("HEAD"), cancellable = true)
    private void lc2h$enterSpawnSearch(Level world,
                                       IDimensionInfo provider,
                                       Predicate<BlockPos> isSuitable,
                                       ServerLevelData serverLevelData,
                                       CallbackInfoReturnable<BlockPos> cir) {
        SpawnSearchGuard.enter();
        BlockPos cached = SpawnSearchScheduler.findCachedSpawn(
            (ForgeEventHandlers) (Object) this,
            world,
            provider,
            serverLevelData
        );
        if (cached != null) {
            SpawnSearchGuard.exit();
            cir.setReturnValue(cached);
            cir.cancel();
        }
    }

    @Inject(method = "findSafeSpawnPoint", at = @At("RETURN"))
    private void lc2h$exitSpawnSearch(Level world,
                                      IDimensionInfo provider,
                                      Predicate<BlockPos> isSuitable,
                                      ServerLevelData serverLevelData,
                                      CallbackInfoReturnable<BlockPos> cir) {
        SpawnSearchGuard.exit();
    }
}
