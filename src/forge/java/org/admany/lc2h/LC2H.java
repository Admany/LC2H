package org.admany.lc2h;

import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import org.admany.lc2h.compat.C2MECompat;
import org.admany.lc2h.world.cleanup.VineClusterCleaner;
import org.admany.lc2h.worldgen.async.warmup.AsyncChunkWarmup;
import org.admany.lc2h.worldgen.lostcities.LostCityProfileOverrideManager;
import org.admany.lc2h.worldgen.terrain.IntercityHighwayIndex;

import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.concurrent.atomic.AtomicLong;
import org.admany.lc2h.dev.diagnostics.DiagnosticsReporter;
import org.admany.lc2h.dev.diagnostics.StallDetector;
import org.admany.lc2h.dev.diagnostics.AsyncIssueMonitor;
import org.admany.lc2h.dev.diagnostics.ChunkGenTracker;
import org.admany.lc2h.dev.diagnostics.LifecycleTortureTracker;
import org.admany.lc2h.dev.diagnostics.Lc2hMonitorService;
import org.admany.lc2h.config.ConfigManager;
import org.admany.lc2h.util.chunk.ChunkPostProcessor;
import org.admany.lc2h.util.ResourceLocations;
import org.admany.lc2h.util.server.ServerRescheduler;
import org.admany.lc2h.client.LC2HClient;
import org.admany.lc2h.config.sync.ConfigSyncNetwork;
import org.admany.lc2h.dev.debug.DebugCommands;
import org.admany.lc2h.dev.debug.MultiChunkParityAutoRunner;
import org.admany.lc2h.dev.debug.PreCaptureTargetTraceRegistry;
import org.admany.lc2h.dev.debug.ShadowMutationAutoRunner;
import org.admany.lc2h.dev.debug.WorldParityAutoRunner;
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
import org.admany.lc2h.runtime.Lc2hRuntimeModes;
import org.admany.quantified.api.QuantifiedAPI;
import org.admany.quantified.api.compute.GpuBackendPreference;
import org.admany.quantified.core.common.cache.CacheManager;
import org.admany.quantified.core.common.async.task.ModPriorityManager;
import org.admany.quantified.core.common.util.TaskScheduler;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import net.minecraftforge.fml.common.Mod;
import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandSourceStack;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
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
        () -> net.minecraft.sounds.SoundEvent.createVariableRangeEvent(ResourceLocations.of(MODID, "countdown")));
    public static final net.minecraftforge.registries.RegistryObject<net.minecraft.sounds.SoundEvent> BUTTON_CLICK_SOUND = SOUND_EVENTS.register("button_click",
        () -> net.minecraft.sounds.SoundEvent.createVariableRangeEvent(ResourceLocations.of(MODID, "button_click")));
    private static final java.util.concurrent.atomic.AtomicBoolean SHUTDOWN_FINALIZED = new java.util.concurrent.atomic.AtomicBoolean(false);
    private static final int WARMUP_PREFETCH_INTERVAL_TICKS =
        Math.max(1, Integer.getInteger("lc2h.warmup.prefetchIntervalTicks", 20));
    private static final AtomicLong LAST_WARMUP_PREFETCH_TICK = new AtomicLong(-1);
    private static final long ASYNC_START_DELAY_TICKS = Math.max(0L,
        Long.getLong("lc2h.async.startDelayTicks", 200L));
    private static final java.util.concurrent.atomic.AtomicLong ASYNC_START_TICK = new java.util.concurrent.atomic.AtomicLong(-1L);
    private static final java.util.concurrent.atomic.AtomicBoolean ASYNC_DELAYED = new java.util.concurrent.atomic.AtomicBoolean(false);
    private static final java.time.Duration CACHE_MAINTENANCE_INTERVAL = java.time.Duration.ofMinutes(
        Math.max(2L, Long.getLong("lc2h.cache.maintenanceIntervalMinutes", 10L)));
    private static final java.time.Duration CACHE_MAINTENANCE_RETENTION = java.time.Duration.ofMinutes(
        Math.max(CACHE_MAINTENANCE_INTERVAL.toMinutes(), Long.getLong("lc2h.cache.maintenanceRetentionMinutes", 20L)));

    @SuppressWarnings("removal")
    public LC2H() {
        MinecraftForge.EVENT_BUS.register(this);

        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::onCommonSetup);
        ConfigSyncNetwork.register();
        ChunkDebugNetwork.register();
        FrustumDebugNetwork.register();
        try {
            CacheManager.startMaintenance(CACHE_MAINTENANCE_INTERVAL, CACHE_MAINTENANCE_RETENTION);
        } catch (Throwable ignored) {
        }

        SOUND_EVENTS.register(FMLJavaModLoadingContext.get().getModEventBus());

        C2MECompat.init();


        ConfigManager.initializeGlobals();

        registerWithQuantifiedApi();

        redirectQuantifiedJulLogging();

        if (Lc2hRuntimeModes.baselineMode()) {
            LOGGER.warn("[LC2H] Baseline mode active. LC2H runtime is loaded for A/B parity export but gameplay mixins are disabled.");
        }



        if (FMLEnvironment.dist == Dist.CLIENT) {
            LC2HClient.init();
        }

        if (org.admany.lc2h.config.ConfigManager.ENABLE_DEBUG_LOGGING) {
            LOGGER.info("[LC2H] Multithreading Engine Started");
        } else {
            LOGGER.debug("[LC2H] Multithreading Engine Started");
        }
    }

    private void registerWithQuantifiedApi() {
        try {
            QuantifiedAPI.setGpuBackendPreference(MODID, GpuBackendPreference.VULKAN_PREFERRED);
            LOGGER.info("[LC2H] Connected to Quantified API V2");
            LOGGER.debug("[LC2H] Set Quantified GPU backend preference to Vulkan-first");
            try {
                ModPriorityManager.setMaxTasksForMod(MODID, 1_000_000L);
                LOGGER.debug("[LC2H] Applied Quantified mod priority tuning");
            } catch (Throwable priorityError) {
                LOGGER.warn("[LC2H] Could not adjust Quantified mod priority: {}", priorityError.getMessage());
            }
        } catch (Throwable t) {
            LOGGER.debug("[LC2H] Quantified API not present or failed to initialize: {}", t.getMessage());
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
        // The mod constructor runs before third-party block registries are
        // complete. Refresh registry-backed floating-vegetation defaults now
        // so Immersive Weathering frost is written into the config when IW is
        // actually present.
        ConfigManager.refreshFloatingVegetationDefaults();
        if (org.admany.lc2h.config.ConfigManager.ENABLE_DEBUG_LOGGING) {
            LOGGER.info("[LC2H] onCommonSetup called");
        } else {
            LOGGER.debug("[LC2H] onCommonSetup called");
        }
        registerWithQuantifiedApi();
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        org.admany.lc2h.worldgen.scope.WorldGenScope.refreshServer(event.getServer());
        IntercityHighwayIndex.allowAsynchronousWarmups();
        LifecycleTortureTracker.onServerStarted(event.getServer());
        DiagnosticsReporter.logPerformanceSnapshot("server-started");
        try {
            if (requiresForcedSpawnAssets(event.getServer())) {
                org.admany.lc2h.util.spawn.SpawnAssetIndex.refresh(event.getServer().overworld());
            } else {
                LOGGER.debug("[LC2H] Skipping eager spawn asset index refresh; no forced spawn requirements detected");
            }
        } catch (Throwable t) {
            LOGGER.warn("[LC2H] Failed to refresh Lost Cities asset index on server start", t);
        }
        try {
            ShadowMutationAutoRunner.maybeRun(event.getServer());
        } catch (Throwable t) {
            LOGGER.warn("[LC2H] Failed to schedule shadow mutation autorun", t);
        }
        try {
            if (Lc2hRuntimeModes.worldParityAuto()) {
                WorldParityAutoRunner.maybeRun(event.getServer());
            } else {
                MultiChunkParityAutoRunner.maybeRun(event.getServer());
            }
        } catch (Throwable t) {
            LOGGER.warn("[LC2H] Failed to schedule parity autorun", t);
        }
    }

    @SubscribeEvent
    public void onServerAboutToStart(ServerAboutToStartEvent event) {
        SHUTDOWN_FINALIZED.set(false);
        long lifecycleId = org.admany.lc2h.worldgen.scope.WorldGenScope.beginServer(event.getServer());
        LOGGER.debug("[LC2H] Worldgen scope lifecycle started during about-to-start: {}", lifecycleId);
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        SHUTDOWN_FINALIZED.set(false);
        LifecycleTortureTracker.onServerStarting(event.getServer());
        LostCityProfileOverrideManager.clearAllOverrides();
        initializeAsyncDelay(event.getServer());
        registerWithQuantifiedApi();
        org.admany.lc2h.dev.diagnostics.CriticalMixinHookValidator.resetWatch();
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
            if (Lc2hRuntimeModes.worldParityAuto()) {
                PreCaptureTargetTraceRegistry.armForUpcomingRun();
                WorldParityAutoRunner.applyProfileOverrides(event.getServer());
            } else {
                MultiChunkParityAutoRunner.applyProfileOverrides();
            }
        } catch (Throwable t) {
            LOGGER.warn("[LC2H] Failed to apply parity profile overrides", t);
        }
        resetLostCitiesLifecycleCaches("server start");

        try {
            if (AsyncChunkWarmup.shouldInitializeGpuWarmupOnServerStart()) {
                AsyncChunkWarmup.initializeGpuWarmup();
                if (org.admany.lc2h.config.ConfigManager.ENABLE_DEBUG_LOGGING) {
                    LOGGER.info("[LC2H] GPU warmup initialized during server startup");
                } else {
                    LOGGER.debug("[LC2H] GPU warmup initialized during server startup");
                }
            } else {
                LOGGER.debug("[LC2H] Skipping GPU warmup initialization during server startup. It will initialize on first active use");
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
            AsyncIssueMonitor.start(event.getServer());
        } catch (Throwable t) {
            LOGGER.warn("[LC2H] Failed to start AsyncIssueMonitor: {}", t.getMessage());
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

    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        try {
            event.getDispatcher().register(buildLc2hCommand());
        } catch (Throwable t) {
            LOGGER.warn("[LC2H] Failed to register /lc2h commands", t);
        }
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        LifecycleTortureTracker.onServerStopping(event.getServer());
        LostCityProfileOverrideManager.clearAllOverrides();
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
            AsyncIssueMonitor.stop();
        } catch (Throwable t) {
            LOGGER.warn("[LC2H] Failed to stop AsyncIssueMonitor: {}", t.getMessage());
        }

        LOGGER.info("[LC2H] Fast shutdown started");

        try {
            org.admany.lc2h.worldgen.dag.LostCityDagScheduler.shutdown();
            LOGGER.info("[LC2H] DAG stage batches shut down");
        } catch (Throwable t) {
            LOGGER.warn("[LC2H] Error shutting down DAG stage batches: {}", t.getMessage());
        }

        try {
            org.admany.lc2h.worldgen.async.planner.AsyncMultiChunkPlanner.shutdown();
            LOGGER.info("[LC2H] Multi-chunk planner shut down");
        } catch (Throwable t) {
            LOGGER.warn("[LC2H] Error shutting down multi-chunk planner: {}", t.getMessage());
        }

        try {
            org.admany.lc2h.worldgen.async.planner.AsyncBuildingInfoPlanner.shutdown();
            LOGGER.info("[LC2H] Building info planner shut down");
        } catch (Throwable t) {
            LOGGER.warn("[LC2H] Error shutting down building info planner: {}", t.getMessage());
        }

        try {
            org.admany.lc2h.worldgen.async.planner.AsyncTerrainFeaturePlanner.shutdown();
            LOGGER.info("[LC2H] Terrain feature planner shut down");
        } catch (Throwable t) {
            LOGGER.warn("[LC2H] Error shutting down terrain feature planner: {}", t.getMessage());
        }

        try {
            org.admany.lc2h.worldgen.async.planner.AsyncTerrainCorrectionPlanner.shutdown();
            LOGGER.info("[LC2H] Terrain correction planner shut down");
        } catch (Throwable t) {
            LOGGER.warn("[LC2H] Error shutting down terrain correction planner: {}", t.getMessage());
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
            org.admany.lc2h.worldgen.apply.ShadowBlockMutationApplier.clearAll();
            LOGGER.info("[LC2H] Shadow mutation queue cleared");
        } catch (Throwable t) {
            LOGGER.warn("[LC2H] Error clearing shadow mutation queue: {}", t.getMessage());
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
        LifecycleTortureTracker.onPlayerJoin(player);
        AsyncChunkWarmup.initializeGpuWarmup();
        AsyncChunkWarmup.notifyPlayerJoin();
        tryStartAsyncAfterDelay(player);
    }

    @SubscribeEvent
    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            LifecycleTortureTracker.onPlayerLeave(player);
        }
        MinecraftServer server = event.getEntity() != null ? event.getEntity().getServer() : null;
        AsyncChunkWarmup.notifyPlayerLeave(server);
    }

    @SubscribeEvent
    public void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            LifecycleTortureTracker.onPlayerChangedDimension(player, event.getFrom(), player.level().dimension());
        }
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
        if (!AsyncChunkWarmup.canWarmup(server)) {
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
        LifecycleTortureTracker.onServerStopped(event.getServer());
        try {
            org.admany.lc2h.worldgen.gpu.CityCenterGpuCache.clearAll();
            org.admany.lc2h.worldgen.gpu.TerrainCorrectionGpuPipeline.clearRegions();
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

        resetLostCitiesLifecycleCaches("server stop");

        try {
            org.admany.lc2h.worldgen.scope.WorldGenScope.endServer();
            LOGGER.info("[LC2H] Worldgen scope lifecycle ended");
        } catch (Throwable t) {
            LOGGER.warn("[LC2H] Error ending worldgen scope lifecycle: {}", t.getMessage());
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
                        boolean forcedSpawn = requiresForcedSpawn(profile);
                        boolean allowForcedSpawnDelay = Boolean.getBoolean("lc2h.async.delayForcedSpawn");
                        delay = forcedSpawn && allowForcedSpawnDelay;
                        if (forcedSpawn && !allowForcedSpawnDelay) {
                            LOGGER.debug("[LC2H] Forced spawn requirements detected; async startup delay disabled for spawn search");
                        }
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
            return requiresForcedSpawn(profile) && Boolean.getBoolean("lc2h.async.delayForcedSpawn");
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean requiresForcedSpawnAssets(MinecraftServer server) {
        if (server == null) {
            return false;
        }
        try {
            ServerLevel level = server.overworld();
            if (level == null) {
                return false;
            }
            IDimensionInfo provider = Registration.LOSTCITY_FEATURE.get().getDimensionInfo(level);
            if (provider == null) {
                return false;
            }
            return requiresForcedSpawn(provider.getProfile());
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean requiresForcedSpawn(LostCityProfile profile) {
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
    }

    private static void resetLostCitiesLifecycleCaches(String phase) {
        try {
            mcjty.lostcities.worldgen.lost.BuildingInfo.cleanCache();
        } catch (Throwable t) {
            LOGGER.debug("[LC2H] Could not clear Lost Cities BuildingInfo cache at {}: {}", phase, t.getMessage());
        }
        try {
            mcjty.lostcities.worldgen.lost.City.cleanCache();
        } catch (Throwable t) {
            LOGGER.debug("[LC2H] Could not clear Lost Cities City cache at {}: {}", phase, t.getMessage());
        }
        try {
            mcjty.lostcities.worldgen.lost.MultiChunk.cleanCache();
        } catch (Throwable t) {
            LOGGER.debug("[LC2H] Could not clear Lost Cities MultiChunk cache at {}: {}", phase, t.getMessage());
        }
        try {
            mcjty.lostcities.worldgen.lost.Railway.cleanCache();
        } catch (Throwable t) {
            LOGGER.debug("[LC2H] Could not clear Lost Cities Railway cache at {}: {}", phase, t.getMessage());
        }
        try {
            org.admany.lc2h.data.cache.BiomeInfoRuntimeCache.clear();
        } catch (Throwable t) {
            LOGGER.debug("[LC2H] Could not clear LC2H biome cache at {}: {}", phase, t.getMessage());
        }
        try {
            org.admany.lc2h.worldgen.lostcities.MultiChunkPlanningCache.clear();
        } catch (Throwable t) {
            LOGGER.debug("[LC2H] Could not clear LC2H multichunk planning cache at {}: {}", phase, t.getMessage());
        }
        try {
            org.admany.lc2h.worldgen.lostcities.MultiChunkBoundaryRegistry.clearAll();
        } catch (Throwable t) {
            LOGGER.debug("[LC2H] Could not clear LC2H multichunk boundary registry at {}: {}", phase, t.getMessage());
        }
        try {
            org.admany.lc2h.worldgen.lostcities.FastMultiChunkPlanner.resetAuditDisabled();
        } catch (Throwable t) {
            LOGGER.debug("[LC2H] Could not reset fast multichunk audit exclusions at {}: {}", phase, t.getMessage());
        }
        try {
            org.admany.lc2h.worldgen.lostcities.ChunkRoleProbe.clear();
        } catch (Throwable t) {
            LOGGER.debug("[LC2H] Could not clear chunk role probe at {}: {}", phase, t.getMessage());
        }
        try {
            org.admany.lc2h.worldgen.terrain.CityShiftField.clear();
        } catch (Throwable t) {
            LOGGER.debug("[LC2H] Could not clear city shift field at {}: {}", phase, t.getMessage());
        }
        try {
            org.admany.lc2h.worldgen.terrain.NaturalHeightSampler.clear();
        } catch (Throwable t) {
            LOGGER.debug("[LC2H] Could not clear natural height sampler at {}: {}", phase, t.getMessage());
        }
        try {
            org.admany.lc2h.worldgen.terrain.MountainCityBlendDiagnostics.clearLifecycleState();
        } catch (Throwable t) {
            LOGGER.debug("[LC2H] Could not clear terrain blend diagnostics at {}: {}", phase, t.getMessage());
        }
    }

    private static LiteralArgumentBuilder<CommandSourceStack> buildLc2hCommand() {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("lc2h")
            .executes(ctx -> showCommandHelp(ctx.getSource()))
            .then(Commands.literal("help").executes(ctx -> showCommandHelp(ctx.getSource())))
            .then(Commands.literal("status").executes(ctx -> reportStats(ctx.getSource())))
            .then(Commands.literal("gpu")
                .executes(ctx -> showGpuStatus(ctx.getSource()))
                .then(Commands.literal("status").executes(ctx -> showGpuStatus(ctx.getSource())))
                .then(Commands.literal("cleanup")
                    .requires(source -> source.hasPermission(2))
                    .executes(ctx -> {
                        GPUMemoryManager.comprehensiveCleanup();
                        ChatMessenger.success(ctx.getSource(), "GPU cache cleanup completed");
                        return showGpuStatus(ctx.getSource());
                    })))
            .then(Commands.literal("cache")
                .executes(ctx -> showCacheStatus(ctx.getSource()))
                .then(Commands.literal("status").executes(ctx -> showCacheStatus(ctx.getSource())))
                .then(Commands.literal("clear")
                    .requires(source -> source.hasPermission(2))
                    .then(Commands.literal("memory").executes(ctx -> clearFeatureCache(ctx.getSource(), false)))
                    .then(Commands.literal("disk").executes(ctx -> clearFeatureCache(ctx.getSource(), true))))
                .then(Commands.literal("cleanup")
                    .requires(source -> source.hasPermission(2))
                    .executes(ctx -> {
                        org.admany.lc2h.data.cache.FeatureCache.triggerMemoryPressureCleanup();
                        ChatMessenger.success(ctx.getSource(), "Cache pressure cleanup completed");
                        return showCacheStatus(ctx.getSource());
                    })))
            .then(Commands.literal("cleanup")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("chunk")
                    .executes(ctx -> rescanChunks(ctx.getSource(), 0))
                    .then(Commands.argument("radius", IntegerArgumentType.integer(0, 8))
                        .executes(ctx -> rescanChunks(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "radius"))))))
            .then(Commands.literal("terrain")
                .then(Commands.literal("explain").executes(ctx -> explainTerrain(ctx.getSource()))))
            .then(Commands.literal("diagnostics")
                .executes(ctx -> showDiagnosticsHelp(ctx.getSource()))
                .then(Commands.literal("dump")
                    .requires(source -> source.hasPermission(2))
                    .executes(ctx -> {
                        StallDetector.triggerDump(ctx.getSource().getServer());
                        ChatMessenger.success(ctx.getSource(), net.minecraft.network.chat.Component.translatable("lc2h.command.diagnostics.dump_triggered"));
                        return 1;
                    }))
                .then(Commands.literal("monitor")
                    .executes(ctx -> sendMonitorStatus(ctx.getSource()))
                    .then(Commands.literal("start")
                        .requires(source -> source.hasPermission(2))
                        .executes(ctx -> startMonitor(ctx.getSource(), 60))
                        .then(Commands.argument("seconds", IntegerArgumentType.integer(10, 300))
                            .executes(ctx -> startMonitor(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "seconds")))))
                    .then(Commands.literal("status").executes(ctx -> sendMonitorStatus(ctx.getSource())))
                    .then(Commands.literal("stop")
                        .requires(source -> source.hasPermission(2))
                        .executes(ctx -> {
                            ChatMessenger.info(ctx.getSource(), Lc2hMonitorService.stop(ctx.getSource().getServer()));
                            return 1;
                        })))
                .then(Commands.literal("chunk")
                    .executes(ctx -> showChunkInfo(ctx.getSource(), null, null))
                    .then(Commands.argument("chunkX", IntegerArgumentType.integer())
                        .then(Commands.argument("chunkZ", IntegerArgumentType.integer())
                            .executes(ctx -> showChunkInfo(ctx.getSource(),
                                IntegerArgumentType.getInteger(ctx, "chunkX"),
                                IntegerArgumentType.getInteger(ctx, "chunkZ"))))))
                .then(Commands.literal("chunkdebug")
                    .requires(source -> source.hasPermission(2))
                    .executes(ctx -> setChunkDebug(ctx.getSource(), true))
                    .then(Commands.literal("enable").executes(ctx -> setChunkDebug(ctx.getSource(), true)))
                    .then(Commands.literal("disable").executes(ctx -> setChunkDebug(ctx.getSource(), false)))
                    .then(Commands.literal("clear").executes(ctx -> {
                        ChunkDebugManager.clearSelection(ctx.getSource().getPlayerOrException());
                        return 1;
                    }))
                    .then(Commands.literal("explain")
                        .executes(ctx -> explainChunkDebug(ctx.getSource())))
                    .then(Commands.literal("export")
                        .executes(ctx -> exportChunkDebug(ctx.getSource().getPlayerOrException(), null))
                        .then(Commands.argument("label", StringArgumentType.greedyString())
                            .executes(ctx -> exportChunkDebug(ctx.getSource().getPlayerOrException(), StringArgumentType.getString(ctx, "label"))))))
                .then(Commands.literal("frustum")
                    .requires(source -> source.hasPermission(2))
                    .executes(ctx -> {
                        FrustumDebugManager.toggle(ctx.getSource().getPlayerOrException());
                        return 1;
                    })
                    .then(Commands.literal("enable").executes(ctx -> setFrustumDebug(ctx.getSource(), true)))
                    .then(Commands.literal("disable").executes(ctx -> setFrustumDebug(ctx.getSource(), false)))));

        LiteralArgumentBuilder<CommandSourceStack> developer = Commands.literal("dev")
            .requires(source -> source.hasPermission(4))
            .executes(ctx -> {
                ChatMessenger.info(ctx.getSource(), "Developer tools are grouped under /lc2h dev");
                ChatMessenger.commandLine(ctx.getSource(), "/lc2h dev diagnostics", "Full internal diagnostics");
                ChatMessenger.commandLine(ctx.getSource(), "/lc2h dev hooks", "Critical mixin hook state");
                ChatMessenger.commandLine(ctx.getSource(), "/lc2h dev kernelbench", "Kernel benchmark tools");
                return 1;
            });
        DebugCommands.appendTo(developer);
        root.then(developer);
        return root;
    }

    private static int showCommandHelp(CommandSourceStack source) {
        ChatMessenger.info(source, "LC2H V4 commands");
        ChatMessenger.commandLine(source, "/lc2h status", "Runtime, queue and cache status");
        ChatMessenger.commandLine(source, "/lc2h gpu", "GPU backend and workload status");
        ChatMessenger.commandLine(source, "/lc2h cache", "Feature cache status and maintenance");
        ChatMessenger.commandLine(source, "/lc2h cleanup chunk [radius]", "Rescan loaded chunks for worldgen artifacts");
        ChatMessenger.commandLine(source, "/lc2h terrain explain", "Explain the terrain plan at your position");
        ChatMessenger.commandLine(source, "/lc2h diagnostics", "Runtime diagnostics and capture tools");
        if (source.hasPermission(4)) {
            ChatMessenger.commandLine(source, "/lc2h dev", "Low level developer and parity tools");
        }
        return 1;
    }

    private static int showDiagnosticsHelp(CommandSourceStack source) {
        ChatMessenger.info(source, "Diagnostics are idle until you explicitly start or capture them");
        ChatMessenger.commandLine(source, "/lc2h diagnostics chunk", "Show generation state for your chunk");
        ChatMessenger.commandLine(source, "/lc2h diagnostics chunkdebug", "Enable the stick-based chunk selection overlay");
        ChatMessenger.commandLine(source, "/lc2h diagnostics chunkdebug explain", "Explain the selected target chunk in chat");
        ChatMessenger.commandLine(source, "/lc2h diagnostics chunkdebug export [label]", "Export target role, field, gate and height evidence as JSON");
        ChatMessenger.commandLine(source, "/lc2h diagnostics monitor status", "Show monitor state");
        ChatMessenger.commandLine(source, "/lc2h diagnostics monitor start 60", "Capture a bounded 60 second monitor report");
        ChatMessenger.commandLine(source, "/lc2h diagnostics dump", "Write an immediate stall dump");
        return 1;
    }

    private static int showGpuStatus(CommandSourceStack source) {
        String stats = GPUMemoryManager.getComprehensiveMemoryStats()
            + " | " + AsyncChunkWarmup.describeGpuProcessingStats();
        ChatMessenger.info(source, net.minecraft.network.chat.Component.translatable("lc2h.command.gpu.stats", stats));
        return 1;
    }

    private static int showCacheStatus(CommandSourceStack source) {
        org.admany.lc2h.data.cache.FeatureCache.CacheStats stats = org.admany.lc2h.data.cache.FeatureCache.snapshot();
        Long distributed = stats.quantifiedEntries();
        String entries = distributed == null
            ? "local=" + stats.localEntries()
            : "local=" + stats.localEntries() + " quantified=" + distributed;
        ChatMessenger.info(source, "Feature cache: " + entries
            + " memory=" + org.admany.lc2h.data.cache.FeatureCache.getMemoryUsageMB() + " MiB"
            + " pressure=" + (org.admany.lc2h.data.cache.FeatureCache.isMemoryPressureHigh() ? "high" : "normal"));
        return 1;
    }

    private static int clearFeatureCache(CommandSourceStack source, boolean includeDisk) {
        org.admany.lc2h.data.cache.FeatureCache.CacheStats cleared = org.admany.lc2h.data.cache.FeatureCache.clear(includeDisk);
        ChatMessenger.success(source, "Cleared " + cleared.localEntries() + " local cache entries"
            + (includeDisk ? " and the disk cache" : ""));
        return 1;
    }

    private static int rescanChunks(CommandSourceStack source, int radius) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        ChunkPos pos = player.chunkPosition();
        int queued = ChunkPostProcessor.forceRescanArea(player.serverLevel(), pos, radius);
        ChatMessenger.success(source, "Queued artifact cleanup for " + queued
            + " loaded chunk" + (queued == 1 ? "" : "s") + " around " + pos.x + ", " + pos.z);
        return Math.max(1, queued);
    }

    private static int explainTerrain(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        for (String line : org.admany.lc2h.worldgen.terrain.CityBlendDebugger.explain(player.serverLevel(), player.blockPosition())) {
            ChatMessenger.info(source, line);
        }
        return 1;
    }

    private static int sendMonitorStatus(CommandSourceStack source) {
        ChatMessenger.info(source, Lc2hMonitorService.status(source.getServer()));
        return 1;
    }

    private static int startMonitor(CommandSourceStack source, int seconds) {
        ChatMessenger.info(source, Lc2hMonitorService.start(source.getServer(), source.getTextName(), seconds));
        return 1;
    }

    private static int showChunkInfo(CommandSourceStack source, Integer chunkX, Integer chunkZ)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ChunkCoord coord;
        if (chunkX == null || chunkZ == null) {
            ServerPlayer player = source.getPlayerOrException();
            ChunkPos pos = player.chunkPosition();
            coord = new ChunkCoord(player.level().dimension(), pos.x, pos.z);
        } else {
            coord = new ChunkCoord(source.getLevel().dimension(), chunkX, chunkZ);
        }
        ChatMessenger.info(source, ChunkGenTracker.buildReportComponent(coord));
        return 1;
    }

    private static int setChunkDebug(CommandSourceStack source, boolean enabled)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ChunkDebugManager.setEnabled(source.getPlayerOrException(), enabled);
        return 1;
    }

    private static int explainChunkDebug(CommandSourceStack source)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        ChunkDebugManager.ChunkSelection selection = ChunkDebugManager.snapshot(player);
        for (String line : ChunkDebugExporter.explainSelection(player, selection)) {
            ChatMessenger.info(source, line);
        }
        return 1;
    }

    private static int setFrustumDebug(CommandSourceStack source, boolean enabled)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        FrustumDebugManager.setEnabled(source.getPlayerOrException(), enabled);
        return 1;
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
        lines.add(statLine("Planner pressure",
            String.format(Locale.ROOT, "dropped=%d deduped=%d",
                PlannerBatchQueue.getPressureDropCount(),
                PlannerBatchQueue.getDuplicateDropCount())));
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
        lines.add(statLine("GPU compute", AsyncChunkWarmup.describeGpuProcessingStats()));
        lines.add(statLine("GPU cache",
            String.format(Locale.ROOT, "promotions=%d", GPUMemoryManager.getDiskPromotionCount())));
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
            .append(statLine("Planner pressure",
                String.format(Locale.ROOT, "dropped=%d deduped=%d",
                    PlannerBatchQueue.getPressureDropCount(),
                    PlannerBatchQueue.getDuplicateDropCount()))).append('\n')
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
        log.append(statLine("GPU compute", AsyncChunkWarmup.describeGpuProcessingStats())).append('\n');
        log.append(statLine("GPU cache",
            String.format(Locale.ROOT, "promotions=%d", GPUMemoryManager.getDiskPromotionCount()))).append('\n');
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
        ChatMessenger.success(source, title == null ? "LC2H status" : title);
        for (String line : lines) {
            if (line != null && !line.isBlank()) {
                ChatMessenger.info(source, line.stripTrailing());
            }
        }
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
