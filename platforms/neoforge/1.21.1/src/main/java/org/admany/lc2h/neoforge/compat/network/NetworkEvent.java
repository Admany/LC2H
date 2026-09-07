package org.admany.lc2h.neoforge.compat.network;

import net.minecraft.world.entity.player.Player;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.concurrent.Executor;

/** Legacy packet context backed by NeoForge's payload context. */
public final class NetworkEvent {
    private NetworkEvent() {
    }

    public static final class Context {
        private final IPayloadContext payloadContext;
        private final NetworkDirection direction;
        private final ServerPlayer sender;

        public Context(NetworkDirection direction, ServerPlayer sender) {
            this.payloadContext = null;
            this.direction = direction;
            this.sender = sender;
        }

        public Context(IPayloadContext payloadContext) {
            this.payloadContext = payloadContext;
            this.direction = payloadContext.flow() == net.minecraft.network.protocol.PacketFlow.CLIENTBOUND
                ? NetworkDirection.PLAY_TO_CLIENT
                : NetworkDirection.PLAY_TO_SERVER;
            Player player = payloadContext.player();
            this.sender = player instanceof ServerPlayer serverPlayer ? serverPlayer : null;
        }

        public NetworkDirection getDirection() {
            return direction;
        }

        public ServerPlayer getSender() {
            return sender;
        }

        public void enqueueWork(Runnable work) {
            if (work != null) {
                if (payloadContext != null) {
                    payloadContext.enqueueWork(work);
                } else {
                    work.run();
                }
            }
        }

        public void enqueueWork(Executor executor, Runnable work) {
            if (work != null) {
                if (executor == null) {
                    enqueueWork(work);
                } else {
                    executor.execute(work);
                }
            }
        }

        public void setPacketHandled(boolean handled) {
        }
    }
}
