package org.admany.lc2h.util.server;

import net.minecraft.server.MinecraftServer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.admany.lc2h.LC2H;

@Mod.EventBusSubscriber(modid = LC2H.MODID)
public final class ServerTickLoad {

    private static final double EWMA_ALPHA = 0.15D;
    private static final long CONFIG_REFRESH_NS = java.util.concurrent.TimeUnit.SECONDS.toNanos(1);

    private static volatile long tickStartNs = 0L;
    private static volatile double lastTickMs = 0.0D;
    private static volatile double smoothedTickMs = 0.0D;
    private static volatile double elapsedLimitMs = readDouble("lc2h.lag_guard.elapsed_ms", 18.0D);
    private static volatile double avgLimitMs = readDouble("lc2h.lag_guard.avg_ms", 40.0D);
    private static volatile long lastConfigRefreshNs = 0L;

    private ServerTickLoad() {
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.START) {
            tickStartNs = System.nanoTime();
            return;
        }

        long start = tickStartNs;
        if (start == 0L) {
            return;
        }

        double ms = (System.nanoTime() - start) / 1_000_000.0D;
        lastTickMs = ms;
        if (smoothedTickMs <= 0.0D) {
            smoothedTickMs = ms;
        } else {
            smoothedTickMs = (smoothedTickMs * (1.0D - EWMA_ALPHA)) + (ms * EWMA_ALPHA);
        }
    }

    public static double getElapsedMsInCurrentTick() {
        long start = tickStartNs;
        if (start == 0L) {
            return 0.0D;
        }
        return (System.nanoTime() - start) / 1_000_000.0D;
    }

    public static double getLastTickMs() {
        return lastTickMs;
    }

    public static double getSmoothedTickMs() {
        return smoothedTickMs;
    }

    public static double getAverageTickMs(MinecraftServer server, double fallback) {
        if (server == null) {
            return fallback;
        }
        try {
            return server.getAverageTickTime();
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    public static boolean shouldPauseNonCritical(MinecraftServer server) {
        refreshConfigIfNeeded();

        double elapsed = getElapsedMsInCurrentTick();
        if (elapsedLimitMs > 0.0D && elapsed >= elapsedLimitMs) {
            return true;
        }

        double avg = getAverageTickMs(server, 50.0D);
        return avgLimitMs > 0.0D && avg >= avgLimitMs;
    }

    private static void refreshConfigIfNeeded() {
        long now = System.nanoTime();
        long lastRefresh = lastConfigRefreshNs;
        if (now - lastRefresh < CONFIG_REFRESH_NS) {
            return;
        }
        lastConfigRefreshNs = now;
        elapsedLimitMs = readDouble("lc2h.lag_guard.elapsed_ms", elapsedLimitMs);
        avgLimitMs = readDouble("lc2h.lag_guard.avg_ms", avgLimitMs);
    }

    private static double readDouble(String key, double fallback) {
        String value = System.getProperty(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
