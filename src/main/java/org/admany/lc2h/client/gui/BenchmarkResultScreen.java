package org.admany.lc2h.client.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.admany.lc2h.benchmark.BenchmarkResult;

@OnlyIn(Dist.CLIENT)
public class BenchmarkResultScreen extends Screen {
    private final Screen parent;
    private final BenchmarkResult result;
    private final String uploadStatus;
    private Button closeButton;
    private Button copyButton;

    public BenchmarkResultScreen(Screen parent, BenchmarkResult result, String uploadStatus) {
        super(Component.literal("LC2H Benchmark Results"));
        this.parent = parent;
        this.result = result;
        this.uploadStatus = uploadStatus;
    }

    @Override
    protected void init() {
        int buttonWidth = 100;
        int buttonHeight = 20;
        int spacing = 8;
        int centerX = this.width / 2;
        int footerY = this.height - 35;

        closeButton = Button.builder(CommonComponents.GUI_DONE, btn -> this.onClose())
                .bounds(centerX - buttonWidth - spacing, footerY, buttonWidth, buttonHeight)
                .build();
        copyButton = Button.builder(Component.literal("Copy Text"), btn -> copyResultsToClipboard())
                .bounds(centerX + spacing, footerY, buttonWidth, buttonHeight)
                .build();

        addRenderableWidget(closeButton);
        addRenderableWidget(copyButton);
    }

    @Override
    public void render(GuiGraphics gfx, int mouseX, int mouseY, float partialTick) {
        renderBackground(gfx);
        RenderSystem.enableBlend();
        gfx.fillGradient(20, 20, this.width - 20, this.height - 50, 0xD0000000, 0xC0000000);

        int titleY = 30;
        gfx.drawCenteredString(this.font, this.title, this.width / 2, titleY, 0xFFFFFF);

        int left = this.width / 2 - 140;
        int top = titleY + 20;
        int line = top;
        int lineSpacing = 14;

        line = drawSection(gfx, "Summary", left, line, lineSpacing);
        line = drawMetric(gfx, "Chunks Loaded", String.valueOf(result.chunksGenerated()), left, line, lineSpacing);
        line = drawMetric(gfx, "Effective CPM", format(result.effectiveChunksPerMinute()), left, line, lineSpacing);
        line = drawMetric(gfx, "Average TPS", format(result.commonTps()), left, line, lineSpacing);
        line = drawMetric(gfx, "Freezes", String.valueOf(result.freezeCount()), left, line, lineSpacing);
        line = drawMetric(gfx, "Run Time (s)", format(result.realDurationSeconds()), left, line, lineSpacing);

        line += lineSpacing;
        line = drawSection(gfx, "Performance", left, line, lineSpacing);
        line = drawMetric(gfx, "Min TPS", format(result.minTps()), left, line, lineSpacing);
        line = drawMetric(gfx, "Max TPS", format(result.maxTps()), left, line, lineSpacing);
        line = drawMetric(gfx, "Avg Tick (ms)", format(result.averageTickMillis()), left, line, lineSpacing);
        line = drawMetric(gfx, "Max Tick (ms)", format(result.maxTickMillis()), left, line, lineSpacing);
        line = drawMetric(gfx, "Score", format(result.scoreEstimate()), left, line, lineSpacing);

        line += lineSpacing;
        line = drawSection(gfx, "Environment", left, line, lineSpacing);
        line = drawMetric(gfx, "Seed", String.valueOf(result.seed()), left, line, lineSpacing);
        line = drawMetric(gfx, "Dimension", result.dimension().location().toString(), left, line, lineSpacing);
        line = drawMetric(gfx, "LC2H Version", result.modVersion(), left, line, lineSpacing);
        line = drawMetric(gfx, "Minecraft", result.minecraftVersion(), left, line, lineSpacing);
        line = drawMetric(gfx, "Player", result.playerName(), left, line, lineSpacing);
        line = drawMetric(gfx, "Upload", uploadStatus, left, line, lineSpacing);

        if (result.aborted()) {
            line += lineSpacing;
            drawSection(gfx, "Status", left, line, lineSpacing);
            drawMetric(gfx, "Aborted", result.abortReason(), left, line + lineSpacing, lineSpacing);
        }

        super.render(gfx, mouseX, mouseY, partialTick);
    }

    private int drawSection(GuiGraphics gfx, String title, int left, int y, int spacing) {
        gfx.drawString(this.font, title, left, y, 0xFFAA00);
        return y + spacing;
    }

    private int drawMetric(GuiGraphics gfx, String label, String value, int left, int y, int spacing) {
        gfx.drawString(this.font, label + ":", left, y, 0xCCCCCC);
        gfx.drawString(this.font, value, left + 140, y, 0xFFFFFF);
        return y + spacing;
    }

    private String format(double val) {
        return String.format("%.2f", val);
    }

    private void copyResultsToClipboard() {
        StringBuilder sb = new StringBuilder();
        sb.append("LC2H Benchmark Result\n");
        sb.append("Chunks Loaded: ").append(result.chunksGenerated()).append('\n');
        sb.append("Effective CPM: ").append(format(result.effectiveChunksPerMinute())).append('\n');
        sb.append("Average TPS: ").append(format(result.commonTps())).append('\n');
        sb.append("Freezes: ").append(result.freezeCount()).append('\n');
        sb.append("Run Time (s): ").append(format(result.realDurationSeconds())).append('\n');
        sb.append("Min TPS: ").append(format(result.minTps())).append('\n');
        sb.append("Max TPS: ").append(format(result.maxTps())).append('\n');
        sb.append("Avg Tick (ms): ").append(format(result.averageTickMillis())).append('\n');
        sb.append("Max Tick (ms): ").append(format(result.maxTickMillis())).append('\n');
        sb.append("Score: ").append(format(result.scoreEstimate())).append('\n');
        sb.append("Seed: ").append(result.seed()).append('\n');
        sb.append("Dimension: ").append(result.dimension().location()).append('\n');
        sb.append("MC: ").append(result.minecraftVersion()).append('\n');
        sb.append("LC2H: ").append(result.modVersion()).append('\n');
        sb.append("Player: ").append(result.playerName()).append('\n');
        sb.append("Upload: ").append(uploadStatus).append('\n');
        if (result.aborted()) {
            sb.append("Aborted: ").append(result.abortReason()).append('\n');
        }
        Minecraft.getInstance().keyboardHandler.setClipboard(sb.toString());
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }
}
