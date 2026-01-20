package org.admany.lc2h.config.sync;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.simple.SimpleChannel;

import org.admany.lc2h.config.ConfigManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;


public final class ConfigSyncNetwork {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final String PROTOCOL = "1";
    private static final SimpleChannel CHANNEL = NetworkRegistry.ChannelBuilder
        .named(ResourceLocation.fromNamespaceAndPath("lc2h", "config_sync"))
        .networkProtocolVersion(() -> PROTOCOL)
        .clientAcceptedVersions(PROTOCOL::equals)
        .serverAcceptedVersions(PROTOCOL::equals)
        .simpleChannel();
    private static final String SNAPSHOT_TYPE = "lc2h-config-snapshot";
    private static final String APPLY_TYPE = "lc2h-config-apply";

    private ConfigSyncNetwork() {
    }

    public static void register() {
        CHANNEL.registerMessage(0, Payload.class, Payload::encode, Payload::decode, ConfigSyncNetwork::handlePayload);
    }

    private static void handlePayload(Payload msg, Supplier<NetworkEvent.Context> ctxSup) {
        NetworkEvent.Context ctx = ctxSup.get();
        if (ctx.getDirection().getReceptionSide().isServer()) {
            ServerPlayer sender = ctx.getSender();
            if (sender == null) {
                ctx.setPacketHandled(true);
                return;
            }
            ctx.enqueueWork(() -> handleApplyOnServer(sender.server, sender, new String(msg.data, StandardCharsets.UTF_8)));
        } else {
            ctx.enqueueWork(() -> handleSnapshotOnClient(new String(msg.data, StandardCharsets.UTF_8)));
        }
        ctx.setPacketHandled(true);
    }

    private static void handleApplyOnServer(MinecraftServer server, ServerPlayer sender, String json) {
        try {
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            String cfgString = obj.get("config").getAsString();
            UUID playerId = UUID.fromString(obj.get("player").getAsString());
            if (!server.getPlayerList().isOp(sender.getGameProfile()) || !sender.getUUID().equals(playerId)) {
                LOGGER.warn("[LC2H] Rejected config apply from non-OP {}", playerId);
                return;
            }
            ConfigManager.Config cfg = ConfigManager.GSON.fromJson(cfgString, ConfigManager.Config.class);
            if (cfg != null) {
                logConfigDiffs(sender, cfg);
                ConfigManager.writePrettyJsonConfig(cfg);
                ConfigManager.CONFIG = cfg;
                ConfigManager.initializeGlobals();
                byte[] snapshot = ConfigManager.GSON.toJson(ConfigManager.CONFIG).getBytes();
                CHANNEL.send(PacketDistributor.PLAYER.with(() -> sender), new Payload(buildSnapshotPayload(snapshot)));
                LOGGER.info("[LC2H] Applied config from {}", sender.getGameProfile().getName());
            }
        } catch (Exception e) {
            LOGGER.warn("[LC2H] Failed to handle config apply: {}", e.getMessage());
        }
    }

    private static void handleSnapshotOnClient(String json) {
        try {
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            String type = obj.get("type").getAsString();
            if (!SNAPSHOT_TYPE.equals(type)) return;
            String cfgString = obj.get("config").getAsString();
            ConfigManager.Config cfg = ConfigManager.GSON.fromJson(cfgString, ConfigManager.Config.class);
            if (cfg != null) {
                ConfigManager.CONFIG = cfg;
                ConfigManager.writePrettyJsonConfig(cfg);
                ConfigManager.initializeGlobals();
            }
        } catch (Exception ignored) {
        }
    }

    private static void logConfigDiffs(ServerPlayer sender, ConfigManager.Config incoming) {
        ConfigManager.Config current = ConfigManager.CONFIG;
        String playerName = sender == null ? "unknown" : sender.getGameProfile().getName();
        if (current == null || incoming == null) {
            LOGGER.info("[LC2H] Config apply from {} (no diff available)", playerName);
            return;
        }
        StringBuilder changes = new StringBuilder();
        try {
            for (java.lang.reflect.Field field : ConfigManager.Config.class.getFields()) {
                Object oldVal = field.get(current);
                Object newVal = field.get(incoming);
                if (oldVal == null && newVal == null) {
                    continue;
                }
                if (oldVal != null && oldVal.equals(newVal)) {
                    continue;
                }
                if (changes.length() > 0) {
                    changes.append(", ");
                }
                changes.append(field.getName())
                    .append("=")
                    .append(String.valueOf(newVal));
            }
        } catch (Exception e) {
            LOGGER.warn("[LC2H] Failed to compute config diff: {}", e.getMessage());
        }
        if (changes.length() == 0) {
            LOGGER.info("[LC2H] Config apply from {} (no changes detected)", playerName);
        } else {
            LOGGER.info("[LC2H] Config apply from {}: {}", playerName, changes);
        }
    }

    private static byte[] buildApplyPayload(ConfigManager.Config cfg, UUID player) {
        JsonObject obj = new JsonObject();
        obj.addProperty("type", APPLY_TYPE);
        obj.addProperty("config", ConfigManager.GSON.toJson(cfg));
        obj.addProperty("player", player.toString());
        return obj.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] buildSnapshotPayload(byte[] cfgBytes) {
        JsonObject obj = new JsonObject();
        obj.addProperty("type", SNAPSHOT_TYPE);
        obj.addProperty("config", new String(cfgBytes, StandardCharsets.UTF_8));
        return obj.toString().getBytes(StandardCharsets.UTF_8);
    }

    public static boolean sendApplyRequest(ConfigManager.Config cfg) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getConnection() == null || mc.player == null) {
            return false;
        }
        try {
            UUID player = mc.player.getUUID();
            byte[] data = buildApplyPayload(cfg, player);
            CHANNEL.sendToServer(new Payload(data));
            return true;
        } catch (Exception e) {
            LOGGER.warn("[LC2H] Failed to send config apply: {}", e.getMessage());
            return false;
        }
    }


    public record Payload(byte[] data) {
        public static void encode(Payload msg, net.minecraft.network.FriendlyByteBuf buf) {
            buf.writeByteArray(msg.data);
        }

        public static Payload decode(net.minecraft.network.FriendlyByteBuf buf) {
            return new Payload(buf.readByteArray());
        }
    }
}
