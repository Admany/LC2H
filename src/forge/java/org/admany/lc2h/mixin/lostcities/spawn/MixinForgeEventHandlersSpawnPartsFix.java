package org.admany.lc2h.mixin.lostcities.spawn;

import mcjty.lostcities.setup.ForgeEventHandlers;
import mcjty.lostcities.worldgen.IDimensionInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ServerLevelData;
import org.admany.lc2h.util.spawn.SpawnSearchScheduler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import javax.annotation.Nonnull;
import java.util.function.Predicate;

@Mixin(value = ForgeEventHandlers.class, remap = false)
public abstract class MixinForgeEventHandlersSpawnPartsFix {

    @Shadow
    private boolean isValidStandingPosition(Level world, BlockPos pos) {
        throw new IllegalStateException("Shadowed");
    }

    /**
     * This addresses an issue in Lost Cities - the spawn suitability check was performed at a fixed Y=128, but certain filters like 'forceSpawnParts' depend on the actual Y position. As a result, valid configurations could appear impossible, triggering endless spawn searches. We now assess the suitability predicate at the appropriate Y level.
     *
     * @author Admany
     * @reason Avoid Y=128 suitability gate breaking forceSpawnParts.
     */
    @Overwrite
    private BlockPos findSafeSpawnPoint(Level world, IDimensionInfo provider, @Nonnull Predicate<BlockPos> isSuitable,
                                        @Nonnull ServerLevelData serverLevelData) {
        return SpawnSearchScheduler.findSafeSpawnPoint(world, provider, isSuitable, serverLevelData, this::isValidStandingPosition);
    }
}
