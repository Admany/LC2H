package org.admany.lc2h.mixin.lostcities.spawn;

import mcjty.lostcities.config.LostCityProfile;
import mcjty.lostcities.setup.ForgeEventHandlers;
import mcjty.lostcities.setup.ModSetup;
import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.IDimensionInfo;
import mcjty.lostcities.worldgen.lost.BuildingInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ServerLevelData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import javax.annotation.Nonnull;
import java.util.Random;
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
        Random rand = new Random(provider.getSeed());
        int radius = provider.getProfile().SPAWN_CHECK_RADIUS;
        int attempts = 0;

        while (true) {
            for (int i = 0; i < 200; i++) {
                int x = rand.nextInt(radius * 2) - radius;
                int z = rand.nextInt(radius * 2) - radius;
                attempts++;

                ChunkCoord coord = new ChunkCoord(provider.getType(), x >> 4, z >> 4);
                LostCityProfile profile = BuildingInfo.getProfile(coord, provider);

                for (int y = profile.GROUNDLEVEL - 5; y < 125; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (!isSuitable.test(pos)) {
                        continue;
                    }
                    if (isValidStandingPosition(world, pos)) {
                        return pos.above();
                    }
                }
            }
            radius += provider.getProfile().SPAWN_RADIUS_INCREASE;
            if (attempts > provider.getProfile().SPAWN_CHECK_ATTEMPTS) {
                ModSetup.getLogger().error("Can't find a valid spawn position!");
                throw new RuntimeException("Can't find a valid spawn position!");
            }
        }
    }
}
