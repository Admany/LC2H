package org.admany.lc2h.dev.debug.frustum;

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

public final class FrustumDebugNetwork {
    private static final String PROTOCOL = "1";
    private static final SimpleChannel CHANNEL = NetworkRegistry.ChannelBuilder
        .named(ResourceLocation.fromNamespaceAndPath(LC2H.MODID, "frustum_debug"))
        .networkProtocolVersion(() -> PROTOCOL)
        .clientAcceptedVersions(PROTOCOL::equals)
        .serverAcceptedVersions(PROTOCOL::equals)
        .simpleChannel();

    private FrustumDebugNetwork() {
    }

    public static void register() {
        CHANNEL.registerMessage(
            0,
            StateUpdate.class,
            StateUpdate::encode,
            StateUpdate::decode,
            FrustumDebugNetwork::handleStateUpdate,
            Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        );
    }

    public static void sendState(ServerPlayer player, FrustumDebugState state) {
        if (player == null || state == null) {
            return;
        }
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new StateUpdate(state));
    }

    private static void handleStateUpdate(StateUpdate msg, Supplier<NetworkEvent.Context> ctxSup) {
        NetworkEvent.Context ctx = ctxSup.get();
        ctx.enqueueWork(() -> FrustumDebugOverlay.applyServerUpdate(msg.state));
        ctx.setPacketHandled(true);
    }

    public record StateUpdate(FrustumDebugState state) {
        public static void encode(StateUpdate msg, net.minecraft.network.FriendlyByteBuf buf) {
            msg.state.encode(buf);
        }

        public static StateUpdate decode(net.minecraft.network.FriendlyByteBuf buf) {
            return new StateUpdate(FrustumDebugState.decode(buf));
        }
    }
}
