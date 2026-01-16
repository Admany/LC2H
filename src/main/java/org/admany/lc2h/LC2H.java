package org.admany.lc2h;

import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.event.TickEvent;
import org.admany.lc2h.compat.C2MECompat;
import org.admany.lc2h.world.cleanup.VineClusterCleaner;
import org.admany.lc2h.worldgen.async.warmup.AsyncChunkWarmup;

import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.concurrent.atomic.AtomicLong;
import org.admany.lc2h.dev.diagnostics.DiagnosticsReporter;
import org.admany.lc2h.dev.diagnostics.StallDetector;
import org.admany.lc2h.dev.diagnostics.ChunkGenTracker;
import org.admany.lc2h.config.ConfigManager;
import org.admany.lc2h.util.chunk.ChunkPostProcessor;
import org.admany.lc2h.util.server.ServerRescheduler;
import org.admany.lc2h.client.LC2HClient;
import org.admany.lc2h.config.sync.ConfigSyncNetwork;
import org.admany.lc2h.dev.debug.chunk.ChunkDebugNetwork;
import org.admany.lc2h.dev.debug.chunk.ChunkDebugManager;
import org.admany.lc2h.dev.debug.chunk.ChunkDebugExporter;
import org.admany.lc2h.dev.debug.frustum.FrustumDebugManager;
import org.admany.lc2h.dev.debug.frustum.FrustumDebugNetwork;
import org.admany.lc2h.util.log.ChatMessenger;
import org.admany.lc2h.worldgen.async.planner.AsyncMultiChunkPlanner;
import org.admany.lc2h.worldgen.async.planner.PlannerBatchQueue;
import org.admany.lc2h.worldgen.async.planner.PlannerTaskKind;
import org.admany.lc2h.worldgen.apply.MainThreadChunkApplier;
import org.admany.lc2h.tweaks.TweaksActorSystem;
import org.admany.lc2h.worldgen.gpu.GPUMemoryManager;
import org.admany.lc2h.dev.diagnostics.ViewCullingStats;
import org.admany.quantified.api.QuantifiedAPI;
import org.admany.quantified.core.common.cache.CacheManager;
import org.admany.quantified.core.common.async.task.ModPriorityManager;
import org.admany.quantified.core.common.util.TaskScheduler;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import net.minecraftforge.fml.common.Mod;
import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandSourceStack;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.ChunkPos;
import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.setup.Registration;
import mcjty.lostcities.worldgen.IDimensionInfo;
import mcjty.lostcities.config.LostCityProfile;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.server.level.ServerLevel;
import java.util.Locale;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.nio.file.Path;

@Mod(LC2H.MODID)
public class LC2H {
    public static final String MODID = "lc2h";
    public static final Logger LOGGER = LogManager.getLogger();

    public static final DeferredRegister<net.minecraft.sounds.SoundEvent> SOUND_EVENTS = DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, MODID);
    public static final net.minecraftforge.registries.RegistryObject<net.minecraft.sounds.SoundEvent> COUNTDOWN_SOUND = SOUND_EVENTS.register("countdown",
        () -> net.minecraft.sounds.SoundEvent.createVariableRangeEvent(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(MODID, "countdown")));
    public static final net.minecraftforge.registries.RegistryObject<net.minecraft.sounds.SoundEvent> BUTTON_CLICK_SOUND = SOUND_EVENTS.register("button_click",
        () -> net.minecraft.sounds.SoundEvent.createVariableRangeEvent(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(MODID, "button_click")));
    private static final java.util.concurrent.atomic.AtomicBoolean SHUTDOWN_FINALIZED = new java.util.concurrent.atomic.AtomicBoolean(false);
    private static final int WARMUP_PREFETCH_INTERVAL_TICKS =
        Math.max(1, Integer.getInteger("lc2h.warmup.prefetchIntervalTicks", 20));
    private static final AtomicLong LAST_WARMUP_PREFETCH_TICK = new AtomicLong(-1);
    private static final long ASYNC_START_DELAY_TICKS = Math.max(0L,
        Long.getLong("lc2h.async.startDelayTicks", 200L));
    private static final java.util.concurrent.atomic.AtomicLong ASYNC_START_TICK = new java.util.concurrent.atomic.AtomicLong(-1L);
    private static final java.util.concurrent.atomic.AtomicBoolean ASYNC_DELAYED = new java.util.concurrent.atomic.AtomicBoolean(false);

    @SuppressWarnings("removal")
    public LC2H() {
        MinecraftForge.EVENT_BUS.register(this);

        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::onCommonSetup);
        ConfigSyncNetwork.register();
        ChunkDebugNetwork.register();
        FrustumDebugNetwork.register();
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

        if (org.admany.lc2h.config.ConfigManager.ENABLE_DEBUG_LOGGING) {
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
        if (org.admany.lc2h.config.ConfigManager.ENABLE_DEBUG_LOGGING) {
            LOGGER.info("[LC2H] onCommonSetup called");
        } else {
            LOGGER.debug("[LC2H] onCommonSetup called");
        }
        registerWithQuantifiedApi();
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        SHUTDOWN_FINALIZED.set(false);
        initializeAsyncDelay(event.getServer());
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
            if (org.admany.lc2h.config.ConfigManager.ENABLE_DEBUG_LOGGING) {
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
            if (org.admany.lc2h.config.ConfigManager.ENABLE_DEBUG_LOGGING) {
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
            if (org.admany.lc2h.config.ConfigManager.ENABLE_DEBUG_LOGGING) {
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
                        ctx.getSource().sendSuccess(() -> net.minecraft.network.chat.Component.translatable("lc2h.command.diagnostics.dump_triggered"), false);
                        return 1;
                    })
                ).then(
                Commands.literal("gpu").executes(ctx -> {
                    String stats = org.admany.lc2h.worldgen.gpu.GPUMemoryManager.getComprehensiveMemoryStats();
                    ctx.getSource().sendSuccess(() -> net.minecraft.network.chat.Component.translatable("lc2h.command.gpu.stats", stats), false);
                    return 1;
                }).then(
                        Commands.literal("cleanup").executes(ctx -> {
                            org.admany.lc2h.worldgen.gpu.GPUMemoryManager.comprehensiveCleanup();
                            String stats = org.admany.lc2h.worldgen.gpu.GPUMemoryManager.getComprehensiveMemoryStats();
                            ctx.getSource().sendSuccess(() -> net.minecraft.network.chat.Component.translatable("lc2h.command.gpu.cleanup_done", stats), false);
                            return 1;
                        })
                    )
                ).then(
                    Commands.literal("rescanChunk").executes(ctx -> {
                        ServerPlayer player = ctx.getSource().getPlayerOrException();
                        ChunkPos pos = player.chunkPosition();
                        ChunkPostProcessor.forceRescanChunk(player.serverLevel(), pos);
                        ctx.getSource().sendSuccess(() -> net.minecraft.network.chat.Component.translatable("lc2h.command.rescan.queued", pos), false);
                        return 1;
                    })
                ).then(
                    Commands.literal("stats").executes(ctx -> reportStats(ctx.getSource()))
                ).then(
                    Commands.literal("chunkinfo").executes(ctx -> {
                        ServerPlayer player = ctx.getSource().getPlayerOrException();
                        ChunkPos pos = player.chunkPosition();
                        ChunkCoord coord = new ChunkCoord(player.level().dimension(), pos.x, pos.z);
                        net.minecraft.network.chat.Component report = ChunkGenTracker.buildReportComponent(coord);
                        ctx.getSource().sendSuccess(() -> report, false);
                        return 1;
                    }).then(
                        Commands.argument("chunkX", IntegerArgumentType.integer()).then(
                            Commands.argument("chunkZ", IntegerArgumentType.integer()).executes(ctx -> {
                                int chunkX = IntegerArgumentType.getInteger(ctx, "chunkX");
                                int chunkZ = IntegerArgumentType.getInteger(ctx, "chunkZ");
                                ChunkCoord coord = new ChunkCoord(ctx.getSource().getLevel().dimension(), chunkX, chunkZ);
                                net.minecraft.network.chat.Component report = ChunkGenTracker.buildReportComponent(coord);
                                ctx.getSource().sendSuccess(() -> report, false);
                                return 1;
                            })
                        )
                    )
                ).then(
                    Commands.literal("chunkdebug")
                        .executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            ChunkDebugManager.setEnabled(player, true);
                            return 1;
                        })
                        .then(Commands.literal("enable").executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            ChunkDebugManager.setEnabled(player, true);
                            return 1;
                        }))
                        .then(Commands.literal("disable").executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            ChunkDebugManager.setEnabled(player, false);
                            return 1;
                        }))
                        .then(Commands.literal("clear").executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            ChunkDebugManager.clearSelection(player);
                            return 1;
                        }))
                        .then(Commands.literal("export")
                            .executes(ctx -> exportChunkDebug(ctx.getSource().getPlayerOrException(), null))
                            .then(Commands.argument("label", StringArgumentType.greedyString())
                                .executes(ctx -> exportChunkDebug(ctx.getSource().getPlayerOrException(), StringArgumentType.getString(ctx, "label")))
                            )
                        )
                )
                .then(
                    Commands.literal("frustumdebug")
                        .executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            FrustumDebugManager.toggle(player);
                            return 1;
                        })
                        .then(Commands.literal("enable").executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            FrustumDebugManager.setEnabled(player, true);
                            return 1;
                        }))
                        .then(Commands.literal("disable").executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            FrustumDebugManager.setEnabled(player, false);
                            return 1;
                        }))
                )
            );
        } catch (Throwable t) {
            LOGGER.warn("[LC2H] Failed to register /lc2h commands: {}", t.getMessage());
        }
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        resetAsyncDelay();
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
            org.admany.lc2h.world.cleanup.VineClusterCleaner.shutdown();
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
            // Heavy cleanup is deferred to ServerStoppedEvent so it doesn't block world saving.
            SHUTDOWN_FINALIZED.set(false);
        } catch (Throwable ignored) {
        }
    }

    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        tryStartAsyncAfterDelay(player);
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        var server = event.getServer();
        if (server == null || server.getPlayerList() == null) {
            return;
        }
        if (server.getPlayerList().getPlayerCount() == 0) {
            return;
        }
        if (!isAsyncReady(server)) {
            return;
        }
        long tick = server.getTickCount();
        long last = LAST_WARMUP_PREFETCH_TICK.get();
        if (last >= 0 && (tick - last) < WARMUP_PREFETCH_INTERVAL_TICKS) {
            return;
        }
        if (!LAST_WARMUP_PREFETCH_TICK.compareAndSet(last, tick)) {
            return;
        }

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ServerLevel level = player.serverLevel();
            WorldGenLevel worldGen = level;
            IDimensionInfo provider = Registration.LOSTCITY_FEATURE.get().getDimensionInfo(worldGen);
            if (provider == null) {
                continue;
            }
            try {
                provider.setWorld(worldGen);
            } catch (Throwable ignored) {
            }
            ChunkPos pos = player.chunkPosition();
            ChunkCoord coord = new ChunkCoord(provider.getType(), pos.x, pos.z);
            AsyncChunkWarmup.startBackgroundPrefetch(provider, coord);
        }
        AsyncChunkWarmup.kickFlushMaybe();
    }

    @SubscribeEvent
    public void onServerStopped(ServerStoppedEvent event) {
        if (!SHUTDOWN_FINALIZED.compareAndSet(false, true)) {
            return;
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

    private static void initializeAsyncDelay(MinecraftServer server) {
        ASYNC_START_TICK.set(-1L);
        if (server == null) {
            ASYNC_DELAYED.set(false);
            return;
        }
        boolean delay = false;
        try {
            ServerLevel level = server.overworld();
            if (level != null) {
                IDimensionInfo provider = Registration.LOSTCITY_FEATURE.get().getDimensionInfo(level);
                if (provider != null) {
                    LostCityProfile profile = provider.getProfile();
                    if (profile != null) {
                        delay = profile.FORCE_SPAWN_IN_BUILDING
                            || (profile.FORCE_SPAWN_BUILDINGS != null && profile.FORCE_SPAWN_BUILDINGS.length > 0)
                            || (profile.FORCE_SPAWN_PARTS != null && profile.FORCE_SPAWN_PARTS.length > 0);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        ASYNC_DELAYED.set(delay);
    }

    private static void resetAsyncDelay() {
        ASYNC_DELAYED.set(false);
        ASYNC_START_TICK.set(-1L);
    }

    public static boolean isAsyncReady(MinecraftServer server) {
        if (server == null) {
            return true;
        }
        if (!ASYNC_DELAYED.get()) {
            return true;
        }
        long startTick = ASYNC_START_TICK.get();
        if (startTick < 0L) {
            return false;
        }
        return server.getTickCount() >= startTick;
    }

    private static void tryStartAsyncAfterDelay(ServerPlayer player) {
        if (player == null) {
            return;
        }
        if (ASYNC_START_TICK.get() >= 0L) {
            return;
        }
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        boolean delay = shouldDelayForSpawn(player);
        if (!ASYNC_DELAYED.get()) {
            ASYNC_DELAYED.set(delay);
        }
        long startTick = server.getTickCount();
        if (delay) {
            startTick += ASYNC_START_DELAY_TICKS;
        }
        ASYNC_START_TICK.compareAndSet(-1L, startTick);
    }

    private static boolean shouldDelayForSpawn(ServerPlayer player) {
        try {
            IDimensionInfo provider = Registration.LOSTCITY_FEATURE.get().getDimensionInfo(player.serverLevel());
            if (provider == null) {
                return false;
            }
            LostCityProfile profile = provider.getProfile();
            if (profile == null) {
                return false;
            }
            if (profile.FORCE_SPAWN_IN_BUILDING) {
                return true;
            }
            if (profile.FORCE_SPAWN_BUILDINGS != null && profile.FORCE_SPAWN_BUILDINGS.length > 0) {
                return true;
            }
            return profile.FORCE_SPAWN_PARTS != null && profile.FORCE_SPAWN_PARTS.length > 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static int reportStats(CommandSourceStack source) {
        var server = source.getServer();
        int players = 0;
        try {
            if (server.getPlayerList() != null) {
                players = server.getPlayerList().getPlayerCount();
            }
        } catch (Throwable ignored) {
        }
        long tick = server.getTickCount();
        double avgTick = server.getAverageTickTime();

        int warmupQueue = AsyncChunkWarmup.getRegionBufferSize();
        int warmupActive = AsyncChunkWarmup.getActiveBatchCount();
        long warmupHits = AsyncChunkWarmup.getCacheHits();
        long warmupMisses = AsyncChunkWarmup.getCacheMisses();
        long warmupTotal = warmupHits + warmupMisses;
        double warmupHitRate = warmupTotal > 0 ? (warmupHits * 100.0) / warmupTotal : 0.0;

        int planned = AsyncMultiChunkPlanner.getPlannedCount();
        int gpuCache = AsyncMultiChunkPlanner.getGpuDataCacheSize();

        PlannerBatchQueue.PlannerBatchStats planner = PlannerBatchQueue.snapshotStats();
        String plannerKinds = formatPlannerKinds(planner.pendingByKind());

        int applyQueue = MainThreadChunkApplier.getQueueSize();
        int inflight = TweaksActorSystem.getInFlightCount();
        int validated = TweaksActorSystem.getValidatedCount();
        int pendingScans = ChunkPostProcessor.getPendingScanCount();
        ViewCullingStats.Snapshot viewCulling = ViewCullingStats.snapshot();
        ChunkGenTracker.PrioritySnapshot prioritySnapshot = ChunkGenTracker.prioritySnapshot();

        int gpuEntries = GPUMemoryManager.getCachedEntryCount();
        long gpuBytes = GPUMemoryManager.getCachedBytes();
        long diskEntries = GPUMemoryManager.getDiskCacheEntryCount();
        long diskBytes = GPUMemoryManager.getDiskCacheBytes();
        long quantifiedBytes = GPUMemoryManager.getQuantifiedAPICacheSize();

        org.admany.lc2h.data.cache.FeatureCache.CacheStats cacheStats = org.admany.lc2h.data.cache.FeatureCache.snapshot();
        long localCacheEntries = cacheStats.localEntries();
        Long quantifiedEntries = cacheStats.quantifiedEntries();
        long cacheMemMB = org.admany.lc2h.data.cache.FeatureCache.getMemoryUsageMB();
        boolean cachePressure = org.admany.lc2h.data.cache.FeatureCache.isMemoryPressureHigh();

        TaskScheduler.SchedulingStats schedulerStats = null;
        try {
            schedulerStats = TaskScheduler.getStats();
        } catch (Throwable ignored) {
        }

        String header = net.minecraft.network.chat.Component.translatable("lc2h.command.stats.header",
            tick, String.format(Locale.ROOT, "%.2f", avgTick), players).getString();
        List<String> lines = new ArrayList<>();
        lines.add(header);
        lines.add(statLine(net.minecraft.network.chat.Component.translatable("lc2h.command.stats.label.planner").getString(),
            net.minecraft.network.chat.Component.translatable("lc2h.command.stats.body.planner",
                planner.batchCount(), planner.pendingTasks(), plannerKinds).getString()));
        lines.add(statLine(net.minecraft.network.chat.Component.translatable("lc2h.command.stats.label.multichunk").getString(),
            net.minecraft.network.chat.Component.translatable("lc2h.command.stats.body.multichunk",
                planned, gpuCache).getString()));
        lines.add(statLine(net.minecraft.network.chat.Component.translatable("lc2h.command.stats.label.warmup").getString(),
            net.minecraft.network.chat.Component.translatable("lc2h.command.stats.body.warmup",
                warmupQueue, warmupActive, String.format(Locale.ROOT, "%.1f", warmupHitRate), warmupHits, warmupTotal).getString()));
        lines.add(statLine(net.minecraft.network.chat.Component.translatable("lc2h.command.stats.label.main_thread").getString(),
            net.minecraft.network.chat.Component.translatable("lc2h.command.stats.body.main_thread",
                applyQueue, inflight, validated).getString()));
        lines.add(statLine(net.minecraft.network.chat.Component.translatable("lc2h.command.stats.label.post_process").getString(),
            net.minecraft.network.chat.Component.translatable("lc2h.command.stats.body.post_process", pendingScans).getString()));
        lines.add(statLine(net.minecraft.network.chat.Component.translatable("lc2h.command.stats.label.chunk_priority").getString(),
            net.minecraft.network.chat.Component.translatable("lc2h.command.stats.body.chunk_priority",
                prioritySnapshot.foreground(), prioritySnapshot.background()).getString()));
        lines.add(statLine(net.minecraft.network.chat.Component.translatable("lc2h.command.stats.label.view_culling").getString(),
            net.minecraft.network.chat.Component.translatable("lc2h.command.stats.body.view_culling",
                viewCulling.total(),
                viewCulling.plannerQueue(),
                viewCulling.plannerBatch(),
                viewCulling.multiChunkPending(),
                viewCulling.warmupQueue(),
                viewCulling.warmupBatch(),
                viewCulling.mainThreadApply()).getString()));
        lines.add(statLine(net.minecraft.network.chat.Component.translatable("lc2h.command.stats.label.gpu").getString(),
            net.minecraft.network.chat.Component.translatable("lc2h.command.stats.body.gpu",
                gpuEntries, formatBytes(gpuBytes), diskEntries, formatBytes(diskBytes), formatBytes(quantifiedBytes)).getString()));
        if (quantifiedEntries != null) {
            String pressureLabel = net.minecraft.network.chat.Component.translatable(cachePressure
                ? "lc2h.command.stats.pressure.high"
                : "lc2h.command.stats.pressure.normal").getString();
            lines.add(statLine(net.minecraft.network.chat.Component.translatable("lc2h.command.stats.label.feature_cache").getString(),
                net.minecraft.network.chat.Component.translatable("lc2h.command.stats.body.feature_cache_distributed",
                    localCacheEntries, quantifiedEntries, cacheMemMB, pressureLabel).getString()));
        } else {
            String pressureLabel = net.minecraft.network.chat.Component.translatable(cachePressure
                ? "lc2h.command.stats.pressure.high"
                : "lc2h.command.stats.pressure.normal").getString();
            lines.add(statLine(net.minecraft.network.chat.Component.translatable("lc2h.command.stats.label.feature_cache").getString(),
                net.minecraft.network.chat.Component.translatable("lc2h.command.stats.body.feature_cache_local",
                    localCacheEntries, cacheMemMB, pressureLabel).getString()));
        }
        if (schedulerStats != null) {
            lines.add(statLine(net.minecraft.network.chat.Component.translatable("lc2h.command.stats.label.quantified").getString(),
                net.minecraft.network.chat.Component.translatable("lc2h.command.stats.body.quantified",
                    schedulerStats.totalTasks(),
                    schedulerStats.gpuTasks(),
                    schedulerStats.cpuTasks(),
                    String.format(Locale.ROOT, "%.2f", schedulerStats.gpuUtilizationRatio())).getString()));
        }
        sendBoxedStats(source, net.minecraft.network.chat.Component.translatable("lc2h.command.stats.title").getString(), lines);

        StringBuilder log = new StringBuilder();
        log.append("[LC2H] Stats dump\n")
            .append(header).append('\n')
            .append(statLine("Planner",
                String.format(Locale.ROOT, "batches=%d pending=%d byKind=%s",
                    planner.batchCount(), planner.pendingTasks(), plannerKinds))).append('\n')
            .append(statLine("MultiChunk",
                String.format(Locale.ROOT, "planned=%d gpuCache=%d",
                    planned, gpuCache))).append('\n')
            .append(statLine("Warmup",
                String.format(Locale.ROOT, "regionQueue=%d activeBatches=%d hitRate=%.1f%% (%d/%d)",
                    warmupQueue, warmupActive, warmupHitRate, warmupHits, warmupTotal))).append('\n')
            .append(statLine("MainThread",
                String.format(Locale.ROOT, "queue=%d | Tweaks inflight=%d validated=%d",
                    applyQueue, inflight, validated))).append('\n')
            .append(statLine("PostProcess",
                String.format(Locale.ROOT, "pendingScans=%d", pendingScans))).append('\n')
            .append(statLine("ChunkPriority",
                String.format(Locale.ROOT, "foreground=%d background=%d",
                    prioritySnapshot.foreground(), prioritySnapshot.background()))).append('\n')
            .append(statLine("ViewCulling",
                String.format(Locale.ROOT, "total=%d plannerQ=%d plannerBatch=%d multiChunk=%d warmupQ=%d warmupBatch=%d apply=%d",
                    viewCulling.total(),
                    viewCulling.plannerQueue(),
                    viewCulling.plannerBatch(),
                    viewCulling.multiChunkPending(),
                    viewCulling.warmupQueue(),
                    viewCulling.warmupBatch(),
                    viewCulling.mainThreadApply()))).append('\n')
            .append(statLine("GPU",
                String.format(Locale.ROOT, "entries=%d mem=%s diskEntries=%d diskMem=%s qApiMem=%s",
                    gpuEntries, formatBytes(gpuBytes), diskEntries, formatBytes(diskBytes), formatBytes(quantifiedBytes)))).append('\n');
        if (quantifiedEntries != null) {
            log.append(statLine("FeatureCache",
                String.format(Locale.ROOT, "local=%d distributed=%d mem=%dMB pressure=%s",
                    localCacheEntries, quantifiedEntries, cacheMemMB, cachePressure ? "HIGH" : "NORMAL"))).append('\n');
        } else {
            log.append(statLine("FeatureCache",
                String.format(Locale.ROOT, "local=%d mem=%dMB pressure=%s",
                    localCacheEntries, cacheMemMB, cachePressure ? "HIGH" : "NORMAL"))).append('\n');
        }
        if (schedulerStats != null) {
            log.append(statLine("Quantified",
                String.format(Locale.ROOT, "total=%d gpu=%d cpu=%d gpuRatio=%.2f",
                    schedulerStats.totalTasks(), schedulerStats.gpuTasks(), schedulerStats.cpuTasks(), schedulerStats.gpuUtilizationRatio()))).append('\n');
        }
        LOGGER.info(log.toString());
        return 1;
    }

    private static String formatPlannerKinds(Map<PlannerTaskKind, Integer> byKind) {
        if (byKind == null || byKind.isEmpty()) {
            return "none";
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<PlannerTaskKind, Integer> entry : byKind.entrySet()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            PlannerTaskKind kind = entry.getKey();
            sb.append(kind != null ? kind.displayName() : "unknown")
                .append('=')
                .append(entry.getValue());
        }
        return sb.toString();
    }

    private static String formatBytes(long bytes) {
        if (bytes <= 0L) {
            return "0B";
        }
        double mb = bytes / (1024.0 * 1024.0);
        if (mb < 1.0) {
            return String.format(Locale.ROOT, "%.0fKB", bytes / 1024.0);
        }
        return String.format(Locale.ROOT, "%.1fMB", mb);
    }

    private static String statLine(String label, String body) {
        String safeLabel = label == null ? "" : label;
        return String.format(Locale.ROOT, "%-16s %s", safeLabel + ":", body);
    }

    private static void sendBoxedStats(CommandSourceStack source, String title, List<String> lines) {
        String safeTitle = title == null ? "Stats" : title;
        int width = safeTitle.length();
        for (String line : lines) {
            if (line != null && line.length() > width) {
                width = line.length();
            }
        }
        String border = "+-" + "-".repeat(width + 2) + "-+";
        ChatMessenger.info(source, border);
        ChatMessenger.info(source, "| " + padRight(safeTitle, width) + " |");
        ChatMessenger.info(source, border);
        for (String line : lines) {
            String safeLine = line == null ? "" : line;
            ChatMessenger.info(source, "| " + padRight(safeLine, width) + " |");
        }
        ChatMessenger.info(source, border);
    }

    private static String padRight(String value, int width) {
        if (value == null) {
            value = "";
        }
        if (value.length() >= width) {
            return value;
        }
        return value + " ".repeat(width - value.length());
    }

    private static int exportChunkDebug(ServerPlayer player, String label) {
        if (player == null) {
            return 0;
        }
        try {
            ChunkDebugManager.ChunkSelection selection = ChunkDebugManager.snapshot(player);
            Path outFile = ChunkDebugExporter.exportSelection(player, selection, label);
            ChatMessenger.success(player.createCommandSourceStack(),
                net.minecraft.network.chat.Component.translatable("lc2h.command.chunkdebug.export_written", outFile.toAbsolutePath()));
            return 1;
        } catch (Exception e) {
            ChatMessenger.error(player.createCommandSourceStack(),
                net.minecraft.network.chat.Component.translatable("lc2h.command.chunkdebug.export_failed", e.getMessage()));
            return 0;
        }
    }
}
