package org.admany.lc2h.util.server;

import net.minecraft.server.MinecraftServer;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

public final class ServerRescheduler {
    private static final Queue<Runnable> PENDING = new ConcurrentLinkedQueue<>();
    private static MinecraftServer server;

    private ServerRescheduler() {}

    public static void setServer(MinecraftServer srv) {
        server = srv;
        drainPending();
    }

    public static MinecraftServer getServer() {
        return server;
    }

    public static boolean isServerAvailable() {
        return server != null;
    }


    private static void drainPending() {
        if (server == null) return;
        Runnable r;
        while ((r = PENDING.poll()) != null) {
            try {
                server.execute(r);
            } catch (Throwable t) {
                
            }
        }
    }


    public static void runOnServer(Runnable task) {
        if (server != null) {
            server.execute(task);
            return;
        }
        PENDING.add(task);
    }
}