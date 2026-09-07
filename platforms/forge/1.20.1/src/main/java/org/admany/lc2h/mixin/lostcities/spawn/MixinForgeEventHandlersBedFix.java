package org.admany.lc2h.mixin.lostcities.spawn;

import mcjty.lostcities.setup.ForgeEventHandlers;
import mcjty.lostcities.setup.Registration;
import mcjty.lostcities.varia.ComponentFactory;
import mcjty.lostcities.varia.CustomTeleporter;
import mcjty.lostcities.varia.WorldTools;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.event.entity.player.PlayerSleepInBedEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import static mcjty.lostcities.setup.Registration.LOSTCITY;

@Mixin(value = ForgeEventHandlers.class, remap = false)
public abstract class MixinForgeEventHandlersBedFix {

    @Shadow
    private boolean isValidSpawnBed(Level world, BlockPos pos) {
        throw new IllegalStateException("Shadowed");
    }

/** Keeps the Lost Cities bed search inside the destination world's height range
 * and finds the fallback floor in the correct dimension. */
    @Overwrite
    private BlockPos findLocation(BlockPos bedLocation, ServerLevel destWorld) {
        BlockPos top = bedLocation.above(5);
        BlockPos location = top;
        int minY = destWorld.getMinBuildHeight() + 2;
        while (location.getY() > minY && destWorld.getBlockState(location).isAir()) {
            location = location.below();
        }
        if (location.getY() <= minY || destWorld.isEmptyBlock(location.below())) {
            // No solid surface at the bed coordinates. Use the destination heightmap.
            BlockPos surface = destWorld.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bedLocation);
            return surface.above();
        }
        return location.above(1);
    }

    /** Defers dimension changes until the sleep event has finished dispatching. */
    @Overwrite
    public void onPlayerSleepInBedEvent(PlayerSleepInBedEvent event) {
        Level world = event.getEntity().getCommandSenderWorld();
        if (world.isClientSide) {
            return;
        }
        BlockPos bedLocation = event.getPos();
        if (bedLocation == null || !isValidSpawnBed(world, bedLocation)) {
            return;
        }
        if (!(event.getEntity() instanceof ServerPlayer serverPlayer)) {
            return;
        }
        MinecraftServer server = world.getServer();
        if (server == null) {
            return;
        }

        // Cancel the sleep action before deferring so the client never enters the sleep state.
        event.setResult(Player.BedSleepingProblem.OTHER_PROBLEM);

        final boolean toLostCityDim = (world.dimension() != Registration.DIMENSION);
        server.execute(() -> {
            // Re-validate player is still online.
            if (!serverPlayer.isAlive() || serverPlayer.hasDisconnected()) {
                return;
            }
            ServerLevel destWorld;
            if (toLostCityDim) {
                destWorld = server.getLevel(Registration.DIMENSION);
                if (destWorld == null) {
                    serverPlayer.sendSystemMessage(
                        ComponentFactory.literal("Error finding Lost City dimension: " + LOSTCITY + "!")
                            .withStyle(ChatFormatting.RED));
                    return;
                }
            } else {
                destWorld = WorldTools.getOverworld(serverPlayer.serverLevel());
                if (destWorld == null) {
                    return;
                }
            }
            BlockPos location = findLocation(bedLocation, destWorld);
            CustomTeleporter.teleportToDimension(serverPlayer, destWorld, location);
        });
    }
}
