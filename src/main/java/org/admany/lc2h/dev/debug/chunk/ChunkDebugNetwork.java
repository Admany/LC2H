package org.admany.lc2h.dev.debug.chunk;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import org.admany.lc2h.LC2H;

import java.util.Optional;
import java.util.function.Supplier;

public final class ChunkDebugNetwork {
    private static final String PROTOCOL = "2";
    private static final SimpleChannel CHANNEL = NetworkRegistry.ChannelBuilder
        .named(ResourceLocation.fromNamespaceAndPath(LC2H.MODID, "chunk_debug"))
        .networkProtocolVersion(() -> PROTOCOL)
        .clientAcceptedVersions(PROTOCOL::equals)
        .serverAcceptedVersions(PROTOCOL::equals)
        .simpleChannel();

    private ChunkDebugNetwork() {
    }

    public static void register() {
        CHANNEL.registerMessage(
            0,
            SelectionUpdate.class,
            SelectionUpdate::encode,
            SelectionUpdate::decode,
            ChunkDebugNetwork::handleSelectionUpdate,
            Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        );
    }

    public static void sendSelection(ServerPlayer player, ChunkDebugSelection selection) {
        if (player == null || selection == null) {
            return;
        }
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new SelectionUpdate(selection));
    }

    private static void handleSelectionUpdate(SelectionUpdate msg, Supplier<NetworkEvent.Context> ctxSup) {
        NetworkEvent.Context ctx = ctxSup.get();
        ctx.enqueueWork(() -> ChunkDebugOverlay.applyServerUpdate(msg.selection));
        ctx.setPacketHandled(true);
    }

    public record SelectionUpdate(ChunkDebugSelection selection) {
        public static void encode(SelectionUpdate msg, net.minecraft.network.FriendlyByteBuf buf) {
            msg.selection.encode(buf);
        }

        public static SelectionUpdate decode(net.minecraft.network.FriendlyByteBuf buf) {
            return new SelectionUpdate(ChunkDebugSelection.decode(buf));
        }
    }
}
