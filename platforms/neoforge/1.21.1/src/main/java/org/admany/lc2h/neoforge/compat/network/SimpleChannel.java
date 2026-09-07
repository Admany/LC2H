package org.admany.lc2h.neoforge.compat.network;

import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadHandler;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

/** Adapts the Forge SimpleChannel declarations to NeoForge play payloads. */
public final class SimpleChannel {
    private final ResourceLocation channelName;
    private final Supplier<String> protocolVersion;
    private final List<MessageRegistration<?>> registrations = new ArrayList<>();

    SimpleChannel(ResourceLocation channelName, Supplier<String> protocolVersion) {
        this.channelName = channelName;
        this.protocolVersion = protocolVersion;
    }

    public <T> void registerMessage(int id,
                                    Class<T> type,
                                    BiConsumer<T, FriendlyByteBuf> encoder,
                                    Function<FriendlyByteBuf, T> decoder,
                                    BiConsumer<T, Supplier<NetworkEvent.Context>> handler) {
        register(id, type, encoder, decoder, handler, Optional.empty());
    }

    public <T> void registerMessage(int id,
                                    Class<T> type,
                                    BiConsumer<T, FriendlyByteBuf> encoder,
                                    Function<FriendlyByteBuf, T> decoder,
                                    BiConsumer<T, Supplier<NetworkEvent.Context>> handler,
                                    Optional<NetworkDirection> direction) {
        register(id, type, encoder, decoder, handler, direction == null ? Optional.empty() : direction);
    }

    public <T> void send(PacketDistributor.Target target, T message) {
        MessageRegistration<T> registration = registrationFor(message);
        if (registration == null || target == null) {
            return;
        }
        ServerPlayer player = target.player();
        if (player != null && player.connection != null) {
            player.connection.send(new ClientboundCustomPayloadPacket(registration.payload(message)));
        }
    }

    public <T> void sendToServer(T message) {
        MessageRegistration<T> registration = registrationFor(message);
        if (registration == null) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft != null && minecraft.getConnection() != null) {
            minecraft.getConnection().getConnection()
                .send(new ServerboundCustomPayloadPacket(registration.payload(message)));
        }
    }

    private <T> void register(int id,
                              Class<T> type,
                              BiConsumer<T, FriendlyByteBuf> encoder,
                              Function<FriendlyByteBuf, T> decoder,
                              BiConsumer<T, Supplier<NetworkEvent.Context>> handler,
                              Optional<NetworkDirection> direction) {
        if (type == null || encoder == null || decoder == null || handler == null) {
            throw new IllegalArgumentException("Network message registration cannot contain null values");
        }
        synchronized (registrations) {
            for (MessageRegistration<?> existing : registrations) {
                if (existing.id == id || existing.type == type) {
                    throw new IllegalArgumentException("Duplicate network message registration for " + channelName + " id " + id);
                }
            }
            ResourceLocation payloadId = ResourceLocation.fromNamespaceAndPath(
                channelName.getNamespace(), channelName.getPath() + "_" + id);
            CustomPacketPayload.Type<Envelope> payloadType = new CustomPacketPayload.Type<>(payloadId);
            registrations.add(new MessageRegistration<>(id, type, encoder, decoder, handler, direction, payloadType));
        }
    }

    void registerPayloads(RegisterPayloadHandlersEvent event) {
        String version = protocolVersion.get();
        if (version == null || version.isBlank()) {
            version = "1";
        }
        PayloadRegistrar registrar = event.registrar(version);
        List<MessageRegistration<?>> snapshot;
        synchronized (registrations) {
            snapshot = List.copyOf(registrations);
        }
        for (MessageRegistration<?> registration : snapshot) {
            registration.register(registrar);
        }
    }

    @SuppressWarnings("unchecked")
    private <T> MessageRegistration<T> registrationFor(T message) {
        if (message == null) {
            return null;
        }
        synchronized (registrations) {
            for (MessageRegistration<?> registration : registrations) {
                if (registration.type.isInstance(message)) {
                    return (MessageRegistration<T>) registration;
                }
            }
        }
        return null;
    }

    private static final class MessageRegistration<T> {
        private final int id;
        private final Class<T> type;
        private final BiConsumer<T, FriendlyByteBuf> encoder;
        private final Function<FriendlyByteBuf, T> decoder;
        private final BiConsumer<T, Supplier<NetworkEvent.Context>> handler;
        private final Optional<NetworkDirection> direction;
        private final CustomPacketPayload.Type<Envelope> payloadType;
        private final StreamCodec<RegistryFriendlyByteBuf, Envelope> codec;

        private MessageRegistration(int id,
                                     Class<T> type,
                                     BiConsumer<T, FriendlyByteBuf> encoder,
                                     Function<FriendlyByteBuf, T> decoder,
                                     BiConsumer<T, Supplier<NetworkEvent.Context>> handler,
                                     Optional<NetworkDirection> direction,
                                     CustomPacketPayload.Type<Envelope> payloadType) {
            this.id = id;
            this.type = type;
            this.encoder = encoder;
            this.decoder = decoder;
            this.handler = handler;
            this.direction = direction;
            this.payloadType = payloadType;
            this.codec = StreamCodec.of(
                (RegistryFriendlyByteBuf buffer, Envelope payload) -> encoder.accept(type.cast(payload.message()), buffer),
                buffer -> new Envelope(payloadType, decoder.apply(buffer))
            );
        }

        private Envelope payload(T message) {
            return new Envelope(payloadType, type.cast(message));
        }

        private void register(PayloadRegistrar registrar) {
            IPayloadHandler<Envelope> payloadHandler = (payload, context) ->
                handler.accept(type.cast(payload.message()), () -> new NetworkEvent.Context(context));
            if (direction.isPresent()) {
                if (direction.get() == NetworkDirection.PLAY_TO_CLIENT) {
                    registrar.playToClient(payloadType, codec, payloadHandler);
                } else {
                    registrar.playToServer(payloadType, codec, payloadHandler);
                }
            } else {
                registrar.playBidirectional(payloadType, codec, payloadHandler);
            }
        }
    }

    private record Envelope(CustomPacketPayload.Type<Envelope> type, Object message) implements CustomPacketPayload {
        @Override
        public CustomPacketPayload.Type<Envelope> type() {
            return type;
        }
    }
}
