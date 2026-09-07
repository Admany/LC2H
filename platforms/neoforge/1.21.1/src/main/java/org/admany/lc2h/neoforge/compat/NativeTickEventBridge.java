package org.admany.lc2h.neoforge.compat;

import net.minecraft.server.MinecraftServer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Converts NeoForge's split pre/post tick events to the legacy phase shape. */
@EventBusSubscriber(modid = "lc2h")
public final class NativeTickEventBridge {
    private NativeTickEventBridge() {
    }

    @SubscribeEvent
    public static void serverPre(ServerTickEvent.Pre event) {
        postServer(event.getServer(), TickEvent.Phase.START);
    }

    @SubscribeEvent
    public static void serverPost(ServerTickEvent.Post event) {
        postServer(event.getServer(), TickEvent.Phase.END);
    }

    @SubscribeEvent
    public static void levelPre(LevelTickEvent.Pre event) {
        NeoForge.EVENT_BUS.post(new TickEvent.LevelTickEvent(event.getLevel(), TickEvent.Phase.START));
    }

    @SubscribeEvent
    public static void levelPost(LevelTickEvent.Post event) {
        NeoForge.EVENT_BUS.post(new TickEvent.LevelTickEvent(event.getLevel(), TickEvent.Phase.END));
    }

    private static void postServer(MinecraftServer server, TickEvent.Phase phase) {
        NeoForge.EVENT_BUS.post(new TickEvent.ServerTickEvent(server, phase));
    }

    @EventBusSubscriber(modid = "lc2h", value = Dist.CLIENT)
    public static final class Client {
        private Client() {
        }

        @SubscribeEvent
        public static void clientPre(net.neoforged.neoforge.client.event.ClientTickEvent.Pre event) {
            NeoForge.EVENT_BUS.post(new TickEvent.ClientTickEvent(TickEvent.Phase.START));
        }

        @SubscribeEvent
        public static void clientPost(net.neoforged.neoforge.client.event.ClientTickEvent.Post event) {
            NeoForge.EVENT_BUS.post(new TickEvent.ClientTickEvent(TickEvent.Phase.END));
        }
    }
}
