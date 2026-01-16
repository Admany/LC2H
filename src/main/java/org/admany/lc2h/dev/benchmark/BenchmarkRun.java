package org.admany.lc2h.dev.benchmark;

import com.google.gson.JsonObject;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.SharedConstants;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import org.admany.lc2h.util.log.ChatMessenger;

import java.util.HashMap;
import java.util.Map;

final class BenchmarkRun {
    private final ServerPlayer player;
    private final PlayerSnapshot snapshot;
    private final LongSet seenChunks = new LongOpenHashSet();
    private final int originalViewDistance;
    private final int originalSimulationDistance;
    private final Vec3 flightDirection;

    private Vec3 currentPos;
    private BenchmarkManager.Stage stage = BenchmarkManager.Stage.PREPARING;
    private int prepTicks;
    private int runTicks;
    private long chunkLoads;
    private long lastTickNanos;
    private long runStartNano;
    private double totalTickMs;
    private double maxTickMs;
    private double minTps = Double.MAX_VALUE;
    private double maxTps;
    private final Map<Integer, Integer> tpsHistogram = new HashMap<>();
    private int freezeCount;
    private boolean aborted;
    private String abortReason = "";

    BenchmarkRun(ServerPlayer player) {
        this.player = player;
        this.snapshot = PlayerSnapshot.capture(player);
        MinecraftServer server = player.getServer();
        this.originalViewDistance = server.getPlayerList().getViewDistance();
        this.originalSimulationDistance = server.getPlayerList().getSimulationDistance();
        this.flightDirection = Vec3.directionFromRotation(BenchmarkManager.START_PITCH, BenchmarkManager.START_YAW).normalize();
    }

    void begin() {
        BenchmarkManager.notifyStatus(Component.translatable("lc2h.benchmark.preparing_environment"), BenchmarkManager.COLOR_INFO, BenchmarkManager.Stage.PREPARING);
        ServerLevel level = player.serverLevel();
        MinecraftServer server = player.getServer();

        server.getPlayerList().setViewDistance(8);
        server.getPlayerList().setSimulationDistance(8);

        player.gameMode.changeGameModeForPlayer(GameType.CREATIVE);
        player.getAbilities().mayfly = true;
        player.getAbilities().flying = true;
        player.getAbilities().setFlyingSpeed(1.0f);
        player.onUpdateAbilities();
        player.setNoGravity(true);
        player.setInvulnerable(true);
        currentPos = Vec3.atCenterOf(BenchmarkManager.START_POS);
        player.teleportTo(level, currentPos.x, currentPos.y, currentPos.z, BenchmarkManager.START_YAW, BenchmarkManager.START_PITCH);
        player.sendSystemMessage(ChatMessenger.prefixedComponent(
            Component.translatable("lc2h.benchmark.starting_soon"), BenchmarkManager.COLOR_INFO));
    }

    boolean isActive() {
        return stage == BenchmarkManager.Stage.PREPARING || stage == BenchmarkManager.Stage.RUNNING;
    }

    void tick() {
        if (aborted) {
            return;
        }
        if (stage == BenchmarkManager.Stage.PREPARING) {
            prepTicks++;
            if (prepTicks >= BenchmarkManager.PREP_TICKS) {
                stage = BenchmarkManager.Stage.RUNNING;
                player.sendSystemMessage(ChatMessenger.prefixedComponent(
                    Component.translatable("lc2h.benchmark.running_for_duration", 60), BenchmarkManager.COLOR_INFO));
                BenchmarkManager.notifyStatus(Component.translatable("lc2h.benchmark.running_gathering_metrics"), BenchmarkManager.COLOR_INFO, BenchmarkManager.Stage.RUNNING);
                runTicks = 0;
                chunkLoads = 0;
                freezeCount = 0;
                tpsHistogram.clear();
                minTps = Double.MAX_VALUE;
                maxTps = 0.0;
                totalTickMs = 0.0;
                maxTickMs = 0.0;
                runStartNano = System.nanoTime();
                lastTickNanos = runStartNano;
            }
            return;
        }
        if (stage != BenchmarkManager.Stage.RUNNING) {
            return;
        }
        runTicks++;
        long now = System.nanoTime();
        if (lastTickNanos > 0L) {
            double deltaMs = (now - lastTickNanos) / 1_000_000.0;
            double tps = deltaMs > 0 ? Math.min(20.0, 1000.0 / deltaMs) : 20.0;
            minTps = Math.min(minTps, tps);
            maxTps = Math.max(maxTps, tps);
            int bucket = (int) Math.round(tps * 2.0);
            tpsHistogram.merge(bucket, 1, Integer::sum);
            if (deltaMs > 0.0) {
                totalTickMs += deltaMs;
                maxTickMs = Math.max(maxTickMs, deltaMs);
            }
            if (deltaMs >= 3000.0) {
                freezeCount++;
            }
        }
        lastTickNanos = now;

        advancePlayer();

        if (runTicks >= BenchmarkManager.RUN_TICKS) {
            finish(false);
        }
    }

    void requestUserCancel(String reason) {
        if (!isActive()) {
            return;
        }
        aborted = true;
        abortReason = reason;
        finish(true);
    }

    void requestAbort(String reason) {
        if (stage == BenchmarkManager.Stage.FINISHED || stage == BenchmarkManager.Stage.ABORTED) {
            return;
        }
        aborted = true;
        abortReason = reason;
        finish(true);
    }

    BenchmarkManager.Stage getStage() {
        return stage;
    }

    int getRunTicks() {
        return runTicks;
    }

    ServerPlayer getPlayer() {
        return player;
    }

    void handleChunkLoad(ServerLevel level, LevelChunk chunk) {
        if (stage != BenchmarkManager.Stage.RUNNING) {
            return;
        }
        if (level != player.serverLevel()) {
            return;
        }
        if (chunk.getStatus() != ChunkStatus.FULL) {
            return;
        }
        long key = chunk.getPos().toLong();
        if (seenChunks.add(key)) {
            chunkLoads++;
        }
    }

    private void advancePlayer() {
        player.setDeltaMovement(Vec3.ZERO);
        player.setYRot(BenchmarkManager.START_YAW);
        player.setXRot(BenchmarkManager.START_PITCH);
        Vec3 delta = flightDirection.scale(BenchmarkManager.SPEED_BLOCKS_PER_TICK);
        currentPos = currentPos.add(delta);
        player.teleportTo(player.serverLevel(), currentPos.x, currentPos.y, currentPos.z, BenchmarkManager.START_YAW, BenchmarkManager.START_PITCH);
        player.resetFallDistance();
    }

    private void finish(boolean cancelled) {
        stage = cancelled ? BenchmarkManager.Stage.ABORTED : BenchmarkManager.Stage.FINISHED;

        MinecraftServer server = player.getServer();
        server.getPlayerList().setViewDistance(originalViewDistance);
        server.getPlayerList().setSimulationDistance(originalSimulationDistance);

        player.setNoGravity(snapshot.noGravity());
        player.setInvulnerable(snapshot.invulnerable());
        snapshot.restore(player);
        player.setDeltaMovement(Vec3.ZERO);
        player.resetFallDistance();
        BenchmarkResult result = buildResult();
        if (cancelled) {
            player.sendSystemMessage(ChatMessenger.prefixedComponent(
                Component.translatable("lc2h.benchmark.cancelled", abortReason), BenchmarkManager.COLOR_ERROR));
        } else {
            player.sendSystemMessage(ChatMessenger.prefixedComponent(
                Component.translatable("lc2h.benchmark.finished",
                    String.format("%.1f", result.effectiveChunksPerMinute()),
                    result.freezeCount()),
                BenchmarkManager.COLOR_SUCCESS));
        }
        BenchmarkManager.completeRun(this, result);
    }

    private BenchmarkResult buildResult() {
        double simulatedSeconds = runTicks / 20.0;
        double realDurationSeconds = runStartNano > 0L ? Math.max(0.001, (System.nanoTime() - runStartNano) / 1_000_000_000.0) : simulatedSeconds;
        double simulatedChunksPerMinute = simulatedSeconds > 0 ? (chunkLoads * 60.0) / simulatedSeconds : 0.0;
        double effectiveChunksPerMinute = realDurationSeconds > 0 ? (chunkLoads * 60.0) / realDurationSeconds : 0.0;
        double min = minTps == Double.MAX_VALUE ? 0.0 : minTps;
        double mode = computeMode();
        double averageTickMillis = runTicks > 0 ? totalTickMs / runTicks : 0.0;
        double averageTps = averageTickMillis > 0.0 ? Math.min(20.0, 1000.0 / averageTickMillis) : (runTicks > 0 ? 20.0 : 0.0);
        double roundedTps = Math.round(averageTps);
        double scoreEstimate = Math.max(0.0, (roundedTps * 1000.0) + (effectiveChunksPerMinute * 10.0) - (freezeCount * 5000.0));
        long seed = player.serverLevel().getSeed();
        Map<Integer, Integer> histogramSnapshot = new HashMap<>(tpsHistogram);
        JsonObject hardwareInfo = BenchmarkManager.getClientHardwareInfo();
        return new BenchmarkResult(!aborted && stage == BenchmarkManager.Stage.FINISHED, aborted, abortReason, chunkLoads,
            simulatedChunksPerMinute, simulatedSeconds, realDurationSeconds, min, maxTps, mode, averageTps, roundedTps,
            averageTickMillis, maxTickMs, effectiveChunksPerMinute, scoreEstimate, freezeCount, runTicks, seed,
            player.serverLevel().dimension(), BenchmarkManager.getModVersion(), SharedConstants.getCurrentVersion().getName(),
            player.getGameProfile().getName(), histogramSnapshot,
            hardwareInfo != null ? hardwareInfo : BenchmarkHardware.collectEnvironmentHardware());
    }

    private double computeMode() {
        if (tpsHistogram.isEmpty()) {
            return 0.0;
        }
        int bestBucket = 0;
        int bestCount = 0;
        for (Map.Entry<Integer, Integer> entry : tpsHistogram.entrySet()) {
            if (entry.getValue() > bestCount) {
                bestCount = entry.getValue();
                bestBucket = entry.getKey();
            }
        }
        return bestBucket / 2.0;
    }

    private record PlayerSnapshot(net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension, Vec3 position, float yRot, float xRot,
                                  GameType gameType, boolean mayfly, boolean flying,
                                  boolean invulnerable, boolean noGravity,
                                  float flyingSpeed, float walkingSpeed,
                                  float health, int foodLevel, float saturation, boolean sprinting) {
        static PlayerSnapshot capture(ServerPlayer player) {
            return new PlayerSnapshot(player.level().dimension(), player.position(), player.getYRot(), player.getXRot(),
                player.gameMode.getGameModeForPlayer(), player.getAbilities().mayfly, player.getAbilities().flying,
                player.isInvulnerable(), player.isNoGravity(),
                player.getAbilities().getFlyingSpeed(), player.getAbilities().getWalkingSpeed(),
                player.getHealth(), player.getFoodData().getFoodLevel(), player.getFoodData().getSaturationLevel(), player.isSprinting());
        }

        void restore(ServerPlayer player) {
            MinecraftServer server = player.getServer();
            if (server != null) {
                ServerLevel target = server.getLevel(dimension);
                if (target != null) {
                    player.teleportTo(target, position.x, position.y, position.z, yRot, xRot);
                }
            }
            player.gameMode.changeGameModeForPlayer(gameType);
            player.getAbilities().mayfly = mayfly;
            player.getAbilities().flying = flying;
            player.getAbilities().setFlyingSpeed(flyingSpeed);
            player.getAbilities().setWalkingSpeed(walkingSpeed);
            player.setInvulnerable(invulnerable);
            player.setNoGravity(noGravity);
            player.setHealth(health);
            player.getFoodData().setFoodLevel(foodLevel);
            player.getFoodData().setSaturation(saturation);
            player.setSprinting(sprinting);
            player.onUpdateAbilities();
        }
    }
}
