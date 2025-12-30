package org.admany.lc2h.debug;

import net.minecraft.commands.Commands;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.admany.lc2h.LC2H;
import org.admany.lc2h.data.cache.FeatureCache;
import org.admany.lc2h.util.log.ChatMessenger;

@Mod.EventBusSubscriber(modid = LC2H.MODID)
public class DebugCommands {

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("lc2h")
            .then(Commands.literal("cache")
                .executes(context -> {
                    FeatureCache.CacheStats stats = FeatureCache.snapshot();
                    long local = stats.localEntries();
                    Long distributed = stats.quantifiedEntries();
                    long memoryMB = FeatureCache.getMemoryUsageMB();
                    boolean pressure = FeatureCache.isMemoryPressureHigh();
                    StringBuilder sb = new StringBuilder();
                    if (distributed != null) {
                        long total = local + distributed;
                        sb.append("Cache entries ▸ total ").append(total)
                            .append(" (local ").append(local)
                            .append(", distributed ").append(distributed).append(')');
                    } else {
                        sb.append("Cache entries ▸ local ").append(local);
                    }
                    sb.append(" | Memory: ").append(memoryMB).append("MB")
                        .append(" | Pressure: ").append(pressure ? "HIGH" : "NORMAL");
                    ChatMessenger.info(context.getSource(), sb.toString());
                    return 1;
                }))
            .then(Commands.literal("clearcache")
                .executes(context -> {
                    FeatureCache.CacheStats cleared = FeatureCache.clear();
                    long local = cleared.localEntries();
                    Long distributed = cleared.quantifiedEntries();
                    StringBuilder sb = new StringBuilder("Cleared cache ▸ flushed ")
                        .append(local).append(" local");
                    if (distributed != null) {
                        sb.append(", ").append(distributed).append(" distributed");
                    }
                    ChatMessenger.success(context.getSource(), sb.toString());
                    return 1;
                })
                .then(Commands.literal("disk")
                    .executes(context -> {
                        FeatureCache.CacheStats cleared = FeatureCache.clear(true);
                        long local = cleared.localEntries();
                        Long distributed = cleared.quantifiedEntries();
                        StringBuilder sb = new StringBuilder("Cleared disk cache ▸ flushed ")
                            .append(local).append(" local");
                        if (distributed != null) {
                            sb.append(", ").append(distributed).append(" distributed");
                        }
                        ChatMessenger.success(context.getSource(), sb.toString());
                        return 1;
                    })))
            .then(Commands.literal("cleanup")
                .executes(context -> {
                    FeatureCache.triggerMemoryPressureCleanup();
                    ChatMessenger.success(context.getSource(), "Triggered memory pressure cleanup");
                    return 1;
                })));
    }
}