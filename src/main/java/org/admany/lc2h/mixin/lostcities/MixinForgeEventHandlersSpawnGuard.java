package org.admany.lc2h.mixin.lostcities;

import mcjty.lostcities.setup.ForgeEventHandlers;
import mcjty.lostcities.worldgen.lost.cityassets.AssetRegistries;
import net.minecraft.world.level.LevelAccessor;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ForgeEventHandlers.class, remap = false)
public class MixinForgeEventHandlersSpawnGuard {

    @Inject(
        method = "onCreateSpawnPoint(Lnet/minecraftforge/event/level/LevelEvent$CreateSpawnPosition;)V",
        at = @At("HEAD")
    )
    private void lc2h$ensureAssetRegistriesLoaded(LevelEvent.CreateSpawnPosition event, CallbackInfo ci) {
        LevelAccessor world = event.getLevel();
        if (world instanceof ServerLevel serverLevel) {
            AssetRegistries.load(serverLevel);
        }
    }
}
