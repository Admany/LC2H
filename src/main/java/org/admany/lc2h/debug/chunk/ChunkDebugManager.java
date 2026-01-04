package org.admany.lc2h.debug.chunk;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.admany.lc2h.LC2H;
import org.admany.lc2h.util.log.ChatMessenger;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Mod.EventBusSubscriber(modid = LC2H.MODID)
public final class ChunkDebugManager {

    private static final Map<UUID, Session> SESSIONS = new ConcurrentHashMap<>();

    private ChunkDebugManager() {
    }

    public static boolean isEnabled(ServerPlayer player) {
        if (player == null) {
            return false;
        }
        Session session = SESSIONS.get(player.getUUID());
        return session != null && session.enabled;
    }

    public static void setEnabled(ServerPlayer player, boolean enabled) {
        if (player == null) {
            return;
        }
        if (enabled) {
            Session session = SESSIONS.computeIfAbsent(player.getUUID(), id -> new Session());
            session.enabled = true;
            session.dimension = player.level().dimension().location();
            sendUpdate(player, session);
            ChatMessenger.success(player.createCommandSourceStack(),
                "Chunk debug enabled. Use a stick: right click to set start, left click to set end.");
        } else {
            Session removed = SESSIONS.remove(player.getUUID());
            sendUpdate(player, removed != null ? removed.disabled() : new Session().disabled());
            ChatMessenger.success(player.createCommandSourceStack(), "Chunk debug disabled.");
        }
    }

    public static void clearSelection(ServerPlayer player) {
        if (player == null) {
            return;
        }
        Session session = SESSIONS.computeIfAbsent(player.getUUID(), id -> new Session());
        session.enabled = true;
        session.primary = null;
        session.secondary = null;
        session.dimension = player.level().dimension().location();
        sendUpdate(player, session);
        ChatMessenger.info(player.createCommandSourceStack(), "Chunk debug selection cleared.");
    }

    public static void setPrimary(ServerPlayer player, ChunkPos pos) {
        if (player == null || pos == null) {
            return;
        }
        Session session = SESSIONS.computeIfAbsent(player.getUUID(), id -> new Session());
        session.enabled = true;
        session.primary = pos;
        session.dimension = player.level().dimension().location();
        sendUpdate(player, session);
        ChatMessenger.info(player.createCommandSourceStack(),
            "Chunk debug primary set to chunk " + pos.x + ", " + pos.z + ".");
    }

    public static void setSecondary(ServerPlayer player, ChunkPos pos) {
        if (player == null || pos == null) {
            return;
        }
        Session session = SESSIONS.computeIfAbsent(player.getUUID(), id -> new Session());
        session.enabled = true;
        session.secondary = pos;
        session.dimension = player.level().dimension().location();
        sendUpdate(player, session);
        ChatMessenger.info(player.createCommandSourceStack(),
            "Chunk debug secondary set to chunk " + pos.x + ", " + pos.z + ".");
    }

    public static ChunkSelection snapshot(ServerPlayer player) {
        if (player == null) {
            return null;
        }
        Session session = SESSIONS.get(player.getUUID());
        if (session == null) {
            return new ChunkSelection(false, null, null, player.level().dimension().location());
        }
        return session.toSelection();
    }

    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() == null) {
            return;
        }
        SESSIONS.remove(event.getEntity().getUUID());
    }

    private static void sendUpdate(ServerPlayer player, Session session) {
        if (player == null || session == null) {
            return;
        }
        ChunkDebugNetwork.sendSelection(player, session.toSelection().toNetwork());
    }

    private static final class Session {
        private boolean enabled;
        private ResourceLocation dimension;
        private ChunkPos primary;
        private ChunkPos secondary;

        private ChunkSelection toSelection() {
            return new ChunkSelection(enabled, primary, secondary, dimension);
        }

        private Session disabled() {
            this.enabled = false;
            this.primary = null;
            this.secondary = null;
            return this;
        }
    }

    public record ChunkSelection(boolean enabled, ChunkPos primary, ChunkPos secondary, ResourceLocation dimension) {
        private ChunkDebugSelection toNetwork() {
            return new ChunkDebugSelection(enabled, dimension, primary, secondary);
        }
    }
}
