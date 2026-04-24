package org.admany.lc2h.dev.debug;

import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.admany.lc2h.LC2H;
import org.admany.lc2h.data.cache.FeatureCache;
import org.admany.lc2h.config.ConfigManager;

@Mod.EventBusSubscriber(modid = LC2H.MODID)
public class LiveMonitor {

    private static long lastLogMs = 0L;
    private static int lastCacheSize = -1;
    private static final long LOG_INTERVAL_MS = 5_000L; // 5s between logs

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }

        if (!ConfigManager.ENABLE_CACHE_STATS_LOGGING) return;

        long now = System.currentTimeMillis();
        if (now - lastLogMs < LOG_INTERVAL_MS) return;

        int size = FeatureCache.getSize();

        if (size == 0 && lastCacheSize == 0) {
            lastLogMs = now;
            return;
        }

        lastCacheSize = size;
        lastLogMs = now;

        LC2H.LOGGER.debug("Live monitor - Cache size: {}", size);
    }
}
