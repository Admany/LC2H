package org.admany.lc2h.dev.benchmark;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.ModList;
import org.admany.lc2h.LC2H;
import org.admany.lc2h.client.gui.BenchmarkResultScreen;
import org.admany.lc2h.util.log.ChatMessenger;

import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

public final class BenchmarkManager {
    static final BlockPos START_POS = new BlockPos(10000, 100, 10000);
    static final float START_YAW = 180.0f;
    static final float START_PITCH = 0.0f;
    static final int PREP_TICKS = 20 * 5;
    static final int RUN_TICKS = 20 * 60;
    static final double SPEED_BLOCKS_PER_TICK = 1.0;
    static final int COLOR_SUCCESS = 0xAAAAAA;
    static final int COLOR_ERROR = 0xFF0000;
    static final int COLOR_INFO = 0xFFFFFF;
    static final int COLOR_COUNTDOWN_WHITE = 0xFFFFFF;
    static final int COLOR_COUNTDOWN_LIGHT_GRAY = 0xCCCCCC;
    static final int COLOR_COUNTDOWN_DARK_GRAY = 0x888888;
    static final int COLOR_PURPLE = 0xAA00AA;
    static final int COLOR_BLUE = 0x0000FF;
    static final int COLOR_RED = 0xFF0000;
    static final int COLOR_GREEN = 0x00FF00;
    static final int COLOR_YELLOW = 0xFFFF00;
    static final String UPLOAD_ENDPOINT = "https://admany.dev/api/lc2h/benchmark";
    static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    static final CopyOnWriteArrayList<StatusListener> LISTENERS = new CopyOnWriteArrayList<>();
    static final boolean SAVE_RESULTS_TO_FILE = true;

    private static BenchmarkRun currentRun;
    private static boolean registered;
    private static JsonObject clientHardwareInfo = null;

    static JsonObject getClientHardwareInfo() {
        return clientHardwareInfo;
    }

    public static void initClient() {
        if (registered) {
            return;
        }
        registered = true;
        MinecraftForge.EVENT_BUS.register(BenchmarkEventHandler.INSTANCE);
    }

    public static BenchmarkStatus requestStart() {
        if (!FMLEnvironment.dist.isClient()) {
            return new BenchmarkStatus(Component.translatable("lc2h.benchmark.not_supported_dedicated"), COLOR_ERROR, Stage.ABORTED);
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return new BenchmarkStatus(Component.translatable("lc2h.benchmark.client_unavailable"), COLOR_ERROR, Stage.ABORTED);
        }
        if (currentRun != null && currentRun.isActive()) {
            return new BenchmarkStatus(Component.translatable("lc2h.benchmark.already_running"), COLOR_ERROR, Stage.RUNNING);
        }
        LocalPlayer player = minecraft.player;
        if (player == null) {
            return new BenchmarkStatus(Component.translatable("lc2h.benchmark.join_singleplayer"), COLOR_ERROR, Stage.ABORTED);
        }
        if (minecraft.getSingleplayerServer() == null) {
            return new BenchmarkStatus(Component.translatable("lc2h.benchmark.requires_integrated"), COLOR_ERROR, Stage.ABORTED);
        }
        int originalRenderDistance = minecraft.options.renderDistance().get();
        BenchmarkEventHandler.INSTANCE.beginCountdown(originalRenderDistance);
        minecraft.options.renderDistance().set(8);
        notifyCountdownFull(10, COLOR_INFO);
        return new BenchmarkStatus(Component.translatable("lc2h.benchmark.countdown_full", 10), COLOR_INFO, Stage.PREPARING);
    }

    public static void addListener(StatusListener listener) {
        if (listener != null) {
            LISTENERS.add(listener);
        }
    }

    public static void removeListener(StatusListener listener) {
        if (listener != null) {
            LISTENERS.remove(listener);
        }
    }

    public static boolean isRunning() {
        return currentRun != null && currentRun.isActive();
    }

    public static void requestUserCancelFromClient(String reason) {
        if (BenchmarkEventHandler.INSTANCE.cancelCountdown(reason)) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getSingleplayerServer() == null) return;
        MinecraftServer server = mc.getSingleplayerServer();
        server.execute(() -> {
            BenchmarkRun run = currentRun;
            if (run != null) {
                run.requestUserCancel(reason);
            }
        });
    }

    static void startRun(ServerPlayer serverPlayer) {
        if (serverPlayer == null) return;
        if (currentRun != null && currentRun.isActive()) {
            notifyStatus(Component.translatable("lc2h.benchmark.already_running"), COLOR_ERROR, Stage.RUNNING);
            return;
        }
        MinecraftServer server = serverPlayer.getServer();
        if (server == null || !server.isSingleplayer()) {
            notifyStatus(Component.translatable("lc2h.benchmark.only_singleplayer"), COLOR_ERROR, Stage.ABORTED);
            return;
        }
        currentRun = new BenchmarkRun(serverPlayer);
        currentRun.begin();
    }

    static BenchmarkRun getCurrentRun() {
        return currentRun;
    }

    static void notifyStatus(Component message, int color, Stage stage) {
        Objects.requireNonNull(message, "message");
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            return;
        }
        BenchmarkStatus status = new BenchmarkStatus(message, color, stage);
        mc.execute(() -> {
            for (StatusListener listener : LISTENERS) {
                try {
                    listener.onStatus(status);
                } catch (Throwable t) {
                    LC2H.LOGGER.warn("Benchmark status listener threw", t);
                }
            }
            if (stage == Stage.FINISHED || stage == Stage.ABORTED) {
                BenchmarkEventHandler.INSTANCE.restoreRenderDistance(mc);
            }
        });
    }

    static void notifyCountdownShort(int seconds, int color) {
        notifyStatus(Component.translatable("lc2h.benchmark.countdown_short", seconds), color, Stage.PREPARING);
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.player != null) {
            mc.player.sendSystemMessage(ChatMessenger.prefixedComponent(
                Component.translatable("lc2h.benchmark.countdown_short", seconds), color));
        }
        playCountdownSound(seconds);
    }

    private static void notifyCountdownFull(int seconds, int color) {
        notifyStatus(Component.translatable("lc2h.benchmark.countdown_full", seconds), color, Stage.PREPARING);
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.player != null) {
            mc.player.sendSystemMessage(ChatMessenger.prefixedComponent(
                Component.translatable("lc2h.benchmark.countdown_full", seconds), color));
        }
        playCountdownSound(seconds);
    }

    private static void playCountdownSound(int seconds) {
        if (seconds != 10 && seconds != 6 && seconds != 2) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc != null ? mc.player : null;
        if (player == null || !LC2H.COUNTDOWN_SOUND.isPresent()) {
            return;
        }
        float pitch = switch (seconds) {
            case 10 -> 1.0f;
            case 6 -> 1.2f;
            case 2 -> 1.4f;
            default -> 1.0f;
        };
        player.playSound(LC2H.COUNTDOWN_SOUND.get(), 1.5f, pitch);
    }

    static void completeRun(BenchmarkRun run, BenchmarkResult result) {
        currentRun = null;
        if (result == null) {
            return;
        }

        final String uploadStatus;
        if (SAVE_RESULTS_TO_FILE) {
            uploadStatus = Component.translatable("lc2h.benchmark.upload_saved_file").getString();
        } else {
            uploadStatus = "";
        }

        if (result.success()) {
            notifyStatus(Component.translatable("lc2h.benchmark.complete",
                String.format("%.1f", result.effectiveChunksPerMinute()),
                String.format("%.1f", result.commonTps())), COLOR_SUCCESS, Stage.FINISHED);
        } else if (result.aborted()) {
            notifyStatus(Component.translatable("lc2h.benchmark.cancelled", result.abortReason()), COLOR_ERROR, Stage.ABORTED);
        } else {
            return;
        }

        if (SAVE_RESULTS_TO_FILE) {
            BenchmarkResultIO.save(result);
        }

        BenchmarkResultIO.submit(result);

        Minecraft mc = Minecraft.getInstance();
        if (mc != null) {
            mc.execute(() -> {
                Screen parent = mc.screen;
                mc.setScreen(new BenchmarkResultScreen(parent, result, uploadStatus));
            });
        }
    }

    static void collectClientHardwareInfo() {
        clientHardwareInfo = BenchmarkHardware.collectClientHardware();
    }

    static String getModVersion() {
        return ModList.get().getModContainerById(LC2H.MODID)
            .map(container -> container.getModInfo().getVersion().toString())
            .orElse("unknown");
    }

    public enum Stage {
        IDLE,
        PREPARING,
        RUNNING,
        FINISHED,
        ABORTED
    }

    public static record BenchmarkStatus(Component message, int color, Stage stage) {
    }

    @FunctionalInterface
    public interface StatusListener {
        void onStatus(BenchmarkStatus status);
    }

}
