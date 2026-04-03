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

    /**
     * Fixes two bugs in the original Lost Cities findLocation:
     *
     * Bug 1 (crash): The original while loop condition was `top.getY() > 1` where `top` never
     * changes - it is a fixed reference to bedLocation.above(5). When the destination dimension
     * is empty (fresh world, all air), `location` descends unchecked into negative Y, and
     * ServerLevel.getBlockState() throws IllegalArgumentException for out-of-bounds Y, crashing
     * the server. Fix: guard on `location.getY()` against the world's actual minimum build height.
     *
     * Bug 2: When no solid floor was found, the original code called
     * `destWorld.setBlockAndUpdate(bedLocation, Blocks.COBBLESTONE...)`, placing a block in
     * destWorld at the source-world bed coordinates (wrong dimension). Replaced with a heightmap
     * lookup to find a safe surface in the destination world instead.
     *
     * @author Admany
     * @reason Fix out-of-bounds Y crash and wrong-dimension block placement in bed ritual teleport.
     */
    @Overwrite
    private BlockPos findLocation(BlockPos bedLocation, ServerLevel destWorld) {
        BlockPos top = bedLocation.above(5);
        BlockPos location = top;
        int minY = destWorld.getMinBuildHeight() + 2;
        while (location.getY() > minY && destWorld.getBlockState(location).isAir()) {
            location = location.below();
        }
        if (location.getY() <= minY || destWorld.isEmptyBlock(location.below())) {
            // No solid surface found at bed coordinates in the dest world; fall back to heightmap.
            BlockPos surface = destWorld.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bedLocation);
            return surface.above();
        }
        return location.above(1);
    }

    /**
     * Defers the actual dimension-change call to the next server tick. Calling
     * player.changeDimension() from within a PlayerSleepInBedEvent callback is unsafe because
     * it removes/re-adds the player entity while the event dispatcher is still iterating the
     * entity list, which can produce ConcurrentModificationException or leave the player in a
     * partially-removed state and crash the client on the subsequent render tick.
     *
     * @author Admany
     * @reason Prevent crash from calling changeDimension during PlayerSleepInBedEvent dispatch.
     */
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
