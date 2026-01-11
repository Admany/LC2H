package org.admany.lc2h.mixin.compat;

import mcjty.lostcities.worldgen.IDimensionInfo;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.ServerLevelData;
import org.admany.lc2h.util.spawn.SpawnSearchScheduler;
import org.admany.lc2h.util.server.DimensionInfoAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "com.natamus.biomespawnpoint_common_forge.events.BiomeSpawnEvent", remap = false)
public class MixinBiomeSpawnPointSpawnGuard {

    private static final boolean SKIP_BIOME_SPAWNPOINT =
        Boolean.parseBoolean(System.getProperty("lc2h.spawn.biomespawnpoint.skip", "true"));

    @Inject(method = "onWorldLoad", at = @At("HEAD"), cancellable = true)
    private static void lc2h$skipBiomeSpawnPoint(ServerLevel level,
                                                 ServerLevelData serverLevelData,
                                                 CallbackInfoReturnable<Boolean> cir) {
        if (!SKIP_BIOME_SPAWNPOINT || level == null) {
            return;
        }
        IDimensionInfo info = DimensionInfoAccessor.getForLevel(level);
        if (info == null) {
            return;
        }
        if (!SpawnSearchScheduler.shouldTimeSlice(info)) {
            return;
        }
        cir.setReturnValue(false);
        cir.cancel();
    }
}
