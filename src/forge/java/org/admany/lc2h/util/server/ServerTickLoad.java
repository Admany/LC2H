package org.admany.lc2h.util.server;

import net.minecraft.server.MinecraftServer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.admany.lc2h.LC2H;
import org.admany.lc2h.compat.ChunkyCompat;

@Mod.EventBusSubscriber(modid = LC2H.MODID)
public final class ServerTickLoad {

    private static final double EWMA_ALPHA = 0.15D;
    private static final long CONFIG_REFRESH_NS = java.util.concurrent.TimeUnit.SECONDS.toNanos(1);

    private static volatile long tickStartNs = 0L;
    private static volatile double lastTickMs = 0.0D;
    private static volatile double smoothedTickMs = 0.0D;
    private static volatile double elapsedLimitMs = readDouble("lc2h.lag_guard.elapsed_ms", 18.0D);
    private static volatile double avgLimitMs = readDouble("lc2h.lag_guard.avg_ms", 40.0D);
    private static volatile double budgetTargetMs = readDouble("lc2h.budget.target_ms", 75.0D);
    private static volatile double budgetMinScale = readDouble("lc2h.budget.min_scale", 0.5D);
    private static volatile double budgetMaxScale = readDouble("lc2h.budget.max_scale", 1.5D);
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

    public static double getBudgetScale(MinecraftServer server) {
        double avg = getAverageTickMs(server, 50.0D);
        if (avg <= 0.0D) {
            return 1.0D;
        }
        refreshConfigIfNeeded();
        double target = budgetTargetMs > 0.0D ? budgetTargetMs : 75.0D;
        double minScale = budgetMinScale > 0.0D ? budgetMinScale : 0.5D;
        double maxScale = budgetMaxScale > 0.0D ? budgetMaxScale : 1.5D;
        double scale = target / avg;
        if (scale < minScale) {
            return minScale;
        }
        if (scale > maxScale) {
            return maxScale;
        }
        return scale;
    }

    public static boolean shouldPauseNonCritical(MinecraftServer server) {
        refreshConfigIfNeeded();

        if (ChunkyCompat.shouldPauseForChunky()) {
            return true;
        }

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
        budgetTargetMs = readDouble("lc2h.budget.target_ms", budgetTargetMs);
        budgetMinScale = readDouble("lc2h.budget.min_scale", budgetMinScale);
        budgetMaxScale = readDouble("lc2h.budget.max_scale", budgetMaxScale);
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
