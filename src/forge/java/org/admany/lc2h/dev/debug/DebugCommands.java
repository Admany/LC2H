package org.admany.lc2h.dev.debug;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import mcjty.lostcities.config.LostCityProfile;
import mcjty.lostcities.config.ProfileSetup;
import mcjty.lostcities.setup.Config;
import mcjty.lostcities.setup.Registration;
import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.IDimensionInfo;
import mcjty.lostcities.worldgen.lost.BuildingInfo;
import mcjty.lostcities.worldgen.lost.City;
import mcjty.lostcities.worldgen.lost.MultiChunk;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraftforge.fml.loading.FMLPaths;
import org.admany.lc2h.LC2H;
import org.admany.lc2h.concurrency.async.AsyncManager;
import org.admany.lc2h.concurrency.async.Priority;
import org.admany.lc2h.data.cache.FeatureCache;
import org.admany.lc2h.data.cache.LostCitiesCacheBridge;
import org.admany.lc2h.dev.benchmark.LostCityKernelBenchmark;
import org.admany.lc2h.dev.diagnostics.BuildingInfoDiagnostics;
import org.admany.lc2h.dev.diagnostics.CriticalMixinHookValidator;
import org.admany.lc2h.dev.diagnostics.LifecycleTortureTracker;
import org.admany.lc2h.dev.diagnostics.Lc2hTimingRegistry;
import org.admany.lc2h.mixin.accessor.lostcities.MultiChunkAccessor;
import org.admany.lc2h.util.log.ChatMessenger;
import org.admany.lc2h.worldgen.async.planner.AsyncMultiChunkPlanner;
import org.admany.lc2h.worldgen.async.planner.AsyncBuildingInfoPlanner;
import org.admany.lc2h.worldgen.async.planner.PlannerBatchQueue;
import org.admany.lc2h.worldgen.async.snapshot.MultiChunkSnapshot;
import org.admany.lc2h.worldgen.dag.LostCityDagScheduler;
import org.admany.lc2h.worldgen.apply.ShadowMutationTraceRegistry;
import org.admany.lc2h.worldgen.lostcities.ChunkRoleProbe;
import org.admany.lc2h.worldgen.lostcities.DeferredTreeEventHandler;
import org.admany.lc2h.worldgen.lostcities.FastMultiChunkPlanner;
import org.admany.lc2h.worldgen.lostcities.LostCityProfileOverrideManager;
import org.admany.lc2h.worldgen.lostcities.MultiChunkPlanningCache;
import org.admany.lc2h.worldgen.apply.ShadowBlockMutationApplier;
import org.admany.lc2h.worldgen.lostcities.TreeCompatTracker;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class DebugCommands {
    private static final SuggestionProvider<CommandSourceStack> PROFILE_SUGGESTIONS = (context, builder) -> {
        ensureProfilesInitialized();
        return SharedSuggestionProvider.suggest(LostCityProfileOverrideManager.discoverProfileNames(profileSearchDirs()), builder);
    };

    private static int blendMap(CommandContext<CommandSourceStack> context, int radiusChunks) {
        net.minecraft.server.level.ServerPlayer player = context.getSource().getPlayer();
        if (player == null) {
            emitDiagnosticLine(context.getSource(), "blend map: must be run by a player");
            return 0;
        }
        for (String line : org.admany.lc2h.worldgen.terrain.ShiftFieldDebug.renderMap(
            player.serverLevel(), player.blockPosition(), radiusChunks)) {
            emitDiagnosticLine(context.getSource(), line);
        }
        return 1;
    }

    private static int blendSlope(CommandContext<CommandSourceStack> context, int radiusChunks) {
        net.minecraft.server.level.ServerPlayer player = context.getSource().getPlayer();
        if (player == null) {
            emitDiagnosticLine(context.getSource(), "blend slope: must be run by a player");
            return 0;
        }
        for (String line : org.admany.lc2h.worldgen.terrain.ShiftFieldDebug.slopeReport(
            player.serverLevel(), player.blockPosition(), radiusChunks)) {
            emitDiagnosticLine(context.getSource(), line);
        }
        return 1;
    }

    private static int applyBlendSettings(CommandContext<CommandSourceStack> context,
                                          org.admany.lc2h.worldgen.terrain.CityShiftField.ShiftSettings settings) {
        ChatMessenger.success(context.getSource(), "Shift field: " + settings.describe());
        emitDiagnosticLine(context.getSource(),
            "Caches were dropped. Already-generated chunks keep their old shape - "
                + "regenerate or fly to fresh terrain to see this setting.");
        return 1;
    }

    public static void appendTo(LiteralArgumentBuilder<CommandSourceStack> root) {
        root.then(Commands.literal("cache")
                .requires(source -> source.hasPermission(2))
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
                .requires(source -> source.hasPermission(2))
                .executes(context -> {
                    FeatureCache.triggerMemoryPressureCleanup();
                    ChatMessenger.success(context.getSource(), Component.translatable("lc2h.dev.cache.cleanup_triggered"));
                    return 1;
                }))
            .then(Commands.literal("diagnostics")
                .requires(source -> source.hasPermission(2))
                .executes(context -> {
                    emitDiagnosticLine(context.getSource(), "DAG: " + LostCityDagScheduler.diagnostics());
                    emitDiagnosticLine(context.getSource(), "DistantHorizons: " + org.admany.lc2h.compat.DHCompat.diagnostics());
                    emitDiagnosticLine(context.getSource(), "MultiChunk: " + AsyncMultiChunkPlanner.telemetrySummary());
                    emitDiagnosticLine(context.getSource(), "FastMultiChunkPlanner: " + FastMultiChunkPlanner.diagnostics());
                    emitDiagnosticLine(context.getSource(), "ExactCityCenterGPU: " + org.admany.lc2h.worldgen.gpu.CityCenterGpuCache.diagnostics());
                    emitDiagnosticLine(context.getSource(), "TerrainCorrectionGPU: " + org.admany.lc2h.worldgen.gpu.TerrainCorrectionGpuPipeline.diagnostics());
                    emitDiagnosticLine(context.getSource(), "TerrainOwner: Minecraft NoiseChunk native-density coordinate transform (single shaper)");
                    emitDiagnosticLine(context.getSource(), "MountainCityBlend: " + org.admany.lc2h.worldgen.MountainCityBlendDiagnostics.diagnostics());
                    emitDiagnosticLine(context.getSource(), "CityShiftField: " + org.admany.lc2h.worldgen.terrain.CityShiftField.diagnostics());
                    emitDiagnosticLine(context.getSource(), "NaturalHeight: " + org.admany.lc2h.worldgen.terrain.NaturalHeightSampler.diagnostics());
                    emitDiagnosticLine(context.getSource(), "MountainCityReservation: " + org.admany.lc2h.worldgen.MountainCityReservationPlanner.diagnostics());
                    emitDiagnosticLine(context.getSource(), "CityTerrainPlanBridge: " + org.admany.lc2h.worldgen.CityTerrainPlanBridge.diagnostics());
                    emitDiagnosticLine(context.getSource(), "MultiChunkPlanCache: " + MultiChunkPlanningCache.diagnostics());
                    emitDiagnosticLine(context.getSource(), "BiomeInfoRuntimeCache: " + org.admany.lc2h.data.cache.BiomeInfoRuntimeCache.diagnostics());
                    emitDiagnosticLine(context.getSource(), "BuildingInfoPlanner: " + AsyncBuildingInfoPlanner.telemetrySummary());
                    for (String line : MultiChunkParityHarness.summaryLines()) {
                        emitDiagnosticLine(context.getSource(), line);
                    }
                    for (String line : WorldParityLegacyArtifacts.summaryLines()) {
                        emitDiagnosticLine(context.getSource(), line);
                    }
                    for (String line : LifecycleTortureTracker.summaryLines()) {
                        emitDiagnosticLine(context.getSource(), line);
                    }
                    emitDiagnosticLine(context.getSource(), "CriticalHooks note: verifiedUnseen=target resolved but never observed, blocked=upstream prerequisite observed first, unresolved=target could not be proven, failed=target lookup broke in this runtime");
                    for (String line : CriticalMixinHookValidator.summaryLines()) {
                        emitDiagnosticLine(context.getSource(), line);
                    }
                    for (String line : BuildingInfoDiagnostics.summaryLines()) {
                        emitDiagnosticLine(context.getSource(), line);
                    }
                    for (String line : topTimingLines(8)) {
                        emitDiagnosticLine(context.getSource(), line);
                    }
                    emitDiagnosticLine(context.getSource(), "Trees: " + DeferredTreeEventHandler.capturedTreeDiagnostics());
                    emitDiagnosticLine(context.getSource(), "TreePolicy: " + org.admany.lc2h.worldgen.lostcities.TreeCapturePolicy.diagnostics());
                    emitDiagnosticLine(context.getSource(), "VineCleaner: " + org.admany.lc2h.world.cleanup.VineClusterCleaner.diagnostics());
                    emitDiagnosticLine(context.getSource(), "TerrainClearance: " + org.admany.lc2h.worldgen.lostcities.CityTerrainClearance.diagnostics());
                    for (String line : TreeCompatTracker.summaryLines()) {
                        emitDiagnosticLine(context.getSource(), line);
                    }
                    emitDiagnosticLine(context.getSource(), "ShadowApply: " + ShadowBlockMutationApplier.diagnostics());
                    for (String line : ShadowMutationTraceRegistry.summaryLines()) {
                        emitDiagnosticLine(context.getSource(), line);
                    }
                    for (String line : PreCaptureTargetTraceRegistry.summaryLines()) {
                        emitDiagnosticLine(context.getSource(), line);
                    }
                    emitDiagnosticLine(context.getSource(), "LostCitiesDiskCache: " + LostCitiesCacheBridge.diagnostics());
                    emitDiagnosticLine(context.getSource(), "LostCitiesGenerationLock: " + org.admany.lc2h.worldgen.lostcities.LostCitiesGenerationLocks.diagnostics());
                    return 1;
                }))
            .then(Commands.literal("whyflat")
                .requires(source -> source.hasPermission(2))
                .executes(context -> {
                    net.minecraft.server.level.ServerPlayer player = context.getSource().getPlayer();
                    if (player == null) {
                        emitDiagnosticLine(context.getSource(), "whyflat: must be run by a player");
                        return 0;
                    }
                    for (String line : org.admany.lc2h.worldgen.CityBlendDebugger.explain(
                        player.serverLevel(), player.blockPosition())) {
                        emitDiagnosticLine(context.getSource(), line);
                    }
                    return 1;
                }))
            .then(Commands.literal("blend")
                .requires(source -> source.hasPermission(2))
                .executes(context -> {
                    emitDiagnosticLine(context.getSource(), "shift field: "
                        + org.admany.lc2h.worldgen.terrain.CityShiftField.ShiftSettings.current().describe());
                    emitDiagnosticLine(context.getSource(),
                        org.admany.lc2h.worldgen.terrain.CityShiftField.diagnostics());
                    emitDiagnosticLine(context.getSource(),
                        org.admany.lc2h.worldgen.terrain.NaturalHeightSampler.diagnostics());
                    return 1;
                })
                .then(Commands.literal("map")
                    .executes(ctx -> blendMap(ctx, 24))
                    .then(Commands.argument("radiusChunks", IntegerArgumentType.integer(4, 96))
                        .executes(ctx -> blendMap(ctx, IntegerArgumentType.getInteger(ctx, "radiusChunks")))))
                .then(Commands.literal("slope")
                    .executes(ctx -> blendSlope(ctx, 8))
                    .then(Commands.argument("radiusChunks", IntegerArgumentType.integer(1, 32))
                        .executes(ctx -> blendSlope(ctx, IntegerArgumentType.getInteger(ctx, "radiusChunks")))))
                .then(Commands.literal("slopes")
                    .then(Commands.argument("flat", DoubleArgumentType.doubleArg(0.10D, 1.0D))
                        .then(Commands.argument("steep", DoubleArgumentType.doubleArg(0.15D, 2.0D))
                            .executes(ctx -> applyBlendSettings(ctx,
                                org.admany.lc2h.worldgen.terrain.CityShiftField.ShiftSettings.withSlopes(
                                    DoubleArgumentType.getDouble(ctx, "flat"),
                                    DoubleArgumentType.getDouble(ctx, "steep")))))))
                .then(Commands.literal("relief")
                    .then(Commands.argument("strength", DoubleArgumentType.doubleArg(0.0D, 1.0D))
                        .executes(ctx -> applyBlendSettings(ctx,
                            org.admany.lc2h.worldgen.terrain.CityShiftField.ShiftSettings.withReliefStrength(
                                DoubleArgumentType.getDouble(ctx, "strength"))))))
                .then(Commands.literal("maxshift")
                    .then(Commands.argument("blocks", IntegerArgumentType.integer(16, 256))
                        .executes(ctx -> applyBlendSettings(ctx,
                            org.admany.lc2h.worldgen.terrain.CityShiftField.ShiftSettings.withMaxShift(
                                IntegerArgumentType.getInteger(ctx, "blocks"))))))
                .then(Commands.literal("on")
                    .executes(ctx -> applyBlendSettings(ctx,
                        org.admany.lc2h.worldgen.terrain.CityShiftField.ShiftSettings.withEnabled(true))))
                .then(Commands.literal("off")
                    .executes(ctx -> applyBlendSettings(ctx,
                        org.admany.lc2h.worldgen.terrain.CityShiftField.ShiftSettings.withEnabled(false))))
                .then(Commands.literal("reset")
                    .executes(ctx -> applyBlendSettings(ctx,
                        org.admany.lc2h.worldgen.terrain.CityShiftField.ShiftSettings.reset())))
                .then(Commands.literal("clear")
                    .executes(context -> {
                        org.admany.lc2h.worldgen.terrain.CityShiftField.clear();
                        org.admany.lc2h.worldgen.terrain.NaturalHeightSampler.clear();
                        ChatMessenger.success(context.getSource(),
                            "Cleared shift field and natural height caches. Newly generated chunks will rebuild them.");
                        return 1;
                    })))
            .then(Commands.literal("lifecycle")
                .requires(source -> source.hasPermission(2))
                .executes(context -> {
                    for (String line : LifecycleTortureTracker.summaryLines()) {
                        emitDiagnosticLine(context.getSource(), line);
                    }
                    for (String line : LifecycleTortureTracker.detailLines()) {
                        emitDiagnosticLine(context.getSource(), line);
                    }
                    return 1;
                })
                .then(Commands.literal("snapshot")
                    .executes(context -> snapshotLifecycle(context, "manual"))
                    .then(Commands.argument("label", StringArgumentType.greedyString())
                        .executes(context -> snapshotLifecycle(context, StringArgumentType.getString(context, "label")))))
                .then(Commands.literal("reset")
                    .executes(context -> {
                        LifecycleTortureTracker.reset("manual-command");
                        ChatMessenger.success(context.getSource(), "Reset LC2H lifecycle torture tracker.");
                        return 1;
                    })))
            .then(Commands.literal("hooks")
                .requires(source -> source.hasPermission(2))
                .executes(context -> {
                    emitDiagnosticLine(context.getSource(), "CriticalHooks note: blocked is the strongest signal during V4 migration because an upstream Lost Cities path already ran without this hook.");
                    for (String line : CriticalMixinHookValidator.summaryLines()) {
                        emitDiagnosticLine(context.getSource(), line);
                    }
                    return 1;
                }))
            .then(Commands.literal("shadowtest")
                .requires(source -> source.hasPermission(2))
                .then(Commands.argument("scenario", StringArgumentType.word())
                    .suggests((context, builder) -> SharedSuggestionProvider.suggest(List.of("all", "gravity", "vines", "fluids", "treeplacer", "midgard"), builder))
                    .then(Commands.argument("x", IntegerArgumentType.integer())
                        .then(Commands.argument("y", IntegerArgumentType.integer())
                            .then(Commands.argument("z", IntegerArgumentType.integer())
                                .executes(context -> runShadowScenario(
                                    context,
                                    StringArgumentType.getString(context, "scenario"),
                                    IntegerArgumentType.getInteger(context, "x"),
                                    IntegerArgumentType.getInteger(context, "y"),
                                    IntegerArgumentType.getInteger(context, "z"))))))))
            .then(Commands.literal("profile")
                .requires(source -> source.hasPermission(2))
                .then(Commands.argument("profile", StringArgumentType.word())
                    .suggests(PROFILE_SUGGESTIONS)
                    .executes(DebugCommands::setProfileOverride)))
            .then(Commands.literal("outsideprofile")
                .requires(source -> source.hasPermission(2))
                .then(Commands.argument("profile", StringArgumentType.word())
                    .suggests(PROFILE_SUGGESTIONS)
                    .executes(DebugCommands::setOutsideProfileOverride)))
            .then(Commands.literal("multichunkparity")
                .requires(source -> source.hasPermission(2))
                .executes(context -> runMultiChunkParity(context, 2, 64))
                .then(Commands.argument("radius", IntegerArgumentType.integer(0, 8))
                    .executes(context -> runMultiChunkParity(context, IntegerArgumentType.getInteger(context, "radius"), 64))
                    .then(Commands.argument("samples", IntegerArgumentType.integer(1, 512))
                        .executes(context -> runMultiChunkParity(
                            context,
                            IntegerArgumentType.getInteger(context, "radius"),
                            IntegerArgumentType.getInteger(context, "samples"))))))
            .then(Commands.literal("kernelbench")
                .requires(source -> source.hasPermission(2))
                .executes(context -> runKernelBenchmark(context, 2, 16))
                .then(Commands.argument("radius", IntegerArgumentType.integer(0, 8))
                    .executes(context -> runKernelBenchmark(context, IntegerArgumentType.getInteger(context, "radius"), 16))
                    .then(Commands.argument("iterations", IntegerArgumentType.integer(1, 256))
                        .executes(context -> runKernelBenchmark(
                            context,
                            IntegerArgumentType.getInteger(context, "radius"),
                            IntegerArgumentType.getInteger(context, "iterations"))))));
    }

    private static List<String> topTimingLines(int limit) {
        return Lc2hTimingRegistry.snapshot().entrySet().stream()
            .filter(entry -> entry.getValue().count() > 0L && entry.getValue().totalNs() > 0L)
            .sorted(Comparator.comparingLong((Map.Entry<String, Lc2hTimingRegistry.TimingSnapshot> entry) -> entry.getValue().totalNs()).reversed())
            .limit(Math.max(1, limit))
            .map(entry -> {
                Lc2hTimingRegistry.TimingSnapshot timing = entry.getValue();
                return String.format(Locale.ROOT,
                    "Hotpath %s: count=%d total=%.3fms avg=%.3fms max=%.3fms",
                    entry.getKey(),
                    timing.count(),
                    timing.totalNs() / 1_000_000.0D,
                    timing.avgNs() / 1_000_000.0D,
                    timing.maxNs() / 1_000_000.0D);
            })
            .toList();
    }

    private static void emitDiagnosticLine(CommandSourceStack source, String line) {
        ChatMessenger.info(source, line);
        LC2H.LOGGER.info("[LC2H] {}", line);
    }

    private static int runKernelBenchmark(CommandContext<CommandSourceStack> context, int radius, int iterations) {
        CommandSourceStack source = context.getSource();
        IDimensionInfo dimInfo;
        try {
            dimInfo = Registration.LOSTCITY_FEATURE.get().getDimensionInfo(source.getLevel());
        } catch (Throwable t) {
            ChatMessenger.error(source, "Unable to resolve Lost Cities dimension info: " + t.getMessage());
            return 0;
        }
        if (dimInfo == null) {
            ChatMessenger.error(source, "Lost Cities dimension info is unavailable for this level.");
            return 0;
        }
        int chunkX = Math.floorDiv((int) Math.floor(source.getPosition().x), 16);
        int chunkZ = Math.floorDiv((int) Math.floor(source.getPosition().z), 16);
        ChunkCoord center = new ChunkCoord(source.getLevel().dimension(), chunkX, chunkZ);
        ChatMessenger.info(source, "Started LC kernel head-to-head benchmark: radius=" + radius + ", iterations=" + iterations);

        AsyncManager.submitSupplierFallback("kernel-benchmark", () -> LostCityKernelBenchmark.run(dimInfo, center, radius, iterations))
            .whenComplete((result, throwable) -> AsyncManager.syncToMain(() -> {
                if (throwable != null) {
                    ChatMessenger.error(source, "LC kernel benchmark failed: " + throwable.getMessage());
                } else {
                    ChatMessenger.success(source, "LC kernel benchmark: " + result.summary());
                }
            }));
        return 1;
    }

    private static int runMultiChunkParity(CommandContext<CommandSourceStack> context, int radius, int samples) {
        CommandSourceStack source = context.getSource();
        IDimensionInfo dimInfo;
        try {
            dimInfo = Registration.LOSTCITY_FEATURE.get().getDimensionInfo(source.getLevel());
        } catch (Throwable t) {
            ChatMessenger.error(source, "Unable to resolve Lost Cities dimension info: " + t.getMessage());
            return 0;
        }
        if (dimInfo == null) {
            ChatMessenger.error(source, "Lost Cities dimension info is unavailable for this level.");
            return 0;
        }
        int areaSize;
        try {
            areaSize = dimInfo.getWorldStyle().getMultiSettings().areasize();
        } catch (Throwable t) {
            ChatMessenger.error(source, "Unable to resolve Lost Cities multichunk area size: " + t.getMessage());
            return 0;
        }
        int chunkX = Math.floorDiv((int) Math.floor(source.getPosition().x), 16);
        int chunkZ = Math.floorDiv((int) Math.floor(source.getPosition().z), 16);
        ChunkCoord centerMulti = new ChunkCoord(
            source.getLevel().dimension(),
            Math.floorDiv(chunkX, areaSize),
            Math.floorDiv(chunkZ, areaSize));
        ChatMessenger.info(source, "Started LC multichunk parity: radius=" + radius + ", samples=" + samples);

        AsyncManager.submitSupplierFallback("multichunk-parity", () -> MultiChunkParityHarness.run(dimInfo, centerMulti, areaSize, radius, samples))
            .whenComplete((result, throwable) -> AsyncManager.syncToMain(() -> {
                if (throwable != null) {
                    ChatMessenger.error(source, "LC multichunk parity failed: " + throwable.getMessage());
                } else if (result.mismatches() > 0) {
                    ChatMessenger.error(source, "LC multichunk parity mismatch: " + result.summary());
                } else {
                    ChatMessenger.success(source, "LC multichunk parity: " + result.summary());
                }
            }));
        return 1;
    }

    private static int runShadowScenario(CommandContext<CommandSourceStack> context,
                                         String scenario,
                                         int x,
                                         int y,
                                         int z) {
        CommandSourceStack source = context.getSource();
        net.minecraft.server.level.ServerLevel level = source.getLevel();
        if (level == null) {
            ChatMessenger.error(source, "Shadow runtime validation requires a server level.");
            return 0;
        }
        var results = ShadowMutationRuntimeHarness.run(level, new net.minecraft.core.BlockPos(x, y, z), scenario);
        boolean success = true;
        for (ShadowMutationRuntimeHarness.ScenarioResult result : results) {
            emitDiagnosticLine(source, "ShadowTest " + result.summary());
            success &= result.success();
        }
        emitDiagnosticLine(source, "ShadowApply: " + ShadowBlockMutationApplier.diagnostics());
        for (String line : TreeCompatTracker.summaryLines()) {
            emitDiagnosticLine(source, line);
        }
        return success ? 1 : 0;
    }

    private static int setProfileOverride(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        String requestedProfile = StringArgumentType.getString(context, "profile");
        String profileName = resolveProfile(requestedProfile);
        if (profileName == null) {
            ChatMessenger.error(source, "Unknown Lost Cities profile '" + requestedProfile + "'. Put custom profiles in config/lostcities/profiles.");
            return 0;
        }

        ResourceKey<Level> dimension = source.getLevel().dimension();
        LostCityProfileOverrideManager.setOverride(dimension, profileName);
        LifecycleTortureTracker.onProfileOverride(dimension, profileName);
        Config.resetProfileCache();
        clearGenerationCachesForProfileSwitch();

        ChatMessenger.success(source, "Succeeded: Lost Cities profile '" + profileName + "' is now forced for " + dimension.location() + ". Fresh chunks only; existing generated chunks are not overwritten.");
        ChatMessenger.error(source, "Risky: changing profiles mid-world can create seams, terrain mismatches, or broken transitions between old and new chunks. Back up the world first.");
        LC2H.LOGGER.warn("Forced Lost Cities profile '{}' for dimension {} via /lc2h profile. Existing chunks are not rewritten.", profileName, dimension.location());
        return 1;
    }

    private static int setOutsideProfileOverride(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        String requestedProfile = StringArgumentType.getString(context, "profile");
        String profileName = resolveProfile(requestedProfile);
        if (profileName == null) {
            ChatMessenger.error(source, "Unknown Lost Cities outside profile '" + requestedProfile + "'. Put custom profiles in config/lostcities/profiles.");
            return 0;
        }

        ResourceKey<Level> dimension = source.getLevel().dimension();
        LostCityProfileOverrideManager.setOutsideOverride(dimension, profileName);
        LifecycleTortureTracker.onOutsideProfileOverride(dimension, profileName);
        Config.resetProfileCache();
        clearGenerationCachesForProfileSwitch();

        ChatMessenger.success(source, "Succeeded: Lost Cities outside profile '" + profileName + "' is now forced for " + dimension.location() + ". Fresh chunks only; existing generated chunks are not overwritten.");
        ChatMessenger.error(source, "Risky: changing outside profiles mid-world can create edge seams, sphere-border mismatches, or broken transitions between old and new chunks. Back up the world first.");
        LC2H.LOGGER.warn("Forced Lost Cities outside profile '{}' for dimension {} via /lc2h outsideprofile. Existing chunks are not rewritten.", profileName, dimension.location());
        return 1;
    }

    private static String resolveProfile(String requestedProfile) {
        if (requestedProfile == null || requestedProfile.isBlank()) {
            return null;
        }
        String profileName = requestedProfile.trim();
        ensureProfilesInitialized();

        if (LostCityProfileOverrideManager.hasKnownProfile(profileName)) {
            return profileName;
        }

        LostCityProfile loaded = LostCityProfileOverrideManager.loadProfileFromDisk(profileName, profileSearchDirs()).orElse(null);
        if (loaded != null) {
            ProfileSetup.STANDARD_PROFILES.put(profileName, loaded);
            return profileName;
        }

        return null;
    }

    private static void ensureProfilesInitialized() {
        if (!ProfileSetup.STANDARD_PROFILES.isEmpty()) {
            return;
        }
        try {
            ProfileSetup.setupProfiles();
        } catch (Throwable t) {
            LC2H.LOGGER.debug("Unable to eagerly initialize Lost Cities profiles for command suggestions: {}", t.getMessage());
        }
    }

    private static List<Path> profileSearchDirs() {
        Path config = FMLPaths.CONFIGDIR.get();
        return List.of(
            config.resolve("lostcities").resolve("profiles"),
            config.resolve("lostcities")
        );
    }

    private static void clearGenerationCachesForProfileSwitch() {
        try {
            FeatureCache.clear();
        } catch (Throwable t) {
            LC2H.LOGGER.debug("Failed to clear LC2H feature cache after profile switch: {}", t.getMessage());
        }
        try {
            ChunkRoleProbe.clear();
        } catch (Throwable t) {
            LC2H.LOGGER.debug("Failed to clear LC2H chunk role cache after profile switch: {}", t.getMessage());
        }
        try {
            PlannerBatchQueue.shutdown();
        } catch (Throwable t) {
            LC2H.LOGGER.debug("Failed to clear LC2H planner batches after profile switch: {}", t.getMessage());
        }
        try {
            BuildingInfo.cleanCache();
            City.cleanCache();
            MultiChunk.cleanCache();
        } catch (Throwable t) {
            LC2H.LOGGER.debug("Failed to clear Lost Cities caches after profile switch: {}", t.getMessage());
        }
    }

    private static int snapshotLifecycle(CommandContext<CommandSourceStack> context, String label) {
        var snapshot = LifecycleTortureTracker.snapshot(context.getSource().getServer(), label == null || label.isBlank() ? "manual" : label.trim());
        emitDiagnosticLine(context.getSource(), snapshot.describe());
        return 1;
    }
}
