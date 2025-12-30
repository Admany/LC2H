package org.admany.lc2h.benchmark;

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
            return new BenchmarkStatus(Component.literal("Benchmark not supported on dedicated servers."), COLOR_ERROR, Stage.ABORTED);
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return new BenchmarkStatus(Component.literal("Minecraft client unavailable."), COLOR_ERROR, Stage.ABORTED);
        }
        if (currentRun != null && currentRun.isActive()) {
            return new BenchmarkStatus(Component.literal("Benchmark already running."), COLOR_ERROR, Stage.RUNNING);
        }
        LocalPlayer player = minecraft.player;
        if (player == null) {
            return new BenchmarkStatus(Component.literal("Join a singleplayer world before starting the benchmark."), COLOR_ERROR, Stage.ABORTED);
        }
        if (minecraft.getSingleplayerServer() == null) {
            return new BenchmarkStatus(Component.literal("Benchmark requires an integrated (singleplayer) server."), COLOR_ERROR, Stage.ABORTED);
        }
        int originalRenderDistance = minecraft.options.renderDistance().get();
        BenchmarkEventHandler.INSTANCE.beginCountdown(originalRenderDistance);
        minecraft.options.renderDistance().set(8);
        notifyStatus(Component.literal("[LC2H] Benchmark will start in 10s. Press ESC to cancel."), COLOR_INFO, Stage.PREPARING);
        player.sendSystemMessage(Component.literal("[LC2H] Benchmark will start in 10s. Press ESC to cancel.")
            .withStyle(style -> style.withColor(COLOR_INFO)));
        return new BenchmarkStatus(Component.literal("[LC2H] Benchmark will start in 10s. Press ESC to cancel."), COLOR_INFO, Stage.PREPARING);
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
            notifyStatus(Component.literal("Benchmark already running."), COLOR_ERROR, Stage.RUNNING);
            return;
        }
        MinecraftServer server = serverPlayer.getServer();
        if (server == null || !server.isSingleplayer()) {
            notifyStatus(Component.literal("Benchmark only runs in singleplayer."), COLOR_ERROR, Stage.ABORTED);
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
            String msg = message.getString();
            if (msg.startsWith("[LC2H] Benchmark will start in")) {
                String[] parts = msg.split(" ");
                if (parts.length >= 5) {
                    try {
                        String secStr = parts[4].replace("s", "");
                        int secs = Integer.parseInt(secStr);
                        if (secs == 10 || secs == 6 || secs == 2) {
                            float pitch = switch (secs) {
                                case 10 -> 1.0f;
                                case 6 -> 1.2f;
                                case 2 -> 1.4f;
                                default -> 1.0f;
                            };
                            LocalPlayer player = mc.player;
                            if (player != null && LC2H.COUNTDOWN_SOUND.isPresent()) {
                                player.playSound(LC2H.COUNTDOWN_SOUND.get(), 1.5f, pitch);
                            }
                        }
                    } catch (NumberFormatException e) {
                    }
                }
            }

            if (stage == Stage.FINISHED || stage == Stage.ABORTED) {
                BenchmarkEventHandler.INSTANCE.restoreRenderDistance(mc);
            }
        });
    }

    static void completeRun(BenchmarkRun run, BenchmarkResult result) {
        currentRun = null;
        if (result == null) {
            return;
        }

        final String uploadStatus;
        if (SAVE_RESULTS_TO_FILE) {
            uploadStatus = "Results saved to file (website upload disabled)";
        }

        if (result.success()) {
            notifyStatus(Component.literal(String.format("[LC2H] Benchmark complete. CPM %.1f, common TPS %.1f",
                result.effectiveChunksPerMinute(), result.commonTps())), COLOR_SUCCESS, Stage.FINISHED);
        } else if (result.aborted()) {
            notifyStatus(Component.literal("Benchmark cancelled: " + result.abortReason()), COLOR_ERROR, Stage.ABORTED);
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
