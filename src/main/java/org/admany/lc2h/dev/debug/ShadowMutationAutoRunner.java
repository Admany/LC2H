package org.admany.lc2h.dev.debug;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.admany.lc2h.LC2H;
import org.admany.lc2h.concurrency.async.AsyncManager;
import org.admany.lc2h.concurrency.async.Priority;
import org.admany.lc2h.dev.diagnostics.CriticalMixinHookValidator;
import org.admany.lc2h.worldgen.apply.ShadowBlockMutationApplier;
import org.admany.lc2h.worldgen.lostcities.TreeCompatTracker;
import org.admany.lc2h.world.cleanup.VineClusterCleaner;
import org.admany.lc2h.util.chunk.ChunkPostProcessor;
import org.admany.lc2h.util.ResourceLocations;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ShadowMutationAutoRunner {

    private static final String AUTO_SCENARIO = System.getProperty("lc2h.shadowtest.auto", "").trim();
    private static final int AUTO_X = Integer.getInteger("lc2h.shadowtest.x", 0);
    private static final int AUTO_Y = Integer.getInteger("lc2h.shadowtest.y", 96);
    private static final int AUTO_Z = Integer.getInteger("lc2h.shadowtest.z", 0);
    private static final String AUTO_DIMENSION = System.getProperty("lc2h.shadowtest.dimension", "minecraft:overworld").trim();
    private static final boolean AUTO_STOP = Boolean.getBoolean("lc2h.shadowtest.auto.stop");
    private static final int AUTO_STOP_DELAY_TICKS = Math.max(0,
        Integer.getInteger("lc2h.shadowtest.auto.stop.delay_ticks", 0));
    private static final AtomicBoolean EXECUTED = new AtomicBoolean(false);

    private ShadowMutationAutoRunner() {
    }

    public static void maybeRun(MinecraftServer server) {
        if (server == null) {
            return;
        }
        String scenario = normalizedScenario();
        if (scenario.isEmpty()) {
            return;
        }
        if (!EXECUTED.compareAndSet(false, true)) {
            return;
        }
        server.execute(() -> run(server, scenario));
    }

    private static void run(MinecraftServer server, String scenario) {
        ServerLevel level = resolveLevel(server);
        if (level == null) {
            LC2H.LOGGER.warn("[LC2H] Shadow autorun skipped: dimension '{}' unavailable", AUTO_DIMENSION);
            return;
        }
        BlockPos origin = new BlockPos(AUTO_X, AUTO_Y, AUTO_Z);
        LC2H.LOGGER.info("[LC2H] Shadow autorun starting: scenario={} dimension={} origin={} stopAfter={}", scenario, level.dimension().location(), origin, AUTO_STOP);
        long startedNs = System.nanoTime();
        List<ShadowMutationRuntimeHarness.ScenarioResult> results = ShadowMutationRuntimeHarness.run(level, origin, scenario);
        boolean success = true;
        for (ShadowMutationRuntimeHarness.ScenarioResult result : results) {
            LC2H.LOGGER.info("[LC2H] Shadow autorun {}", result.summary());
            success &= result.success();
        }
        long elapsedMs = Math.round((System.nanoTime() - startedNs) / 1_000_000.0D);
        LC2H.LOGGER.info("[LC2H] Shadow autorun complete: success={} elapsedMs={}", success, elapsedMs);
        LC2H.LOGGER.info("[LC2H] ShadowApply: {}", ShadowBlockMutationApplier.diagnostics());
        LC2H.LOGGER.info("[LC2H] VineCleaner: {}", VineClusterCleaner.diagnostics());
        LC2H.LOGGER.info("[LC2H] FloatingCleanup: {}", ChunkPostProcessor.floatingDiagnostics());
        for (String line : TreeCompatTracker.summaryLines()) {
            LC2H.LOGGER.info("[LC2H] {}", line);
        }
        for (String line : CriticalMixinHookValidator.summaryLines()) {
            LC2H.LOGGER.info("[LC2H] {}", line);
        }
        if (AUTO_STOP) {
            if (AUTO_STOP_DELAY_TICKS <= 0) {
                server.halt(false);
            } else {
                long delayMs = AUTO_STOP_DELAY_TICKS * 50L;
                AsyncManager.runLater("shadow-auto-stop", () -> server.execute(() -> {
                    LC2H.LOGGER.info("[LC2H] Shadow delayed diagnostics: ShadowApply: {}", ShadowBlockMutationApplier.diagnostics());
                    LC2H.LOGGER.info("[LC2H] Shadow delayed diagnostics: VineCleaner: {}", VineClusterCleaner.diagnostics());
                    LC2H.LOGGER.info("[LC2H] Shadow delayed diagnostics: FloatingCleanup: {}", ChunkPostProcessor.floatingDiagnostics());
                    server.halt(false);
                }), delayMs, Priority.LOW);
            }
        }
    }

    private static String normalizedScenario() {
        if (AUTO_SCENARIO.isBlank()) {
            return "";
        }
        return AUTO_SCENARIO.toLowerCase(Locale.ROOT);
    }

    private static ServerLevel resolveLevel(MinecraftServer server) {
        if (server == null || AUTO_DIMENSION.isBlank() || "minecraft:overworld".equals(AUTO_DIMENSION)) {
            return server == null ? null : server.overworld();
        }
        try {
            ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, ResourceLocations.parse(AUTO_DIMENSION));
            return server.getLevel(key);
        } catch (Throwable t) {
            LC2H.LOGGER.warn("[LC2H] Shadow autorun could not resolve dimension '{}': {}", AUTO_DIMENSION, t.toString());
            return null;
        }
    }
}
