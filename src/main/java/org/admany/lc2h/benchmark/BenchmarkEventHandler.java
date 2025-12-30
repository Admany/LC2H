package org.admany.lc2h.benchmark;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.LogicalSide;
import org.admany.lc2h.LC2H;
import org.lwjgl.glfw.GLFW;

final class BenchmarkEventHandler {
    static final BenchmarkEventHandler INSTANCE = new BenchmarkEventHandler();
    private static final int SERVER_COUNTDOWN_TICKS = 20 * 10;

    private boolean countdownActive;
    private int countdownTicks;
    private int lastAnnouncedSecond = -1;
    private int originalClientRenderDistance = -1;

    private BenchmarkEventHandler() {
    }

    void beginCountdown(int originalRenderDistance) {
        countdownActive = true;
        countdownTicks = SERVER_COUNTDOWN_TICKS;
        lastAnnouncedSecond = -1;
        originalClientRenderDistance = originalRenderDistance;
    }

    boolean cancelCountdown(String reason) {
        if (!countdownActive) {
            return false;
        }
        countdownActive = false;
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.player != null) {
            mc.player.sendSystemMessage(Component.literal("[LC2H] Benchmark cancelled: " + reason)
                .withStyle(style -> style.withColor(BenchmarkManager.COLOR_ERROR)));
        }
        BenchmarkManager.notifyStatus(Component.literal("Benchmark cancelled: " + reason), BenchmarkManager.COLOR_ERROR, BenchmarkManager.Stage.ABORTED);
        if (mc != null) {
            mc.execute(() -> restoreRenderDistance(mc));
        }
        return true;
    }

    void restoreRenderDistance(Minecraft mc) {
        if (mc != null && originalClientRenderDistance != -1) {
            mc.options.renderDistance().set(originalClientRenderDistance);
            originalClientRenderDistance = -1;
        }
    }

    private int getCountdownColor(int seconds) {
        if (seconds >= 7) return BenchmarkManager.COLOR_COUNTDOWN_WHITE;
        if (seconds >= 4) return BenchmarkManager.COLOR_COUNTDOWN_LIGHT_GRAY;
        return BenchmarkManager.COLOR_COUNTDOWN_DARK_GRAY;
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.side != LogicalSide.SERVER || event.phase != TickEvent.Phase.END) {
            return;
        }
        BenchmarkRun run = BenchmarkManager.getCurrentRun();
        if (run != null) {
            run.tick();
        }
    }

    @OnlyIn(Dist.CLIENT)
    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        BenchmarkRun run = BenchmarkManager.getCurrentRun();
        if (run != null && run.getStage() == BenchmarkManager.Stage.RUNNING) {
            // Block client-side movement inputs while benchmark is running
            var options = Minecraft.getInstance().options;
            options.keyUp.setDown(false);
            options.keyDown.setDown(false);
            options.keyLeft.setDown(false);
            options.keyRight.setDown(false);
            options.keySprint.setDown(false);
            options.keyJump.setDown(false);
            options.keyShift.setDown(false);
        }

        if (!countdownActive) return;
        // Countdown handling below
        countdownTicks--;
        int secs = (countdownTicks + 19) / 20;
        if (secs != lastAnnouncedSecond) {
            lastAnnouncedSecond = secs;
            int color = getCountdownColor(secs);
            BenchmarkManager.notifyStatus(Component.literal("[LC2H] Benchmark will start in " + secs + "s"), color, BenchmarkManager.Stage.PREPARING);
            LocalPlayer player = Minecraft.getInstance().player;
            if (player != null) {
                player.sendSystemMessage(Component.literal("[LC2H] Benchmark will start in " + secs + "s")
                    .withStyle(style -> style.withColor(color)));
            }
            if (secs == 10 || secs == 6 || secs == 2) {
                float pitch = switch (secs) {
                    case 10 -> 1.0f;
                    case 6 -> 1.2f;
                    case 2 -> 1.4f;
                    default -> 1.0f;
                };
                if (player != null && LC2H.COUNTDOWN_SOUND.isPresent()) {
                    player.playSound(LC2H.COUNTDOWN_SOUND.get(), 1.5f, pitch);
                }
            }
        }
        if (countdownTicks <= 0) {
            countdownActive = false;
            lastAnnouncedSecond = -1;
            BenchmarkManager.collectClientHardwareInfo();
            MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
            if (server != null) {
                server.execute(() -> {
                    Minecraft mc = Minecraft.getInstance();
                    if (mc != null && mc.player != null) {
                        ServerPlayer sp = server.getPlayerList().getPlayer(mc.player.getUUID());
                        BenchmarkManager.startRun(sp);
                    }
                });
            }
        }
    }

    @OnlyIn(Dist.CLIENT)
    @SubscribeEvent
    public void onRenderGuiOverlay(RenderGuiOverlayEvent.Post event) {
        BenchmarkRun run = BenchmarkManager.getCurrentRun();
        if (run == null || run.getStage() != BenchmarkManager.Stage.RUNNING) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null) return;

        int remainingSeconds = Math.max(0, (BenchmarkManager.RUN_TICKS - run.getRunTicks()) / 20);

        int timeColor = BenchmarkManager.COLOR_INFO;
        if (remainingSeconds <= 30 && remainingSeconds > 10) timeColor = BenchmarkManager.COLOR_COUNTDOWN_LIGHT_GRAY;
        else if (remainingSeconds <= 10) {
            timeColor = BenchmarkManager.COLOR_COUNTDOWN_DARK_GRAY;
        }

        Component hudText = Component.literal("Running ")
            .withStyle(Style.EMPTY.withColor(BenchmarkManager.COLOR_INFO).withBold(true))
            .append(Component.literal("LC2H").withStyle(Style.EMPTY.withColor(BenchmarkManager.COLOR_INFO).withBold(true)))
            .append(Component.literal(" Benchmark").withStyle(Style.EMPTY.withColor(BenchmarkManager.COLOR_PURPLE).withBold(true)))
            .append(Component.literal(". Time Remaining: ").withStyle(Style.EMPTY.withColor(BenchmarkManager.COLOR_INFO).withBold(true)))
            .append(Component.literal(remainingSeconds + "s").withStyle(Style.EMPTY.withColor(timeColor).withBold(true)))
            .append(Component.literal(". Press ESC to cancel").withStyle(Style.EMPTY.withColor(BenchmarkManager.COLOR_INFO).withBold(true)));

        var gui = event.getGuiGraphics();
        int sw = mc.getWindow().getGuiScaledWidth();
        int sh = mc.getWindow().getGuiScaledHeight();
        int x = (sw - mc.font.width(hudText)) / 2;
        int y = sh - 50;
        gui.drawString(mc.font, hudText, x, y, BenchmarkManager.COLOR_INFO);
    }

    @OnlyIn(Dist.CLIENT)
    @SubscribeEvent
    public void onKeyInput(InputEvent.Key event) {
        if (event.getAction() != GLFW.GLFW_PRESS && event.getAction() != GLFW.GLFW_REPEAT) {
            return;
        }
        BenchmarkRun run = BenchmarkManager.getCurrentRun();
        if (run != null) {
            if (event.getKey() == GLFW.GLFW_KEY_ESCAPE) {
                BenchmarkManager.requestUserCancelFromClient("User pressed ESC");
            }
            event.setCanceled(true);
            return;
        }
        if (countdownActive && event.getKey() == GLFW.GLFW_KEY_ESCAPE) {
            cancelCountdown("ESC pressed during countdown");
        }
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        BenchmarkRun run = BenchmarkManager.getCurrentRun();
        if (run != null) {
            run.requestAbort("Server stopping");
        }
    }

    @SubscribeEvent
    public void onChunkLoad(ChunkEvent.Load event) {
        BenchmarkRun run = BenchmarkManager.getCurrentRun();
        if (run == null) {
            return;
        }
        if (!(event.getChunk() instanceof LevelChunk chunk)) {
            return;
        }
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        run.handleChunkLoad(level, chunk);
    }
}
