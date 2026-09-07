package org.admany.lc2h.neoforge.compat.network;

import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Compatibility facade for the legacy SimpleChannel declaration style. */
public final class NetworkRegistry {
    private static final List<SimpleChannel> CHANNELS = new CopyOnWriteArrayList<>();
    private static final AtomicBoolean LISTENER_INSTALLED = new AtomicBoolean();

    private NetworkRegistry() {
    }

    static void track(SimpleChannel channel) {
        CHANNELS.add(channel);
        installListener();
    }

    private static void installListener() {
        if (LISTENER_INSTALLED.compareAndSet(false, true)) {
            org.admany.lc2h.neoforge.compat.FMLJavaModLoadingContext.get()
                .getModEventBus()
                .addListener(NetworkRegistry::registerPayloads);
        }
    }

    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        for (SimpleChannel channel : CHANNELS) {
            channel.registerPayloads(event);
        }
    }

    public static final class ChannelBuilder {
        private final ResourceLocation name;
        private Supplier<String> protocol = () -> "1";

        private ChannelBuilder(ResourceLocation name) {
            this.name = name;
        }

        public static ChannelBuilder named(ResourceLocation name) {
            if (name == null) {
                throw new IllegalArgumentException("Network channel name cannot be null");
            }
            return new ChannelBuilder(name);
        }

        public ChannelBuilder networkProtocolVersion(Supplier<String> version) {
            if (version != null) {
                this.protocol = version;
            }
            return this;
        }

        public ChannelBuilder clientAcceptedVersions(Predicate<String> versions) {
            return this;
        }

        public ChannelBuilder serverAcceptedVersions(Predicate<String> versions) {
            return this;
        }

        public SimpleChannel simpleChannel() {
            SimpleChannel channel = new SimpleChannel(name, protocol);
            NetworkRegistry.track(channel);
            return channel;
        }
    }
}
