package org.admany.lc2h.debug.frustum;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.admany.lc2h.LC2H;
import org.admany.lc2h.util.log.ChatMessenger;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Mod.EventBusSubscriber(modid = LC2H.MODID)
public final class FrustumDebugManager {

    private static final Map<UUID, FrustumDebugState> STATES = new ConcurrentHashMap<>();

    private FrustumDebugManager() {
    }

    public static boolean isEnabled(ServerPlayer player) {
        if (player == null) {
            return false;
        }
        FrustumDebugState state = STATES.get(player.getUUID());
        return state != null && state.enabled();
    }

    public static void toggle(ServerPlayer player) {
        if (player == null) {
            return;
        }
        setEnabled(player, !isEnabled(player));
    }

    public static void setEnabled(ServerPlayer player, boolean enabled) {
        if (player == null) {
            return;
        }
        if (enabled) {
            ResourceLocation dimension = player.level().dimension().location();
            FrustumDebugState state = new FrustumDebugState(true, dimension);
            STATES.put(player.getUUID(), state);
            FrustumDebugNetwork.sendState(player, state);
            ChatMessenger.success(player.createCommandSourceStack(),
                "Frustum debug enabled. Yellow=POV priority, Blue=background.");
        } else {
            STATES.remove(player.getUUID());
            FrustumDebugState state = new FrustumDebugState(false, null);
            FrustumDebugNetwork.sendState(player, state);
            ChatMessenger.success(player.createCommandSourceStack(), "Frustum debug disabled.");
        }
    }

    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() == null) {
            return;
        }
        STATES.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onPlayerChangeDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (!isEnabled(player)) {
            return;
        }
        ResourceLocation dimension = player.level().dimension().location();
        FrustumDebugState state = new FrustumDebugState(true, dimension);
        STATES.put(player.getUUID(), state);
        FrustumDebugNetwork.sendState(player, state);
    }
}
