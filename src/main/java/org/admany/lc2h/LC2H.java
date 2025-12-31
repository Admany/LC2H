package org.admany.lc2h;

import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import org.admany.lc2h.compat.C2MECompat;
import org.admany.lc2h.world.VineClusterCleaner;
import org.admany.lc2h.worldgen.async.warmup.AsyncChunkWarmup;

import java.util.logging.Handler;
import java.util.logging.Level;
import org.admany.lc2h.diagnostics.DiagnosticsReporter;
import org.admany.lc2h.diagnostics.StallDetector;
import org.admany.lc2h.diagnostics.ChunkGenTracker;
import org.admany.lc2h.logging.config.ConfigManager;
import org.admany.lc2h.util.chunk.ChunkPostProcessor;
import org.admany.lc2h.util.server.ServerRescheduler;
import org.admany.lc2h.client.LC2HClient;
import org.admany.lc2h.network.ConfigSyncNetwork;
import org.admany.quantified.api.QuantifiedAPI;
import org.admany.quantified.core.common.cache.CacheManager;
import org.admany.quantified.core.common.async.task.ModPriorityManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import net.minecraftforge.fml.common.Mod;
import net.minecraft.commands.Commands;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import mcjty.lostcities.varia.ChunkCoord;

@Mod(LC2H.MODID)
public class LC2H {
    public static final String MODID = "lc2h";
    public static final Logger LOGGER = LogManager.getLogger();

    public static final DeferredRegister<net.minecraft.sounds.SoundEvent> SOUND_EVENTS = DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, MODID);
    public static final net.minecraftforge.registries.RegistryObject<net.minecraft.sounds.SoundEvent> COUNTDOWN_SOUND = SOUND_EVENTS.register("countdown",
        () -> net.minecraft.sounds.SoundEvent.createVariableRangeEvent(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(MODID, "countdown")));
    public static final net.minecraftforge.registries.RegistryObject<net.minecraft.sounds.SoundEvent> BUTTON_CLICK_SOUND = SOUND_EVENTS.register("button_click",
        () -> net.minecraft.sounds.SoundEvent.createVariableRangeEvent(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(MODID, "button_click")));

    @SuppressWarnings("removal")
    public LC2H() {
        MinecraftForge.EVENT_BUS.register(this);

        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::onCommonSetup);
        ConfigSyncNetwork.register();
        try {
            CacheManager.startMaintenance(java.time.Duration.ofMinutes(5), java.time.Duration.ofMinutes(10));
        } catch (Throwable ignored) {
        }

        SOUND_EVENTS.register(FMLJavaModLoadingContext.get().getModEventBus());

        C2MECompat.init();


        ConfigManager.initializeGlobals();

        registerWithQuantifiedApi();

        redirectQuantifiedJulLogging();



        DistExecutor.safeRunWhenOn(Dist.CLIENT, () -> LC2HClient::init);

        if (org.admany.lc2h.logging.config.ConfigManager.ENABLE_DEBUG_LOGGING) {
            LOGGER.info("[LC2H] Multithreading Engine Started");
        } else {
            LOGGER.debug("[LC2H] Multithreading Engine Started");
        }
    }

    private void registerWithQuantifiedApi() {
        try {
            if (QuantifiedAPI.register(MODID)) {
                LOGGER.info("[LC2H] Registered with Quantified API");
                try {
                    ModPriorityManager.setMaxTasksForMod(MODID, 1_000_000L);
                    LOGGER.debug("[LC2H] Applied Quantified mod priority tuning");
                } catch (Throwable priorityError) {
                    LOGGER.warn("[LC2H] Could not adjust Quantified mod priority: {}", priorityError.getMessage());
                }
            } else {
                LOGGER.warn("[LC2H] Quantified API registration returned false");
            }
        } catch (Throwable t) {
            LOGGER.debug("[LC2H] Quantified API not present or failed to register: {}", t.getMessage());
        }
    }


    private void redirectQuantifiedJulLogging() {
        try {
            java.util.logging.Logger jul = java.util.logging.Logger.getLogger("org.admany.quantified");
            jul.setUseParentHandlers(false);
            for (Handler h : jul.getHandlers()) {
                jul.removeHandler(h);
            }
            jul.setLevel(Level.ALL);
            jul.addHandler(new Handler() {
                @Override
                public void publish(java.util.logging.LogRecord record) {
                    if (!isLoggable(record)) return;
                    String msg = record.getMessage();
                    Throwable thrown = record.getThrown();
                    Level lvl = record.getLevel();
                    if (lvl.intValue() >= Level.SEVERE.intValue()) {
                        LOGGER.error("[Quantified] {}", msg, thrown);
                    } else if (lvl.intValue() >= Level.WARNING.intValue()) {
                        LOGGER.warn("[Quantified] {}", msg, thrown);
                    } else if (lvl.intValue() >= Level.INFO.intValue()) {
                        LOGGER.info("[Quantified] {}", msg, thrown);
                    } else {
                        LOGGER.debug("[Quantified] {}", msg, thrown);
                    }
                }
                @Override public void flush() { }
                @Override public void close() { }
            });
            LOGGER.debug("[LC2H] Redirected Quantified JUL logging to LC2H logger");
        } catch (Throwable t) {
            LOGGER.debug("[LC2H] Could not adjust Quantified JUL logging: {}", t.getMessage());
        }
    }

    public void onCommonSetup(FMLCommonSetupEvent event) {
        if (org.admany.lc2h.logging.config.ConfigManager.ENABLE_DEBUG_LOGGING) {
            LOGGER.info("[LC2H] onCommonSetup called");
        } else {
            LOGGER.debug("[LC2H] onCommonSetup called");
        }
        registerWithQuantifiedApi();
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        registerWithQuantifiedApi();
        try {
            org.admany.lc2h.worldgen.lostcities.LostCityFeatureGuards.reset();
            org.admany.lc2h.worldgen.lostcities.LostCityTerrainFeatureGuards.reset();
            LOGGER.debug("[LC2H] Reset Lost Cities generation guards at server start");
        } catch (Throwable t) {
            LOGGER.debug("[LC2H] Could not reset Lost Cities generation guards: {}", t.getMessage());
        }
        try {
            mcjty.lostcities.setup.Config.resetProfileCache();
            LOGGER.debug("[LC2H] Reset Lost Cities profile cache at server start");
        } catch (Throwable t) {
            LOGGER.debug("[LC2H] Could not reset Lost Cities profile cache: {}", t.getMessage());
        }

        try {
            AsyncChunkWarmup.initializeGpuWarmup();
            if (org.admany.lc2h.logging.config.ConfigManager.ENABLE_DEBUG_LOGGING) {
                LOGGER.info("[LC2H] GPU warmup initialized during server startup");
            } else {
                LOGGER.debug("[LC2H] GPU warmup initialized during server startup");
            }
        } catch (Throwable t) {
            LOGGER.warn("[LC2H] Failed to initialize GPU warmup during server startup: {}", t.getMessage());
        }

        VineClusterCleaner.initialize(event.getServer());

        ServerRescheduler.setServer(event.getServer());

        try {
            DiagnosticsReporter.start(event.getServer());
            if (org.admany.lc2h.logging.config.ConfigManager.ENABLE_DEBUG_LOGGING) {
                LOGGER.info("[LC2H] Diagnostics reporter started");
            } else {
                LOGGER.debug("[LC2H] Diagnostics reporter started");
            }
        } catch (Throwable t) {
            LOGGER.warn("[LC2H] Failed to start diagnostics reporter: {}", t.getMessage());
        }

        try {
            StallDetector.start(event.getServer());
        } catch (Throwable t) {
            LOGGER.warn("[LC2H] Failed to start StallDetector: {}", t.getMessage());
        }

        try {
            java.util.concurrent.ScheduledExecutorService cleanupExecutor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "lc2h-gpu-cleanup");
                t.setDaemon(true);
                return t;
            });
            cleanupExecutor.scheduleAtFixedRate(() -> {
                try {
                    org.admany.lc2h.worldgen.gpu.GPUMemoryManager.cleanupOldEntries(2 * 60 * 1000);
                    String stats = org.admany.lc2h.worldgen.gpu.GPUMemoryManager.getMemoryStats();
                    LOGGER.debug("[LC2H] GPU memory cleanup completed: {}", stats);

                    if (stats.contains("MB used")) {
                        try {
                            String memoryPart = stats.substring(stats.indexOf("MB used") - 10, stats.indexOf("MB used")).trim();
                            int memoryMB = Integer.parseInt(memoryPart.split(": ")[1]);
                            if (memoryMB > 100) {
                                org.admany.lc2h.worldgen.gpu.GPUMemoryManager.continuousCleanup();
                            }
                        } catch (Exception e) {
                            LOGGER.debug("[LC2H] Could not parse memory stats: {}", stats);
                        }
                    }
                } catch (Throwable t) {
                    LOGGER.warn("[LC2H] GPU memory cleanup failed: {}", t.getMessage());
                }
            }, 30, 30, java.util.concurrent.TimeUnit.SECONDS);
            if (org.admany.lc2h.logging.config.ConfigManager.ENABLE_DEBUG_LOGGING) {
                LOGGER.info("[LC2H] Aggressive GPU memory cleanup scheduler started (30s intervals)");
            } else {
                LOGGER.debug("[LC2H] Aggressive GPU memory cleanup scheduler started (30s intervals)");
            }
        } catch (Throwable t) {
            LOGGER.warn("Failed to start GPU memory cleanup: {}", t.getMessage());
        }

        try {
            event.getServer().getCommands().getDispatcher().register(
                Commands.literal("lc2h").then(
                    Commands.literal("diagnostics").executes(ctx -> {
                        StallDetector.triggerDump(ctx.getSource().getServer());
                        ctx.getSource().sendSuccess(() -> net.minecraft.network.chat.Component.literal("LC2H diagnostics dump triggered"), false);
                        return 1;
                    })
                ).then(
                Commands.literal("gpu").executes(ctx -> {
                    String stats = org.admany.lc2h.worldgen.gpu.GPUMemoryManager.getComprehensiveMemoryStats();
                    ctx.getSource().sendSuccess(() -> net.minecraft.network.chat.Component.literal("Memory Stats: " + stats), false);
                    return 1;
                }).then(
                        Commands.literal("cleanup").executes(ctx -> {
                            org.admany.lc2h.worldgen.gpu.GPUMemoryManager.comprehensiveCleanup();
                            String stats = org.admany.lc2h.worldgen.gpu.GPUMemoryManager.getComprehensiveMemoryStats();
                            ctx.getSource().sendSuccess(() -> net.minecraft.network.chat.Component.literal("Comprehensive cleanup completed. Stats: " + stats), false);
                            return 1;
                        })
                    )
                ).then(
                    Commands.literal("rescanChunk").executes(ctx -> {
                        ServerPlayer player = ctx.getSource().getPlayerOrException();
                        ChunkPos pos = player.chunkPosition();
                        ChunkPostProcessor.forceRescanChunk(player.serverLevel(), pos);
                        ctx.getSource().sendSuccess(() -> net.minecraft.network.chat.Component.literal("Queued rescan for chunk " + pos), false);
                        return 1;
                    })
                ).then(
                    Commands.literal("chunkinfo").executes(ctx -> {
                        ServerPlayer player = ctx.getSource().getPlayerOrException();
                        ChunkPos pos = player.chunkPosition();
                        ChunkCoord coord = new ChunkCoord(player.level().dimension(), pos.x, pos.z);
                        String report = ChunkGenTracker.buildReport(coord);
                        ctx.getSource().sendSuccess(() -> net.minecraft.network.chat.Component.literal(report), false);
                        return 1;
                    }).then(
                        Commands.argument("chunkX", IntegerArgumentType.integer()).then(
                            Commands.argument("chunkZ", IntegerArgumentType.integer()).executes(ctx -> {
                                int chunkX = IntegerArgumentType.getInteger(ctx, "chunkX");
                                int chunkZ = IntegerArgumentType.getInteger(ctx, "chunkZ");
                                ChunkCoord coord = new ChunkCoord(ctx.getSource().getLevel().dimension(), chunkX, chunkZ);
                                String report = ChunkGenTracker.buildReport(coord);
                                ctx.getSource().sendSuccess(() -> net.minecraft.network.chat.Component.literal(report), false);
                                return 1;
                            })
                        )
                    )
                )
            );
        } catch (Throwable t) {
            LOGGER.warn("[LC2H] Failed to register /lc2h commands: {}", t.getMessage());
        }
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        try {
            org.admany.lc2h.worldgen.lostcities.LostCityFeatureGuards.reset();
            org.admany.lc2h.worldgen.lostcities.LostCityTerrainFeatureGuards.reset();
            LOGGER.debug("[LC2H] Reset Lost Cities generation guards at server stop");
        } catch (Throwable t) {
            LOGGER.debug("[LC2H] Could not reset Lost Cities generation guards: {}", t.getMessage());
        }
        try {
            DiagnosticsReporter.stop();
            LOGGER.info("[LC2H] Diagnostics reporter stopped");
        } catch (Throwable t) {
            LOGGER.warn("[LC2H] Error stopping diagnostics reporter: {}", t.getMessage());
        }

        try {
            StallDetector.stop();
        } catch (Throwable t) {
            LOGGER.warn("[LC2H] Failed to stop StallDetector: {}", t.getMessage());
        }

        try {
            org.admany.lc2h.worldgen.async.planner.AsyncMultiChunkPlanner.flushPendingBatches();
            LOGGER.info("[LC2H] Pending multi-chunk batches flushed");
        } catch (Throwable t) {
            LOGGER.warn("[LC2H] Error flushing pending batches: {}", t.getMessage());
        }

        try {
            org.admany.lc2h.worldgen.async.planner.AsyncMultiChunkPlanner.shutdown();
            LOGGER.info("[LC2H] Multi-chunk planner shut down");
        } catch (Throwable t) {
            LOGGER.warn("[LC2H] Error shutting down multi-chunk planner: {}", t.getMessage());
        }

        try {
            org.admany.lc2h.worldgen.async.planner.AsyncBuildingInfoPlanner.flushPendingBuildingBatches();
            LOGGER.info("[LC2H] Pending building info batches flushed");
        } catch (Throwable t) {
            LOGGER.warn("[LC2H] Error flushing pending building batches: {}", t.getMessage());
        }

        try {
            org.admany.lc2h.worldgen.async.planner.AsyncBuildingInfoPlanner.shutdown();
            LOGGER.info("[LC2H] Building info planner shut down");
        } catch (Throwable t) {
            LOGGER.warn("[LC2H] Error shutting down building info planner: {}", t.getMessage());
        }

        try {
            org.admany.lc2h.worldgen.async.planner.AsyncTerrainFeaturePlanner.flushPendingTerrainBatches();
            LOGGER.info("[LC2H] Pending terrain feature batches flushed");
        } catch (Throwable t) {
            LOGGER.warn("[LC2H] Error flushing pending terrain batches: {}", t.getMessage());
        }

        try {
            org.admany.lc2h.worldgen.async.planner.AsyncTerrainFeaturePlanner.shutdown();
            LOGGER.info("[LC2H] Terrain feature planner shut down");
        } catch (Throwable t) {
            LOGGER.warn("[LC2H] Error shutting down terrain feature planner: {}", t.getMessage());
        }

        try {
            org.admany.lc2h.worldgen.async.planner.AsyncTerrainCorrectionPlanner.flushPendingCorrectionBatches();
            LOGGER.info("[LC2H] Pending terrain correction batches flushed");
        } catch (Throwable t) {
            LOGGER.warn("[LC2H] Error flushing pending correction batches: {}", t.getMessage());
        }

        try {
            org.admany.lc2h.worldgen.async.planner.AsyncTerrainCorrectionPlanner.shutdown();
            LOGGER.info("[LC2H] Terrain correction planner shut down");
        } catch (Throwable t) {
            LOGGER.warn("[LC2H] Error shutting down terrain correction planner: {}", t.getMessage());
        }

        try {
            org.admany.lc2h.worldgen.async.planner.AsyncCityPlanner.flushPendingCityBatches();
            LOGGER.info("[LC2H] Pending city planning batches flushed");
        } catch (Throwable t) {
            LOGGER.warn("[LC2H] Error flushing pending city batches: {}", t.getMessage());
        }

        try {
            org.admany.lc2h.worldgen.async.planner.AsyncCityPlanner.shutdown();
            LOGGER.info("[LC2H] City planner shut down");
        } catch (Throwable t) {
            LOGGER.warn("[LC2H] Error shutting down city planner: {}", t.getMessage());
        }

        try {
            org.admany.lc2h.worldgen.async.planner.PlannerBatchQueue.shutdown();
            LOGGER.info("[LC2H] Planner batch queue drained");
        } catch (Throwable t) {
            LOGGER.warn("[LC2H] Error draining planner batch queue: {}", t.getMessage());
        }

        try {
            org.admany.lc2h.worldgen.async.warmup.AsyncChunkWarmup.shutdown();
            LOGGER.info("[LC2H] Chunk warmup system shut down");
        } catch (Throwable t) {
            LOGGER.warn("[LC2H] Error shutting down chunk warmup: {}", t.getMessage());
        }

        try {
            org.admany.lc2h.world.VineClusterCleaner.shutdown();
            LOGGER.info("[LC2H] Vine cluster cleaner shut down");
        } catch (Throwable t) {
            LOGGER.warn("[LC2H] Error shutting down vine cluster cleaner: {}", t.getMessage());
        }

        try {
            org.admany.lc2h.util.server.ServerRescheduler.setServer(null);
            LOGGER.info("[LC2H] Server rescheduler cleared");
        } catch (Throwable t) {
            LOGGER.warn("[LC2H] Error clearing server rescheduler: {}", t.getMessage());
        }

        try {
            org.admany.lc2h.worldgen.gpu.GPUMemoryManager.clearAllGPUCaches();
            LOGGER.info("[LC2H] GPU caches cleared");
        } catch (Throwable t) {
            LOGGER.warn("[LC2H] Error clearing GPU caches: {}", t.getMessage());
        }

        try {
            org.admany.lc2h.data.cache.FeatureCache.forceShutdown();
            LOGGER.info("[LC2H] Feature cache system force shut down");
        } catch (Throwable t) {
            LOGGER.warn("[LC2H] Error force shutting down feature cache: {}", t.getMessage());
        }
    }
}
