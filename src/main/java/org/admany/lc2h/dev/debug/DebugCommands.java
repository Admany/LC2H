package org.admany.lc2h.dev.debug;

import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
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

                    Component entries = distributed != null
                        ? Component.translatable("lc2h.dev.cache.entries_total", local + distributed, local, distributed)
                        : Component.translatable("lc2h.dev.cache.entries_local", local);
                    Component pressureLabel = Component.translatable(pressure
                        ? "lc2h.dev.cache.pressure.high"
                        : "lc2h.dev.cache.pressure.normal");
                    Component memoryLine = Component.translatable("lc2h.dev.cache.memory_line", memoryMB, pressureLabel);

                    ChatMessenger.info(context.getSource(), Component.empty().append(entries).append(Component.literal(" | ")).append(memoryLine));
                    return 1;
                }))
            .then(Commands.literal("clearcache")
                .executes(context -> {
                    FeatureCache.CacheStats cleared = FeatureCache.clear();
                    long local = cleared.localEntries();
                    Long distributed = cleared.quantifiedEntries();
                    ChatMessenger.success(context.getSource(), distributed != null
                        ? Component.translatable("lc2h.dev.cache.cleared_with_distributed", local, distributed)
                        : Component.translatable("lc2h.dev.cache.cleared", local));
                    return 1;
                })
                .then(Commands.literal("disk")
                    .executes(context -> {
                        FeatureCache.CacheStats cleared = FeatureCache.clear(true);
                        long local = cleared.localEntries();
                        Long distributed = cleared.quantifiedEntries();
                        ChatMessenger.success(context.getSource(), distributed != null
                            ? Component.translatable("lc2h.dev.cache.cleared_disk_with_distributed", local, distributed)
                            : Component.translatable("lc2h.dev.cache.cleared_disk", local));
                        return 1;
                    })))
            .then(Commands.literal("cleanup")
                .executes(context -> {
                    FeatureCache.triggerMemoryPressureCleanup();
                    ChatMessenger.success(context.getSource(), Component.translatable("lc2h.dev.cache.cleanup_triggered"));
                    return 1;
                })));
    }
}
