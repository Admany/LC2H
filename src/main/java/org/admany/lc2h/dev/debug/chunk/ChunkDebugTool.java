package org.admany.lc2h.dev.debug.chunk;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.HitResult;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.admany.lc2h.LC2H;

@Mod.EventBusSubscriber(modid = LC2H.MODID)
public final class ChunkDebugTool {

    private ChunkDebugTool() {
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (!ChunkDebugManager.isEnabled(player)) {
            return;
        }
        if (!isDebugStick(event.getItemStack())) {
            return;
        }

        ChunkPos pos = new ChunkPos(event.getPos());
        ChunkDebugManager.setPrimary(player, pos, event.getPos().getY());
        event.setCancellationResult(InteractionResult.SUCCESS);
        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (!ChunkDebugManager.isEnabled(player)) {
            return;
        }
        if (!isDebugStick(event.getItemStack())) {
            return;
        }

        HitResult hit = player.pick(5.0D, 1.0F, false);
        if (hit != null && hit.getType() == HitResult.Type.BLOCK) {
            return;
        }

        ChunkPos pos = player.chunkPosition();
        ChunkDebugManager.setPrimary(player, pos, player.blockPosition().getY());
        event.setCancellationResult(InteractionResult.SUCCESS);
        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (!ChunkDebugManager.isEnabled(player)) {
            return;
        }
        if (!isDebugStick(event.getItemStack())) {
            return;
        }

        ChunkPos pos = new ChunkPos(event.getPos());
        ChunkDebugManager.setSecondary(player, pos, event.getPos().getY());
        event.setCanceled(true);
    }

    private static boolean isDebugStick(ItemStack stack) {
        return stack != null && stack.is(Items.STICK);
    }
}
